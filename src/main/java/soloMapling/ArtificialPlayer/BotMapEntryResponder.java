package soloMapling.ArtificialPlayer;

import org.gms.client.Character;
import org.gms.server.maps.MapleMap;
import org.gms.util.PacketCreator;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.server.BotTiming;
import soloMapling.server.ExecutorServiceManager;
import soloMapling.server.EventMessageSystem.EventBus;
import soloMapling.server.EventMessageSystem.EventSubscriber;
import soloMapling.server.EventMessageSystem.EventType;
import soloMapling.server.EventMessageSystem.GameEvent;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

import static soloMapling.ArtificialPlayer.BotHelpers.isBot;

// Makes bots feel instantly alive when a real player and a bot come to share a map. Two directions,
// one nudge:
//   A) a player enters a map that has bots - onEvent(MAP_ENTERED): mark the map observed now (instant
//      FULL movement/combat for the GC LOD) and nudge every running bot on it to re-evaluate promptly
//      instead of on its slow 2-6s/10s wheel.
//   B) a bot enters a map a real player is already on - onBotArrivedObserved(): nudge just the arriving
//      bot. Its movement is already made instant in GCMovementDriver.onMapChange; this wakes its macro
//      brain too.
// Reuses the already-published MAP_ENTERED event - no base Cosmic file is touched.
public final class BotMapEntryResponder implements EventSubscriber {

    private static final BotMapEntryResponder INSTANCE = new BotMapEntryResponder();
    private static final AtomicBoolean REGISTERED = new AtomicBoolean(false);

    // Stagger window: each nudged bot's next tick fires somewhere in here, so bots don't all react on
    // the same frame (organic, and spreads the reschedule cost across the burst).
    private static final long NUDGE_MIN_MS = 150;
    private static final long NUDGE_MAX_MS = 700;

    private BotMapEntryResponder() {
    }

    // Subscribe once at startup (idempotent). Called from EnvironmentManager.environmentLoadStartup.
    public static void register() {
        if (REGISTERED.compareAndSet(false, true)) {
            EventBus.getInstance().subscribe(EventType.MAP_ENTERED, INSTANCE);
        }
    }

    @Override
    public boolean matchesFilter(GameEvent event) {
        return event != null && event.getType() == EventType.MAP_ENTERED;
    }

    // Direction A. Runs synchronously on the player's map-change thread (EventBus.publish has no
    // try/catch and does no offloading), so keep it cheap: do the O(1) observe inline, hand the bot
    // sweep to a virtual thread, and swallow everything - an uncaught throw here would break the
    // player's map entry.
    @Override
    public void onEvent(GameEvent event) {
        try {
            MapleMap map = event.getMap();
            if (map == null) {
                return;
            }
            GCMovement.markObservedNow(map.getId()); // instant FULL for the GC movement/combat LOD
            ExecutorServiceManager.runAsync(() -> nudgeBotsOnMap(map, event.getMapleCharacter()));
        } catch (Throwable ignored) {
            // never let a responder failure propagate into MapleMap.addPlayer
        }
    }

    /**
     * Re-publishes every party-member bot's HP to the entering player.
     *
     * <p>The client only renders a teammate's HP bar once it receives UPDATE_PARTYMEMBER_HP;
     * a freshly built HUD (login / channel switch) starts empty. The host's own pull
     * ({@code Character.receivePartyMemberHP}) goes through
     * {@code getPartyMembersOnSameMap()}, whose {@code isLoggedInWorld()} gate is
     * {@code isLoggedIn() && !isAwayFromWorld()} — and a template-cloned bot's
     * {@code loggedIn} is never set (only {@code newClient} and
     * {@code loadCharFromDB(channelServer=true)} set it, and clones take the
     * {@code channelServer=false} early return). So the bot is filtered out of the
     * pull and the bar stays empty until the bot's next HP change pushes it.
     *
     * <p>Deliberately scans the map and asks each bot whether the player is on its
     * roster, rather than asking the player for its party members: that direction is
     * the very gate that drops bots. Deferred a little because on login the client
     * only learns its party roster at {@code PlayerLoggedinHandler} LOG_ONOFF, well
     * after this event fires from {@code addPlayer} — an eager packet would be dropped.
     */
    private void pushPartyHpSoon(MapleMap map, Character player) {
        if (player == null || player.getParty() == null) {
            return;
        }
        BotTiming.afterRandom(NUDGE_MIN_MS, NUDGE_MAX_MS, () -> {
            try {
                for (Character chr : map.getAllPlayers()) { // snapshot copy - safe off-thread
                    if (chr == null || !isBot(chr)) {
                        continue;
                    }
                    if (!chr.isPartyMember(player.getId())) {
                        continue;
                    }
                    player.sendPacket(PacketCreator.updatePartyMemberHP(
                            chr.getId(), chr.getHp(), chr.getCurrentMaxHp()));
                }
            } catch (Throwable ignored) {
            }
        });
    }

    private void nudgeBotsOnMap(MapleMap map, Character entering) {
        try {
            for (Character chr : map.getAllPlayers()) { // snapshot copy - safe to iterate off-thread
                if (chr != null && isBot(chr)) {
                    nudge(chr);
                }
            }
            pushPartyHpSoon(map, entering);
        } catch (Throwable ignored) {
        }
    }

    // Direction B. A bot just entered a map a real player is already on; wake its macro brain. Called
    // from the movement driver while it holds the bot's movement monitor, so the nudge is dispatched
    // instead of inlined: nudgeSoon takes the macro monitor, and taking it from under the movement
    // monitor would close an AB-BA cycle with the stop path (stopScheduledTask holds the macro monitor
    // and waits for the movement monitor via GCMovement.disable). Off the driver, no order to invert.
    public static void onBotArrivedObserved(Character bot) {
        if (bot == null) {
            return;
        }
        try {
            ExecutorServiceManager.runAsync(() -> INSTANCE.nudge(bot));
        } catch (Throwable ignored) {
        }
    }

    private void nudge(Character botChr) {
        BotSM bot = CharacterStorage.getAllBots().get(botChr.getId());
        if (bot == null || !bot.getRunning()) {
            return;
        }
        long jitter = ThreadLocalRandom.current().nextLong(NUDGE_MIN_MS, NUDGE_MAX_MS + 1);
        bot.nudgeSoon(jitter);
    }
}
