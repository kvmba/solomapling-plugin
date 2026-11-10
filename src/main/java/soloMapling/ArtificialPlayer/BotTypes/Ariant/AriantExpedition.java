package soloMapling.ArtificialPlayer.BotTypes.Ariant;

import org.gms.client.Character;
import org.gms.net.server.channel.Channel;
import org.gms.server.expeditions.Expedition;
import org.gms.server.expeditions.ExpeditionType;
import org.gms.server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotCommandsPack.WarpCommands;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.BotLogger;
import soloMapling.server.BotChannelRouter;
import soloMapling.server.ExecutorServiceManager;

import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Ariant Coliseum's own recruit path: enrolment in the arena's expedition.
 *
 * <p>Every other quest in this package is entered by a party, so "recruited" there means
 * "invited to a party". Ariant is the exception on both sides of the engine:
 * <ul>
 *   <li>a match is run by {@code AriantColiseum} over an {@code Expedition}, created by the
 *       lobby NPC (2101014) and started by the queue-hall NPC (2101017);</li>
 *   <li>that start path refuses the whole match while <em>any</em> competitor is in a party
 *       ({@code NPCConversationManager.startAriantBattle}), so a party invite can never put a
 *       bot into an Ariant run.</li>
 * </ul>
 *
 * <p>So the recruit hооk here mirrors {@code BotPartyInviteBridge}: watch the channel's
 * registering ARIANT expeditions (one per arena edition) and enrol every eligible lobby bot.
 * The player creates the expedition with the NPC as usual, walks into a queue hall, and finds
 * the lobby crowd already enrolled and waiting there.
 */
public final class AriantExpedition {

    private AriantExpedition() {
    }

    /**
     * The three editions, in the order their queue halls sit: index 0 starts ARIANT (arena
     * 980010101), 1 ARIANT1 (980010201), 2 ARIANT2 (980010301) - the same triple the queue
     * hall's NPC switches on.
     */
    private static final List<ExpeditionType> EDITIONS = List.of(
            ExpeditionType.ARIANT, ExpeditionType.ARIANT1, ExpeditionType.ARIANT2);

    /** How often enrolment is re-checked, ms. */
    private static final long POLL_PERIOD_MS = 2_000L;

    /**
     * The live poll, or null when none is running.
     *
     * <p>Held so {@link #release()} can cancel it: the plugin can be unloaded and loaded again
     * in the same process, and an un-cancelled poll would keep enrolling bots into a torn-down
     * plugin while the second load found its "already started" flag set and scheduled nothing.
     */
    private static final AtomicReference<ScheduledFuture<?>> POLL = new AtomicReference<>();

    /**
     * Start the enrolment poll (idempotent).
     *
     * <p>A poll rather than an event hооk: the expedition is created by an NPC script
     * ({@code cm.createExpedition}), which has no extension event - the same reason
     * {@code CarnivalArena} polls its fields.
     */
    public static synchronized void start() {
        if (POLL.get() != null) {
            return;
        }
        try {
            POLL.set(ExecutorServiceManager.getScheduledExecutorService()
                    .scheduleWithFixedDelay(AriantExpedition::safeTick,
                            POLL_PERIOD_MS, POLL_PERIOD_MS, TimeUnit.MILLISECONDS));
        } catch (RuntimeException e) {
            POLL.set(null); // let a later call retry instead of leaving nothing scheduled
            BotLogger.log("Ariant expedition poll could not be scheduled: " + e);
        }
    }

    /** Cancel the poll on plugin unload, so a reload can start a fresh one. */
    public static synchronized void release() {
        ScheduledFuture<?> poll = POLL.getAndSet(null);
        if (poll != null) {
            poll.cancel(false);
        }
    }

    /** One poll, with every failure contained so the scheduled task is never cancelled. */
    private static void safeTick() {
        try {
            tick();
        } catch (Throwable t) {
            BotLogger.log("Ariant expedition tick failed: " + t);
        }
    }

    private static void tick() {
        var world = org.gms.net.server.Server.getInstance()
                .getWorld(soloMapling.server.SoloMaplingConstants.GameConstants.WORLD_SCANIA);
        if (world == null) {
            return;
        }
        for (Channel channel : world.getChannels()) {
            if (channel == null) {
                continue;
            }
            // getExpeditions() is the synchronized copy: the per-type getExpedition() reads the
            // channel's plain HashMap without its lock, and this poll is a new reader on it.
            for (Expedition exped : channel.getExpeditions()) {
                if (!exped.isRegistering() || editionOf(exped.getType()) < 0) {
                    continue;
                }
                try {
                    enrol(channel, exped);
                } catch (Throwable t) {
                    // One edition's failure must not cost the others their poll.
                    BotLogger.log("Ariant expedition enrolment failed (" + exped.getType() + "): " + t);
                }
            }
        }
    }

