package soloMapling.ArtificialPlayer.BotTypes.Ludi;

import org.gms.client.Character;
import org.gms.server.life.Monster;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAuraState;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import org.gms.constants.skills.Brawler;
import org.gms.constants.skills.Rogue;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotBuffConfig;
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
     * The one whole-room sweep a bot makes when its room's work is done, before delivering:
     * the live radius is deliberately small (bots keep to their own kills' drops), so a drop
     * that landed just outside everyone's reach would orphan the pass and stall the stage -
     * the quiet-room sweep is what guarantees completeness.
     */
    private static final int CLEANUP_RADIUS_PX = 2_000;

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
        // Room quiet: the work is done. Hold the stage-NPC post and hand the stock over the
        // moment the leader comes for the turn-in. The quiet-room sweep is the one wide one:
        // the live radius is a kill's neighbourhood, so a pass nobody was beside when it
        // settled would orphan without this.
        PqActions.loot(bot, bot.getPosition(), CLEANUP_RADIUS_PX, new int[]{LudiPqData.PASS});
        if (PqActions.handItemsToLeaderAfterStage(bot, LudiPqData.PASS) > 0) {
            PqActions.say(bot, BotMessages.get("pq.passes_dropped"));
        }
        if (PqActions.countItem(bot, LudiPqData.PASS) <= 0) {
            // Delivered: spread into the NPC ring with the rest of the ready party.
            PqActions.spreadNearStageNpc(bot);
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
        if (PqActions.handItemsToLeaderAfterStage(bot, LudiPqData.PASS) > 0) {
            PqActions.say(bot, BotMessages.get("pq.passes_dropped"));
        }
        if (PqActions.countItem(bot, LudiPqData.PASS) <= 0) {
            // Delivered: park by the stage NPC with the rest of the ready party.
            PqActions.spreadNearStageNpc(bot);
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
        if (bot.getPosition().distanceSq(exit.getPosition()) > EXIT_WARP_RANGE_SQ) {
            PqActions.walkTo(bot, exit.getPosition());
            return; // still climbing; the next tick takes over from wherever we are
        }
        PqActions.enterPortal(bot, exit);
    }

    /** How close the bot must stand to the trap room's exit before it takes it. */
    private static final double EXIT_WARP_RANGE_SQ = 250.0 * 250.0;

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
            if (PqActions.handItemsToLeaderAfterStage(bot, LudiPqData.PASS) > 0) {
                PqActions.say(bot, BotMessages.get("pq.passes_dropped"));
            }
            if (PqActions.countItem(bot, LudiPqData.PASS) <= 0) {
                PqActions.spreadNearStageNpc(bot);
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
     * The door rooms this bot has personally cleared, per instance: the tower's room ids
     * repeat across concurrent runs, so a fresh run's rooms must not read as pre-cleared.
     * A cleared room is never re-claimed; released with the bot so a fresh run starts clean.
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
        if (bot.getPosition() != null
                && bot.getPosition().distanceSq(door.getPosition()) > EXIT_WARP_RANGE_SQ) {
            PqActions.walkTo(bot, door.getPosition());
            return false;
        }
        PqActions.enterPortal(bot, door);
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
        if (bot.getPosition().distanceSq(exit.getPosition()) > EXIT_WARP_RANGE_SQ) {
            PqActions.walkTo(bot, exit.getPosition());
            return; // still walking; the next tick takes over from wherever we are
        }
        PqActions.enterPortal(bot, exit);
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
            deliverAndPark(bot);
            return;
        }
        enterDoorRoom(bot, room, LudiPqData.STAGE4_ROOM_FIRST);
    }

    /**
     * Hold the stage-NPC post and hand the stock over the moment the leader walks into
     * hand-off range - the same post-work beat the collection stages run. One wide sweep
     * first: a pass that settled just outside every bot's kill-radius would orphan here.
     */
    private static void deliverAndPark(Character bot) {
        PqActions.recoverUngatheredHandoffs(bot, LudiPqData.PASS);
        PqActions.loot(bot, bot.getPosition(), CLEANUP_RADIUS_PX, new int[]{LudiPqData.PASS});
        if (PqActions.handItemsToLeaderAfterStage(bot, LudiPqData.PASS) > 0) {
            PqActions.say(bot, BotMessages.get("pq.passes_dropped"));
        }
        PqActions.waitNearStageNpc(bot);
    }

    /**
     * Work the stage-5 door rooms in stealth: each room's four pass boxes are guarded by
     * invincible Block Golems (PAD 999 - a touch is a death sentence), so the bot shows
     * 隐身术 for the whole visit. The hide makes it untouchable (isMonsterImmune) and costs
     * nothing: it is cancelled by the reactor strike itself, and re-shown next tick.
     *
     * <p>Only a bot whose kit actually carries a hide may enter a guard room: 隐身术 is
     * rogue-lineage (Assassin/Bandit/夜行者), 橡木伪装 is the Brawler's barrel disguise -
     * both are the two auras that make a bot monster-immune, and anything else walking in
     * is a one-touch death. Non-hide bots stay on the main map and wait by the stage NPC.
     */
    public static void sneakDoorRooms(Character bot) {
        int mapId = bot.getMapId();
        if (mapId < LudiPqData.STAGE5_ROOM_FIRST || mapId > LudiPqData.STAGE5_ROOM_LAST) {
            workStage5FromMainRoom(bot);
            return;
        }
        // Re-show every tick while inside: the strike cancels the hide (an attack action),
        // so the next tick's show re-stealths - one box per tick, the honest sneak pace.
        if (!BotAuraState.isMonsterImmune(bot)) {
            soloMapling.ArtificialPlayer.BotAttackSystem.BotBuffEffects.showBuff(bot, hideSkillFor(bot));
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
        // stage in there, not at this room's mouth. Leaving while a box stands (or with
        // nothing pocketed, or while the leader is still in the room - the follow beat would
        // walk the bot back in behind him) keeps the sneak inside its room.
        if (box >= 0 || PqActions.countItem(bot, LudiPqData.PASS) <= 0
                || leaderInRoom(bot)) {
            return;
        }
        markRoomCleared(bot, mapId);
        exitDoorRoom(bot);
    }

    /**
     * The main-room half of the stage-5 loop. A bot whose kit carries a hide (隐身术 rogue
     * lineage or 橡木伪装 Brawler) claims a guard room the same way stage 4's are claimed
     * and sneaks in alone; everyone else - and a bot with nothing left to do - parks by
     * the stage NPC and delivers its stock there: a non-hide body in a PAD-999 room dies
     * to the first touch, and stage 5's turn-in runs from this post.
     */
    private static void workStage5FromMainRoom(Character bot) {
        if (hideSkillFor(bot) == 0) {
            deliverAndPark(bot);
            return;
        }
        int room = claimDoorRoom(bot, LudiPqData.STAGE5_ROOM_FIRST, LudiPqData.STAGE5_ROOM_LAST);
        if (room < 0) {
            deliverAndPark(bot);
            return;
        }
        enterDoorRoom(bot, room, LudiPqData.STAGE5_ROOM_FIRST);
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
            PqActions.hitReactor(bot, oid);
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
            PqActions.Approach outcome =
                    PqActions.descendToFloorAerialTarget(bot, reactor.getPosition());
            if (outcome == PqActions.Approach.TRAVELLING) {
                // Hand the approach to the 250ms combat sweep: it strikes the box the beat
                // the descent lands instead of the box waiting out the next macro tick.
                PqActions.armReactorBeat(bot, oid);
                return true;
            }
            if (outcome == PqActions.Approach.IN_POSITION) {
                PqActions.armReactorBeat(bot, -1); // arrived: the visit's own combo takes over
                breakBoxInPlace(bot, oid);
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
            PqActions.Approach outcome = PqActions.approachUnder(bot, reactor.getPosition());
            if (outcome == PqActions.Approach.IN_POSITION) {
                PqActions.armReactorBeat(bot, -1); // arrived: the visit's own combo takes over
                breakBoxInPlace(bot, oid);
                return true;
            }
            if (outcome == PqActions.Approach.TRAVELLING) {
                if (onBeat) {
                    PqActions.armReactorBeat(bot, oid); // the sweep strikes it on arrival
                }
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
            // No rung above us: either the climb is finished (the party may have completed it
            // while this bot was retrying) or this bot is somehow above the top rung.
            return stageCleared(bot, 6) || previousY > hereY;
        }
        PqActions.enterPortal(bot, rung);
        PqActions.holdArea(bot, bot.getPosition(), 600);
        // A working rung leaves the bot higher than it was (the tower's y decreases as it goes
        // up); a decoy puts it back at the bottom.
        return bot.getPosition().y < hereY;
    }

    /**
     * The tower portal that lands on a rung above {@code fromY}, or null when there is none.
     *
     * <p>Only {@code h0NN} portals count, and only those whose {@link
     * org.gms.server.maps.Portal#getTarget()} names another {@code h0NN} standing higher up -
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
                    || target == null || !target.startsWith(LudiPqData.CLIMB_PORTAL_PREFIX)
                    || target.equals(name)) {
                continue; // a decoy (lands on st00 / on itself), not a rung
            }
            org.gms.server.maps.Portal landing = map.getPortal(target);
            if (landing == null) {
                continue;
            }
            int landingY = landing.getPosition().y;
            // Higher up means a smaller y. Take the smallest step that still gains height.
            if (landingY < fromY && (best == null || landingY > bestY)) {
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
     * @return how many crates the plan wants, or -1 when the quest has not picked yet
     */
    public static int takeCrate(Character bot, int bodyIndex) {
        String combo = PqActions.readEimString(bot, LudiPqData.COMBO_PROPERTY);
        if (combo == null) {
            return -1;
        }
        List<Point> wanted = LudiPqData.cratesIn(combo);
        if (wanted.size() != LudiPqData.CRATES_TO_STAND_ON) {
            // The quest builds its combination as exactly five of nine; anything else means
            // the property was written by something other than the stage script.
            return -1;
        }
        Point mine = LudiPqData.myCrate(combo, bodyIndex);
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
     * <p>The stage ends when one Alishar trophy is in hand, and that drop belongs to whoever
     * killed him, so the bot only has to add damage.
     */
    public static void fightBoss(Character bot) {
        PqActions.seekAndAttack(bot);
    }
}
