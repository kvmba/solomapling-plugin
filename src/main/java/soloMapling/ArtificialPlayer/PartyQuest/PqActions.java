package soloMapling.ArtificialPlayer.PartyQuest;

import org.gms.client.Character;
import org.gms.scripting.event.EventInstanceManager;
import org.gms.server.maps.MapItem;
import org.gms.server.maps.MapObject;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Portal;
import org.gms.server.life.NPC;
import org.gms.scripting.npc.NPCScriptManager;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackDriver;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAuraState;
import soloMapling.ArtificialPlayer.BotCommandsPack.DropCommands;
import soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands;
import soloMapling.ArtificialPlayer.BotLogic;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.MapVFX.CustomReactor;

import java.awt.Point;
import java.util.List;

import static soloMapling.ArtificialPlayer.BotHelpers.blockingSleep;

/**
 * The things a party-quest bot has to be able to do, in one place: read the instance's
 * stage flags, move within the instance, hit reactors, hand items over, hold a spot, kill
 * for drops, and talk to the NPC that advances the stage.
 *
 * <p>Each quest's bot is expected to be a state machine over these calls only. Anything a
 * quest needs that is not here is a sign the capability is missing rather than that the
 * quest should reach into the engine directly - the engine details that bite (instance
 * maps versus channel maps, the NPC click throttle, single-stack item triggers, the
 * distinction between an attack animation and an attack that lands) are handled once, here.
 *
 * <p>All methods are no-ops on a null bot or a bot with no map, and read like ordinary
 * calls at the call site; a quest's state machine should never have to guard them.
 */
public final class PqActions {

    private PqActions() {
    }

    // =========================================================================
    // N1 - instance state
    // =========================================================================

    /**
     * An integer stage flag from the running instance, or {@code fallback} when the bot is
     * not in an instance at all.
     *
     * <p>Quests key their progress on these ({@code statusStg0}, {@code 2stageclear},
     * {@code stg2Property} ...), and reading them is how a bot learns what a stage wants
     * without guessing - several puzzles publish their own answer this way.
     */
    public static int readEimInt(Character bot, String key, int fallback) {
        EventInstanceManager eim = instanceOf(bot);
        return eim == null ? fallback : eim.getIntProperty(key);
    }

    public static String readEimString(Character bot, String key) {
        EventInstanceManager eim = instanceOf(bot);
        return eim == null ? null : eim.getProperty(key);
    }

    public static boolean inInstance(Character bot) {
        return instanceOf(bot) != null;
    }

    private static EventInstanceManager instanceOf(Character bot) {
        return bot == null ? null : bot.getEventInstance();
    }

    // =========================================================================
    // N2 - movement inside the instance
    // =========================================================================

    /**
     * Warp to a map <em>within the bot's instance</em>.
     *
     * <p>The channel's map factory returns the shared, non-instanced copy of a map id, so
     * using it inside a party quest drops the bot into another instance's rooms. The
     * instance manager is the only thing that can hand back the right copy.
     */
    public static boolean warpWithinInstance(Character bot, int mapId, int portal) {
        EventInstanceManager eim = instanceOf(bot);
        if (eim == null) {
            return false;
        }
        MapleMap target = eim.getMapInstance(mapId);
        if (target == null) {
            return false;
        }
        bot.changeMap(target, target.getPortal(portal));
        return true;
    }