    /**
     * Enrol the eligible lobby bots of this channel into {@code exped}.
     *
     * <p>Eligibility is exactly what the match's own start check demands of a competitor: the
     * arena's level band, no party, standing in the public lobby, and - because a bot is warped
     * through its own client's channel - living on the channel the expedition belongs to. The
     * count stops at the expedition's own max size, so a crowd larger than the arena is not
     * pushed past the NPC's size check.
     */
    private static void enrol(Channel channel, Expedition exped) {
        int room = exped.getMaxSize() - exped.getMembers().size();
        if (room <= 0) {
            return;
        }
        int queueMapId = AriantPqData.queueMapFor(editionOf(exped.getType()));
        for (BotSM bot : CharacterStorage.getAllBots().values()) {
            if (room <= 0) {
                return;
            }
            if (!(bot instanceof AriantPQBot) || !bot.getRunning()) {
                continue;
            }
            Character chr = bot.getChr();
            if (chr == null || chr.getMap() == null
                    || chr.getMapId() != AriantPqData.LOBBY_MAP
                    || chr.getParty() != null
                    || !AriantPqData.levelEligible(chr.getLevel())
                    || BotChannelRouter.channelOf(chr) != channel.getId()
                    || exped.contains(chr)) {
                continue;
            }
            if (enrolOne(chr, exped, queueMapId)) {
                room--;
            }
        }
    }

    /**
     * The join itself, done the way the lobby NPC's "join an existing arena" branch does it:
     * {@code addMemberInt} (which also arms the member's registration clock), then the warp to
     * the queue hall.
     *
     * @return true when the bot is now a member
     */
    private static boolean enrolOne(Character chr, Expedition exped, int queueMapId) {
        if (exped.addMemberInt(chr) != 0) {
            return false; // closed, full, or banned - the next poll re-reads the state
        }
        MapleMap queue = queueMapId > 0 ? WarpCommands.mapForBot(chr, queueMapId) : null;
        if (queue != null) {
            chr.changeMap(queue, 0); // the same warp the NPC performs for a joining member
            BotLogger.log("Ariant: " + chr.getName() + " enrolled in " + exped.getType()
                    + " (queue map " + queueMapId + ")");
        }
        return true;
    }

    /** This edition's index, or -1 when the type is not an Ariant arena edition. */
    private static int editionOf(ExpeditionType type) {
        return EDITIONS.indexOf(type);
    }

    /**
     * The expedition a bot standing in an arena's queue hall or arena belongs to, or null.
     *
     * <p>The edition is read off the map id, which is the same arithmetic the queue hall's NPC
     * uses to pick its expedition.
     */
    public static Expedition expeditionFor(Character bot) {
        if (bot == null || bot.getClient() == null || bot.getClient().getChannelServer() == null) {
            return null;
        }
        int index = AriantPqData.queueIndex(bot.getMapId());
        if (index < 0) {
            index = AriantPqData.arenaEdition(bot.getMapId());
        }
        return index < 0 ? null : bot.getClient().getChannelServer().getExpedition(EDITIONS.get(index));
    }

    /**
     * Whether this bot is a competitor of the run it stands in.
     *
     * <p>Used by {@code AriantPQBot} in place of the base class's party test: the engine forbids
     * a competitor from being in a party, so "no party" is the normal and correct state there.
     *
     * <p>Deliberately read from the channel's expedition roster, not from
     * {@code Character.getAriantColiseum()}: the live-arena pointer survives until the arena's
     * own dispose or the character's leave-map callback, and while a match is running the
     * expedition is still registered on the channel anyway - so the roster alone is the honest
     * answer, and it is the one that turns false the moment the arena unregisters (which is
     * exactly when a bot should stop treating itself as competing).
     */
    public static boolean enrolled(Character bot) {
        if (bot == null) {
            return false;
        }
        Expedition exped = expeditionFor(bot);
        return exped != null && exped.contains(bot);
    }

    /**
     * Drop a bot from its expedition, if it is still on the roster.
     *
     * <p>The roster is the arena's own membership record, and nothing removes a member except
     * the arena's {@code leaveArena} (driven by {@code Character.leaveMap}) or the expedition's
     * own three-minute registration timeout. A bot walked home by the base class therefore has to
     * give up its seat explicitly - otherwise it keeps a slot in a match it will never join, and
     * enough of those fill the expedition so no real player can register.
     *
     * <p>Called on the paths that end a bot's participation outside the arena: the queue hall
     * telling the base class the run is over, and the plugin shutdown.
     */
    public static void leave(Character bot) {
        if (bot == null) {
            return;
        }
        Expedition exped = expeditionFor(bot);
        if (exped != null && exped.contains(bot)) {
            exped.removeMember(bot);
            BotLogger.log("Ariant: " + bot.getName() + " left " + exped.getType()
                    + " (seats " + exped.getMembers().size() + "/" + exped.getMaxSize() + ")");
        }
    }
}
