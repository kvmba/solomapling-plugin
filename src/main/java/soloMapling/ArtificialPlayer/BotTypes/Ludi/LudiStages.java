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
        if (killableMobPresent(bot)) {
            PqActions.seekAndAttack(bot);
            PqActions.loot(bot, bot.getPosition(), LOOT_RADIUS_PX, new int[]{LudiPqData.PASS});
            return;
        }
        // Stage 7 hand-writes the passes: its only source is the Rombard (9300010) that the
        // three box reactors (2201002) summon when hit - the room's pre-placed mobs drop no
        // passes at all. With nothing killable left, an armed box means the stage is NOT
        // done: break one (same claim/approach/beat pattern as the stage-1/2 box towers) and
        // let the next beat hunt what it summons. Only a room whose boxes are all spent
        // reaches the delivery below.
        if (stage == 7) {
            int box = nearestOwnedBoxOid(bot, LudiPqData.BOX_STAGE7);
            if (box >= 0 && hitReactorRotate(bot, box, LudiPqData.BOX_STAGE7)) {
                PqActions.loot(bot, bot.getPosition(), LOOT_RADIUS_PX, new int[]{LudiPqData.PASS});
                return;
            }
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
     * <p>The room's twelfth box, the trap (2200002), is left strictly alone: its script
     * warps the whole party into the trap room (922010201), where four more of the fifteen
     * passes wait behind {@link #workTrapRoom}. A bot that pops it "for free mesos" drags
     * the run sideways instead.
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
     * Whether a room exit must stand down this tick because the movement driver still holds a
     * live move. {@code walkTo} replaces {@code moveTarget} unconditionally, and entering the
     * portal mid-walk cancels the walk outright - either way a pickup/reclaim walk armed earlier
     * in the tick (or the chase) is cut off and its drop is orphaned in a room the bot is
     * leaving. This is the same "a live move is never re-targeted mid-flight" contract the park
     * re-arms honour, but STRICTER than {@code PqActions.yieldsToLiveWalk}: that gate lets a
     * re-arm through when the bot stands on its own spot, while a transition - the one move
     * that leaves the room - must never cancel a walk, even from its spot. The exit waits for
     * the driver to go idle; the walk's own arrival nudge re-runs the tick's work promptly, and
     * a walk the engine ABANDONS (its no-progress watchdog, ~8s) frees the exit on a later tick.
     * Deliberate trade: a drop that is visible but UNREACHABLE keeps re-arming a walk (loot's
     * own scan, then the 6s orphan probe) and holds the exit until the drop despawns (~3 min)
     * or a teammate takes it - the drop is stage-required, and leaving without it orphans it
     * for good, which is the defect this gate closes.
     */
    private static boolean standsDownForLiveMove(Character bot) {
        return GCMovement.hasMoveTarget(bot);
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
        PqActions.seekAndAttack(bot);
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
            return false;
        }
        if (PqActions.walkTo(bot, door.getPosition())) PqActions.enterPortal(bot, door);
        return bot.getMapId() == room;
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
        // Inside a room: fight the box mobs and sweep the passes they drop.
        PqActions.seekAndAttack(bot);
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
     * Work the stage-5 door rooms in stealth: the rooms' four pass boxes can be guarded by
     * invincible Block Golems (PAD 999 - a touch is a death sentence), so the bot shows
     * 隐身术 for the whole visit. Only 922010500 (the main room, 2 golems) and 922010506
     * (7) actually hold them - rooms 922010501-505 are empty in WZ - but the stealth stance
     * is harmless where no guard stands, and it is re-evaluated per room, so a guard added
     * by a future WZ edit is covered. The hide makes the bot untouchable (isMonsterImmune)
     * and costs nothing: it is cancelled by the reactor strike itself, and re-shown next
     * tick.
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
        // Re-show every tick while inside: the strike cancels the hide (an attack action),
        // so the next tick's show re-stealths - one box per tick, the honest sneak pace.
        // Only hide-carrying kits are ever routed into the guard room (the posting table);
        // a bot in 501-505 without a hide is simply on its own honest legs there - those
        // rooms are WZ-empty of guards.
        stayHidden(bot);
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
        // Stealth is already up: updateState() raises it for every stage-5 map before any
        // movement, which covers both this door walk and following the leader through a door.
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
     * walking into), not against "some room somewhere". The main map is admitted freely even
     * though WZ seats guards there too (922010500 holds two 9300013, on the ground floor at
     * y≈54-58; the seven-guard room is the bottom sub-room 922010506): following into the
     * main map is ordinary party movement, and the combat sweep already stands the bot's
     * swings down whenever a guard is in its seek box (LudiPQBot#fightsOnSweep), while
     * the NPC post sits ~273px above the guards' ledge - past a touch. The sub-rooms are the
     * rooms a box fight happens IN, which is where the posting table binds.
     */
    public static boolean stage5AdmitsTo(Character bot, int mapId) {
        if (LudiPqData.roomStage(mapId) >= 0) {
            return stage5RoomAdmits(bot, mapId); // a sub-room: its own posting rule
        }
        return true; // the main map: no guard geometry to survive
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
     * @return how many crates the plan wants, or -1 when the quest's plan is unusable
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
        return wanted.size();
    }

    // =========================================================================
    // Stage 9 - the boss
    // =========================================================================

    /**
     * Fight the boss stage.
     *
     * <p>The stage ends when the leader brings an Alishar trophy to the Violet Balloon
     * ({@code 2040044.js}), whose turn-in calls {@code eim.clearPQ()}. That is synchronous:
     * the event stops its timer, marks itself cleared and warps the whole registered team to
     * the BONUS map right there, so a bot in this room is normally already in the reward room
     * by the next tick. The bot therefore only has to add damage; the trophy delivery belongs
     * to the leader.
     *
     * <p>The cleared check is the once-per-tick guard for the window between the flag being
     * set and the warp landing: nothing in this room has to die any more, so the bot must not
     * start another chase on the controller's last spawns.
     */
    public static void fightBoss(Character bot) {
        if (stageCleared(bot, 9)) {
            return; // nothing left to kill here - clearPQ has already warped the team on
        }
        PqActions.seekAndAttack(bot);
    }

    // =========================================================================
    // The post-boss stages
    // =========================================================================

    /**
     * Work the bonus stage: break the reward boxes and scoop what they spray.
     *
     * <p>{@code 922011000} is seeded with 34 reward reactors ({@code 2202004}, script
     * {@code ludiquest4} -> {@code rm.sprayItems(true, 1, 30, 60, 15)}), each of which sprays
     * meso and one item. A player's own share is whatever they pick up before the one-minute
     * timer hands the team to the exit stage, so the bot does the same thing a player does:
     * hit every box it can reach and pick the spray up. The boxes are not stage-gated - there
     * is no turn-in here - so the work is purely "more boxes, then leave".
     *
     * <p>Leaving is the Pink Balloon ({@code 2040045.js}, {@code cm.warp(922011100, "st00")}):
     * the bot takes that warp as soon as the boxes are spent, so the run reaches the reward
     * stage instead of standing in an empty room until the event's one-minute timer fires.
     * Only the leader's {@code 9stageclear} turn-in puts the party here, so the room is never
     * "not ready" - it is only ever "not finished yet".
     */
    public static void workBonusStage(Character bot) {
        if (LudiPqData.BONUS_MAP != bot.getMapId()) {
            return;
        }
        // The bonus stage is a reward room: there is no stage turn-in and nothing to preserve.
        // Any pass still pocketed from stage 9 is dead weight the run never asks for again - the
        // reward boxes are the only work here.
        int box = nearestBoxOid(bot, LudiPqData.BONUS_BOX);
        if (box >= 0) {
            hitReactorRotate(bot, box, LudiPqData.BONUS_BOX);
        }
        // The reward spray is the whole point of the room: the boxes drop RANDOM items, so
        // there is no id to filter on - scoop everything this bot is entitled to, at a reach
        // that covers a box's spray without dragging the bot across the whole room.
        PqActions.lootAll(bot, bot.getPosition(), BONUS_LOOT_RADIUS_PX);
        if (box < 0 && !standsDownForLiveMove(bot)) {
            // Every box is broken and no pickup walk is in flight: talk to the Pink Balloon,
            // who warps the body on to the exit stage. The event's own one-minute timer is the
            // backstop, but a player does not stand in an empty reward room for a minute - and
            // the exit stage is where the reward is actually collected, so waiting here delays
            // the whole run. A live move is a drop in flight and must not be cut (the same
            // contract the room exits follow); the next tick leaves once it has landed.
            goToExitStage(bot);
        }
    }

    /** Wide enough for a reward box's spray (it scatters across the ledge) and no wider. */
    private static final double BONUS_LOOT_RADIUS_PX = 420;

    /** How close a body must stand to an NPC before its dialogue can be opened, squared. */
    private static final double NPC_TALK_REACH_SQ = 250.0 * 250.0;

    /**
     * Walk to the bonus stage's Pink Balloon and take his warp to the exit stage.
     *
     * <p>{@code 2040045.js}: {@code start()} only resets {@code status}; the FIRST action shows
     * "Would you like to leave the bonus stage?" and the SECOND one ({@code status == 1}) warps
     * to {@code 922011100}. Both must be driven - one action only renders the prompt, and the
     * bot would then stand in front of a dialogue nobody answers.
     */
    private static void goToExitStage(Character bot) {
        org.gms.server.life.NPC balloon = bot.getMap().getNPCById(LudiPqData.BONUS_NPC);
        if (balloon == null || balloon.getPosition() == null) {
            return; // no balloon in this room: the event timer is the only way on
        }
        if (bot.getPosition().distanceSq(balloon.getPosition()) > NPC_TALK_REACH_SQ) {
            PqActions.walkTo(bot, balloon.getPosition());
            return;
        }
        if (!PqActions.talkingTo(bot, LudiPqData.BONUS_NPC)) {
            PqActions.talkTo(bot, LudiPqData.BONUS_NPC, 0, 0);
        }
    }

    /**
     * Work the exit stage: collect the reward from Arturo, then let the run end.
     *
     * <p>{@code 922011100} holds only Arturo ({@code 2040035.js}) and a portal-less flat ledge.
     * His script hands the body its event reward and warps it to the quest's recruit map
     * ({@code 221024500}, this bot's own lobby) in one conversation. That warp is the run's
     * real end - not the boss room, and not the bonus map.
     *
     * <p>The bot talks to him itself (the reward is per body, so it cannot ride the leader's
     * conversation) and the {@link PartyQuestBot#returnToLobby} path takes over on the next
     * tick, when the warp has already put the bot in the lobby.
     */
    public static void workExitStage(Character bot) {
        if (LudiPqData.EXIT_MAP != bot.getMapId()) {
            return;
        }
        org.gms.server.life.NPC arturo = bot.getMap().getNPCById(LudiPqData.EXIT_NPC);
        if (arturo == null || arturo.getPosition() == null) {
            return;
        }
        // Walk onto him first: a client cannot open a dialogue across the room, and the reward
        // warp is the only thing that ends the run.
        if (bot.getPosition().distanceSq(arturo.getPosition()) > NPC_TALK_REACH_SQ) {
            PqActions.walkTo(bot, arturo.getPosition());
            return;
        }
        if (!PqActions.talkingTo(bot, LudiPqData.EXIT_NPC)) {
            // Arturo's start() already invokes action(1,...) once (status -1 -> 0), which sends
            // the "here take this prize" line. One further action (status 0 -> 1) runs the
            // reward + warp branch. Two actions would over-run it.
            PqActions.talkTo(bot, LudiPqData.EXIT_NPC, 0);
        }
    }
}
