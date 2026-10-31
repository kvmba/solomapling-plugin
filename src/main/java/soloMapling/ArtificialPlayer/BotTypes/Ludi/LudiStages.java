package soloMapling.ArtificialPlayer.BotTypes.Ludi;

import org.gms.client.Character;
import org.gms.client.Job;
import org.gms.server.life.Monster;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAuraState;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import org.gms.constants.skills.Brawler;
import org.gms.constants.skills.Rogue;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotBuffConfig;
import soloMapling.ArtificialPlayer.BotGrindSystem.MovementStylePolicy;
import soloMapling.ArtificialPlayer.PartyQuest.PqActions;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.Environment.BotMessages;
import soloMapling.ArtificialPlayer.BotClientBinding;
import org.gms.client.inventory.manipulator.InventoryManipulator;
import org.gms.constants.inventory.ItemConstants;

import java.awt.Point;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static soloMapling.ArtificialPlayer.BotHelpers.blockingSleep;

/**
 * What a bot does in each Ludi PQ stage.
 *
 * <p>Most of the quest is the same instruction repeated: the room holds monsters, they drop
 * passes, and the stage NPC wants a set number of them. Those stages are one method.
 *
 * <p>Two are different. Stage 6 is a tower whose portals mostly throw the climber back down,
 * so progress comes from trying them - the bot walks the row rather than pretending to know
 * which one is right. Stage 8 wants exactly five people standing on five specific crates out
 * of nine, and the quest has already written down which five; the bot reads that and takes
 * its share, leaving room for the rest of the party.
 */
public final class LudiStages {

    private LudiStages() {
    }

    /** The pass boxes: stage 2's tower, stage 3's mob crates, stage 5's guarded rooms. */
    // (box ids live in LudiPqData; the loot radius lives here because it is this file's pacing)

    /**
     * How far a bot walks to pick up a dropped pass: its own kill's drop plus a small
     * neighbourhood, not the whole room. The old full-room sweep read as every bot racing
     * the others across the map for each card; a kill lands its drop at the mob's x, so a
     * walk-up-and-pick radius keeps a bot on its own share.
     */
    private static final int LOOT_RADIUS_PX = 260;
    /** Stage 3's crates pop 3 mobs from one box; their spawn spread needs a wider reach. */
    private static final int CRATE_LOOT_RADIUS_PX = 400;

    /**
     * The stage-2 box this bot is working: the map instance's identity (the tower and its
     * trap room are sibling maps whose reactor oids collide - a claim carried across a trap
     * warp would shadow an unrelated box on the other side), the reactor oid, and the box y.
     * Claims are best-effort anti-queue bookkeeping, not a lock: two bots may still briefly
     * walk the same box, and the first strike wins.
     */
    private record BoxClaim(int mapInstance, int oid, int y) {}

    private static final Map<Integer, BoxClaim> TOWER_BOX_CLAIMS = new java.util.concurrent.ConcurrentHashMap<>();

    /** A stage is done when the quest says so; these are its own per-stage flags. */
    public static boolean stageCleared(Character bot, int stage) {
        return PqActions.readEimString(bot, stage + "stageclear") != null;
    }

    /** The map instance a stage-2 claim belongs to: two concurrent runs must not collide. */
    private static int mapInstanceOf(Character bot) {
        return System.identityHashCode(bot.getMap());
    }

    // =========================================================================
    // The collection stages
    // =========================================================================

    /**
     * Fight what is in the room and gather this stage's passes.
     *
     * <p>The bot attacks and loots its share while the fight runs; the stock stays pocketed.
     * Once the room is quiet (the stage's work is done) the bot walks to the stage NPC - the
     * spot the party idles at while the leader makes the turn-in - and drops its whole stock
     * of passes only when the leader walks into hand-off range there. The stage NPC checks
     * the inventory of whoever talks to him, and that is the leader - a bot that keeps its
     * share starves the turn-in and the party stalls on the stage. Delivering mid-fight (the
     * old behaviour, on every leader pass-by) threw passes around while the party was still
     * killing; the turn-in is impossible before the bar is met, so the stock is worth nothing
     * until the work is over anyway.
     *
     * <p>"Quiet" means no KILLABLE mob is left. The stage-5 main map's Block Golems
     * (9300013) carry WZ invincible and a 99999 HP pool a level-35 party cannot burn
     * through - they are scenery, not work - so they are excluded; counting them kept
     * the room "busy" forever and the delivery never fired.
     */
    public static void gatherPasses(Character bot, int stage) {
        // Recover our stale hand-off piles (the leader missed them and the despawn clock runs).
        PqActions.recoverUngatheredHandoffs(bot, LudiPqData.PASS);
        // Stage 7 is not a box-breaking stage: the three pass boxes are item-triggered
        // (type 100) and are armed by the 4001156 drops of the three ledge mobs. Those mobs
        // stand on ledges with no inbound edge (only a fall from above enters them), so the
        // work is killing them from the LEFT ledges with a ranged/magic kit - the drop re-seats
        // onto the mob's own ledge, inside the box's trigger box, and the box summons the
        // Rombard on its own five seconds later.
        //
        // <p>Two things this stage must NOT do: walk at the box (unreachable), and pick the
        // 4001156 up (a picked-up item can never arm the box - the trigger matches a live drop
        // inside the trigger box).
        if (stage == 7) {
            if (stage7LedgeMobAlive(bot) || stage7PassStillWanted(bot)) {
                // Reach the shot, not the inaccessible mob floor: the chase can select a
                // walkable firing ledge for ranged/magic and leaves impossible melee shots alone.
                PqActions.seekAndAttack(bot, true);
                return;
            }
        }
        if (killableMobPresent(bot)) {
            // Pickup first: a pass a kill left inside the loot radius is the nearest unfinished
            // work, and loot() can only walk to it when no live move owns the driver. Issuing
            // the chase first (the old order) meant this branch never ran.
            if (!PqActions.pickupBeforeChase(bot, LOOT_RADIUS_PX, new int[]{LudiPqData.PASS})) {
                PqActions.seekAndAttack(bot);
            }
            PqActions.loot(bot, bot.getPosition(), LOOT_RADIUS_PX, new int[]{LudiPqData.PASS});
            return;
        }
        // Room quiet: the work is done. Hold the stage-NPC post and hand the stock over the
        // moment the leader comes for the turn-in.
        int stageNpc = LudiPqData.stageNpcId(stage);
        PqActions.loot(bot, bot.getPosition(), LOOT_RADIUS_PX, new int[]{LudiPqData.PASS});
        if (PqActions.handItemsToLeaderAfterStage(bot, LudiPqData.PASS, stageNpc) > 0) {
            PqActions.say(bot, BotMessages.get("pq.passes_dropped"));
        }
        if (PqActions.countItem(bot, LudiPqData.PASS) <= 0) {
            // Delivered: spread into the NPC ring with the rest of the ready party.
            PqActions.spreadNearStageNpc(bot, stageNpc);
        }
    }