    /**
     * Walk to a point and block until the bot has arrived, so a position check right after
     * sees it (and so an item thrown at {@code target} lands where the bot is standing).
     *
     * <p>Runs on the dynamic engine ({@link GCMovement}), not the recorded-path engine:
     * the recordings were captured from a Haste-speed player and replayed at 1:1, so every
     * quest bot walked ~40% faster than a real one - and only the handful of quests that
     * happened to have recordings could move at all. The dynamic engine derives the route
     * from the map's own WZ terrain, so it works on any map and walks at the bot's real
     * speed stat.
     *
     * <p>Blocks the calling (virtual) thread until arrival or a deadline, the same
     * synchronous contract the old recorded walk had. A bot that cannot path there (no
     * baked graph yet, an unreachable point) is left where it is and the caller simply
     * retries on its next tick - the pre-existing behaviour when a recording was missing.
     */
    public static void walkTo(Character bot, Point target) {
        if (bot == null || target == null) {
            return;
        }
        java.util.concurrent.CountDownLatch arrived = new java.util.concurrent.CountDownLatch(1);
        GCMovement.move(bot, target.x, target.y, arrived::countDown);
        long deadline = System.currentTimeMillis() + WALK_TIMEOUT_MS;
        try {
            // Wait on arrival, but also stop as soon as the engine gives the move up (an
            // unreachable/stalled target that the driver abandons without firing the callback),
            // so a wedged walk costs a tick or two rather than the whole timeout.
            while (System.currentTimeMillis() < deadline) {
                if (arrived.await(50, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    return;
                }
                if (!GCMovement.isMoving(bot)) {
                    return;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Upper bound on a single {@link #walkTo} block so a wedged walk cannot hold a tick forever. */
    private static final long WALK_TIMEOUT_MS = 20_000;

    /**
     * Walk so the bot ends up standing on the floor <em>under</em> an airborne point.
     *
     * <p>Some quest targets are not on the ground - Orbis's cloud reactors float above a
     * platform and the bot interacts with them from below. The recorded engine had a bespoke
     * "aerial path" for this; on the dynamic engine the equivalent is to resolve the ground
     * the terrain puts under that X and walk to it. The caller drives the vertical step
     * (jump/attack) separately, as it always did.
     */
    public static void walkUnder(Character bot, Point aerialTarget) {
        if (bot == null || aerialTarget == null || bot.getMap() == null) {
            return;
        }
        Point ground = GCMovement.groundPointBelow(bot.getMap(), aerialTarget.x, aerialTarget.y);
        walkTo(bot, ground != null ? ground : aerialTarget);
    }

    /** Walk to the position of a portal on the bot's current map. No-op if the portal is unknown. */
    public static void walkToPortal(Character bot, int portalId) {
        if (bot == null || bot.getMap() == null) {
            return;
        }
        Portal portal = bot.getMap().getPortal(portalId);
        if (portal != null) {
            walkTo(bot, portal.getPosition());
        }
    }

    // =========================================================================
    // N3 - reactors
    // =========================================================================

    /** Hit a reactor through the engine's own state walk, so its script actually runs. */
    public static void hitReactor(Character bot, int reactorOid) {
        if (bot == null) {
            return;
        }
        // Striking a reactor is an attack action (the OPQ path swings first via BotAttack.basicSwing;
        // the stage scripts hit reactors directly), so it breaks the hide auras the same way.
        BotAuraState.cancelHidesForAction(bot);
        CustomReactor.hitReactorWithScript(bot.getMap(), reactorOid, bot);
    }

    /** Reactor oid by data id, or -1. Quests name reactors by data id ("stone4" and friends). */
    public static int findReactorOid(Character bot, int dataId) {
        if (bot == null || bot.getMap() == null) {
            return -1;
        }
        return bot.getMap().getAllReactors().stream()
                .filter(r -> r.getId() == dataId)
                .mapToInt(r -> r.getObjectId())
                .findFirst().orElse(-1);
    }

    // =========================================================================
    // N4 - items
    // =========================================================================

    /**
     * Drop a stack of exactly {@code qty} at a point.
     *
     * <p>Item-triggered reactors match {@code item.getQuantity()} against the number in
     * their WZ condition, so the stack size is part of the trigger and cannot be split up.
     * The drop also re-seats itself onto the floor 85px below the throw
     * (MapleMap#calcDropPos), so {@code at} wants to be a point whose landing is still
     * inside the target's box.
     */
    public static void dropStack(Character bot, int itemId, int qty, Point at) {
        if (bot == null || qty <= 0) {
            return;
        }
        DropCommands.botThrowItemQty(bot, itemId, qty, at);
    }

    /** How many of an item the bot is carrying. */
    public static int countItem(Character bot, int itemId) {
        return bot == null ? 0 : bot.getItemQuantity(itemId, false);
    }

    /**
     * Hand the bot's copies of an item to another character by dropping them at their feet.
     *
     * <p>Quests check {@code cm.haveItem}, which reads the inventory of whoever is talking
     * to the NPC. When the bot is the one holding the quest items, the player cannot turn
     * them in, so the bot has to give them up - and there is no trade helper here, so the
     * drop is the transfer.
     */
    public static void giveItemTo(Character bot, Character receiver, int itemId, int qty) {
        if (bot == null || receiver == null || qty <= 0) {
            return;
        }
        DropCommands.botThrowItemQty(bot, itemId, qty, receiver.getPosition());
    }

    /**
     * Hand this bot's whole stock of an item to the party leader, dropping it at his feet.
     *
     * <p>Every stage turn-in in these quests reads the inventory of whoever talks to the
     * NPC, and only the leader may talk - so a bot that keeps its share starves the turn-in
     * and the party stalls on the stage. Handing the items over is the only contribution
     * that counts; the leader picks the drops up and turns them in. Dropping is safe: quest
     * items in an instance stay visible to the party, and nothing here runs unless there is
     * something to hand over.
     *
     * <p>Passes the actual quantity the bot holds so the drop exactly matches the stock,
     * and reports what it handed over so the caller can say so once instead of every tick.
     *
     * @return how many of the item were handed to the leader (0 when there was nothing)
     */
    public static int handItemsToLeader(Character bot, int itemId) {
        if (bot == null) {
            return 0;
        }
        Character leader = partyLeader(bot);
        if (leader == null || leader == bot) {
            return 0;
        }
        int qty = countItem(bot, itemId);
        if (qty <= 0) {
            return 0;
        }
        giveItemTo(bot, leader, itemId, qty);
        return qty;
    }

    /** The bot's party leader, or null when the bot has no party (or the leader is offline). */
    public static Character partyLeader(Character bot) {
        if (bot == null || bot.getParty() == null || bot.getParty().getLeader() == null) {
            return null;
        }
        return bot.getParty().getLeader().getPlayer();
    }

    // =========================================================================
    // N5 - holding an area
    // =========================================================================

    /**
     * Stand on a spot and stay there.
     *
     * <p>Standing still is the whole mechanic for the area puzzles: the quest counts players
     * inside each rectangle ({@code MapleMap#getNumPlayersInArea}) and compares that against
     * a target it picked at random. A bot that wanders mid-check reads as absent.
     */
    public static void holdArea(Character bot, Point spot, long millis) {
        if (bot == null || spot == null) {
            return;
        }
        walkTo(bot, spot);
        blockingSleep(millis);
    }

    // =========================================================================
    // N6 - hunting
    // =========================================================================

    /**
     * Attack whatever is in reach.
     *
     * <p>Note this is {@link BotAttackDriver#botAttack}, not the swing used for reactors: a
     * swing plays the attack animation and deals nothing, so a bot that only swings can never
     * kill a monster or produce its drops.
     */
    public static void attack(Character bot) {
        if (bot == null) {
            return;
        }
        BotAttackDriver.botAttack(bot);
    }

    /** Pick up matching drops near a point. Returns how many items were gathered. */
    public static int loot(Character bot, Point at, double range, int[] itemIds) {
        if (bot == null || at == null) {
            return 0;
        }
        List<MapObject> found = BotLogic.checkForItemsOnFloor(bot, at, range, itemIds);
        if (found.isEmpty()) {
            return 0;
        }
        // Never take a multi-piece stack: the item-triggered reactors read their stack five
        // seconds after it lands and match it by identity, so picking one up cancels it.
        List<MapObject> lone = found.stream()
                .filter(o -> !(o instanceof MapItem drop) || drop.getItem().getQuantity() <= 1)
                .toList();
        if (lone.isEmpty()) {
            return 0;
        }
        int before = 0;
        for (int id : itemIds) {
            before += countItem(bot, id);
        }
        DropCommands.lootItemListOnFloor(bot, lone);
        int after = 0;
        for (int id : itemIds) {
            after += countItem(bot, id);
        }
        return after - before;
    }

    // =========================================================================
    // N7 - NPC conversation
    // =========================================================================

    /**
     * Talk to an NPC and walk the conversation to the selection at the end.
     *
     * <p>Stages advance by talking: the NPC script holds the {@code statusStgN} checks and
     * calls {@code clearStage}. Reaching a given branch means starting the script and then
     * answering each prompt in turn; {@code selections} holds those answers, and the method
     * stops early if the dialog ends before they run out (a script that branches differently
     * must not be pushed further).
     *
     * <p>Two engine details this hides: the script manager resolves the speaker through
     * {@code client.getPlayer()}, so the bot needs its own client rather than the shared
     * per-channel one (see BotGeneration.adoptPrivateClient), and a client may only click
     * once every 500ms, so the prompts are paced.
     */
    public static boolean talkTo(Character bot, int npcId, int... selections) {
        if (bot == null || bot.getMap() == null) {
            return false;
        }
        NPC npc = bot.getMap().getNPCById(npcId);
        if (npc == null) {
            return false;
        }
        NPCScriptManager manager = NPCScriptManager.getInstance();
        if (!manager.start(bot.getClient(), npcId, npc.getObjectId(), (Character) null)) {
            return false;
        }
        for (int selection : selections) {
            blockingSleep(600); // the client refuses a second click within 500ms
            manager.action(bot.getClient(), (byte) 1, (byte) 0, selection);
        }
        return true;
    }

    /** Whether the conversation with this NPC is still open (a prompt is waiting). */
    public static boolean talkingTo(Character bot, int npcId) {
        if (bot == null) {
            return false;
        }
        var cm = bot.getClient().getCM();
        return cm != null && cm.getNpc() == npcId;
    }

    // =========================================================================
    // misc
    // =========================================================================

    /** Say something as the bot. */
    public static void say(Character bot, String message) {
        if (bot != null) {
            SocialCommands.BotSpeak(bot, message);
        }
    }

    /** Follow the leader through a portal, when the party is moving between rooms. */
    public static void takePortal(Character bot, int portalId) {
        if (bot == null || bot.getMap() == null) {
            return;
        }
        bot.changeMap(bot.getWarpMap(bot.getMap().getPortal(portalId).getTargetMapId()), portalId);
    }

    /**
     * Enter the closest portal to the bot, running the portal's script the way a real client
     * does. Callers position the bot on the portal first (e.g. with {@link #walkTo}).
     *
     * <p>Several quests route rooms through script portals - Orbis's tower rooms are entered by
     * the {@code in0N} portals, whose scripts warp to the room - so a bot that only walks onto
     * one never leaves the tower. No-op without a client (script portals resolve their character
     * through it).
     */
    public static void enterPortalHere(Character bot) {
        if (bot == null || bot.getMap() == null || bot.getClient() == null) {
            return;
        }
        Portal portal = bot.getMap().findClosestPortal(bot.getPosition());
        if (portal != null) {
            portal.enterPortal(bot.getClient());
        }
    }

    /**
     * Enter one specific portal, running its script.
     *
     * <p>Preferred over {@link #enterPortalHere} wherever the caller already knows which door it
     * wants: "closest to where the bot is standing" is a guess, and in a quest room the closest
     * portal is often the spawn point, whose target is the engine's "no map" sentinel. Going
     * through the portal object (rather than a direct {@code changeMap}) is what keeps the
     * quest's own gate in play - Kerning's {@code kpq0} refuses to let anyone through before the
     * stage is clear.
     */
    public static void enterPortal(Character bot, Portal portal) {
        if (bot == null || portal == null || bot.getClient() == null) {
            return;
        }
        portal.enterPortal(bot.getClient());
    }
}
