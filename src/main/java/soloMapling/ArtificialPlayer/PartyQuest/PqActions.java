package soloMapling.ArtificialPlayer.PartyQuest;

import org.gms.client.Character;
import org.gms.scripting.event.EventInstanceManager;
import org.gms.server.life.Monster;
import org.gms.server.maps.Foothold;
import org.gms.server.maps.MapItem;
import org.gms.server.maps.MapObject;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Portal;
import org.gms.server.maps.Reactor;
import org.gms.server.life.NPC;
import org.gms.constants.inventory.ItemConstants;
import org.gms.client.inventory.manipulator.InventoryManipulator;
import org.gms.scripting.npc.NPCScriptManager;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackDriver;
import soloMapling.ArtificialPlayer.BotClientBinding;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAuraState;
import soloMapling.ArtificialPlayer.BotCommandsPack.BotAttack;
import soloMapling.ArtificialPlayer.BotCommandsPack.DropCommands;
import soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands;
import soloMapling.ArtificialPlayer.BotLogic;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.MapVFX.CustomReactor;
import soloMapling.server.ExecutorServiceManager;

import static soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement.movementNowMs;

import java.awt.Point;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

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
        Point here = bot.getPosition();
        if (here != null
                && Math.abs(here.x - target.x) <= ARRIVAL_EPS_PX
                && Math.abs(here.y - target.y) <= ARRIVAL_EPS_PX) {
            return; // already standing on the target - re-arming the walk only overwrites
        }
        armStageWalkShield(bot);
        java.util.concurrent.CountDownLatch arrived = new java.util.concurrent.CountDownLatch(1);
        GCMovement.move(bot, target.x, target.y, () -> {
            arrived.countDown();
            nudgeStageTick(bot); // arrival is the event the FSM waits on - wake it (debounced)
        });
        // Bounded block: the tick waits for the walk to land, but never longer than one slow
        // cadence. A bot-only room's ticks are 36-48s apart, and a full-length block there is
        // what froze pure-bot parties standing still between macro ticks (the LPQ "stand
        // still, then snap back" report). Past the cap the walk keeps running on its own;
        // the tick moves on and position checks retry on the next one (the pre-existing
        // no-pathing behaviour).
        long deadline = movementNowMs() + WALK_BLOCK_CAP_MS;
        boolean arrivedFlag = false;
        try {
            // Stop as soon as the engine gives the move up (an unreachable/stalled
            // target that the driver abandons without firing the callback), so a wedged
            // walk costs one cap at most rather than the whole timeout.
            while (movementNowMs() < deadline) {
                if (arrived.await(50, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    arrivedFlag = true;
                    break;
                }
                if (!GCMovement.isMoving(bot)) {
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // The shield outlives the WAIT, not the walk (3ff20b0): a walk still flying after this
        // method stops waiting keeps its shield — the 250ms combat beat must not re-target it
        // mid-walk — and the shield expires on its own after STAGE_WALK_SHIELD_MS (the wedge
        // escape). Only a finished or engine-abandoned walk has nothing left to shield; releasing
        // on every exit re-opened the puzzle blackout the shield exists to prevent, and releasing
        // on none left the 8s blackout an interrupt used to block the beat with. The live engine
        // answer is read once here (not per exit) so the deadline, interrupt and give-up paths
        // cannot drift apart.
        if (walkShieldReleases(arrivedFlag, GCMovement.isMoving(bot))) {
            disarmStageWalkShield(bot);
        }
    }

    /**
     * The pickup walk: a bare driver move to a drop, plus the arrival nudge. Deliberately NOT
     * {@link #walkTo} - a pickup may fire from the combat beat's flow (seekAndAttack callers),
     * where the stage-walk shield would black out the fight and the bounded block would freeze
     * the tick behind a 260-400px walk. The nudge is the whole point: it re-runs the owning
     * stage sweep promptly on arrival (decisive in a bot-only room whose macro ticks are
     * 36-48s apart), and that re-run picks the drop up close or re-arms this walk for the
     * next out-of-reach one. Callers only reach here with the drop already past
     * {@link #PICKUP_REACH_SQ}, so no arrival re-check is needed.
     */
    private static void walkForPickup(Character bot, Point at) {
        GCMovement.move(bot, at.x, at.y, () -> nudgeStageTick(bot));
    }

    /**
     * Whether {@link #walkTo} must release the stage-walk shield when it stops waiting: only when
     * nothing is left to shield — the walk landed ({@code arrived}) or the engine gave it up. A
     * walk still in flight past the wait window keeps its shield (it outlives the block, 3ff20b0)
     * and expires on its own. Pure and package-visible so the decision table is pinned by test
     * rather than re-derived at each exit (it has already regressed twice).
     */
    static boolean walkShieldReleases(boolean arrived, boolean engineStillMoving) {
        return arrived || !engineStillMoving;
    }

    /**
     * How long a {@link #walkTo} may block its tick. A normal in-room walk lands well inside
     * it (the arrival callback ends the wait early); the sweep shares this thread family, so
     * a wedged walk must cost one short cap rather than a wedged combat beat everywhere.
     */
    private static final long WALK_BLOCK_CAP_MS = 1_200;

    // Stage-walk shield: the macro tick's walks (portal following, puzzle holds) must not be
    // re-targeted by the 250ms combat beat, which would yank the walk at a mob and oscillate
    // the bot between its goal and the fight. walkTo/holdArea arm a shield that outlives the
    // blocking window (the walk keeps flying after walkTo gives up waiting) and expires on
    // its own - the combat beat reads it and skips only the chase's steering (the swing half
    // still runs) until then. walkTo releases it through one decision seam (walkShieldReleases)
    // when the wait saw the landing or an engine-abandoned move; a walk still flying past the
    // wait keeps the shield until the expiry - the wedge escape. Deliberately NOT armed by the
    // wait spots (waitNearStageNpc/spreadNearStageNpc): a park renewed every macro tick was a
    // permanent combat blackout, the reported "发呆".
    private static final long STAGE_WALK_SHIELD_MS = 8_000;
    private static final Map<Integer, Long> stageWalkShieldUntilByBot = new java.util.concurrent.ConcurrentHashMap<>();

    /** Whether the combat beat must leave this bot's movement alone right now. */
    public static boolean movementShielded(int botId) {
        Long until = stageWalkShieldUntilByBot.get(botId);
        if (until == null) {
            return false;
        }
        if (movementNowMs() >= until) {
            stageWalkShieldUntilByBot.remove(botId);
            return false;
        }
        return true;
    }

    private static void armStageWalkShield(Character bot) {
        if (bot != null) {
            stageWalkShieldUntilByBot.put(bot.getId(),
                    movementNowMs() + STAGE_WALK_SHIELD_MS);
        }
    }

    private static void disarmStageWalkShield(Character bot) {
        if (bot != null) {
            stageWalkShieldUntilByBot.remove(bot.getId());
        }
    }

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

    /** The approach outcome for a box the bot is walking to. */
    public enum Approach { IN_POSITION, TRAVELLING, STUCK }

    /** botId -> the floor point the bot has failed to reach, and how many times. */
    private static final Map<Integer, Point> stuckTargetByBot = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<Integer, Integer> stuckCountByBot = new java.util.concurrent.ConcurrentHashMap<>();
    /** The driver abandons a no-progress move after this long; two abandon cycles = a dead edge. */
    private static final int STUCK_RETRIES = 2;

    /**
     * Approach the floor under an airborne target: points the movement engine at it and
     * returns at once. IN_POSITION when the bot is ALREADY on the target's platform (|dx|
     * and |dy| within the approach box); TRAVELLING while the walk is in flight; STUCK once
     * the same target has outlived two no-progress abandon cycles - the caller should drop
     * this target and try another (the engine's planned edge to it is dead, retrying only
     * replays the failure; a different box is reached by a different edge).
     *
     * <p>The caller must only strike the target on IN_POSITION. A reactor hit has no
     * server-side reach check, so firing it from across the room reads as hitting through
     * walls; the honest shot needs the bot standing beside it.
     */
    public static Approach approachUnder(Character bot, Point aerialTarget) {
        if (bot == null || aerialTarget == null || bot.getMap() == null) {
            return Approach.STUCK;
        }
        Point ground = GCMovement.groundPointBelow(bot.getMap(), aerialTarget.x, aerialTarget.y);
        Point to = ground != null ? ground : aerialTarget;
        Point pos = bot.getPosition();
        if (pos != null && Math.abs(pos.x - to.x) <= APPROACH_X && Math.abs(pos.y - to.y) <= APPROACH_Y) {
            stuckTargetByBot.remove(bot.getId());
            stuckCountByBot.remove(bot.getId());
            return Approach.IN_POSITION; // arrived; clear any stale mark
        }
        Point failed = stuckTargetByBot.get(bot.getId());
        if (failed != null && failed.equals(to)) {
            int attempts = stuckCountByBot.merge(bot.getId(), 1, Integer::sum);
            if (attempts > STUCK_RETRIES) {
                return Approach.STUCK; // this edge is dead; make the caller try a different one
            }
        } else {
            stuckTargetByBot.put(bot.getId(), new Point(to));
            stuckCountByBot.put(bot.getId(), 1);
        }
        // Re-issue the move when idle: the driver's own no-progress watchdog abandons a
        // stalled plan after MOVE_NO_PROGRESS_MS, and this re-issue replans from the bot's
        // CURRENT pixel - which is also what recovers a bot the executor left on a ledge it
        // cannot launch from.
        if (!GCMovement.isMoving(bot)) {
            GCMovement.move(bot, to.x, to.y);
        }
        return Approach.TRAVELLING;
    }

    /** Striking box on the same platform: the approach box around its floor point. */
    private static final int APPROACH_X = 90;
    private static final int APPROACH_Y = 70;

    /**
     * Approach the floor under an aerial target when that floor is BELOW the bot, walking
     * whatever edge chain the map offers (walk-off / down-jump / rope descend).
     *
     * <p>{@link #approachUnder} answers IN_POSITION once the bot is within its box of the
     * target's floor point, which is exactly the box the bot already stands in when it began
     * ABOVE that floor - so a bot on a platform with its box below at the bottom of a tower
     * reads as "in position" and stands still, one room short of the fight (the LPQ stage-2
     * "bots broke the first box and stopped" report: the nearest remaining box hangs 335px
     * straight down, out of jump reach, over one-way ledges).
     *
     * <p>The fix reads the nav graph: when the target floor is lower than the bot, the goal
     * handed to the driver is the raw aerial point itself (a region the bot is not standing
     * in), so the planner must produce an edge chain down - and the executor walks it (v59+
     * graphs mint rope-descend / uncapped down-jump / walk-off edges; GRAPH_VERSION 64's
     * deep-drop escape hatch covers the tower's last shaft). A graph that cannot (or a point
     * with no floor under it) falls back to the plain approach. STUCK propagates, so the
     * rotate-on-stuck callers try a different box instead of replaying a dead edge.
     */
    public static Approach descendToFloorAerialTarget(Character bot, Point aerialTarget) {
        if (bot == null || aerialTarget == null || bot.getMap() == null) {
            return Approach.STUCK;
        }
        Point pos = bot.getPosition();
        Point ground = GCMovement.groundPointBelow(bot.getMap(), aerialTarget.x, aerialTarget.y);
        if (!descendNeedsFloor(pos, ground)) {
            // No floor under the target, or the bot is already level with it: the plain
            // approach's answer is the right one here.
            return approachUnder(bot, aerialTarget);
        }
        // A healthy descent takes many macro ticks (ropes, drop floors), so the STUCK count
        // must track "the driver gave up on this floor" - not merely the same target again.
        // Only an IDLE driver between re-issues means the edge chain is not working.
        if (!GCMovement.isMoving(bot)) {
            if (ground.equals(stuckTargetByBot.get(bot.getId()))) {
                int attempts = stuckCountByBot.merge(bot.getId(), 1, Integer::sum);
                if (attempts > STUCK_RETRIES) {
                    // This floor is dead for THIS round of candidates; drop the record so the
                    // next tick's retry starts fresh instead of STUCK-ing on sight forever
                    // (every caller-side rotate candidate can fail - without the reset that
                    // would strand the bot with no box it may approach, permanently).
                    stuckTargetByBot.remove(bot.getId());
                    stuckCountByBot.remove(bot.getId());
                    return Approach.STUCK; // make the caller try another box
                }
            } else {
                stuckTargetByBot.put(bot.getId(), new Point(ground));
                stuckCountByBot.put(bot.getId(), 1);
            }
            GCMovement.move(bot, aerialTarget.x, aerialTarget.y);
        }
        return Approach.TRAVELLING;
    }

    /**
     * Whether the floor under an aerial target needs a DESCENT to reach: the floor exists and
     * sits clearly below the bot. Pure so the LPQ tower numbers are unit-testable without a
     * map ({@code groundUnderTarget} is what {@link GCMovement#groundPointBelow} resolved).
     */
    static boolean descendNeedsFloor(Point botPos, Point groundUnderTarget) {
        return groundUnderTarget != null && botPos != null
                && groundUnderTarget.y > botPos.y + APPROACH_Y;
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
        // Play the swing, or the reactor breaks by itself while the bot stands idle - the
        // player watches boxes pop open with nobody touching them. Same order OPQ uses:
        // swing first, the engine hit lands under it.
        BotAttack.basicSwing(bot);
        CustomReactor.hitReactorWithScript(bot.getMap(), reactorOid, bot);
    }

    /**
     * Every alive reactor on the map with this data id, as oids. Quest stages scatter several
     * boxes of the same id across the room (LPQ stage 2's eleven pass boxes share 2202003), so
     * the first-oid lookup cannot reach them all.
     *
     * <p>The filter is the engine's own {@code isActive} - alive AND with a further state to
     * walk - not merely {@code isAlive}: on this host a fully broken box is never removed from
     * the map (its {@code reactorTime} is negative, so the break path skips destroyReactor) and
     * stays {@code alive=true} in its terminal state forever. A broken box must not read as a
     * candidate, or the bot walks back to the shell it just emptied and stands there swinging
     * at nothing.
     */
    public static java.util.List<Integer> findAllReactorOids(Character bot, int dataId) {
        if (bot == null || bot.getMap() == null) {
            return List.of();
        }
        return bot.getMap().getAllReactors().stream()
                .filter(r -> r.getId() == dataId && r.isActive())
                .mapToInt(Reactor::getObjectId)
                .boxed().toList();
    }

    /**
     * Reactor oid by data id, or -1. Quests name reactors by data id ("stone4" and friends).
     *
     * <p>Filtered on the engine's own {@code isActive}, not {@code isAlive}: a fully broken
     * reactor stays in the map forever on this host (negative reactorTime skips the destroy
     * path) and keeps {@code isAlive} true in its terminal state. Without the filter the
     * first-oid lookup re-locks onto the last box the party emptied - Pirate's box loop
     * struck the shell once, saw it "still alive", and broke every tick.
     */
    public static int findReactorOid(Character bot, int dataId) {
        if (bot == null || bot.getMap() == null) {
            return -1;
        }
        return bot.getMap().getAllReactors().stream()
                .filter(r -> r.getId() == dataId && r.isActive())
                .mapToInt(r -> r.getObjectId())
                .findFirst().orElse(-1);
    }

    // ===== Beat-driven reactor work: approach + strike on the 250ms combat sweep =====
    //
    // The stage helpers above only RUN on the macro tick (2-6s observed, 36-48s not), so a
    // bot that arrived beside a box mid-sweep stood there until the next macro beat before
    // its first strike, and re-docked 2-6s after every box. These entries let the combat
    // sweep carry the in-flight box work between macro beats: the stage registers the box it
    // is walking to and strikes it the moment the approach reports IN_POSITION, at the
    // driver's swing cadence, so arrival converts into hits within one 250ms beat.

    /** botId -> the box the combat sweep may strike once the approach lands (oid + map instance). */
    private record BeatBox(int oid, int mapInstance) {
    }

    private static final Map<Integer, BeatBox> beatBoxByBot = new java.util.concurrent.ConcurrentHashMap<>();
    /** botId -> the swing beat's next allowed epoch (the driver cadence mirrors here). */
    private static final Map<Integer, Long> nextReactorSwingAtByBot = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Register {@code oid} as the reactor the combat sweep should carry while the stage walk
     * is in flight (call from the macro tick right after issuing the approach). The sweep
     * strikes it on arrival; a {@code null}/{@code -1} clears the registration. Keyed to the
     * map instance: reactor oids collide across maps (the stage-2 tower and its trap room),
     * so an armed box must die with the room change.
     */
    public static void armReactorBeat(Character bot, int oid) {
        if (bot == null) {
            return;
        }
        if (oid >= 0) {
            beatBoxByBot.put(bot.getId(), new BeatBox(oid, System.identityHashCode(bot.getMap())));
        } else {
            beatBoxByBot.remove(bot.getId());
        }
    }

    public static void clearReactorBeat(int botId) {
        beatBoxByBot.remove(botId);
        nextReactorSwingAtByBot.remove(botId);
    }

    /** Whether a box is registered for the combat sweep to carry (the beat's steering gate). */
    public static boolean reactorBeatArmed(Character bot) {
        return bot != null && beatBoxByBot.containsKey(bot.getId());
    }

    /**
     * The swing beat for reactor work: ~{@link #REACTOR_SWING_MIN_MS} between hits, per bot.
     * The 250ms burst read as machine-gun swings; a player's repeat rate is the weapon's
     * attack cadence, which the attack driver already models at 720-900ms.
     */
    private static boolean reactorSwingReady(Character bot, long now) {
        return now >= nextReactorSwingAtByBot.getOrDefault(bot.getId(), 0L);
    }

    /**
     * Strike {@code oid} now if the swing beat allows: the basic swing plus the engine's own
     * hit, the same pair {@link #hitReactor} plays. A fully broken box (its state walk has no
     * further step, {@code isActive} false) drops out of the registration and pulls the next
     * macro tick forward - picking the next box is stage logic that only runs there, and
     * without the pull the party stares at the bot standing beside the rubble it just made
     * for one full macro cadence (the "打完停顿才继续" half of the rhythm report). The nudge
     * is debounced, so a multi-box combo only ever pulls one tick, and the cadence settles
     * back on its own.
     */
    private static void strikeReactorOnBeat(Character bot, int oid, long now) {
        nextReactorSwingAtByBot.put(bot.getId(), now + REACTOR_SWING_MIN_MS
                + ThreadLocalRandom.current().nextLong(REACTOR_SWING_JITTER_MS));
        hitReactor(bot, oid);
        var reactor = bot.getMap().getReactorByOid(oid);
        if (reactor == null || !reactor.isActive()) {
            beatBoxByBot.remove(bot.getId());
            nudgeStageTick(bot);
        }
    }

    /**
     * Carry the armed reactor work one combat beat: approach the box, strike it the beat the
     * approach says IN_POSITION. Non-blocking by design - the caller is the shared 250ms
     * sweep, one Thread.sleep here would hold every registered bot's beat. The registration
     * dies with the map instance it was armed on (oid collisions across rooms).
     *
     * @return true when the approach is still TRAVELLING (the caller may keep swinging at
     *         whatever is in reach - attack never steers), false when the box work ended the
     *         beat (struck, broken, stuck, or disarmed by a room change).
     */
    public static boolean workReactorOnBeat(Character bot) {
        if (bot == null || bot.getMap() == null) {
            return false;
        }
        BeatBox box = beatBoxByBot.get(bot.getId());
        if (box == null || box.mapInstance() != System.identityHashCode(bot.getMap())) {
            return false; // stale room (or nothing armed): the stage re-arms on its next tick
        }
        int oid = box.oid();
        long now = movementNowMs();
        var reactor = bot.getMap().getReactorByOid(oid);
        if (reactor == null || !reactor.isActive()) {
            beatBoxByBot.remove(bot.getId()); // broken by a teammate mid-walk
            return false;
        }
        // Never strike from across the room: the same honest-shot rule approachUnder's
        // callers follow. The approach also owns the walk (and re-issues it when the driver
        // has given up), so the beat advances the descent/approach the macro tick started.
        Approach outcome = descendToFloorAerialTarget(bot, reactor.getPosition());
        if (outcome == Approach.STUCK) {
            beatBoxByBot.remove(bot.getId()); // the stage's next tick rotates the target
            return false;
        }
        if (outcome == Approach.IN_POSITION && reactorSwingReady(bot, now)) {
            strikeReactorOnBeat(bot, oid, now);
            return false;
        }
        return outcome == Approach.TRAVELLING;
    }

    /** Floor between two reactor swings: a player's repeat rate, not a machine-gun burst. */
    private static final long REACTOR_SWING_MIN_MS = 600;
    /** Jitter on that floor so a cohort does not swing in lockstep. */
    private static final long REACTOR_SWING_JITTER_MS = 250;

    /** Short delay for the pulled-forward macro tick: a breath between boxes, not a snap. */
    private static final long STAGE_TICK_NUDGE_MS = 500;

    /**
     * Pull this bot's next macro tick forward so stage work (walk a portal/exit's follow-up,
     * rotate to the next box, loot, hand off) resumes promptly after the event it waits on:
     * a walk's arrival or a beat-driven box break. The pull is the only bridge from the
     * movement/beat layers back into stage logic - it must never run the stage inline, that
     * is what produced the old act-freeze-act cadence. Debounced by BotSM's own nudge guard;
     * a no-op when the wheel entry is gone (bot stopping).
     *
     * <p>ALWAYS DISPATCHED, never inlined: this is reachable from the movement driver's tick,
     * which holds the bot's movement monitor, and {@code BotSM.nudgeSoon} is synchronized on the
     * macro monitor. Taking the macro monitor from there closes an AB-BA cycle with the stop path
     * ({@code stopScheduledTask} holds the macro monitor and waits for the movement monitor via
     * {@code GCMovement.disable}) - a hard deadlock that leaves the bot frozen and the driver
     * thread parked forever. The same hazard is already handled this way in BotDeath,
     * BotMapEntryResponder and BotPlayerReaction. Off the driver there is no order to invert, so
     * dispatching unconditionally is always safe (the nudge is cheap and debounced).
     */
    private static void nudgeStageTick(Character bot) {
        final int botId = bot.getId();
        ExecutorServiceManager.runAsync(() -> {
            BotSM nudgeable = CharacterStorage.getBotById(botId);
            if (nudgeable != null) {
                nudgeable.nudgeSoon(STAGE_TICK_NUDGE_MS);
            }
        });
    }

    /**
     * The stage's visit just finished a box: pull the next macro tick forward so the next
     * box (or the loot/hand-off) starts within a breath instead of a full cadence. Called
     * from BOTH the in-visit combo path (macro tick) and the beat-carried strike path
     * (combat sweep) - whichever broke the box, the next box is stage logic and lives on
     * the macro tick.
     */
    public static void boxFinishedPullTick(Character bot) {
        nudgeStageTick(bot);
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
     *
     * <p>The transfer is real: the stack is removed from the bot's inventory before the
     * drop spawns, so a bot can never hand on more than it actually carried. The drop is
     * also permanently owned by the receiver, so the other bots' floor sweeps (which
     * otherwise read every pass on the ground as loot, the leader's pile included) cannot
     * turn the hand-off into a pass ping-pong across the room.
     */
    public static void giveItemTo(Character bot, Character receiver, int itemId, int qty) {
        giveItemAt(bot, receiver, itemId, qty, receiver.getPosition());
    }

    /**
     * As {@link #giveItemTo}, but the pile lands at an explicit spot - the addressed-drop
     * delivery posts the stock at THE STAGE NPC (the bot's own feet, where it parks) instead
     * of teleporting it to wherever the leader is standing mid-map.
     */
    public static void giveItemAt(Character bot, Character receiver, int itemId, int qty, Point at) {
        if (bot == null || receiver == null || qty <= 0) {
            return;
        }
        if (bot.getMapId() != receiver.getMapId()) {
            return; // nowhere to drop it; the hand-off waits for a shared map
        }
        int carried = countItem(bot, itemId);
        if (carried <= 0) {
            return;
        }
        int handed = Math.min(qty, carried);
        BotClientBinding.runWithBoundPlayer(bot, () ->
                InventoryManipulator.removeById(bot.getClient(),
                        ItemConstants.getInventoryType(itemId), itemId, handed, true, false));
        DropCommands.botThrowToOwnerItemQtyAt(bot, itemId, handed, receiver, at);
    }

    /**
     * Drop an already-removed-from-inventory stack addressed to {@code receiver}. The caller
     * has done the real inventory removal (a batched hand-off of a known quantity); this is
     * only the addressed spawn. A receiver on another map refuses.
     */
    public static void giveItemToQuiet(Character bot, Character receiver, int itemId, int qty) {
        if (bot == null || receiver == null || qty <= 0
                || bot.getMapId() != receiver.getMapId()) {
            return;
        }
        DropCommands.botThrowToOwnerItemQty(bot, itemId, qty, receiver);
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
        // Hand over up close. A pile dropped across the room sits owned by the leader where
        // nobody but him can pick it, and the host despawns drops nobody picked up - an
        // unreachable pile is a timed wipe of the bot's whole stock. Walk to him first; the
        // move is fire-and-forget, so the hand-off just waits for a tick where he is close.
        if (leader.getMapId() != bot.getMapId()) {
            return 0; // nothing to do here; the party's own movement brings them together
        }
        if (!leaderNear(bot, leader)) {
            GCMovement.move(bot, leader.getPosition().x, leader.getPosition().y);
            return 0;
        }
        giveItemTo(bot, leader, itemId, qty);
        return qty;
    }

    /** Inside this range a drop at the leader's feet is his to sweep instantly. */
    private static final double HANDOFF_RANGE_SQ = 400.0 * 400.0;

    /**
     * Whether the leader stands within hand-off range right now - the caller's cue to deliver
     * for free instead of steering a walk toward him (a fight-stage bot keeps its combat post;
     * the delivery happens when the room quiets or the paths cross).
     */
    public static boolean leaderNear(Character bot, Character leader) {
        return bot != null && leader != null && leader != bot
                && leader.getMapId() == bot.getMapId()
                && bot.getPosition() != null && leader.getPosition() != null
                && bot.getPosition().distanceSq(leader.getPosition()) <= HANDOFF_RANGE_SQ;
    }

    /**
     * Deliver this bot's whole stock of an item to the leader, but ONLY once the stage's work
     * is done (the callers gate that). The delivery point is THE STAGE NPC POST, and nowhere
     * else: the bot walks there and only drops once standing on the post, addressed to the
     * leader, who sweeps it when he comes to the NPC for the turn-in. Mid-stage proximity
     * delivery (drop whenever the leader walks near, wherever he is) is gone on purpose - it
     * sprayed piles across rooms mid-fight and ping-ponged them with the reclaim loop.
     *
     * <p>The post is the nearest NPC in the room: use this overload only where the room holds a
     * single one. {@link #handItemsToLeaderAfterStage(Character, int, int)} names the stage's
     * turn-in NPC for rooms that also hold the map-exit NPC.
     *
     * @return how many of the item were dropped for the leader (0 when there was nothing)
     */
    public static int handItemsToLeaderAfterStage(Character bot, int itemId) {
        return handItemsToLeaderAfterStage(bot, itemId, 0);
    }

    /**
     * As {@link #handItemsToLeaderAfterStage(Character, int)}, with the stage's turn-in NPC
     * named by id ({@code stageNpcId > 0}). The quest layer knows which stage it is standing in,
     * so it can name the NPC; rooms that hold both the turn-in NPC and the map-exit NPC (every
     * Ludi PQ stage room) would otherwise post the stock wherever a proximity choice landed.
     */
    public static int handItemsToLeaderAfterStage(Character bot, int itemId, int stageNpcId) {
        if (bot == null || countItem(bot, itemId) <= 0) {
            return 0;
        }
        // Hold the delivery post: the NPC ring spot the cleared-stage wait uses, so the
        // leader knows where the party's stock is. No-op when the room has no NPC (trap
        // room) - the caller handles that room separately.
        Point post = stageNpcSpot(bot, stageNpcId);
        if (post == null) {
            return 0; // nowhere to post the delivery - the stock stays pocketed
        }
        // Reach the post FIRST: the rule is piles AT the stage NPC. A bot still walking
        // there has not delivered this tick (the arrival nudge re-runs it promptly).
        if (bot.getPosition().distanceSq(post) > PICKUP_REACH_SQ) {
            // The delivery re-arms every tick while the stock is held, so it must stand down
            // for a live move like every other recurring origin (yieldsToLiveWalk) - a pickup/
            // reclaim walk armed earlier this tick would otherwise be overwritten and its
            // drop left orphaned. The next tick (or the walk's arrival nudge) re-arms this.
            if (yieldsToLiveWalk(bot, post)) {
                return 0;
            }
            walkTo(bot, post);
            return 0;
        }
        Character leader = partyLeader(bot);
        if (leader == null || leader == bot || leader.getMapId() != bot.getMapId()) {
            return 0;
        }
        int qty = countItem(bot, itemId);
        if (qty <= 0) {
            return 0;
        }
        // At the post: drop the whole stock addressed to the leader. The piles sit here
        // untouched until HE comes for the turn-in - the delivery point is this post and
        // nowhere else.
        giveItemAt(bot, leader, itemId, qty, post);
        return qty;
    }

    /**
     * Sweep back the hand-off piles this bot dropped that the leader has not picked up yet.
     *
     * <p>The host despawns drops after {@code item_expire_time} (3 min by default) whether or
     * not they are owned, so a leader busy fighting can cost the bot its whole stock. A pile
     * of ours older than this window is walked back to and picked up - it re-enters the bot's
     * inventory and the next hand-off attempt drops it again at the delivery post.
     *
     * @return how many items were recovered
     */
    public static int recoverUngatheredHandoffs(Character bot, int itemId) {
        if (bot == null || bot.getMap() == null) {
            return 0;
        }
        long now = movementNowMs();
        List<MapObject> mine = BotLogic.checkForItemsOnFloor(bot, bot.getPosition(),
                HANDOFF_RECLAIM_RANGE_PX, new int[]{itemId});
        int recovered = 0;
        for (MapObject obj : mine) {
            if (!(obj instanceof MapItem drop) || drop.isPickedUp()) {
                continue;
            }
            // Only piles this bot dropped FOR the leader (owner-addressed, permanent owner).
            if (!drop.isPermanentOwner() || drop.getOwnerId() == bot.getId()) {
                continue;
            }
            Character leader = partyLeader(bot);
            if (leader == null || drop.getOwnerId() != leader.getId()) {
                continue;
            }
            if (now - drop.getDropTime() < HANDOFF_RECLAIM_AFTER_MS) {
                continue; // still fresh; give the leader time
            }
            // Walk there and pick up only once close. The engine's pickupItem has no distance
            // check (a real client walks to the item first), so the old grab-from-anywhere
            // teleported the pile across the room into the bot - the reported vacuum pickup.
            Point at = drop.getPosition();
            if (at == null) {
                continue;
            }
            if (bot.getPosition().distanceSq(at) > PICKUP_REACH_SQ) {
                // The reclaim re-runs every deliverAndPark tick and must not overwrite a live
                // move (yieldsToLiveWalk) - a pickup walk/reclaim walk/chase already in flight
                // owns the driver, and the pile has the rest of its 3-minute despawn window to
                // be walked to on a later tick.
                if (!yieldsToLiveWalk(bot, at)) {
                    walkForPickup(bot, at);
                }
                continue; // out of reach; the pile is picked up on a later tick, up close
            }
            DropCommands.botLootSingleDrop(bot, drop);
            recovered++;
            if (recovered >= HANDOFF_RECLAIM_MAX) {
                break;
            }
        }
        return recovered;
    }

    /** A hand-off pile older than this is walked back and re-pocketed (see recoverUngatheredHandoffs). */
    private static final long HANDOFF_RECLAIM_AFTER_MS = 30_000L;
    /** At most this many piles per tick, to pace the walk. */
    private static final int HANDOFF_RECLAIM_MAX = 3;
    /**
     * How far away a reclaimable hand-off pile may sit, in px. The bot walks to it, so this is a
     * room-scale radius, not an at-the-feet box; the sweep must be able to see a pile the leader
     * walked past.
     */
    private static final int HANDOFF_RECLAIM_RANGE_PX = 9_000;

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
     * a target it picked at random. A bot that wanders mid-check reads as absent. The walk in
     * arms the stage-walk shield, and the hold extends it: a puzzle room with mobs must not
     * have its hold yanked apart by the combat beat's chase move.
     */
    public static void holdArea(Character bot, Point spot, long millis) {
        if (bot == null || spot == null) {
            return;
        }
        walkTo(bot, spot);
        // Capped at one walk block: a longer hold freezes a busy stage tick for the whole
        // span, and a bot-only room's 36-48s cadence already spaces its ticks far apart.
        blockingSleep(Math.min(millis, WALK_BLOCK_CAP_MS));
        armStageWalkShield(bot); // keep the beat off the bot while it holds the puzzle spot
    }

    /** Inline-pickup cap for a loot sweep (see {@link #loot}): what one tick may pay for. */
    private static final int LOOT_INLINE_MAX = 5;

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

    // Seek-and-attack pacing: the fight runs on the shared combat ticker's 250ms beat (the
    // PartyQuestBot registers itself as a GrindTickRegistry.Participant while inside a quest
    // room), the same cadence the grind brain fights on. Each beat swings once (the driver's
    // own cooldown gates what lands) and keeps the sticky chase state below alive across
    // beats, so closing on a mob and hammering it need no blocking sleeps - the "act, freeze,
    // act" the macro cadence produced came from waiting out cooldowns inline, and at 4Hz that
    // wait is simply the next beat.
    private static final int SEEK_RANGE_X = 900;            // hunt a live mob within this |dx| (cross-ledge)
    // LPQ stage 1 (922010100) seats its first Ratz 580px above the entry floor over a series of
    // one-way ledges - a box tuned to the grind maps' floor stacks stops the chase before it starts
    // and the room reads as quiet forever (the mobs are mobTime=-1 and never close the gap). The
    // whole tower is ~3000px tall, so a bot standing anywhere in it sees the whole hunt.
    static final int SEEK_STACK_RANGE_Y = 3_200;    // tall PQ towers are one vertical room
    private static final int RETARGET_EPS_PX = 16;          // skip re-issuing a move for tiny shifts
    private static final long RETARGET_TIMEOUT_MS = 4_000;  // give up an unreachable target after this
    private static final int PROGRESS_EPS_PX = 20;          // movement worth counting as chase progress
    /** A mob within this |dy| of the pinned anchor's floor counts as still on the pinned platform
     *  (the one-beat off-platform blip tolerance). Same-LEVEL itself is decided by nav region, not
     *  a raw y band — a 60px band on the LPQ stage-1 tower spans three ~22px stair rows. */
    private static final int FLOOR_SNAP_BAND_PX = 60;
    private static final Map<Integer, Integer> seekTargetByBot = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<Integer, Point> seekAnchorByBot = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<Integer, Long> seekDeadlineByBot = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<Integer, Point> seekLastGoalByBot = new java.util.concurrent.ConcurrentHashMap<>();
    // PINNED chase PLATFORM (LPQ stage-1 "追着上方巡逻怪小步卡顿、永远上不去"): a live mob's position
    // changes EVERY beat (aggro walk / knockback / an airborne arc), and re-aiming the seek move at
    // its floor point each beat can land that point in a DIFFERENT nav region than the last — so the
    // driver discards the committed climb edge mid-rope, replans from the bottom, and the bot paces
    // under the mob forever. So the chase pins the mob's PLATFORM (the nav region), not its pixel:
    // while the mob's live floor stays on the pinned platform the bot walks its live x (a patrol is
    // followed; the region is unchanged so the committed edge survives), and the pinned floor is held
    // through an off-platform blip until CHASE_PLATFORM_DRIFT_BEATS consecutive off-platform beats
    // prove the mob changed floors. Per-bot state: the pinned floor point + the off-platform count.
    private static final Map<Integer, Point> chaseAnchorByBot = new java.util.concurrent.ConcurrentHashMap<>();
    /** botId -> consecutive beats the mob's live floor has been off the pinned platform. */
    private static final Map<Integer, Integer> chaseDriftByBot = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int CHASE_PLATFORM_DRIFT_BEATS = 4;  // ~1s of beats before re-deriving
    // REGION-STABLE GOAL (the unified gate for the whole region-flip family): whatever the anchor
    // branch above computes, the goal actually ISSUED must not change nav region beat to beat — a
    // region flip is what makes the driver discard the committed climb/jump edge mid-flight (the
    // bot replans from the bottom and paces under the mob). The candidate goal's region is compared
    // against the last issued goal's region; a flip is held on the PREVIOUS goal until
    // CHASE_PLATFORM_DRIFT_BEATS consecutive flipping beats prove the mob really changed platforms
    // (then the new region is accepted and the progress clock restarts). Unbaked graphs (-2) and
    // region-less points (-1) cannot answer, so they pass through ungated (peek-only: no bake stall).
    private static final Map<Integer, Point> chaseGoalByBot = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<Integer, Integer> chaseGoalRegionByBot = new java.util.concurrent.ConcurrentHashMap<>();
    /** botId -> consecutive beats the candidate goal's region differed from the issued one. */
    private static final Map<Integer, Integer> chaseFlipByBot = new java.util.concurrent.ConcurrentHashMap<>();
    /** botId -> best manhattan distance to the chased mob so far this episode (progress = closing). */
    private static final Map<Integer, Integer> seekBestDistByBot = new java.util.concurrent.ConcurrentHashMap<>();
    // Goal-pathability memo: a full A* per 250ms beat per bot is waste while the goal's nav region
    // is unchanged (the region gate above pins it for the whole episode). The verdict is cached
    // against the goal's peek region and re-verified only when that region changes (or falls
    // outside the memo's domain). The memo's region ids come from the peek graph while canPathTo
    // plans on the bot's best graph — a mismatch can only ever SKIP a re-check, never fake one:
    // a stale "pathable" self-heals through the driver's own no-path handling and the seek deadline.
    private static final Map<Integer, Integer> chasePathRegionByBot = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<Integer, Integer> chasePathSurfaceYByBot = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<Integer, Boolean> chasePathableByBot = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Seek a mob and fight it, the way the roaming grind brain does: swing at whatever the
     * attack driver already reaches, and when nothing is in reach, find the nearest live mob
     * (across ledges - platforms above, below, ropes between) and walk/jump/climb toward it.
     * The next beat re-checks: closer now, swing; still far, keep moving.
     *
     * <p>This is the half the plain {@link #attack} never had: {@code botAttack} only swings
     * at mobs inside its reach box, and its nearest-mob scan is same-ledge only, so a quest
     * bot facing Ratz on a platform overhead stood still forever. Every stage that reads
     * "kill what is in the room" should call this instead - the swings land, the drops fall,
     * and the bot is never a bystander in its own room.
     *
     * <p>Non-blocking by design: the caller is the shared 250ms combat sweep, and one Thread.sleep
     * here would hold every registered bot's beat. One swing per call (the driver's cooldown
     * gates it), then a chase step - the cadence that reads as fighting comes free at 4Hz.
     *
     * <p>Movement goes through the dynamic engine ({@code GCMovement.move}), which paths
     * across the map's own terrain: walks, jumps, drops and rope climbs are its edges, so
     * "climb the rope to the mob's platform" needs nothing from the caller. Unreachable
     * targets are dropped after a no-progress timeout and re-seeked next beat.
     */
    public static void seekAndAttack(Character bot) {
        if (bot == null || bot.getMap() == null) {
            return;
        }
        Point pos = bot.getPosition();
        if (pos == null) {
            return;
        }

        // 1. Swing at whatever is already in the attack driver's reach. One swing per beat:
        //    the driver's own cooldown (720-900ms for the melee/magic profiles) gates what
        //    lands, and the sweep's next beat is the wait the old burst used to sleep out.
        BotAttackDriver.botAttack(bot);

        // 2. Nothing landed (nothing in reach, cooldown, or debuffed): keep closing on the
        //    chase target. The chase target must be PATHABLE: on a tower whose climb chain
        //    the graph cannot plan end-to-end, the nearest mob sits across a missing link and
        //    the un-pathable chase degrades into walking the bot's own floor under it forever
        //    (the stage-1 report). Prefer the nearest pathable hostile; only a room where
        //    NOTHING is pathable keeps the plain nearest (steering is still better than
        //    standing, and the graph may bake later).
        Monster target = seekPathableTarget(bot, pos);
        if (target == null) {
            seekLastGoalByBot.remove(bot.getId());
            clearChaseGoalState(bot.getId());
            return; // the room is quiet (or nothing reachable); hold position this beat
        }

        // Close on the target: walk to the floor under it (the nav layer jumps/drops/climbs
        // ropes as its edges need). Ranged/magic reach is respected by the swing above firing
        // before the walk gets there, so this walk always ends in a landed swing or a timeout.
        Point mp = target.getPosition();

        // FROZEN PLATFORM / LIVE PIXEL: the nav layer must not have its goal's REGION flip beat to
        // beat (a live mob's floor point on an airborne arc probes a DIFFERENT foothold, so the
        // committed climb edge is discarded mid-rope and the bot paces under the mob forever). But
        // the mob's PIXEL is not what must be frozen - only its PLATFORM. So: while the mob's live
        // floor stays on the pinned platform, follow its live x (a patrol is walked, and the region
        // is unchanged, so the committed edge survives); only when its live floor leaves the platform
        // (airborne arc / another ledge) is the pinned goal held, and only after
        // CHASE_PLATFORM_DRIFT_BEATS consecutive off-platform beats is the platform re-pinned.
        //
        // Pinning x (the old behaviour) was the stage-1 "追着上方怪小步卡顿、上不去" bug: a mob above
        // the bot never reads same-level, so tx stayed at the FIRST-SEEN x forever - the bot walked to
        // a stale pixel, the no-progress watchdog abandoned the move, and the seek's retarget memo
        // (that same stale x) then refused to re-arm it: the bot hung on the rope until the 4s seek
        // deadline wiped state. Following the live x while the platform holds fixes both.
        Point mobFloor = GCMovement.groundPointBelow(bot.getMap(), mp.x, mp.y);
        Point anchorFloor = chaseAnchorByBot.get(bot.getId());
        // Same level = the mob stands on the bot's OWN walk-connected surface (one nav region),
        // NOT a raw y-difference: on the LPQ stage-1 tower the stair rows sit ~22px apart, so a
        // y-band spans three rows and a mob one or two rows up read "same level" - its live-x
        // re-aims then discarded the committed climb edge every beat (卡绳索/永不登台). Regions are
        // the platform granularity the whole frozen-anchor design is keyed on; a raw band is not.
        boolean sameLevel = mobFloor != null && pos != null
                && GCMovement.peekRegionIdAt(bot.getMap(), mp.x, mobFloor.y) >= 0
                && GCMovement.peekRegionIdAt(bot.getMap(), mp.x, mobFloor.y)
                        == GCMovement.peekRegionIdAt(bot.getMap(), pos.x, pos.y);
        int tx;
        int ty;
        if (anchorFloor == null || mobFloor == null) {
            anchorFloor = new Point(mp.x, (mobFloor != null) ? mobFloor.y : mp.y);
            chaseAnchorByBot.put(bot.getId(), anchorFloor);
            chaseDriftByBot.remove(bot.getId());
            tx = anchorFloor.x;
            ty = anchorFloor.y;
        } else if (sameLevel
                || Math.abs(mobFloor.y - anchorFloor.y) <= FLOOR_SNAP_BAND_PX) {
            // Same level (no climb at stake) or the mob's floor probe still reads the pinned
            // platform: follow the mob's live x on the PINNED floor y (the probe falling through
            // a shelf gap onto a deep row is an off-platform blip, and only a same-REGION mob
            // moves the goal y). What keeps the committed climb edge alive is not a frozen x -
            // it is the region-stable goal gate below: a goal whose REGION would change is held
            // back there, while an x shift inside one region never discards the edge
            // (reuseCommittedEdge drops it only on a region change). An earlier cross-level
            // x-pin description was superseded by that gate; live-x tracking is the shipped
            // behaviour.
            anchorFloor = new Point(mp.x, anchorFloor.y);
            chaseAnchorByBot.put(bot.getId(), anchorFloor);
            chaseDriftByBot.remove(bot.getId());
            tx = mp.x;
            ty = sameLevel ? mobFloor.y : anchorFloor.y;
        } else {
            int drift = chaseDriftByBot.merge(bot.getId(), 1, Integer::sum);
            if (drift >= CHASE_PLATFORM_DRIFT_BEATS) {
                anchorFloor = new Point(mp.x, mobFloor.y);
                chaseAnchorByBot.put(bot.getId(), anchorFloor);
                chaseDriftByBot.remove(bot.getId());
                seekAnchorByBot.remove(bot.getId()); // a new platform restarts the progress clock
                tx = mp.x;
                ty = mobFloor.y;
            } else {
                tx = anchorFloor.x; // hold the pinned platform through the off-platform blip
                ty = anchorFloor.y;
            }
        }

        // Some quest rooms seat their prize mob on a ledge the nav graph cannot climb TO (a
        // pedestal with no upward edges - LPQ's Alishar). Chasing the mob's own platform would
        // re-issue an unwalkable goal every tick, so aim for the floor UNDER the mob instead:
        // the bot ends up standing beneath it, which is a real fight position (the boss reach
        // box is vertically padded) and a far better crowd position than the doorway.
        // Memoized per goal-region: the region gate below pins the goal for whole seconds at a
        // time, so re-running a full A* on every beat while pinned is pure waste (and the trace
        // call would double it). The cache is re-verified whenever the goal's region changes.
        boolean pathable = pathableMemo(bot, tx, ty);
        if (!pathable) {
            Point underMob = GCMovement.groundPointBelow(bot.getMap(), mp.x, mp.y + 1);
            if (underMob != null && Math.abs(underMob.y - ty) > 20
                    && pathableMemo(bot, mp.x, underMob.y)) {
                tx = mp.x;
                ty = underMob.y;
            }
        }

        // REGION-STABLE GOAL GATE (the unified fix for the whole region-flip family): the anchor
        // branches above still emit a goal whose nav region can differ from the one the bot is
        // actually being driven to — the sameLevel branch follows the live pixel into a gap, a
        // drift re-pin lands on a neighbouring row, and any of them discards the committed climb
        // edge MID-ROPE. Whatever the branch decided, the goal that reaches GCMovement.move must
        // hold ONE region for the whole approach: a candidate goal in a new region is held back
        // until CHASE_FLIP_BEATS consecutive flipping beats prove the mob really changed
        // platforms — then the new region is adopted and the progress clock restarts. This is the
        // single place the invariant is enforced; the anchor bookkeeping above stays heuristic.
        Point issued = stableChaseGoal(bot, new Point(tx, ty));
        tx = issued.x;
        ty = issued.y;

        // Progress bookkeeping: a chase that moves the bot nowhere for a while is dropped so
        // the next beat seeks something else instead of walking into a wall forever. "Nowhere"
        // means NOT CLOSING on the mob — raw displacement (the old test) renews the deadline for
        // a left-right sway whose stride exceeds the epsilon, which is exactly the treadmill the
        // field traces showed. The manhattan distance to the mob is the honest measure: any
        // real approach beats the best-seen by the epsilon; swaying never does.
        Point anchor = seekAnchorByBot.get(bot.getId());
        int mobDist = Math.abs(pos.x - mp.x) + Math.abs(pos.y - mp.y);
        Integer bestDist = seekBestDistByBot.get(bot.getId());
        if (anchor == null || mobDist < bestDist - PROGRESS_EPS_PX) {
            seekAnchorByBot.put(bot.getId(), new Point(pos));
            seekBestDistByBot.put(bot.getId(), mobDist);
            seekDeadlineByBot.put(bot.getId(), movementNowMs() + RETARGET_TIMEOUT_MS);
        } else if (movementNowMs() > seekDeadlineByBot.getOrDefault(bot.getId(), 0L)) {
            seekTargetByBot.put(bot.getId(), -1);
            clearChaseGoalState(bot.getId());
            seekAnchorByBot.remove(bot.getId());
            seekLastGoalByBot.remove(bot.getId());
            return;
        }

        // Retarget epsilon: re-issuing GCMovement.move for the same POINT every beat would reset the
        // walk's progress clock each time, so only a real shift in the goal re-issues it — UNLESS
        // the driver no longer holds a move target (it gave up on an unreachable leg, or the move
        // was superseded): then the bot has no goal at all and this beat must re-arm it even at the
        // same point, or it sits goal-less until the seek's own timeout (the stage-1 "hangs
        // mid-climb, only recovers after ~4s" report). hasMoveTarget is the driver's "goal still in
        // flight". The Y is part of the goal: a persistent flip adopted on a vertically-stacked
        // tower routinely keeps the same x and changes only the row — comparing x alone left the
        // new y un-issued and the bot chasing the previous row's floor until the driver's own
        // arrival cleared the stale target.
        Point lastGoal = seekLastGoalByBot.get(bot.getId());
        boolean rearmNeeded = !GCMovement.hasMoveTarget(bot);
        boolean goalShifted = lastGoal == null
                || Math.abs(tx - lastGoal.x) >= RETARGET_EPS_PX
                || Math.abs(ty - lastGoal.y) >= RETARGET_EPS_PX;
        if (rearmNeeded || goalShifted) {
            GCMovement.move(bot, tx, ty);
            seekLastGoalByBot.put(bot.getId(), new Point(tx, ty));
        }
        if (seekTraceEnabled(bot.getId())) {
            seekTrace(bot, target, mp, mobFloor, tx, ty, lastGoal);
        }
    }

    // ── Field trace (the LPQ stage-1 treadmill diagnostics): a GM toggles a per-bot trace with
    // !gcmove trace <botId>; each seek beat then logs one line — the target, its floor probe,
    // the emitted goal, the sticky target and the driver's own nav state. The line is the
    // production half of the harness-vs-field divergence hunt (the harness reproduces none of
    // the reported shapes on this map with correct WZ spans).
    private static final Map<Integer, Long> SEEK_TRACE_UNTIL = new java.util.concurrent.ConcurrentHashMap<>();

    public static void toggleSeekTrace(int botId, long durationMs) {
        if (durationMs <= 0) {
            SEEK_TRACE_UNTIL.remove(botId);
        } else {
            SEEK_TRACE_UNTIL.put(botId, movementNowMs() + durationMs);
        }
    }

    private static boolean seekTraceEnabled(int botId) {
        return movementNowMs() < SEEK_TRACE_UNTIL.getOrDefault(botId, 0L);
    }

    private static void seekTrace(Character bot, Monster target, Point mp, Point mobFloor,
                                  int tx, int ty, Point lastGoal) {
        Point pos = bot.getPosition();
        org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PqActions.class);
        log.info("[seek-trace] bot={} pos={} target={} mobPos={} mobFloor={} goal=({},{}) lastGoal={} sticky={} drift={} pathable={} nav={} edge={} block={} hasGoal={}",
                bot.getId(), pos, target.getId(), mp, mobFloor, tx, ty, lastGoal,
                seekTargetByBot.get(bot.getId()), chaseDriftByBot.get(bot.getId()),
                pathableMemo(bot, tx, ty), // the memo, not a raw A*: the trace must not double the beat's path cost
                GCMovement.navDecision(bot), GCMovement.navEdgeSummary(bot),
                GCMovement.edgeBlockReason(bot), GCMovement.hasMoveTarget(bot));
    }

    /** Release every per-bot goal-state entry (the anchor bookkeeping + the region gate). */
    private static void clearChaseGoalState(int botId) {
        chaseAnchorByBot.remove(botId);
        chaseDriftByBot.remove(botId);
        chaseGoalByBot.remove(botId);
        chaseGoalRegionByBot.remove(botId);
        chaseFlipByBot.remove(botId);
        seekBestDistByBot.remove(botId);
        chasePathRegionByBot.remove(botId);
        chasePathSurfaceYByBot.remove(botId);
        chasePathableByBot.remove(botId);
    }

    /**
     * The goal the driver is actually steered with: the candidate from the anchor bookkeeping, or
     * the PREVIOUS issued goal while the candidate's nav region still differs from it. A goal whose
     * region changes beat to beat is what makes the driver discard the committed climb/jump edge
     * mid-flight (reuseCommittedEdge sees previousTargetRegionId != targetRegionId and drops it) —
     * the bot replans from the bottom and paces under the mob. Holding the candidate until
     * CHASE_FLIP_BEATS consecutive flipping beats prove the mob moved platforms keeps every issued
     * goal region-stable for the whole approach, whichever anchor branch produced it.
     *
     * <p>Peek-only region reads (no bake join). Unbaked graph or region-less point: no answer, the
     * candidate passes through and the state is dropped — never strand a chase on an unanswerable
     * gate. This NEVER returns null: during a flip hold it returns the HELD goal, so the caller
     * re-issues it (idempotent for the driver) and keeps the driver's goal in flight — re-issuing
     * the held goal each beat is what re-arms the move if the driver's own no-progress watchdog
     * abandoned it mid-climb (the stage-1 "hangs until the 4s deadline" report).
     */
    private static Point stableChaseGoal(Character bot, Point candidate) {
        int botId = bot.getId();
        int candidateRegion = GCMovement.peekRegionIdAt(bot.getMap(), candidate.x, candidate.y);
        if (candidateRegion < 0) {
            // -1 (no ledge) or -2 (unbaked): cannot gate on region. Issue directly, and reset so a
            // later answerable beat re-arms the gate from whatever the driver last saw.
            chaseGoalByBot.remove(botId);
            chaseGoalRegionByBot.remove(botId);
            chaseFlipByBot.remove(botId);
            return candidate;
        }
        Integer lastRegion = chaseGoalRegionByBot.get(botId);
        if (lastRegion == null) {
            // First gated beat: issue and pin.
            chaseGoalByBot.put(botId, candidate);
            chaseGoalRegionByBot.put(botId, candidateRegion);
            chaseFlipByBot.remove(botId);
            return candidate;
        }
        if (candidateRegion == lastRegion) {
            // Same region: the live pixel may move within the platform (patrol tracking) — adopt
            // it and clear the flip tally.
            chaseGoalByBot.put(botId, candidate);
            chaseGoalRegionByBot.put(botId, candidateRegion);
            chaseFlipByBot.remove(botId);
            return candidate;
        }
        // Region flip: hold the previous goal until the flip proves persistent.
        int flips = chaseFlipByBot.merge(botId, 1, Integer::sum);
        if (flips < CHASE_PLATFORM_DRIFT_BEATS) {
            Point held = chaseGoalByBot.get(botId);
            return held != null ? held : candidate;
        }
        // Persistent flip: the mob really changed platforms — adopt the candidate, restart.
        chaseGoalByBot.put(botId, candidate);
        chaseGoalRegionByBot.put(botId, candidateRegion);
        chaseFlipByBot.remove(botId);
        seekAnchorByBot.remove(botId); // a new platform restarts the progress clock
        seekBestDistByBot.remove(botId);
        return candidate;
    }

    /**
     * {@link GCMovement#canPathTo}, memoized per (goal nav region, goal surface Y): the region
     * gate above keeps the goal in one region for whole seconds, so the beat-by-beat A* re-check
     * is only meaningful when that changes. Region alone is NOT a sufficient key: one region can
     * be two platforms split by a gap (map 1020000 r11's walkway pair), and a verdict from one
     * platform must not serve a goal on the other. The goal's probed surface Y is the platform
     * discriminator — only a goal whose region AND floor both match the memo can skip the A*.
     */
    private static boolean pathableMemo(Character bot, int x, int y) {
        int botId = bot.getId();
        int region = GCMovement.peekRegionIdAt(bot.getMap(), x, y);
        Point surface = GCMovement.groundPointBelow(bot.getMap(), x, y);
        int surfaceY = (surface != null) ? surface.y : y;
        Integer memoRegion = chasePathRegionByBot.get(botId);
        Integer memoSurfaceY = chasePathSurfaceYByBot.get(botId);
        if (region >= 0 && Integer.valueOf(region).equals(memoRegion)
                && Integer.valueOf(surfaceY).equals(memoSurfaceY)) {
            Boolean memo = chasePathableByBot.get(botId);
            if (memo != null) {
                return memo;
            }
        }
        boolean pathable = GCMovement.canPathTo(bot, x, y);
        if (region >= 0) {
            chasePathRegionByBot.put(botId, region);
            chasePathSurfaceYByBot.put(botId, surfaceY);
            chasePathableByBot.put(botId, pathable);
        }
        return pathable;
    }

    /**
     * The chase target this beat: the sticky one while it stays alive, inside the seek box,
     * and PATHABLE (canPathTo from the bot — the sticky check re-plans too, so a mob that
     * wandered onto an unreachable ledge is released rather than walked into a wall under).
     * Otherwise the nearest live hostile in the box, pathable candidates first (platforms
     * above/below included - the nav graph's climb edges make "up the rope to the next
     * platform" a normal approach).
     */
    private static Monster seekPathableTarget(Character bot, Point pos) {
        int sticky = seekTargetByBot.getOrDefault(bot.getId(), -1);
        if (sticky >= 0) {
            MapObject mo = bot.getMap().getMapObject(sticky);
            if (mo instanceof Monster m && isHuntTarget(m, pos) && isPathable(bot, m)) {
                return m;
            }
            seekTargetByBot.put(bot.getId(), -1); // gone, out of the box, or unreachable
            clearChaseGoalState(bot.getId()); // the frozen platform belongs to the dropped mob
        }
        Monster best = null;
        double bestSq = Double.MAX_VALUE;
        Monster bestPathable = null;
        double bestPathableSq = Double.MAX_VALUE;
        for (Monster m : bot.getMap().getAllMonsters()) {
            if (!isHuntTarget(m, pos)) {
                continue;
            }
            Point mp = m.getPosition();
            double dsq = pos.distanceSq(mp);
            if (dsq < bestSq) {
                bestSq = dsq;
                best = m;
            }
            if (dsq < bestPathableSq && isPathable(bot, m)) {
                bestPathableSq = dsq;
                bestPathable = m;
            }
        }
        Monster chosen = bestPathable != null ? bestPathable : best;
        seekTargetByBot.put(bot.getId(), chosen != null ? chosen.getObjectId() : -1);
        clearChaseGoalState(bot.getId()); // a fresh target restarts the frozen platform anchor
        seekAnchorByBot.remove(bot.getId()); // a fresh target restarts the progress clock
        seekLastGoalByBot.remove(bot.getId());
        return chosen;
    }

    /**
     * Whether the bot's graph can plan a route to this mob's floor point (peek, no build).
     *
     * <p>The answer is cached per (bot, mob) for a beat-window: the sweep calls this on every
     * mob in the room every 250ms, and each call walks the nav graph's region data - a
     * per-beat re-plan for a chase that is already under way is wasted work (the sticky check
     * re-plans too), so a mob keeps its last verdict briefly. Short on purpose: a mob that
     * wanders onto an unreachable ledge must be released soon after, not at the timeout.
     */
    private static final long PATHABILITY_TTL_MS = 1_000;
    private record PathabilityKey(int botId, int mobOid) {
    }
    /**
     * A verdict and the instant it was taken, in ONE entry: the read can never see a stamp
     * without its verdict. The two-map shape (stamp here, verdict there) raced every writer -
     * the read path unboxed a Boolean that a concurrent re-stamp or the stop-time clear could
     * have absent while the stamp was still present (NPE on the shared combat sweep).
     */
    private record PathabilityVerdict(long takenAtMs, boolean pathable) {
    }
    private static final Map<PathabilityKey, PathabilityVerdict> pathableVerdictByBot =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static boolean isPathable(Character bot, Monster m) {
        Point mp = m.getPosition();
        if (mp == null) {
            return false;
        }
        var key = new PathabilityKey(bot.getId(), m.getObjectId());
        long now = movementNowMs();
        PathabilityVerdict memo = pathableVerdictByBot.get(key);
        if (memo != null && now - memo.takenAtMs() < PATHABILITY_TTL_MS) {
            return memo.pathable();
        }
        Point ground = GCMovement.groundPointBelow(bot.getMap(), mp.x, mp.y);
        int ty = (ground != null) ? ground.y : mp.y;
        boolean pathable = GCMovement.canPathTo(bot, mp.x, ty);
        pathableVerdictByBot.put(key, new PathabilityVerdict(now, pathable));
        return pathable;
    }

    /**
     * Release every per-bot table this class keeps for a stopped/despawned quest bot (seek /
     * chase / probe / stage-walk shield / trace state) so none of them grows for the life of
     * the process. The shield and the !gcmove trace were the two entries left out of this
     * release: only a later {@code movementShielded}/{@code seekTraceEnabled} read removed an
     * expired entry, and a stopped bot is never read again - one entry per shielded/traced
     * bot, kept forever (the same leak family as the wait-spot sets d79b87e closed).
     */
    public static void clearSeekState(int botId) {
        seekTargetByBot.remove(botId);
        clearChaseGoalState(botId);
        seekAnchorByBot.remove(botId);
        seekDeadlineByBot.remove(botId);
        seekLastGoalByBot.remove(botId);
        stuckTargetByBot.remove(botId);
        stuckCountByBot.remove(botId);
        pathableVerdictByBot.keySet().removeIf(k -> k.botId() == botId);
        orphanProbeAtByBot.remove(botId);
        stageWalkShieldUntilByBot.remove(botId);
        SEEK_TRACE_UNTIL.remove(botId);
    }

    /** Whether this mob is a legitimate chase target from {@code pos}: hostile and in the seek box. */
    private static boolean isHuntTarget(Monster m, Point pos) {
        if (m == null || !m.isAlive()) {
            return false;
        }
        if (m.getStats() != null && m.getStats().isFriendly()) {
            return false; // Moon Bunny and friends are not targets
        }
        Point mp = m.getPosition();
        return mp != null && Math.abs(mp.x - pos.x) <= SEEK_RANGE_X
                && Math.abs(mp.y - pos.y) <= SEEK_STACK_RANGE_Y;
    }

    /** Pick up matching drops near a point. Returns how many items were gathered. */
    public static int loot(Character bot, Point at, double radiusPx, int[] itemIds) {
        if (bot == null || at == null) {
            return 0;
        }
        // The radius is px; BotLogic.checkForItemsOnFloor squares it once at the engine boundary
        // (the old call here passed px through unsquared, so "2_000" scanned a ~45px circle
        // around the bot's feet - the "bots do not pick up the passes" report).
        List<MapObject> found = BotLogic.checkForItemsOnFloor(bot, at, radiusPx, itemIds);
        if (found.isEmpty()) {
            // The caller's radius is a kill's neighbourhood, so a drop that landed outside it
            // (a ranged kill's shot, a knockback slide) is invisible here - the deleted 2000px
            // cleanup sweep used to cover that blind ring, and its completeness was never
            // re-homed (f4043a1/317bfbc). The room-wide orphan probe restores the VISIBILITY
            // half at a paced cadence: it may start a walk to the drop; the pickup itself
            // still happens up close on a later scan (the engine's pickupItem has no distance
            // check, so a grab from here would teleport the item).
            sweepOrphanDropOutside(bot, radiusPx, itemIds);
            return 0;
        }
        // Never take a multi-piece stack: the item-triggered reactors read their stack five
        // seconds after it lands and match it by identity, so picking one up cancels it.
        // Never take an addressed pile either: a drop flagged permanent-owner is somebody's
        // hand-off (the leader's), not floor loot - the same rule botCanLoot enforces for the
        // sweep paths; without it a qty-1 hand-off is read as a pass and the party's stock
        // ping-pongs between the bots. At most LOOT_INLINE_MAX pickups run inline (each
        // carries a 100ms stagger): an overflow pile waits for the next macro tick, far below
        // the drops' own despawn - a wide sweep can no longer freeze a busy stage tick.
        List<MapObject> lone = found.stream()
                .filter(o -> o instanceof MapItem drop && isFloorLoot(drop))
                .limit(LOOT_INLINE_MAX)
                .toList();
        if (lone.isEmpty()) {
            return 0;
        }
        // Pickup must be proximity-honest: the engine's pickupItem has no distance check (a
        // real client walks to the item first), so an item outside the bot's pickup reach
        // teleports to it if grabbed from here - the reported vacuum pickup. Anything in
        // reach is picked this tick; anything farther, the bot starts WALKING to the nearest
        // one and the sweep finishes up close on a later tick. This is what keeps a ranged
        // kill's drop reachable without a whole-room grab.
        Point botPos = bot.getPosition();
        List<MapObject> reachable = new java.util.ArrayList<>();
        Point nearestOutOfReach = null;
        double walkSq = Double.MAX_VALUE;
        for (MapObject o : lone) {
            Point ip = o.getPosition();
            if (ip == null) {
                continue;
            }
            double dsq = botPos.distanceSq(ip);
            if (dsq <= PICKUP_REACH_SQ) {
                reachable.add(o);
            } else if (dsq < walkSq) {
                walkSq = dsq;
                nearestOutOfReach = ip; // the nearest out-of-reach drop is this tick's walk target
            }
        }
        if (reachable.isEmpty()) {
            // No live move to override: seekAndAttack/approachUnder own the walk while one
            // is in flight (a loot walk issued over it would yank the bot off its chase).
            // The re-issue replans from the CURRENT pixel once the driver is idle.
            if (nearestOutOfReach != null && !GCMovement.isMoving(bot)) {
                walkForPickup(bot, nearestOutOfReach);
            }
            return 0;
        }
        int before = 0;
        for (int id : itemIds) {
            before += countItem(bot, id);
        }
        DropCommands.lootItemListOnFloor(bot, reachable);
        int after = 0;
        for (int id : itemIds) {
            after += countItem(bot, id);
        }
        return after - before;
    }

    /**
     * The band {@code walkTo} treats as "already standing on the target": the driver's own
     * PRECISE arrival box (GCMovementDriver.reachedMoveTarget's moveTargetPrecise branch).
     * A parked bot re-asking for its spot must not re-arm the walk - the re-arm is what used
     * to overwrite a live loot/reclaim walk issued earlier the same tick, so the pickup walk
     * never got a driver tick and the out-of-reach drop it was for stayed orphaned.
     */
    private static final int ARRIVAL_EPS_PX = 8;

    /**
     * How close the bot must stand to a drop before picking it up - one reach for every
     * proximity-honest pickup in this class (loot, the delivery post, the reclaim walk):
     * the engine's pickupItem has no distance check, so "close" is this class's rule.
     */
    private static final double PICKUP_REACH_SQ = 150.0 * 150.0;

    /**
     * How often one bot may run the room-wide orphan-drop probe of {@link #loot}: the probe
     * scans the map's whole item table (one range query, bounded by the items on the floor),
     * so it cannot ride every quiet tick. 6s is far inside the 3-minute item_expire despawn
     * the probe exists to beat - a drop seen on its first probe still has ~174s of walk
     * budget.
     */
    private static final long ORPHAN_PROBE_MIN_MS = 6_000L;
    /** botId -> the last time the orphan probe actually ran (throttle; skipped probes are free). */
    private static final Map<Integer, Long> orphanProbeAtByBot = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * One paced, room-wide VISIBILITY sweep for {@link #loot}: when the caller's own radius
     * saw nothing, look across the whole room for a matching drop this bot is entitled to,
     * and start WALKING to the nearest one. The pickup itself never happens here - the drop
     * is by definition outside this tick's reach, so grabbing it would teleport it; walking
     * there re-enters {@link #loot} (the arrival nudge re-runs the stage sweep promptly),
     * and the close-range scan picks it up.
     *
     * <p>This is the re-homed completeness half of the 2000px cleanup sweep f4043a1 deleted
     * (the last pass of a 25-pass stage can land at a ranged kill's fall point, outside every
     * bot's 260px loot radius, and 3 minutes later it despawns). Two rules keep it from
     * re-opening the defects the sweep's removal fixed: it NEVER picks up (no vacuum grab -
     * the walk is proximity-honest like every pickup in this class) and it NEVER takes an
     * addressed pile or another bot's still-protected drop (the same entitlement
     * {@link DropCommands#botCanLoot} enforces for the sweep paths, which is what stopped the
     * pass ping-pong). The walk is only armed when the driver is idle - seekAndAttack and
     * approachUnder own a live move, and a loot walk issued over it would yank the bot off
     * its chase (the 317bfbc guard).
     */
    private static void sweepOrphanDropOutside(Character bot, double radiusPx, int[] itemIds) {
        if (GCMovement.isMoving(bot)) {
            return; // a live chase/approach walk owns the bot; never re-target it mid-flight
        }
        long now = movementNowMs();
        Long last = orphanProbeAtByBot.get(bot.getId());
        if (last != null && now - last < ORPHAN_PROBE_MIN_MS) {
            return; // throttled: the caller's own radius scan already ran this tick
        }
        orphanProbeAtByBot.put(bot.getId(), now);
        // Room-wide, in px: every quest room fits inside it (the widest shipped PQ room is
        // ~2700px), and the engine's range scan compares distanceSq, so this squares once
        // here like every other px radius (see BotLogic.rangeSq).
        List<MapObject> anywhere = BotLogic.checkForItemsOnFloor(bot, bot.getPosition(),
                ORPHAN_PROBE_RANGE_PX, itemIds);
        Point botPos = bot.getPosition();
        double callerReachSq = radiusPx * radiusPx;
        Point nearest = null;
        double bestSq = Double.MAX_VALUE;
        for (MapObject o : anywhere) {
            // botCanLoot already refuses picked-up drops, addressed hand-off piles,
            // still-protected drops AND position-less drops; isFloorLoot additionally
            // refuses multi-piece stacks (item-triggered reactors match their stack by
            // identity).
            if (!(o instanceof MapItem drop)
                    || !DropCommands.botCanLoot(bot, drop)
                    || !isFloorLoot(drop)) {
                continue;
            }
            Point ip = drop.getPosition();
            double dsq = botPos.distanceSq(ip);
            if (dsq <= callerReachSq) {
                continue; // inside the caller's own radius would have matched already
            }
            if (dsq < bestSq) {
                bestSq = dsq;
                nearest = ip;
            }
        }
        if (nearest != null) {
            walkForPickup(bot, nearest);
        }
    }
    /** The orphan probe's reach: room-wide (see {@link #sweepOrphanDropOutside}). */
    private static final double ORPHAN_PROBE_RANGE_PX = 3_000;

    /**
     * Whether a floor drop is ordinary loot a bot may pocket: a single piece (reactor stacks
     * are matched by identity) and not an addressed hand-off pile (permanent owner).
     */
    static boolean isFloorLoot(MapItem drop) {
        return drop.getItem().getQuantity() <= 1 && !drop.isPermanentOwner();
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

    /**
     * A stage's work is done: go stand by the room's stage NPC (the one the leader has to
     * talk to), so the party reads as ready instead of scattered around the room. Falls back
     * to the exit portal's mouth when the room has no NPC.
     */
    /** NPC-sight radius SQUARED for the wait spot - getMapObjectsInRange compares distanceSq. */
    private static final long WAIT_NPC_RANGE_SQ = 25_000_000L; // 5000px squared: any quest room

    /** How far (px) below the NPC a floor may sit and still count as the NPC's wait ring. */
    private static final int RING_FLOOR_TOLERANCE_PX = 30;

    /** map instance -> the wait spots already claimed (the NPC pixel is the first). */
    private static final Map<Integer, java.util.Set<Point>> WAIT_SPOT_CLAIMS = new java.util.concurrent.ConcurrentHashMap<>();

    /** botId -> the wait spot this bot currently holds in one of the rings. */
    private static final Map<Integer, Point> WAIT_CLAIM_BY_BOT = new java.util.concurrent.ConcurrentHashMap<>();

    /** botId -> the map-instance key its claim sits under, so the release touches only that set. */
    private static final Map<Integer, Integer> WAIT_CLAIM_KEY_BY_BOT = new java.util.concurrent.ConcurrentHashMap<>();

    public static void waitNearStageNpc(Character bot) {
        waitNearStageNpc(bot, 0);
    }

    /**
     * As {@link #waitNearStageNpc(Character)}, with the stage's turn-in NPC named by id
     * ({@code stageNpcId > 0}). Rooms that hold both the turn-in NPC and the map-exit NPC
     * (every Ludi PQ stage room) must name theirs: a proximity choice could park the whole
     * ready party - and the delivery post - at the exit instead of the turn-in spot.
     */
    public static void waitNearStageNpc(Character bot, int stageNpcId) {
        if (bot == null || bot.getMap() == null) {
            return;
        }
        Point spot = stageNpcSpot(bot, stageNpcId);
        if (spot == null) {
            return;
        }
        if (yieldsToLiveWalk(bot, spot)) {
            return;
        }
        walkTo(bot, spot);
        // No shield here: parked-by-the-NPC is a wait, not a staged walk. The combat beat
        // may steer at whatever spawns nearby and - critically - the 250ms beat keeps the
        // bot's swings alive while it stands (a shield renewed every macro tick read as a
        // 100% combat blackout for the whole wait).
    }

    /**
     * The stage NPC's spot (the next00 portal's mouth as fallback), or null.
     *
     * <p>{@code stageNpcId > 0} names the NPC BY ID, and a named NPC that is not standing in the
     * room means there is NO post (null). That matters because every Ludi PQ stage room carries
     * its turn-in NPC (2040036..2040044) AND the map-exit NPC (2040047, Sgt. Anderson) on the
     * same ledge, 175-405px apart: a proximity choice is a coin flip between the two, so the
     * party's stock could be posted at the EXIT while the leader turns the stage in at the stage
     * NPC - and the ready ring formed around the exit instead of the turn-in spot. The quest
     * layer, which knows the stage, names the NPC ({@link #stageNpcSpot});
     * {@code stageNpcId <= 0} keeps the nearest-NPC choice for a room that holds only one.
     */
    private static Point stageNpcSpot(Character bot, int stageNpcId) {
        Point spot = null;
        if (stageNpcId > 0) {
            NPC stageNpc = bot.getMap().getNPCById(stageNpcId);
            if (stageNpc != null && stageNpc.getPosition() != null) {
                spot = stageNpc.getPosition();
            }
        } else {
            long bestSq = Long.MAX_VALUE;
            for (MapObject obj : bot.getMap().getMapObjectsInRange(bot.getPosition(), WAIT_NPC_RANGE_SQ,
                    List.of(org.gms.server.maps.MapObjectType.NPC))) {
                if (obj instanceof NPC npc && npc.getPosition() != null) {
                    // The CLOSEST NPC, not the first in the map's unordered object table.
                    double dsq = bot.getPosition().distanceSq(npc.getPosition());
                    if (dsq < bestSq) {
                        bestSq = (long) dsq;
                        spot = npc.getPosition();
                    }
                }
            }
        }
        if (spot == null && stageNpcId <= 0) {
            for (Portal portal : bot.getMap().getPortals()) {
                if ("next00".equals(portal.getName())) {
                    spot = portal.getPosition();
                    break;
                }
            }
        }
        if (spot != null) {
            Point ground = GCMovement.groundPointBelow(bot.getMap(), spot.x, spot.y);
            if (ground != null) {
                spot = ground;
            }
        }
        return spot;
    }

    /**
     * Wait NEAR the stage NPC without standing on it, the way a party idles while the leader
     * turns the passes in.
     *
     * <p>{@link #waitNearStageNpc} walks every waiter to the same NPC pixel, so a party of
     * quest bots plus the leader renders as one body on the NPC (the LPQ stage-1 "bots overlap
     * the NPC" report). The fix is a shared claim table: the first body to arrive takes the NPC
     * spot itself, and every later one is pushed out to an unoccupied point on the NPC's own
     * ledge - a ring of teammates around the NPC rather than a stack. The claims release with
     * the instance, so a new run starts with a clean ring.
     */
    public static void spreadNearStageNpc(Character bot) {
        spreadNearStageNpc(bot, 0);
    }

    /**
     * As {@link #spreadNearStageNpc(Character)}, with the stage's turn-in NPC named by id
     * ({@code stageNpcId > 0}) - the ring forms around the NPC the stage work delivers to,
     * not around whichever NPC happens to be nearer.
     */
    public static void spreadNearStageNpc(Character bot, int stageNpcId) {
        if (bot == null || bot.getMap() == null) {
            return;
        }
        Point npc = stageNpcSpot(bot, stageNpcId);
        if (npc == null) {
            return; // no stage NPC and no next00 to gather by - nothing to spread around
        }
        Point target = claimNear(bot, npc);
        if (yieldsToLiveWalk(bot, target)) {
            return;
        }
        walkTo(bot, target);
        // No shield: the ring is a park, not a puzzle hold (see waitNearStageNpc).
    }

    /**
     * Whether a RECURRING re-arm ({@code waitNearStageNpc}/{@code spreadNearStageNpc} parks,
     * the delivery walk, the hand-off reclaim walk) must yield this tick because the driver
     * already holds a move goal the bot has not reached: TRUE exactly when a live move is in
     * flight and the bot is not standing on the re-arm's own spot.
     *
     * <p>These origins re-arm their walk every macro tick, and {@link #walkTo} only refuses a
     * re-arm while the bot still STANDS on its spot - so a re-arm issued after the bot stepped
     * off would OVERWRITE a pickup/reclaim/chase walk mid-flight and drag the bot back. (That
     * is how the residual orphan-pass ping-pong kept returning: each round gated one origin -
     * loot's pickup walk by 317bfbc, the parks by 8d492d5 - while the delivery and reclaim
     * walks kept re-arming unconditionally.) One rule, one seam, every recurring origin routed
     * through it: the parks, {@link #handItemsToLeaderAfterStage} and
     * {@link #recoverUngatheredHandoffs}.
     *
     * <p>{@link GCMovement#hasMoveTarget} (not {@link GCMovement#isMoving}) is the honest
     * test: a re-arm must still fire after the driver gave a move up (abandoned/unreachable),
     * and {@code isMoving} stays true through inAir/climbing between moves - a falling or
     * climbing bot between moves is exactly when a re-arm is the RIGHT thing to re-issue.
     */
    private static boolean yieldsToLiveWalk(Character bot, Point rearmSpot) {
        return standsDownForLiveWalk(GCMovement.hasMoveTarget(bot), bot.getPosition(), rearmSpot);
    }

    /**
     * Pure form of the recurring-re-arm yield gate (see {@link #yieldsToLiveWalk}), so the
     * decision table is pinned by test without a live driver (the same pure-seam pattern as
     * {@link #walkShieldReleases}, which has already regressed twice). A position-less bot
     * cannot be judged near its spot, so the gate fails OPEN - the re-arm proceeds.
     */
    static boolean standsDownForLiveWalk(boolean driverHoldsGoal, Point botPos, Point rearmSpot) {
        return driverHoldsGoal && botPos != null
                && (Math.abs(botPos.x - rearmSpot.x) > ARRIVAL_EPS_PX
                        || Math.abs(botPos.y - rearmSpot.y) > ARRIVAL_EPS_PX);
    }

    /**
     * The wait spot this bot may use for an NPC-centred ring: the NPC pixel itself for the
     * first claim, otherwise an unoccupied point near the NPC, clear of every earlier claim.
     * The claim is kept until {@link #releaseWaitClaims} drops it, so re-arming the walk on
     * later ticks returns to the same spot instead of re-rolling the ring.
     */
    private static Point claimNear(Character bot, Point npc) {
        // Keyed by the MAP INSTANCE's identity, not its id: two concurrent runs of the same
        // quest hold separate room copies with the same map id, and their parties must not
        // claim each other's spots.
        Integer key = System.identityHashCode(bot.getMap());
        java.util.Set<Point> taken = claimsFor(key);
        Point mine = heldWaitSpotOrRelease(bot.getId(), taken);
        if (mine != null) {
            return mine; // keep walking to (or hold) the spot we already claimed
        }
        Point spot;
        synchronized (taken) {
            if (taken.isEmpty()) {
                spot = new Point(npc);
            } else {
                Point open = nearbyOpenSpot(bot.getMap(), npc, taken);
                spot = open != null ? open : npc; // crowded: hold the NPC rather than fight
            }
            recordWaitClaim(bot.getId(), key, spot);
        }
        return spot;
    }

    /** Whether any rope/ladder's climbing column covers x (a wait spot there renders a standing bot on the rope sprite). */
    private static boolean ropeColumnAt(MapleMap map, int x) {
        for (org.gms.server.maps.Rope rope : map.getRopes()) {
            if (Math.abs(rope.x() - x) <= 18) {
                return true;
            }
        }
        return false;
    }

    /**
     * A point a body can stand on near {@code anchor}, not one of the {@code taken} spots and
     * not under a rope/ladder column, or null when the ledge is full. Scans EVERY ledge on the
     * anchor's floor (getAllFootholds has no ordering contract) so the CLOSEST ledge always
     * wins; the extra scan is bounded by the floor's own foothold count.
     */
    private static Point nearbyOpenSpot(MapleMap map, Point anchor, java.util.Set<Point> taken) {
        Point best = null;
        long bestSq = Long.MAX_VALUE;
        for (Foothold fh : map.getFootholds().getAllFootholds()) {
            if (fh.isForbidFallDown()) {
                continue; // one-way platforms are not wait spots
            }
            int y = fh.getY1();
            if (Math.abs(fh.getY2() - y) > 5 || Math.abs(y - anchor.y) > RING_FLOOR_TOLERANCE_PX) {
                continue; // sloped, or not on the anchor's floor
            }
            for (int x = fh.getX1() + 12; x <= fh.getX2() - 12; x += 24) {
                Point spot = new Point(x, y);
                if (taken.contains(spot) || ropeColumnAt(map, x)) {
                    continue;
                }
                double dsq = spot.distanceSq(anchor);
                if (dsq < bestSq) {
                    bestSq = (long) dsq;
                    best = spot;
                }
            }
        }
        return best;
    }

    /**
     * Record a bot's wait-spot claim under a map-instance key (see {@link #claimNear}). The
     * single place the three maps are written, so the shapes cannot drift; package seam so the
     * lifecycle is unit-testable without a live Character/MapleMap (the bookkeeping is what
     * leaked before). Callers hold the instance set's monitor; the maps are concurrent.
     */
    static void recordWaitClaim(int botId, int key, Point spot) {
        claimsFor(key).add(spot);
        WAIT_CLAIM_BY_BOT.put(botId, spot);
        WAIT_CLAIM_KEY_BY_BOT.put(botId, key);
    }

    /** The claimed-set for a map-instance key, creating it when absent (see {@link #claimNear}). */
    static java.util.Set<Point> claimsFor(int key) {
        return WAIT_SPOT_CLAIMS.computeIfAbsent(key,
                k -> java.util.Collections.synchronizedSet(new java.util.HashSet<>()));
    }

    /** Whether this bot currently holds a wait-spot claim (test/diagnostic seam). */
    static boolean holdsWaitClaim(int botId) {
        return WAIT_CLAIM_BY_BOT.containsKey(botId);
    }

    /** The claimed-set for a map-instance key, or null when nothing holds one (test seam). */
    static java.util.Set<Point> waitClaimsFor(int key) {
        return WAIT_SPOT_CLAIMS.get(key);
    }

    /**
     * The spot this bot already holds when that spot is still in the room it is standing in,
     * else null after RELEASING the stale hold. A bot that moved rooms while holding must not
     * keep the old room's point booked: dropping only the two bot->spot maps (the old shape)
     * left it in the old room's set forever, because a claim is only ever removed from the set
     * named by the bot's own key at release time. Releasing here - the same seam the
     * stop/lobby paths use - hands the point back to ITS set and drops that entry when it
     * empties.
     */
    static Point heldWaitSpotOrRelease(int botId, java.util.Set<Point> roomSpots) {
        Point mine = WAIT_CLAIM_BY_BOT.get(botId);
        if (mine == null) {
            return null;
        }
        if (roomSpots.contains(mine)) {
            return mine; // keep walking to (or hold) the spot we already claimed
        }
        releaseWaitClaims(botId);
        return null;
    }

    /** Give up a wait-spot claim when the bot leaves the room or the run ends. */
    public static void releaseWaitClaims(int botId) {
        Point mine = WAIT_CLAIM_BY_BOT.remove(botId);
        if (mine == null) {
            return;
        }
        // Remove the point from its own set only, then drop the entry once it empties. Iterating
        // every set to find the point (the original shape) left one empty set behind per room
        // INSTANCE ever visited - the key is an identity hash that is never reused, so the table
        // grew for the life of the process while each entry held nothing.
        Integer key = WAIT_CLAIM_KEY_BY_BOT.remove(botId);
        if (key == null) {
            return;
        }
        java.util.Set<Point> spots = WAIT_SPOT_CLAIMS.get(key);
        if (spots == null) {
            return;
        }
        synchronized (spots) {
            spots.remove(mine);
            if (spots.isEmpty()) {
                WAIT_SPOT_CLAIMS.remove(key, spots);
            }
        }
    }

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