    /**
     * Whether any of stage 7's three ledge mobs is still standing.
     *
     * <p>They are the stage's only pass source (their 4001156 arms the boxes that summon the
     * Rombard), so while one lives the stage is not done. Checked by data id rather than by
     * "anything killable": the Rombard is also killable and means the opposite.
     */
    private static boolean stage7LedgeMobAlive(Character bot) {
        for (Monster m : bot.getMap().getAllMonsters()) {
            if (!m.isAlive()) {
                continue;
            }
            for (int id : LudiPqData.STAGE7_LEDGE_MOBS) {
                if (m.getId() == id) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Whether stage 7 still owes passes: a Rombard is up and not yet killed, or a box is still
     * ARMED but unfired.
     *
     * <p>"Armed" is {@code getReactorType() == 100} - the engine's own test for an
     * item-triggered reactor waiting for its item. It must NOT be {@code isActive()}: 2201002
     * has two states and both carry a valid type, so {@code isActive()} stays true forever and
     * the stage would never reach its delivery branch. Once the box fires it advances to its
     * spent state (type 999) and this answers "no more passes from here".
     */
    private static boolean stage7PassStillWanted(Character bot) {
        if (killableMobPresent(bot)) {
            return true; // a Rombard (or anything else killable) is still standing
        }
        for (var reactor : bot.getMap().getAllReactors()) {
            if (reactor.getId() == LudiPqData.BOX_STAGE7
                    && reactor.isAlive()
                    && reactor.getReactorType() == 100) {
                return true; // still waiting for its 4001156: the pass has not been earned yet
            }
        }
        return false;
    }

    /**
     * Whether any mob the party is expected to KILL stands in the room: alive, and not one
     * of the quest's invincible scenery mobs (the stage-5 guard, WZ {@code invincible=1}
     * with a 99999 HP pool). Those never die and never stop the room reading as busy.
     */
    public static boolean killableMobPresent(Character bot) {
        return bot.getMap().getAllMonsters().stream()
                .anyMatch(m -> m.isAlive() && !isInvincibleScenery(m));
    }

    /** The quest's invincible set: mobs the WZ marks unkillable for this party tier. */
    private static boolean isInvincibleScenery(Monster m) {
        return m.getId() == LudiPqData.GUARD_MOB;
    }

    // =========================================================================
    // Stage 2 - the box tower

    /**
     * Break every pass box on the stage-2 tower. The room's eleven boxes (2202003) are the
     * party's only pass source in this room - there are no mobs to hunt here - so the stage
     * is a climb past each box, breaking it on the way.
     *
     * <p>The room's twelfth box, the trap (2200002), is popped ONCE the tower's own eleven are
     * spent: its script warps EVERYBODY on the map into the trap room (922010201), and that
     * room's four boxes carry the last four of the fifteen passes the turn-in asks for. Popping
     * it earlier would yank workers off the boxes they are still breaking, so the gate is
     * "no tower box left standing" - {@code findAllReactorOids}, not {@code nearestOwnedBoxOid}:
     * the latter also answers "-1" while teammates still hold their share.
     *
     * <p>The tower is one vertical room: its boxes hang on floors between the spawn at the
     * top (y -2521) and the ground floor at the bottom (y +129), and the floors between them
     * are reached only by walking off ledges, down-jumping, or grabbing a rope downwards.
     * The bot descends to whatever floor its current box lives on - and so that several bots
     * do not queue for the same one box, each bot claims a share of the standing boxes and
     * works its own share (the claim is by bot id, so a bot that changes floors keeps its
     * boxes; a bot that despawns releases its share).
     */
    public static void breakTowerBoxes(Character bot) {
        // Recover stale hand-off piles (the leader missed them and the despawn clock runs).
        PqActions.recoverUngatheredHandoffs(bot, LudiPqData.PASS);

        int box = nearestOwnedBoxOid(bot, LudiPqData.BOX_STAGE2);
        if (box >= 0 && hitReactorDescendRotate(bot, box, LudiPqData.BOX_STAGE2)) {
            PqActions.loot(bot, bot.getPosition(), LOOT_RADIUS_PX, new int[]{LudiPqData.PASS});
            return;
        }
        // Tower spent: the trap box is the last four passes, so spring it. Gated on the map
        // having NO tower box left at all - not on this bot's share being exhausted, which also
        // happens while teammates are still breaking theirs, and springing the trap then would
        // warp those workers off their boxes mid-climb.
        if (PqActions.findAllReactorOids(bot, LudiPqData.BOX_STAGE2).isEmpty()) {
            int trap = nearestOwnedBoxOid(bot, LudiPqData.BOX_STAGE2_TRAP);
            if (trap >= 0 && hitReactorDescendRotate(bot, trap, LudiPqData.BOX_STAGE2_TRAP)) {
                return;
            }
        }
        // Boxes all gone: the stage's work is done and the leader still has to turn the
        // passes in. Hold the stage-NPC post and deliver only when HE comes into range
        // there - no more mid-climb drops every time he walks past the tower.
        int stageNpc = LudiPqData.stageNpcId(2);
        if (PqActions.handItemsToLeaderAfterStage(bot, LudiPqData.PASS, stageNpc) > 0) {
            PqActions.say(bot, BotMessages.get("pq.passes_dropped"));
        }
        if (PqActions.countItem(bot, LudiPqData.PASS) <= 0) {
            // Delivered: park by the stage NPC with the rest of the ready party.
            PqActions.spreadNearStageNpc(bot, stageNpc);
        }
        // Still carrying passes while a box stands is fine: the stock rides along, the
        // delivery waits for the post-work beat above.
        PqActions.loot(bot, bot.getPosition(), LOOT_RADIUS_PX, new int[]{LudiPqData.PASS});
    }

    /**
     * Work the stage-2 trap room (922010201) - the tower's trap box warps the whole party
     * here, and this room's four boxes carry four of the stage's fifteen passes: they MUST
     * be collected or the stage can never clear.
     *
     * <p>The beat: break the boxes (the room is small, no descent needed), loot, and take
     * the room's own exit back to the tower to rejoin the box work - the exit is a
     * script-less portal, so entering it warps the bot from wherever it stands. Standing
     * still here is the one wrong answer: the room has no stage NPC and no mobs, so the old
     * wait-then-idle fallback froze a trapped bot solid.
     */
    public static void workTrapRoom(Character bot) {
        PqActions.recoverUngatheredHandoffs(bot, LudiPqData.PASS);

        // The stage can be turned in while this bot is still climbing out of the trap room (the
        // rest of the party never went in, or left first). From that moment the room's four boxes
        // are worthless: breaking them spends 30s+ per box AND leaves the bot behind, because the
        // party's next stage is already open and there is no pass left to gather. Leave at once.
        if (stageCleared(bot, 2)) {
            exitTrapRoom(bot);
            return;
        }

        int box = nearestOwnedBoxOid(bot, LudiPqData.BOX_STAGE2);
        if (box >= 0 && hitReactorRotate(bot, box, LudiPqData.BOX_STAGE2)) {
            PqActions.loot(bot, bot.getPosition(), LOOT_RADIUS_PX, new int[]{LudiPqData.PASS});
            return;
        }
        PqActions.loot(bot, bot.getPosition(), LOOT_RADIUS_PX, new int[]{LudiPqData.PASS});
        // Nothing of ours left standing (a teammate's bot may be mid-box): back to the
        // tower to work it from there. Our claim releases with the room change. The passes
        // stay pocketed - the tower's own post-work beat delivers them; this room has no
        // stage NPC to hold a delivery post at, and walking to the leader here would drag
        // the bot out of its room toward a far target.
        exitTrapRoom(bot);
    }

    /**
     * Take the trap room's own exit (out00) back to the stage-2 tower.
     *
     * <p>The exit is a script-less portal, so the engine would accept the warp from
     * anywhere - but the bot walks to it first and only enters up close, so the climb out
     * reads as climbing instead of a teleport. The walk is re-armed each tick until the bot
     * is at the door.
     */
    static void exitTrapRoom(Character bot) {
        org.gms.server.maps.Portal exit = exitPortalOf(bot);
        if (exit == null || exit.getPosition() == null || bot.getPosition() == null) {
            return;
        }
        if (standsDownForLiveMove(bot)) {
            return; // a walk owns the driver; see standsDownForLiveMove
        }
        if (PqActions.walkTo(bot, exit.getPosition())) PqActions.enterPortal(bot, exit);
    }

    /**
     * Whether a room exit must stand down this tick because the bot is walking to a drop it still
     * has to collect.
     *
     * <p>The room's exit is the one move that leaves the room, so it must not cut off a pickup
     * walk whose drop would then be orphaned. But "the driver holds SOME move" was too broad: a
     * CHASE order is a live move too, and a chase is never finished while a mob exists - so a bot
     * that had once targeted a mob it could not reach kept its exit closed for the whole
     * three-minute drop-despawn window, and the party moved on without it. That is one of the ways
     * a run loses a bot per stage.
     *
     * <p>Recognised from the driver's own goal rather than from a flag set when the walk was
     * issued: any other producer replaces that goal, so a flag would go stale - and a stale flag
     * would close this exit (and stand the chase down) for the rest of the run.
     */
    private static boolean standsDownForLiveMove(Character bot) {
        Point goal = PqActions.moveTargetOf(bot);
        if (goal == null || bot.getPosition() == null) {
            return false;
        }
        if (PqActions.withinPickupReach(bot, goal)) {
            return false; // the walk has landed; nothing left to protect
        }
        return !PqActions.itemsOnFloorAt(bot, goal, new int[]{LudiPqData.PASS}).isEmpty();
    }

    /**
     * The nearest alive box of this data id this bot may work.
     *
     * <p>The claims are per bot id and released with the bot (see {@link #releaseTowerBox}),
     * so a mid-run replacement carries no stale share.
     */
    private static int nearestOwnedBoxOid(Character bot, int dataId) {
        return nearestOwnedBoxOid(bot, dataId, -1);
    }

    /**
     * {@link #nearestOwnedBoxOid} for the rotate-on-stuck: {@code excludeOid} - the box this
     * bot just failed to approach - is off the plan, and the bot's claim on it is dropped so
     * a teammate that CAN reach it takes the box instead of nobody working it.
     */
    private static int nearestOwnedBoxOid(Character bot, int dataId, int excludeOid) {
        int myId = bot.getId();
        if (excludeOid >= 0) {
            TOWER_BOX_CLAIMS.remove(myId);
        }
        int nearest = nearestBoxOid(bot, dataId, excludeOid);
        if (nearest < 0) {
            TOWER_BOX_CLAIMS.remove(myId);
            return -1; // nothing standing (or nothing left to rotate onto)
        }

        // The nearest box is contested when another live bot in THIS map instance already
        // claims it. Claims are by reactor oid within one instance - the map's own identity
        // for a box - so two DIFFERENT boxes on one floor never contest each other (the old
        // y-only tolerance read them as one and sent both bots wandering), while two bots on
        // the SAME box always do.
        int here = mapInstanceOf(bot);
        for (Map.Entry<Integer, BoxClaim> claim : TOWER_BOX_CLAIMS.entrySet()) {
            if (claim.getKey() == myId
                    || claim.getValue().mapInstance() != here
                    || claim.getValue().oid() != nearest) {
                continue;
            }
            var holderBot = CharacterStorage.getBotById(claim.getKey());
            if (holderBot == null || holderBot.getChr() == null
                    || holderBot.getChr().getMapId() != bot.getMapId()) {
                continue; // a despawned / elsewhere holder's claim does not contest this room
            }
            // Contested: take the nearest UNCLAIMED box instead, so the party spreads over
            // the tower rather than queueing behind a teammate. Every standing box claimed
            // means the stage is fully staffed - return -1 and let the caller park this bot
            // by the stage NPC until a box (or a holder) disappears.
            int best = -1;
            double bestSq = Double.MAX_VALUE;
            for (int oid : PqActions.findAllReactorOids(bot, dataId)) {
                if (oid == excludeOid || oid == nearest) {
                    continue;
                }
                var candidate = bot.getMap().getReactorByOid(oid);
                if (candidate == null || candidate.getPosition() == null) {
                    continue;
                }
                boolean taken = false;
                for (Map.Entry<Integer, BoxClaim> other : TOWER_BOX_CLAIMS.entrySet()) {
                    if (other.getKey() != myId && other.getValue().mapInstance() == here
                            && other.getValue().oid() == oid) {
                        var holder = CharacterStorage.getBotById(other.getKey());
                        taken = holder != null && holder.getChr() != null
                                && holder.getChr().getMapId() == bot.getMapId();
                        break;
                    }
                }
                if (taken) {
                    continue;
                }
                double dsq = bot.getPosition().distanceSq(candidate.getPosition());
                if (dsq < bestSq) {
                    bestSq = dsq;
                    best = oid;
                }
            }
            if (best < 0) {
                TOWER_BOX_CLAIMS.remove(myId);
                return -1;
            }
            var bestReactor = bot.getMap().getReactorByOid(best);
            TOWER_BOX_CLAIMS.put(myId, new BoxClaim(here, best,
                    bestReactor != null && bestReactor.getPosition() != null
                            ? bestReactor.getPosition().y : 0));
            return best;
        }

        var reactor = bot.getMap().getReactorByOid(nearest);
        TOWER_BOX_CLAIMS.put(myId, new BoxClaim(here, nearest,
                reactor != null && reactor.getPosition() != null ? reactor.getPosition().y : 0));
        return nearest;
    }

    /** Drop this bot's stage-2 tower box claim, so a later bot can take its share. */
    public static void releaseTowerBox(int botId) {
        TOWER_BOX_CLAIMS.remove(botId);
        PqActions.clearReactorBeat(botId);
    }

    // =========================================================================
    // Stage 3 - the mob crates

    /**
     * Break a stage-3 crate, then fight what crawled out. Each crate (2201001) spawns three
     * Blocktopus (9300007), which are the pass carriers this stage asks for - so the loop is
     * hit a box, kill its spawn, loot, repeat.
     */
    public static void breakCratesAndHunt(Character bot) {
        int crate = nearestBoxOid(bot, LudiPqData.BOX_STAGE3);
        if (crate >= 0) {
            hitReactorRotate(bot, crate, LudiPqData.BOX_STAGE3);
        }
        // Same ordering rule as gatherPasses: the crate's three Blocktopus die where they were
        // summoned, so their passes land inside the crate radius and must be collected before
        // the next chase order replaces the pickup walk.
        if (!PqActions.pickupBeforeChase(bot, CRATE_LOOT_RADIUS_PX, new int[]{LudiPqData.PASS})) {
            PqActions.seekAndAttack(bot);
        }
        PqActions.loot(bot, bot.getPosition(), CRATE_LOOT_RADIUS_PX, new int[]{LudiPqData.PASS});
        // No crates left and nothing alive to fight: the stage's work is done. Hold the
        // stage-NPC post and deliver the stock only when the leader comes for the turn-in,
        // instead of throwing passes the moment he passes by mid-fight.
        if (crate < 0
                && bot.getMap().getAllMonsters().stream().noneMatch(m -> m.isAlive())) {
            int stageNpc = LudiPqData.stageNpcId(3);
            if (PqActions.handItemsToLeaderAfterStage(bot, LudiPqData.PASS, stageNpc) > 0) {
                PqActions.say(bot, BotMessages.get("pq.passes_dropped"));
            }
            if (PqActions.countItem(bot, LudiPqData.PASS) <= 0) {
                PqActions.spreadNearStageNpc(bot, stageNpc);
            }
        }
    }

    // =========================================================================
    // Stage 4 - the door rooms

    /**
     * The door room a bot has claimed (stage 4's plain rooms and stage 5's guard rooms
     * share the mechanism), keyed by bot id. The value carries the map INSTANCE's identity
     * along the room id (two concurrent runs hold separate room copies with the same ids -
     * a claim must not shadow the other run's rooms), so a live claim is judged against the
     * claimant's current instance. Claims are best-effort anti-crowding, not locks: two
     * bots may still briefly work one room, and the first clear wins.
     */
    private record DoorClaim(int instance, int room) {}

    private static final Map<Integer, DoorClaim> DOOR_ROOM_CLAIMS = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The door rooms this bot has personally cleared, keyed by bot id. The room ids repeat
     * across runs, so the set has to be empty when a fresh run begins - that is guaranteed by
     * the run-exit paths: every plugin-driven exit from the quest (stage done, party lost,
     * bot stopped) goes through {@link PartyQuestBot#returnToLobby} or
     * {@code stopScheduledTask}, and both call {@link #releaseRoomState} before warping, so
     * the set is dropped whole. (A GM/script warp straight into the lobby would skip the
     * release; the worst case is one bot skipping a room it had cleared in a previous run,
     * which self-corrects on the next release and is not worth instance-keying the set for.)
     */
    private static final Map<Integer, java.util.Set<Integer>> CLEARED_DOOR_ROOMS = new java.util.concurrent.ConcurrentHashMap<>();

    /** The map-instance key a bot's door-room records belong to. */
    private static int instanceOf(Character bot) {
        return System.identityHashCode(bot.getMap());
    }

    /** This bot's cleared-room set for the CURRENT instance (empty if none recorded yet). */
    private static java.util.Set<Integer> clearedRoomsFor(Character bot) {
        return CLEARED_DOOR_ROOMS.getOrDefault(bot.getId(), java.util.Set.of());
    }

    /** Record that this bot has personally finished a door room - it will not re-claim it. */
    private static void markRoomCleared(Character bot, int room) {
        CLEARED_DOOR_ROOMS.computeIfAbsent(bot.getId(),
                k -> java.util.concurrent.ConcurrentHashMap.newKeySet()).add(room);
    }

    /**
     * Whether door-room work still exists for THIS bot: a room it has not cleared that is
     * either unclaimed or claimed only by a dead/despawned bot. This - not "a claim exists"
     * - is what keeps the follow-leader beat off the bot's back: while true, the bot is on
     * its own errand; once false, it parks at the NPC, delivers, and follows the party again.
     */
    public static boolean doorRoomsOutstanding(Character bot, int roomFirst, int roomLast) {
        var cleared = clearedRoomsFor(bot);
        for (int room = roomFirst; room <= roomLast; room++) {
            if (cleared.contains(room) || roomClaimedByOtherLiveBot(bot, room)) {
                continue;
            }
            // Same posting table claimDoorRoom applies: a room this bot can never enter is not
            // "work still outstanding" for it. Without this the bot reported an errand it could
            // not run, so the follow-leader beat kept it from rejoining the party.
            if (roomFirst == LudiPqData.STAGE5_ROOM_FIRST && !stage5RoomAdmits(bot, room)) {
                continue;
            }
            return true;
        }
        return false;
    }

    /** Whether a room is currently claimed by a bot that is alive and still around. */
    private static boolean roomClaimedByOtherLiveBot(Character bot, int room) {
        int here = instanceOf(bot);
        for (Map.Entry<Integer, DoorClaim> claim : DOOR_ROOM_CLAIMS.entrySet()) {
            if (claim.getKey() != bot.getId() && claim.getValue().room() == room
                    && claim.getValue().instance() == here
                    && CharacterStorage.getBotById(claim.getKey()) != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Claim one of {@code roomFirst}..{@code roomLast} for this bot: the room it already
     * holds, or the first unclaimed, not-personally-cleared one. Returns -1 when every
     * room is taken or already done - the caller parks the bot by the stage NPC.
     */
    private static int claimDoorRoom(Character bot, int roomFirst, int roomLast) {
        DoorClaim mine = DOOR_ROOM_CLAIMS.get(bot.getId());
        int here = instanceOf(bot);
        // Keep the claim only while we are INSIDE its room (or holding it from the main map
        // between the claim and the door walk). A bot that exited carries a claim whose room
        // is already in the cleared set - dropping it here is what stops the exit, re-claim,
        // re-enter ping-pong; a bot still walking in keeps its claim.
        if (mine != null && mine.instance() == here && bot.getMapId() == mine.room()) {
            return mine.room();
        }
        if (mine != null && mine.instance() != here) {
            DOOR_ROOM_CLAIMS.remove(bot.getId()); // stale: a room change or a new run
            mine = null;
        }
        var cleared = clearedRoomsFor(bot);
        for (int room = roomFirst; room <= roomLast; room++) {
            if (cleared.contains(room) || roomClaimedByOtherLiveBot(bot, room)) {
                continue;
            }
            // Stage 5's posting table (top/bottom rooms are skill-restricted); stage 4 admits all.
            if (roomFirst == LudiPqData.STAGE5_ROOM_FIRST && !stage5RoomAdmits(bot, room)) {
                continue;
            }
            DOOR_ROOM_CLAIMS.put(bot.getId(), new DoorClaim(here, room));
            return room;
        }
        return -1;
    }

    /**
     * Walk a bot from the stage's main map into its claimed door room through the door's
     * in portal, entered by NAME (in01..in06, in room order) - the WZ portal ids run
     * 0=sp, 1=st00, 2=in01..., so a numeric offset from the room number would land on
     * the spawn portal and go nowhere. These doors carry no script: their WZ tm/tn data
     * (in01 -> 922010401 st00) routes the engine straight into the instance's room copy.
     * Returns true once the bot is inside.
     */
    private static boolean enterDoorRoom(Character bot, int room, int roomFirst) {
        String doorName = LudiPqData.roomPortalName(room - roomFirst);
        org.gms.server.maps.Portal door = bot.getMap().getPortal(doorName);
        if (door == null || door.getPosition() == null) {
            releaseDoorRoom(bot);
            return false;
        }
        if (PqActions.walkTo(bot, door.getPosition())) {
            PqActions.enterPortal(bot, door);
            if (bot.getMapId() == room) {
                return true;
            }
        }
        // Only give the claim up when the walk is genuinely OVER: walkTo's false means "not
        // arrived inside its 1.2s window", and a long walk to a far door is still in flight
        // then. Releasing on that would have two bots swapping the same room every tick.
        // A live move that the driver has not abandoned keeps the claim; a body the driver HAS
        // abandoned (or one that is somehow not moving at all) is the "cannot path there" case
        // the release exists for.
        if (!GCMovement.hasMoveTarget(bot)) {
            releaseDoorRoom(bot);
        }
        return false;
    }

    /** Drop this bot's door-room claim so a teammate can take the room. */
    private static void releaseDoorRoom(Character bot) {
        DOOR_ROOM_CLAIMS.remove(bot.getId());
    }

    /** Whether the party leader stands in this bot's room right now. */
    private static boolean leaderInRoom(Character bot) {
        Character leader = PqActions.partyLeader(bot);
        return leader != null && leader != bot && leader.getMapId() == bot.getMapId();
    }

    /**
     * Take a door room's own exit back to its stage's main map, walking to it first so the
     * return reads as walking. The door rooms' out portals are script-less, so the engine
     * accepts the warp from anywhere; the walk keeps it honest. The exit is whichever
     * {@code out} portal the bot can still path to (see {@link #exitPortalOf}).
     */
    private static void exitDoorRoom(Character bot) {
        org.gms.server.maps.Portal exit = exitPortalOf(bot);
        if (exit == null || exit.getPosition() == null || bot.getPosition() == null) {
            return;
        }
        if (standsDownForLiveMove(bot)) {
            return; // a walk owns the driver; see standsDownForLiveMove
        }
        if (PqActions.walkTo(bot, exit.getPosition())) PqActions.enterPortal(bot, exit);
    }

    /**
     * Work the stage-4 door rooms autonomously: a bot in the main room claims a door room
     * nobody else holds, walks in through its script portal, clears the box mobs inside,
     * and slips back out through the room's own exit once the room is quiet - delivering
     * on the main map's stage-NPC post like before. A bot with no room left to take (all
     * claimed or cleared by teammates) waits by the stage NPC with the leader.
     *
     * <p>The door's in portals are scripted, so entering them warps the bot inside without
     * the leader having to open anything first - which is what makes self-service exploration
     * possible here at all.
     */
    public static void workDoorRooms(Character bot, int roomFirst, int roomLast) {
        int mapId = bot.getMapId();
        if (mapId < roomFirst || mapId > roomLast) {
            workStage4FromMainRoom(bot);
            return;
        }
        // The stage was turned in while this bot was still inside a room: the room and its
        // boxes are worthless now, and the party is already one map on. Leave at once through
        // the room's own out portal instead of waiting for the room to go quiet. Without this
        // the bot stayed in a room whose mobs a teammate had already killed for as long as the
        // stage's remaining time - the reported "stage advances, one bot only shows up minutes
        // later". (The same gate exitTrapRoom has carried for the stage-2 trap room.)
        //
        // The leader must have left first, exactly as in the normal exit: while he is still in
        // the room the follow beat walks this bot straight back in behind him, which would turn
        // the two rules into a ping-pong.
        if (stageCleared(bot, LudiPqData.roomStage(mapId)) && !leaderInRoom(bot)) {
            markRoomCleared(bot, mapId);
            exitDoorRoom(bot);
            return;
        }
        // Inside a room: fight the box mobs and sweep the passes they drop. Pickup first for the
        // same reason as the collection stages - the room's one or two mobs die where they stood,
        // and the chase order used to shut loot()'s walk out for the whole visit.
        if (!PqActions.pickupBeforeChase(bot, LOOT_RADIUS_PX, new int[]{LudiPqData.PASS})) {
            PqActions.seekAndAttack(bot);
        }
        PqActions.loot(bot, bot.getPosition(), LOOT_RADIUS_PX, new int[]{LudiPqData.PASS});
        PqActions.recoverUngatheredHandoffs(bot, LudiPqData.PASS);
        // Room quiet (all the room's killable mobs dead): the work is done. Remember it as
        // cleared, then slip out the door and deliver on the main map's stage-NPC post -
        // the stage NPC lives there, so that is where the leader comes for the turn-in.
        // The exit waits for the leader to have LEFT this room first: while he is still
        // inside, the follow beat re-enters the room behind him and the bot ping-pongs.
        if (killableMobPresent(bot) || leaderInRoom(bot)) {
            return;
        }
        markRoomCleared(bot, mapId);
        exitDoorRoom(bot);
    }

    /**
     * The main-room half of the stage-4 loop. A bot with door work left claims a room and
     * walks in; a bot with nothing left to do (every room personally cleared or held by a
     * live teammate) parks by the stage NPC and delivers its stock when the leader comes
     * for the turn-in - the delivery the room loop can no longer run once it stops
     * entering rooms.
     */
    private static void workStage4FromMainRoom(Character bot) {
        int room = claimDoorRoom(bot, LudiPqData.STAGE4_ROOM_FIRST, LudiPqData.STAGE4_ROOM_LAST);
        if (room < 0) {
            deliverAndPark(bot, 4);
            return;
        }
        enterDoorRoom(bot, room, LudiPqData.STAGE4_ROOM_FIRST);
    }

    /**
     * Hold the stage-NPC post and hand the stock over the moment the leader walks into
     * hand-off range - the same post-work beat the collection stages run.
     *
     * @param stage the stage whose turn-in NPC the delivery belongs to ({@link LudiPqData#stageNpcId}),
     *              so the post is that NPC and not the map-exit NPC standing on the same ledge
     */
    private static void deliverAndPark(Character bot, int stage) {
        int stageNpc = LudiPqData.stageNpcId(stage);
        PqActions.recoverUngatheredHandoffs(bot, LudiPqData.PASS);
        PqActions.loot(bot, bot.getPosition(), LOOT_RADIUS_PX, new int[]{LudiPqData.PASS});
        if (PqActions.handItemsToLeaderAfterStage(bot, LudiPqData.PASS, stageNpc) > 0) {
            PqActions.say(bot, BotMessages.get("pq.passes_dropped"));
        }
        PqActions.waitNearStageNpc(bot, stageNpc);
    }

    /**
     * Work the stage-5 door rooms in stealth. The hide itself is owned by the movement thread
     * ({@code BotAuraState#updateGuardHide}), which arms it from the body's real contact geometry
     * instead of from a macro-tick guess, and covers both rooms that seat guards (922010500's
     * ground floor and 922010506). This method therefore only does the boxes.
     *
     * <p>Which bot enters WHICH room is the posting table ({@link #stage5RoomAdmits}): the
     * guard room (922010506) admits only a hide-carrying kit - 隐身术 (rogue lineage) or
     * 橡木伪装 (the Brawler's barrel disguise), the two auras that make a bot
     * monster-immune; the top room (922010501) only a mobile kit; 502-505 admit everyone.
     * A bot inside its admitted room works the boxes; the hide is re-shown only by kits
     * that carry one.
     */
    public static void sneakDoorRooms(Character bot) {
        int mapId = bot.getMapId();
        if (mapId < LudiPqData.STAGE5_ROOM_FIRST || mapId > LudiPqData.STAGE5_ROOM_LAST) {
            workStage5FromMainRoom(bot);
            return;
        }
        // The stage was turned in while this bot was still inside the maze: every box it could
        // still break is worthless, and the party has already moved on. Leave at once through
        // the room's own out portal. This must come FIRST - before the hide, the box search and
        // the loot - because the old exit condition required a pass in the pocket, so a bot sent
        // into a room a teammate had already emptied stood there until some unrelated drop
        // wandered into its loot radius (the reported "stage advances, one bot appears minutes
        // later"). A maze room has no next00, so nothing else could ever move it.
        if (stageCleared(bot, 5)) {
            markRoomCleared(bot, mapId);
            exitDoorRoom(bot);
            return;
        }
        // Boxes while hidden: the strike cancels the hide, so re-show happens next tick.
        // The beat is off: a swing mid-walk would break the hide in a PAD-999 room.
        int box = nearestBoxOid(bot, LudiPqData.BOX_STAGE5);
        if (box >= 0) {
            hitReactorRotate(bot, box, LudiPqData.BOX_STAGE5, false);
        }
        PqActions.loot(bot, bot.getPosition(), LOOT_RADIUS_PX, new int[]{LudiPqData.PASS});
        PqActions.recoverUngatheredHandoffs(bot, LudiPqData.PASS);
        // All four boxes broken: the room's work is done. Remember it as cleared, then slip
        // out the door and deliver on the main map's stage-NPC post - the leader turns the
        // stage in there, not at this room's mouth. Leaving while a box stands (or while the
        // leader is still in the room - the follow beat would walk the bot back in behind him)
        // keeps the sneak inside its room. The pocket is NOT part of this test: whether the bot
        // personally carries a pass says nothing about whether the room is finished, and the
        // drop itself is already protected - loot() above either picks it up or arms a pickup
        // walk, and exitDoorRoom stands down while a live walk owns the driver.
        if (box >= 0 || leaderInRoom(bot)) {
            return;
        }
        markRoomCleared(bot, mapId);
        exitDoorRoom(bot);
    }

    /**
     * The main-room half of the stage-5 loop. Rooms are assigned BY SKILL, per the quest's own
     * geography (WZ): 922010501 is the TOP room (spawns at its ceiling, every box below it) and
     * 922010506 the BOTTOM room (spawns at its floor, boxes stacked to its ceiling, the one room
     * WZ seeds with the 7 undefeatable guards). So:
     *
     * <ul>
     *   <li>922010501 (top) — a body with vertical mobility: 轻功 haste-thief lineage
     *       (Assassin/Bandit, 2nd job+) or 快速移动 teleport-mage lineage (F/P, I/L, Cleric
     *       2nd job+) — the climb back up to out00 after breaking the ceiling boxes is what
     *       those kits shortcut.</li>
     *   <li>922010506 (bottom) — a body that can survive the guards: a hide (隐身术 rogue
     *       lineage or 橡木伪装 Brawler). PAD 999 invincible golems one-shot anything else.</li>
     *   <li>922010502-505 (spawn level) — every other job: plain box rooms with no guards.</li>
     * </ul>
     *
     * A bot with no eligible room (every room taken or outside its posting) parks by the
     * stage NPC and delivers its stock there: stage 5's turn-in runs from this post.
     */
    private static void workStage5FromMainRoom(Character bot) {
        int room = claimDoorRoom(bot, LudiPqData.STAGE5_ROOM_FIRST, LudiPqData.STAGE5_ROOM_LAST);
        if (room < 0) {
            deliverAndPark(bot, 5);
            return;
        }
        enterDoorRoom(bot, room, LudiPqData.STAGE5_ROOM_FIRST);
    }

    /**
     * Whether this bot's kit is eligible to claim {@code room} in stage 5 - the posting table
     * above (top = mobility, bottom = hide, the rest = anyone).
     *
     * <p>The top room's door (in01 of 922010500) hangs on a ledge a full 100px above the row
     * beneath it, with no WZ foothold / rope / portal bridging the step; WZ Physics.img
     * (gravity 2000, jumpSpeed 555) makes that a jump of ~115% jump stat or a blink. The room is
     * NOT optional - all six stage-5 rooms hold four pass boxes and the stage asks for 24, so
     * refusing the top room to a body that cannot make the step starves the turn-in. The answer
     * is therefore not "skip the room" but "the party's Haste must be modelled": see
     * {@link BotMovementProfile#fromCharacter}, where a haste-thief in the party raises the
     * jump stat it grants (the real skill grants speed AND jump).
     */
    static boolean stage5RoomAdmits(Character bot, int room) {
        if (room == LudiPqData.STAGE5_ROOM_FIRST) {
            Job job = bot.getJob();
            return MovementStylePolicy.hasTeleportSkill(job == null ? 0 : job.getId())
                    || MovementStylePolicy.hasHasteSkill(job);
        }
        if (room == LudiPqData.STAGE5_ROOM_LAST) {
            return hideSkillFor(bot) != 0; // guards: 隐身术 / 橡木伪装 only
        }
        return true;
    }

    /**
     * Whether stage 5 admits this bot to a specific sub-room if the party moved there: the
     * follow gate checks the posting table against the room the party is actually in (or
     * walking into), not against "some room somewhere".
     *
     * <p>The main map is admitted for every kit on the UPPER floors, which is where the party
     * lives: following into it is ordinary party movement, the stage NPC post (y=-225) sits
     * ~273px above the guards' ledge, and the combat sweep stands the bot's swings down while a
     * guard is in its seek box (LudiPQBot#fightsOnSweep). The guarded ground floor is not a
     * "room" a bot is posted to - it is a floor no target may point at, which
     * {@link #refusesFloor} decides where a chase picks its target.
     */
    public static boolean stage5AdmitsTo(Character bot, int mapId) {
        if (LudiPqData.roomStage(mapId) >= 0) {
            return stage5RoomAdmits(bot, mapId); // a sub-room: its own posting rule
        }
        return true; // the main map: the upper floors are ordinary party movement
    }

    /**
     * The hide skill this bot's kit carries for sneaking past the stage-5 guards, or 0 for
     * a job that has none: 隐身术 (rogue lineage - the one place a THIEF bot's registered
     * hide is spent) or 橡木伪装 (the Brawler's barrel disguise). Both are display-only
     * auras on the bot, but the JOB check is real: the kit registry is what decides whether
     * this body can survive the room.
     */
    public static int hideSkillFor(Character bot) {
        var kit = BotBuffConfig.buffsForJob(bot.getJob());
        if (kit.contains(Rogue.DARK_SIGHT)) {
            return Rogue.DARK_SIGHT;
        }
        if (kit.contains(Brawler.OAK_BARREL)) {
            return Brawler.OAK_BARREL;
        }
        return 0;
    }

    /**
     * Show this bot's own hide (隐身术 for rogue lineage, 橡木伪装 for a Brawler) and keep
     * it up: the movement tick only retires the hide on an attack or a mount, so simply
     * re-showing each tick while guards are near reads as one long stealth. A no-op for a
     * bot whose kit carries neither - those never enter a guard room in the first place.
     */
    public static void stayHidden(Character bot) {
        int hide = hideSkillFor(bot);
        if (hide != 0 && !BotAuraState.isMonsterImmune(bot)) {
            soloMapling.ArtificialPlayer.BotAttackSystem.BotBuffEffects.showBuff(bot, hide);
        }
    }

    /**
     * Release everything this bot holds inside the Ludi rooms: its stage-2 tower box claim
     * and its stage-NPC wait-spot claim. Called when the bot leaves the map or the run ends,
     * so a replacement bot starts with a clean share.
     */
    public static void releaseRoomState(int botId) {
        releaseTowerBox(botId);
        DOOR_ROOM_CLAIMS.remove(botId);
        CLEARED_DOOR_ROOMS.remove(botId);
        releaseBonusReward(botId);
        PqActions.releaseWaitClaims(botId);
    }

    /** How many stage-5 guards (9300013) are alive within the seek box. */
    public static int nearbyGuardCount(Character bot) {
        return (int) bot.getMap().getAllMonsters().stream()
                .filter(m -> m.isAlive() && m.getId() == LudiPqData.GUARD_MOB)
                .filter(m -> Math.abs(m.getPosition().x - bot.getPosition().x) <= 900
                        && Math.abs(m.getPosition().y - bot.getPosition().y) <= 3_200)
                .count();
    }

    /**
     * Whether a PAD-999 guard can actually REACH this bot: alive, within its touch box -
     * a tight same-ledge test, not the tower-wide seek box.
     *
     * <p>The guards stand on the ground floor (y≈58); the stage NPC's post (y≈-215) is
     * ~273px above them, well past a touch. The tower-wide box the combat-sweep gate uses
     * counts the guards as "near" from the NPC post too, which is right for "don't swing
     * AT them" but dead wrong for "hide or die": a bot holding the post-work delivery
     * there is untouchable and must keep delivering. This is that reach test.
     */
    public static boolean guardCanReach(Character bot) {
        Point p = bot.getPosition();
        if (p == null) {
            return false;
        }
        return bot.getMap().getAllMonsters().stream()
                .anyMatch(m -> m.isAlive() && m.getId() == LudiPqData.GUARD_MOB
                        && m.getPosition() != null
                        && Math.abs(m.getPosition().x - p.x) <= 120
                        && Math.abs(m.getPosition().y - p.y) <= 150);
    }

    /**
     * Whether this kit carries the hide stage 5's guarded floor demands: 隐身术 (4001003, the
     * rogue lineage) or 橡木伪装 (5101007, the Brawler).
     *
     * <p>The gunslinger line is the trap here - those bots are pirates, but 橡木伪装 is the
     * Brawler's skill and is absent from their registry; the night walker's 隐身术 (14001003) is
     * not registered either. One switch behind both halves of the rule: this same gate decides
     * who may enter 922010506, and who may be given a target on 922010500's guarded floor.
     */
    public static boolean carriesGuardHide(Character bot) {
        return bot != null && hideSkillFor(bot) != 0;
    }

    /**
     * The guards' floor of 922010500 - everything at or below this y.
     *
     * <p>From the WZ life table: the two invincible 9300013 stand on the ledge at y=58 with
     * their feet at y=54/55, and every foothold below them (y=90..662) is the same bottom
     * shaft. Everything the party uses is above it: the stage NPC post y=-225, the stage door
     * y=-216, the sub-room doors y=-525..-1292. Only 922010506's two exits land down there
     * (at in06, y=251).
     *
     * <p>A y line rather than a region id on purpose: regions are bake artifacts that a re-bake
     * may renumber, while these values come straight from the map data.
     */
    public static final int GUARD_FLOOR_Y = 0;

    /**
     * Whether {@code point} is somewhere this bot must not be given as a target: it lies on
     * stage 5's guarded floor and this kit has no hide to survive it.
     *
     * <p>Applied where a chase PICKS its target, not where an order is cancelled afterwards:
     * cancelling only lasts until the next beat re-issues the same order (measured - the bot
     * creeps down a step per beat and ends up beside the golems anyway). There is no way back
     * from that floor either, so a hide-less body sent down stays down until the stage-clear
     * warp collects it.
     */
    public static boolean refusesFloor(Character bot, Point point) {
        return bot != null
                && bot.getMapId() == LudiPqData.STAGE_5
                && point != null
                && point.y >= GUARD_FLOOR_Y
                && !carriesGuardHide(bot);
    }

    /**
     * The room's exit back to its stage's main map: the exit portal the bot can actually
     * PATH to, nearest first.
     *
     * <p>房 501 (and anything shaped like it) makes this a real choice, not a nicety: the
     * 塔的迷路 tower is entered at the top, its boxes sit on ledges that only chain DOWNWARD
     * (the middle rope's bottom hangs above the lowest two-way ledge, so nothing below can
     * climb back), and its second exit (out01) is at the bottom. A bot that finished the deep
     * boxes cannot return to the top-row out00 - the old hard-coded out00 sent the climb back
     * up as an unwalkable goal, and the bot stalled against it until the movement layer gave
     * up (the "climbs, stalls, drops, retries, quits" report). Asking the nav graph which
     * exit is reachable answers this for every room shape: the top-row bot paths to out00,
     * a bot past the one-way ledge paths to out01.
     */
    private static org.gms.server.maps.Portal exitPortalOf(Character bot) {
        var map = bot.getMap();
        org.gms.server.maps.Portal best = null;
        double bestSq = Double.MAX_VALUE;
        for (org.gms.server.maps.Portal p : map.getPortals()) {
            String name = p.getName();
            if (!"out00".equals(name) && !"out01".equals(name)) {
                continue;
            }
            if (p.getPosition() == null) {
                continue;
            }
            double dsq = bot.getPosition().distanceSq(p.getPosition());
            if (dsq >= bestSq) {
                continue;
            }
            if (!GCMovement.canPathTo(bot, p.getPosition().x, p.getPosition().y)) {
                continue; // on the far side of a one-way ledge; the other exit is the way out
            }
            best = p;
            bestSq = dsq;
        }
        // No reachable exit (graph still baking / degenerate room): fall back to the
        // nearest exit rather than none, so the walk at least starts somewhere.
        if (best != null) {
            return best;
        }
        for (org.gms.server.maps.Portal p : map.getPortals()) {
            if ("out00".equals(p.getName())) {
                return p;
            }
        }
        return null;
    }

    /** The nearest alive box of this data id, or -1. */
    private static int nearestBoxOid(Character bot, int dataId) {
        return nearestBoxOid(bot, dataId, -1);
    }

    /**
     * The nearest alive box of this data id except {@code excludeOid}, or -1. The STUCK
     * caller passes the box it just failed to reach, so the bot rotates to another box
     * (a different platform, a different edge) instead of replaying the same dead approach.
     */
    private static int nearestBoxOid(Character bot, int dataId, int excludeOid) {
        int best = -1;
        double bestSq = Double.MAX_VALUE;
        for (int oid : PqActions.findAllReactorOids(bot, dataId)) {
            if (oid == excludeOid) {
                continue;
            }
            var reactor = bot.getMap().getReactorByOid(oid);
            if (reactor == null || reactor.getPosition() == null) {
                continue;
            }
            double dsq = bot.getPosition().distanceSq(reactor.getPosition());
            if (dsq < bestSq) {
                bestSq = dsq;
                best = oid;
            }
        }
        return best;
    }

    /**
     * The nearest reactor of a data id that is still ARMED ({@code getReactorType() == 100}).
     *
     * <p>Distinct from {@link #nearestBoxOid} on purpose: {@code isActive()} only means "alive
     * with a valid state type", which stays true after an item reactor fires (its next state
     * carries a type of its own). An altar that has already summoned its boss must not be
     * struck again, so the caller needs the stricter test.
     */
    private static int nearestArmedReactorOid(Character bot, int dataId) {
        int best = -1;
        double bestSq = Double.MAX_VALUE;
        for (var reactor : bot.getMap().getAllReactors()) {
            if (reactor.getId() != dataId || !reactor.isAlive() || reactor.getReactorType() != 100) {
                continue;
            }
            if (reactor.getPosition() == null) {
                continue;
            }
            double dsq = bot.getPosition().distanceSq(reactor.getPosition());
            if (dsq < bestSq) {
                bestSq = dsq;
                best = reactor.getObjectId();
            }
        }
        return best;
    }

    /**
     * Strike a box until it is gone, standing where the approach left us. The tower's pass
     * box is a multi-state reactor (each hit cracks it further, the last breaks it), and the
     * trap room's boxes share it - so one swing per macro tick was a full tick per state,
     * and eleven boxes crawled. The swings still land in one visit so the box does not
     * straddle macro beats, but each beat waits the driver's swing cadence out rather than a
     * fixed 250ms machine-gun: a player's repeat rate is the weapon's attack speed, which
     * the attack driver models at 720-900ms.
     */
    private static void breakBoxInPlace(Character bot, int oid) {
        for (int swings = 0; swings < BOX_SWING_CAP; swings++) {
            var reactor = bot.getMap().getReactorByOid(oid);
            if (reactor == null || !reactor.isActive()) {
                PqActions.boxFinishedPullTick(bot); // pull the next box's tick forward
                return; // broken (or being reset) - no further transition to walk
            }
            if (!PqActions.hitReactor(bot, oid)) return;
            blockingSleep(reactorSwingBeatMs());
        }
    }

    /** Upper bound on one in-place box combo: a box needs 3-4 swings; more means a bug. */
    private static final int BOX_SWING_CAP = 6;

    /** The floor between a combo's swings, so the swing animation reads before the next one. */
    private static final long BOX_SWING_BEAT_MIN_MS = 600;
    /** Jitter on the beat so a cohort does not swing in lockstep. */
    private static final long BOX_SWING_BEAT_JITTER_MS = 250;

    /** The player-repeat-rate beat between reactor hits, shared with the other quests' loops. */
    public static long reactorSwingBeatMs() {
        return BOX_SWING_BEAT_MIN_MS + ThreadLocalRandom.current().nextLong(BOX_SWING_BEAT_JITTER_MS);
    }

    /**
     * Approach-and-strike {@code oid} when the approach can reach it by walking DOWN (a box
     * on a lower floor of a tower: the bot must take the map's edges to its level); on a
     * STUCK approach rotate to the next-nearest box of the same kind. Returns true while a
     * box is still being worked, false when none is reachable.
     */
    private static boolean hitReactorDescendRotate(Character bot, int firstOid, int dataId) {
        int oid = firstOid;
        for (int attempt = 0; attempt < 3 && oid >= 0; attempt++) {
            var reactor = bot.getMap().getReactorByOid(oid);
            if (reactor == null || reactor.getPosition() == null) {
                return false;
            }
            PqActions.Approach outcome = PqActions.approachReactor(bot, oid, true, true);
            if (outcome != PqActions.Approach.STUCK) {
                // The sweep owns both the approach and the entire strike sequence.
                return true;
            }
            oid = nearestOwnedBoxOid(bot, dataId, oid); // STUCK: rotate to an unclaimed box
        }
        return false; // every candidate failed this tick; try again next tick
    }

    /**
     * Approach-and-strike {@code oid} on the bot's own level (a same-floor walk or jump);
     * on a STUCK approach rotate to the next-nearest box of the same kind. Returns true
     * while a box is still being worked, false when none is reachable.
     *
     * <p>{@code onBeat} arms the combat sweep to carry the in-flight approach and strike on
     * arrival. The sneak stage passes {@code false}: its bot is hidden, the beat's swing
     * would break Dark Sight in a PAD-999 room, and one box per macro tick is already that
     * stage's documented honest pace.
     */
    private static boolean hitReactorRotate(Character bot, int firstOid, int dataId, boolean onBeat) {
        int oid = firstOid;
        for (int attempt = 0; attempt < 3 && oid >= 0; attempt++) {
            var reactor = bot.getMap().getReactorByOid(oid);
            if (reactor == null || reactor.getPosition() == null) {
                return false;
            }
            PqActions.Approach outcome = PqActions.approachReactor(bot, oid, false, onBeat);
            if (outcome == PqActions.Approach.IN_POSITION) {
                if (!onBeat) breakBoxInPlace(bot, oid);
                return true;
            }
            if (outcome == PqActions.Approach.TRAVELLING) {
                return true;
            }
            oid = nearestBoxOid(bot, dataId, oid); // STUCK: rotate to another box
        }
        return false; // every candidate failed this tick; try again next tick
    }

    private static boolean hitReactorRotate(Character bot, int firstOid, int dataId) {
        return hitReactorRotate(bot, firstOid, dataId, true);
    }

    // =========================================================================
    // Stage 6 - the climb
    // =========================================================================

    /**
     * Work up the tower by following the one portal row that actually climbs.
     *
     * <p>The tower's {@code h0NN} portals all target the tower itself and carry no script, so
     * every one of them is a same-map hop and none of them is marked as "the way up". The map
     * data does distinguish them: a portal's {@code tn} is the NAME of the portal it lands on,
     * and most of them land on {@code st00}, the bottom spawn - those are the decoys that drop
     * the climber back down. A handful land on another {@code h0NN} that sits ~170px higher,
     * and those are the ladder.
     *
     * <p>So the climb is read rather than guessed: take the portal whose target is a rung above
     * the bot, land, and repeat. Each rung is one macro tick, which is also what keeps a failed
     * hop cheap - a decoy at worst returns {@code false} and the next tick tries from where the
     * bot now stands.
     */
    public static boolean climbTower(Character bot, int previousY) {
        int hereY = bot.getPosition().y;
        org.gms.server.maps.Portal rung = nextRungUp(bot, hereY);
        if (rung == null) {
            rung = bot.getMap().getPortal("next00");
            if (rung == null) return stageCleared(bot, 6) || previousY > hereY;
        }
        if (!PqActions.walkTo(bot, rung.getPosition())) {
            return false;
        }
        PqActions.enterPortal(bot, rung);
        // A working rung leaves the bot higher than it was (the tower's y decreases as it goes
        // up); a decoy puts it back at the bottom.
        return bot.getPosition().y < hereY;
    }

    /**
     * The tower portal that lands on a rung above {@code fromY}, or null when there is none.
     *
     * <p>Only {@code h0NN} portals count, and only those whose {@link
     * org.gms.server.maps.Portal#getTarget()} names another {@code h0NN} (or {@code next00}) higher up -
     * the rest target {@code st00} and are the decoys. Picking the LOWEST such rung keeps the
     * climb in order instead of teleporting past rungs the party still has to walk.
     */
    private static org.gms.server.maps.Portal nextRungUp(Character bot, int fromY) {
        var map = bot.getMap();
        if (map == null) {
            return null;
        }
        org.gms.server.maps.Portal best = null;
        int bestY = fromY;
        for (org.gms.server.maps.Portal portal : map.getPortals()) {
            String name = portal.getName();
            String target = portal.getTarget();
            if (name == null || !name.startsWith(LudiPqData.CLIMB_PORTAL_PREFIX)
                    || target == null || !(target.startsWith(LudiPqData.CLIMB_PORTAL_PREFIX)
                            || target.equals("next00"))
                    || target.equals(name)) {
                continue; // a decoy (lands on st00 / on itself), not a rung
            }
            org.gms.server.maps.Portal landing = map.getPortal(target);
            if (landing == null) {
                continue;
            }
            int landingY = landing.getPosition().y;
            // Higher up means a smaller y. Take the smallest step that still gains height.
            // Portal anchors sit slightly above their foothold. Do not walk back to the
            // previous rung just because its landing is 1-2px above our grounded feet.
            if (landingY < fromY - 8 && (best == null || landingY > bestY)) {
                best = portal;
                bestY = landingY;
            }
        }
        return best;
    }

    // =========================================================================
    // Stage 8 - the crate combination
    // =========================================================================

    /**
     * Stand on this bot's share of the crates the quest asked for.
     *
     * <p>Exactly five people have to be on crates and the pattern has to match, so the bots
     * cannot simply take five boxes: the real player is one of the five the quest counts, and
     * a bot that filled his crate would make the count come out at five on the wrong boxes.
     * The bots therefore take from the far end of the list and leave the near end free.
     *
     * <p>The combination does not exist yet when the bots first arrive: {@code 2040043.js}
     * generates {@code stage8combo} only inside its {@code playersOnCombo == 5} branch, i.e.
     * only after five bodies are ALREADY standing on crates. Waiting for the property (the old
     * behaviour) was a hard deadlock - the bots waited for a combination that the combination
     * itself was waiting for them to produce. Before it exists the bots therefore take a
     * deterministic guess set; the leader's conversation then generates the real combination,
     * and the next tick re-reads it and moves the bots onto the real five.
     *
     * @return how many crates still need a body (5 minus the bots standing on one), or -1 when
     *         the quest's plan is unusable. This is what the announcement must say: the plan's
     *         total (always 5) read as "5 more needed" and made the player take a sixth crate,
     *         which the script counts as a failed attempt.
     */
    public static int takeCrate(Character bot, int bodyIndex) {
        String combo = PqActions.readEimString(bot, LudiPqData.COMBO_PROPERTY);
        List<Point> wanted = combo == null
                ? LudiPqData.guessCrates()
                : LudiPqData.cratesIn(combo);
        if (wanted.size() != LudiPqData.CRATES_TO_STAND_ON) {
            // The quest builds its combination as exactly five of nine; anything else means
            // the property was written by something other than the stage script.
            return -1;
        }
        // Tail-first, so two bots never pick the same crate and the head slots stay free for
        // whoever else fills the pattern (the same rule for a real combination and a guess).
        Point mine = LudiPqData.crateAt(wanted, bodyIndex);
        if (mine != null) {
            PqActions.holdArea(bot, mine, 1_200);
        }
        // The announcement asks the HUMANS to fill the rest, so the count is "crates still
        // unoccupied by a bot". Bots seat themselves tail-first and a party can hold more bots
        // than crates (6 players, 5 crates), so the seated count is capped by the plan size -
        // see seatedBots, which is also why this cannot go negative.
        return Math.max(0, wanted.size() - seatedBots(bot));
    }

    /**
     * How many of the plan's five crates the bots in this party can occupy: the bots take the
     * TAIL of the list, so a party with more bots than crates fills the plan completely and the
     * rest are simply not seated ({@link LudiPqData#crateAt} answers null past the end).
     */
    private static int seatedBots(Character bot) {
        var party = bot.getParty();
        if (party == null) {
            return 1;
        }
        int bots = 0;
        for (var member : party.getMembers()) {
            if (CharacterStorage.getBotById(member.getId()) != null) {
                bots++;
            }
        }
        return Math.min(LudiPqData.CRATES_TO_STAND_ON, bots);
    }

    // =========================================================================
    // Chase policy
    // =========================================================================

    /**
     * Seconds this bot's chase must stay on targets the navigation graph can actually reach.
     *
     * <p>Set for the two stages whose work sits on a shelf with no inbound edge, cleared for
     * every other room. This is a pure policy flag - it never issues a move. The chase itself is
     * driven by whoever normally drives it (the combat beat through {@code seekAndAttack}), which
     * reads the flag.
     *
     * <p>Why it must be re-asserted on the beat instead of once per stage: the flag lives inside
     * {@code PqActions} and is sticky by design, but the macro tick that used to set it runs
     * 36-48s apart on an unobserved map. Measured on 922010900, a bot entering the room before
     * its first macro tick chased the unreachable altar ledge and stalled at (585,184) for 18.5s.
     */
    public static void applyChasePolicy(Character bot) {
        if (bot == null || bot.getMap() == null) {
            return;
        }
        int stage = LudiPQBot.stageOf(bot.getMapId());
        boolean restrict = stage == 7 || stage == 9;
        // Only the FLAG. Every other room - including the bonus maps, the trap room and the door
        // sub-rooms, which stageOf reads as -1 or 302+ - has to drop it, or the bot would refuse
        // to chase anything off its own ledge for the rest of the run.
        PqActions.setReachableOnly(bot, restrict);
    }

    // =========================================================================
    // After stage 9 - the bonus room
    // =========================================================================

    /**
     * Break the bonus room's reward boxes, and nothing else.
     *
     * <p>{@code 922011000} holds 36 of 2202004; each one sprays a handful of items on the
     * ground ({@code 2202004.js} -> {@code rm.sprayItems}). Those items belong to the party's
     * human members - the bot's job in this minute is to open the boxes, so it must not run a
     * loot sweep at all. (The shared combat beat's stepped-on pickup is disabled for these
     * maps by {@link LudiPQBot#steppedOnLootIds()}.)
     *
     * <p>The room ends by itself: {@code clearPQ} armed a one-minute timer, and
     * {@code scheduledTimeout} warps the whole team to 922011100. So there is no exit to walk
     * and no state to report - break boxes while any stand, and otherwise just stay with the
     * party. The tail room ({@code 922011100}) has no boxes (its reactors are the exit NPC and
     * a scenery object), so it is a plain "wait for the team" room.
     */
    public static void breakBonusBoxes(Character bot) {
        int box = nearestBoxOid(bot, LudiPqData.BONUS_BOX);
        if (box >= 0) {
            // onBeat arms the combat sweep to carry the approach and take the swings; the box
            // is an ordinary breakable reactor (2202004 links 2202002's 4-state walk), so a
            // plain strike sequence is all it needs.
            hitReactorRotate(bot, box, LudiPqData.BONUS_BOX);
        }
    }

    /**
     * Work the bonus stage's TAIL room (922011100) and take its exit.
     *
     * <p>{@code clearPQ} armed a one-minute timer and {@code scheduledTimeout} warped the team
     * from the box room to this one. The room holds no boxes at all - its only contents are the
     * two NPCs (2040035 Arturo at (190,-1136), 9000033 at (24,-582)) and three ropes - so the box
     * loop used above has nothing to do here and the bot stood still forever: 922011100 is inside
     * {@link LudiPqData}'s quest range, so {@code tickLobby} does not reclaim it either.
     *
     * <p>The exit is what a player does there: walk up to 2040035, spend a moment on it, and be
     * warped home. The bot deliberately does NOT run that conversation. {@code 2040035.js} hands
     * out {@code eim.giveEventReward(player)} and then warps, so driving it from a bot would grant
     * the bot an event reward and - when its inventory happens to be full - leave the script's
     * dialog open with nothing able to click it closed. What the bot reproduces is the observable
     * part only: the walk to the NPC, the pause a person spends reading, and the hop back to the
     * party lobby.
     *
     * @return true once the dialogue beat is over and this bot should leave the run
     */
    public static boolean takeBonusReward(Character bot) {
        Point post = bonusTailNpcSpot(bot);
        if (post == null) {
            return false; // no standable spot under the NPC: this room is broken, not the bot
        }
        if (!PqActions.withinPickupReach(bot, post)) {
            // Nothing else in this room owns the driver (no mobs, no boxes), so the walk does not
            // have to yield to anyone; walkTo's own "already standing on the spot" test is what
            // keeps a re-arm from restarting a finished walk.
            PqActions.walkTo(bot, post);
            bonusTalkUntil.remove(bot.getId());
            return false;
        }
        long now = System.currentTimeMillis();
        Long until = bonusTalkUntil.get(bot.getId());
        if (until == null) {
            // Arrived: stand and read. Jittered per bot so a cohort does not warp in lockstep.
            bonusTalkUntil.put(bot.getId(),
                    now + BONUS_TALK_MIN_MS
                            + ThreadLocalRandom.current().nextLong(BONUS_TALK_JITTER_MS));
            return false;
        }
        return now >= until;
    }

    /**
     * The standable spot at the reward NPC, from the room's own WZ geometry: 2040035 is declared
     * at (190,-1136) and the ledge beneath it sits at y=-1133.
     */
    private static Point bonusTailNpcSpot(Character bot) {
        Point at = LudiPqData.BONUS_TAIL_NPC_SPOT;
        Point ground = GCMovement.groundPointBelow(bot.getMap(), at.x, at.y);
        return ground != null ? ground : at;
    }

    /**
     * The pause a player spends on 2040035's two confirmations. Long enough that the walk up to
     * the NPC and the departure read as separate beats; short enough that the room costs seconds.
     */
    private static final long BONUS_TALK_MIN_MS = 2_000L;
    private static final long BONUS_TALK_JITTER_MS = 1_200L;

    /** botId -> the epoch-ms at which this bot's simulated turn-in conversation ends. */
    private static final Map<Integer, Long> bonusTalkUntil = new java.util.concurrent.ConcurrentHashMap<>();

    /** Drop the tail room's dialogue clock with the rest of this bot's room state. */
    static void releaseBonusReward(int botId) {
        bonusTalkUntil.remove(botId);
    }

    // =========================================================================
    // Stage 9 - the boss
    // =========================================================================

    /**
     * Fight the boss stage.
     *
     * <p>What actually ends the stage: one Alishar trophy (4001023) in the party leader's hands,
     * so the bot's job is damage - but only on what it can reach.
     *
     * <p>The crystal (2201003) at (599,0) is the only thing that summons Alishar: its script
     * spawns 9300012 on this map, and the server's own event hooks ({@code monsterKilled} /
     * {@code allMonstersDead} in LudiPQ.js) are empty, so killing the Ratz does nothing at all
     * despite 2040044.js saying it does. The crystal's ledge has no inbound edge in the map
     * data (it can only be entered by falling onto it from above), so a bot that cannot reach
     * it must WAIT rather than circle a target it can never touch - the human player can reach
     * the altar and summon. That is why both halves are best-effort: strike the crystal when
     * standing in its reach, add damage to Alishar when he is up, and otherwise hold.
     */
    public static void fightBoss(Character bot) {
        // Summon: only from inside the crystal's own reach, since the server does no distance
        // check and a hit from across the room would read as hitting through walls. The
        // `type == 100` test is the engine's own "still waiting for its item" state - once the
        // altar fires it advances past it, and striking the spent crystal would do nothing.
        int crystal = nearestArmedReactorOid(bot, LudiPqData.BOSS_CRYSTAL);
        if (crystal >= 0 && PqActions.hitReactorIfInReach(bot, crystal)) {
            return;
        }
        // Everything else is damage-only, and only on what the bot can actually touch:
        // reachableOnly keeps the chase off the altar ledge's Ratz, which stand on a shelf no
        // edge chain reaches. Without it the bot spends every beat walking at a target it can
        // never fight (the "paces under the platform" report) instead of waiting for the human
        // to summon Alishar. Alishar himself is a boss and is exempt from the ledge filter, so
        // he is still chased the moment he appears on a floor the bot can stand on.
        PqActions.seekAndAttack(bot, true);
    }
}
