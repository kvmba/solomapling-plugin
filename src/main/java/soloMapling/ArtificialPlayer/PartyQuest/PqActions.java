package soloMapling.ArtificialPlayer.PartyQuest;

import org.gms.client.Character;
import org.gms.scripting.event.EventInstanceManager;
import org.gms.server.life.Monster;
import org.gms.server.maps.MapItem;
import org.gms.server.maps.MapObject;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Portal;
import org.gms.server.maps.Reactor;
import org.gms.server.life.NPC;
import org.gms.constants.inventory.ItemConstants;
import org.gms.client.inventory.manipulator.InventoryManipulator;
import org.gms.scripting.npc.NPCScriptManager;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackDriver;
import soloMapling.ArtificialPlayer.BotClientBinding;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAuraState;
import soloMapling.ArtificialPlayer.BotCommandsPack.DropCommands;
import soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands;
import soloMapling.ArtificialPlayer.BotLogic;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.MapVFX.CustomReactor;

import java.awt.Point;
import java.util.List;
import java.util.Map;

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

    /**
     * Point the movement engine at the floor under an airborne target and return at once - the
     * bot keeps walking after this call, and the caller strikes the target in the same tick.
     * The blocking {@link #walkUnder} is for callers that must BE there before the next line
     * (dropping a stack); reactor strikes have no reach check and only need the approach in
     * flight, so blocking on it just parks the macro tick for seconds per box.
     */
    public static void walkUnderNonBlocking(Character bot, Point aerialTarget) {
        if (bot == null || aerialTarget == null || bot.getMap() == null) {
            return;
        }
        Point ground = GCMovement.groundPointBelow(bot.getMap(), aerialTarget.x, aerialTarget.y);
        Point to = ground != null ? ground : aerialTarget;
        GCMovement.move(bot, to.x, to.y);
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

    /**
     * Every alive reactor on the map with this data id, as oids. Quest stages scatter several
     * boxes of the same id across the room (LPQ stage 2's eleven pass boxes share 2202003), so
     * the first-oid lookup cannot reach them all.
     */
    public static java.util.List<Integer> findAllReactorOids(Character bot, int dataId) {
        if (bot == null || bot.getMap() == null) {
            return List.of();
        }
        return bot.getMap().getAllReactors().stream()
                .filter(r -> r.getId() == dataId && r.isAlive())
                .mapToInt(Reactor::getObjectId)
                .boxed().toList();
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
     *
     * <p>The transfer is real: the stack is removed from the bot's inventory before the
     * drop spawns, so a bot can never hand on more than it actually carried. The drop is
     * also permanently owned by the receiver, so the other bots' floor sweeps (which
     * otherwise read every pass on the ground as loot, the leader's pile included) cannot
     * turn the hand-off into a pass ping-pong across the room.
     */
    public static void giveItemTo(Character bot, Character receiver, int itemId, int qty) {
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
        DropCommands.botThrowToOwnerItemQty(bot, itemId, handed, receiver);
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
        if (bot.getPosition() == null || leader.getPosition() == null
                || bot.getPosition().distanceSq(leader.getPosition()) > HANDOFF_RANGE_SQ) {
            GCMovement.move(bot, leader.getPosition().x, leader.getPosition().y);
            return 0;
        }
        giveItemTo(bot, leader, itemId, qty);
        return qty;
    }

    /** Inside this range a drop at the leader's feet is his to sweep instantly. */
    private static final double HANDOFF_RANGE_SQ = 400.0 * 400.0;

    /**
     * Sweep back the hand-off piles this bot dropped that the leader has not picked up yet.
     *
     * <p>The host despawns drops after {@code item_expire_time} (3 min by default) whether or
     * not they are owned, so a leader busy fighting can cost the bot its whole stock. A pile
     * of ours older than this window is walked back to and picked up - it re-enters the bot's
     * inventory and the next hand-off attempt drops it again, right at his feet.
     *
     * @return how many items were recovered
     */
    public static int recoverUngatheredHandoffs(Character bot, int itemId) {
        if (bot == null || bot.getMap() == null) {
            return 0;
        }
        long now = System.currentTimeMillis();
        List<MapObject> mine = BotLogic.checkForItemsOnFloor(bot, bot.getPosition(), 9_000, new int[]{itemId});
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
            // Walk there; the pickup itself is instant from any distance on the bot path.
            Point at = drop.getPosition();
            if (at != null) {
                GCMovement.move(bot, at.x, at.y);
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

    // Seek-and-attack pacing: a quest bot's macro tick runs every 2-6s, far slower than the grind
    // ticker's 250ms, so the seek beat is spread over ticks rather than a single call. The sticky
    // per-bot state below keeps a chase alive across those ticks (RoamStrategy's targetOid pattern,
    // minus the spot-claim machinery a quest bot does not need).
    private static final int SEEK_RANGE_X = 900;            // hunt a live mob within this |dx| (cross-ledge)
    // LPQ stage 1 (922010100) seats its first Ratz 580px above the entry floor over a series of
    // one-way ledges - a box tuned to the grind maps' floor stacks stops the chase before it starts
    // and the room reads as quiet forever (the mobs are mobTime=-1 and never close the gap). The
    // whole tower is ~3000px tall, so a bot standing anywhere in it sees the whole hunt.
    static final int SEEK_STACK_RANGE_Y = 3_200;    // tall PQ towers are one vertical room
    private static final int RETARGET_EPS_PX = 16;          // skip re-issuing a move for tiny shifts
    private static final long RETARGET_TIMEOUT_MS = 4_000;  // give up an unreachable target after this
    private static final int PROGRESS_EPS_PX = 20;          // movement worth counting as chase progress
    private static final Map<Integer, Integer> seekTargetByBot = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<Integer, Point> seekAnchorByBot = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<Integer, Long> seekDeadlineByBot = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<Integer, Integer> seekLastXByBot = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Seek a mob and fight it, the way the roaming grind brain does: swing at whatever the
     * attack driver already reaches, and when nothing is in reach, find the nearest live mob
     * (across ledges - platforms above, below, ropes between) and walk/jump/climb toward it.
     * The next tick re-checks: closer now, swing; still far, keep moving.
     *
     * <p>This is the half the plain {@link #attack} never had: {@code botAttack} only swings
     * at mobs inside its reach box, and its nearest-mob scan is same-ledge only, so a quest
     * bot facing Ratz on a platform overhead stood still forever. Every stage that reads
     * "kill what is in the room" should call this instead - the swings land, the drops fall,
     * and the bot is never a bystander in its own room.
     *
     * <p>Movement goes through the dynamic engine ({@code GCMovement.move}), which paths
     * across the map's own terrain: walks, jumps, drops and rope climbs are its edges, so
     * "climb the rope to the mob's platform" needs nothing from the caller. Unreachable
     * targets are dropped after a no-progress timeout and re-seeked next tick.
     */
    public static void seekAndAttack(Character bot) {
        if (bot == null || bot.getMap() == null) {
            return;
        }
        Point pos = bot.getPosition();
        if (pos == null) {
            return;
        }

        // 1. Swing at whatever is already in the attack driver's reach.
        BotAttackDriver.AttackResult r = BotAttackDriver.botAttack(bot);
        if (r != null && r.hit()) {
            seekTargetByBot.put(bot.getId(), -1); // landed: drop the chase, re-seek fresh next tick
            seekLastXByBot.remove(bot.getId());
            return;
        }

        // 2. Nothing in reach: pick a chase target (sticky across ticks) and close on it.
        Monster target = seekTarget(bot, pos);
        if (target == null) {
            seekLastXByBot.remove(bot.getId());
            return; // the room is quiet; hold position this tick
        }

        // Close on the target: walk to the floor under it (the nav layer jumps/drops/climbs
        // ropes as its edges need). Ranged/magic reach is respected by the swing above firing
        // before the walk gets there, so this walk always ends in a landed swing or a timeout.
        Point mp = target.getPosition();
        Point ground = GCMovement.groundPointBelow(bot.getMap(), mp.x, mp.y);
        int tx = mp.x;
        int ty = (ground != null) ? ground.y : mp.y;

        // Some quest rooms seat their prize mob on a ledge the nav graph cannot climb TO (a
        // pedestal with no upward edges - LPQ's Alishar). Chasing the mob's own platform would
        // re-issue an unwalkable goal every tick, so aim for the floor UNDER the mob instead:
        // the bot ends up standing beneath it, which is a real fight position (the boss reach
        // box is vertically padded) and a far better crowd position than the doorway.
        if (!GCMovement.canPathTo(bot, tx, ty)) {
            Point mobFloor = GCMovement.groundPointBelow(bot.getMap(), mp.x, mp.y + 1);
            if (mobFloor != null && Math.abs(mobFloor.y - ty) > 20
                    && GCMovement.canPathTo(bot, mp.x, mobFloor.y)) {
                tx = mp.x;
                ty = mobFloor.y;
            }
        }

        // Progress bookkeeping: a chase that moves the bot nowhere for a while is dropped so
        // the next tick seeks something else instead of walking into a wall forever.
        Point anchor = seekAnchorByBot.get(bot.getId());
        if (anchor == null || Math.abs(pos.x - anchor.x) > PROGRESS_EPS_PX
                || Math.abs(pos.y - anchor.y) > PROGRESS_EPS_PX) {
            seekAnchorByBot.put(bot.getId(), new Point(pos));
            seekDeadlineByBot.put(bot.getId(), System.currentTimeMillis() + RETARGET_TIMEOUT_MS);
        } else if (System.currentTimeMillis() > seekDeadlineByBot.getOrDefault(bot.getId(), 0L)) {
            seekTargetByBot.put(bot.getId(), -1);
            seekAnchorByBot.remove(bot.getId());
            seekLastXByBot.remove(bot.getId());
            return;
        }

        // Retarget epsilon: re-issuing GCMovement.move for the same X every tick would reset
        // the walk's progress clock each time, so only a real shift in the goal re-issues it.
        Integer lastX = seekLastXByBot.get(bot.getId());
        if (lastX == null || Math.abs(tx - lastX) >= RETARGET_EPS_PX) {
            GCMovement.move(bot, tx, ty);
            seekLastXByBot.put(bot.getId(), tx);
        }
    }

    /**
     * The chase target this tick: the sticky one while it stays alive and inside the seek
     * box, else the nearest live hostile in the box (platforms above/below included - the
     * nav graph's climb edges make "up the rope to the next platform" a normal approach).
     */
    private static Monster seekTarget(Character bot, Point pos) {
        int sticky = seekTargetByBot.getOrDefault(bot.getId(), -1);
        if (sticky >= 0) {
            MapObject mo = bot.getMap().getMapObject(sticky);
            if (mo instanceof Monster m && isHuntTarget(m, pos)) {
                return m;
            }
        }
        Monster best = null;
        double bestSq = Double.MAX_VALUE;
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
        }
        seekTargetByBot.put(bot.getId(), best != null ? best.getObjectId() : -1);
        seekAnchorByBot.remove(bot.getId()); // a fresh target restarts the progress clock
        seekLastXByBot.remove(bot.getId());
        return best;
    }

    /** Release a stopped/despawned quest bot's seek state so the per-bot maps do not grow. */
    public static void clearSeekState(int botId) {
        seekTargetByBot.remove(botId);
        seekAnchorByBot.remove(botId);
        seekDeadlineByBot.remove(botId);
        seekLastXByBot.remove(botId);
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

    /**
     * A stage's work is done: go stand by the room's stage NPC (the one the leader has to
     * talk to), so the party reads as ready instead of scattered around the room. Falls back
     * to the exit portal's mouth when the room has no NPC.
     */
    public static void waitNearStageNpc(Character bot) {
        if (bot == null || bot.getMap() == null) {
            return;
        }
        Point spot = null;
        for (MapObject obj : bot.getMap().getMapObjectsInRange(bot.getPosition(), 9_000,
                List.of(org.gms.server.maps.MapObjectType.NPC))) {
            if (obj instanceof NPC npc) {
                spot = npc.getPosition();
                break;
            }
        }
        if (spot == null) {
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
            walkTo(bot, spot);
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
