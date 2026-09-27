package soloMapling.ArtificialPlayer.BotTypes.Ludi;

import org.gms.client.Character;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAuraState;
import org.gms.constants.skills.Rogue;
import soloMapling.ArtificialPlayer.PartyQuest.PqActions;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.Environment.BotMessages;
import soloMapling.ArtificialPlayer.BotClientBinding;
import org.gms.client.inventory.manipulator.InventoryManipulator;
import org.gms.constants.inventory.ItemConstants;

import java.awt.Point;
import java.util.List;
import java.util.Map;

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

    /**
     * botId -> the stage-2 box this bot is working (x = reactor oid, y = box y). Claims are
     * best-effort anti-queue bookkeeping, not a lock: two bots may still briefly walk the
     * same box, and the first strike wins.
     */
    private static final Map<Integer, Point> TOWER_BOX_CLAIMS = new java.util.concurrent.ConcurrentHashMap<>();

    /** A stage is done when the quest says so; these are its own per-stage flags. */
    public static boolean stageCleared(Character bot, int stage) {
        return PqActions.readEimString(bot, stage + "stageclear") != null;
    }

    // =========================================================================
    // The collection stages
    // =========================================================================

    /**
     * Fight what is in the room and gather this stage's passes.
     *
     * <p>The bot attacks, loots its share, and then hands its whole stock of passes to the
     * party leader by dropping them at his feet: the stage NPC checks the inventory of
     * whoever talks to him, and that is the leader - a bot that keeps its share starves the
     * turn-in and the party stalls on the stage.
     */
    public static void gatherPasses(Character bot, int stage) {
        // Deliver FIRST: passes parked in a bot's pockets are passes the leader cannot turn
        // in, and the old "carry your share" early-return left exactly that state standing
        // still until the turn-in. Recover our stale piles, then hand off up close - walking
        // to the leader when he is not (the move is fire-and-forget, no flag required).
        PqActions.recoverUngatheredHandoffs(bot, LudiPqData.PASS);
        if (PqActions.handItemsToLeader(bot, LudiPqData.PASS) > 0) {
            PqActions.say(bot, BotMessages.get("pq.passes_dropped"));
        }
        if (PqActions.countItem(bot, LudiPqData.PASS) > 0) {
            return; // still carrying: the walk to the leader is in flight, deliver before all else
        }

        if (bot.getMap().getAllMonsters().stream().noneMatch(m -> m.isAlive())) {
            // Room quiet, pockets empty: the stage is waiting on the leader's turn-in, so
            // spread around the stage NPC instead of idling at the last fight spot - or
            // piling onto the NPC pixel with the rest of the party.
            PqActions.spreadNearStageNpc(bot);
            return;
        }

        PqActions.seekAndAttack(bot);
        PqActions.loot(bot, bot.getPosition(), 2_000, new int[]{LudiPqData.PASS});
    }

    // =========================================================================
    // Stage 2 - the box tower

    /**
     * Break every pass box on the stage-2 tower. The room's eleven boxes (2202003) are the
     * only pass source - there are no mobs to hunt here - so the stage is a climb past each
     * box, breaking it on the way. The bonus box (2200002) is free mesos on the way out.
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
        // One box per macro tick: the bot walks to the nearest box still standing and breaks
        // it. Eleven boxes is a handful of ticks, which keeps the tower climb visible.
        int box = nearestOwnedBoxOid(bot, LudiPqData.BOX_STAGE2);
        if (box >= 0 && hitReactorDescendRotate(bot, box, LudiPqData.BOX_STAGE2)) {
            return;
        }
        // Boxes all gone (the leader still has to turn the passes in), or a box stood but
        // every approach failed this tick: either way park by the stage NPC instead of
        // idling at the spawn - or piling onto the NPC pixel with the rest of the party.
        PqActions.spreadNearStageNpc(bot);
        int bonus = PqActions.findReactorOid(bot, LudiPqData.BOX_STAGE2_BONUS);
        if (bonus >= 0) {
            hitReactorAt(bot, bonus);
        }
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

        // The nearest box is contested when another live bot on this map already claims it.
        // Claims are by reactor oid - the map's own identity for a box - so two DIFFERENT
        // boxes on one floor never contest each other (the old y-only tolerance read them as
        // one and sent both bots wandering), while two bots on the SAME box always do.
        for (Map.Entry<Integer, Point> claim : TOWER_BOX_CLAIMS.entrySet()) {
            if (claim.getKey() == myId || claim.getValue().x != nearest) {
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
                for (Map.Entry<Integer, Point> other : TOWER_BOX_CLAIMS.entrySet()) {
                    if (other.getKey() != myId && other.getValue().x == oid) {
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
            TOWER_BOX_CLAIMS.put(myId, new Point(best,
                    bestReactor != null && bestReactor.getPosition() != null
                            ? bestReactor.getPosition().y : 0));
            return best;
        }

        var reactor = bot.getMap().getReactorByOid(nearest);
        TOWER_BOX_CLAIMS.put(myId, new Point(nearest,
                reactor != null && reactor.getPosition() != null ? reactor.getPosition().y : 0));
        return nearest;
    }

    /** Drop this bot's stage-2 tower box claim, so a later bot can take its share. */
    public static void releaseTowerBox(int botId) {
        TOWER_BOX_CLAIMS.remove(botId);
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
        PqActions.loot(bot, bot.getPosition(), 2_000, new int[]{LudiPqData.PASS});
        // No crates left and nothing alive to fight: the stage is waiting on the leader's
        // turn-in, so spread around the stage NPC instead of idling at the spawn.
        if (crate < 0
                && bot.getMap().getAllMonsters().stream().noneMatch(m -> m.isAlive())) {
            PqActions.spreadNearStageNpc(bot);
        }
    }

    // =========================================================================
    // Stage 4 - the door rooms

    /**
     * Work the stage-4 door rooms: the main room has no mobs, the five rooms behind the
     * doors each hold box mobs (9300008/9300014) that drop the passes. A bot inside a room
     * kills its occupants and hands the passes over at the door's mouth, so the leader can
     * sweep them without entering; a bot in the main room stands by the doors.
     */
    public static void workDoorRooms(Character bot, int roomFirst, int roomLast, int doorsToOpen) {
        int mapId = bot.getMapId();
        if (mapId < roomFirst || mapId > roomLast) {
            return; // main room: the leader picks the doors and does the talking
        }
        // Fight the room's box mobs and sweep the passes they drop.
        PqActions.seekAndAttack(bot);
        PqActions.loot(bot, bot.getPosition(), 2_000, new int[]{LudiPqData.PASS});
        // Hand over up close: a pile left at the door mouth despawns if the leader never
        // walks there. recoverUngatheredHandoffs + handItemsToLeader keep the stock
        // circulating until it is actually in his pockets.
        PqActions.recoverUngatheredHandoffs(bot, LudiPqData.PASS);
        PqActions.handItemsToLeader(bot, LudiPqData.PASS);
    }

    /**
     * Work the stage-5 door rooms in stealth: each room's four pass boxes are guarded by
     * invincible Block Golems (PAD 999 - a touch is a death sentence), so the bot shows
     * 隐身术 for the whole visit. The hide makes it untouchable (isMonsterImmune) and costs
     * nothing: it is cancelled by the reactor strike itself, and re-shown next tick.
     */
    public static void sneakDoorRooms(Character bot) {
        int mapId = bot.getMapId();
        if (mapId < LudiPqData.STAGE5_ROOM_FIRST || mapId > LudiPqData.STAGE5_ROOM_LAST) {
            return; // main room: the guards there are the leader's problem; stay off them
        }
        if (!BotAuraState.isMonsterImmune(bot)) {
            // Any lineage bot shows the rogue hide: it is a display-only aura on the bot
            // (no job check in showBuff), and this is the one place the quest needs it.
            soloMapling.ArtificialPlayer.BotAttackSystem.BotBuffEffects.showBuff(bot, Rogue.DARK_SIGHT);
        }
        // Boxes while hidden: the strike cancels the hide (an attack action), so re-show
        // happens next tick - one box per tick, which is the honest pace for a sneak.
        int box = nearestBoxOid(bot, LudiPqData.BOX_STAGE5);
        if (box >= 0) {
            hitReactorRotate(bot, box, LudiPqData.BOX_STAGE5);
        }
        PqActions.loot(bot, bot.getPosition(), 2_000, new int[]{LudiPqData.PASS});
        PqActions.recoverUngatheredHandoffs(bot, LudiPqData.PASS);
        PqActions.handItemsToLeader(bot, LudiPqData.PASS);
    }

    /**
     * Show 隐身术 and keep it up: the movement tick only retires the hide on an attack or a
     * mount, so simply re-showing each tick while guards are near reads as one long stealth.
     */
    public static void stayHidden(Character bot) {
        if (!BotAuraState.isMonsterImmune(bot)) {
            soloMapling.ArtificialPlayer.BotAttackSystem.BotBuffEffects.showBuff(bot, Rogue.DARK_SIGHT);
        }
    }

    /**
     * Release everything this bot holds inside the Ludi rooms: its stage-2 tower box claim
     * and its stage-NPC wait-spot claim. Called when the bot leaves the map or the run ends,
     * so a replacement bot starts with a clean share.
     */
    public static void releaseRoomState(int botId) {
        releaseTowerBox(botId);
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

    /** The room's exit portal (out00), or null. */
    private static org.gms.server.maps.Portal exitPortalOf(Character bot) {
        var map = bot.getMap();
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
     * Approach a box and strike it once the bot is standing beside it. The approach is
     * fire-and-forget across ticks; the strike fires only from the box's own platform, so
     * the hit never reads as coming through a wall.
     */
    private static void hitReactorAt(Character bot, int oid) {
        var reactor = bot.getMap().getReactorByOid(oid);
        if (reactor == null || reactor.getPosition() == null) {
            return;
        }
        if (PqActions.approachUnder(bot, reactor.getPosition()) == PqActions.Approach.IN_POSITION) {
            PqActions.hitReactor(bot, oid);
        }
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
                return true;
            }
            if (outcome == PqActions.Approach.IN_POSITION) {
                PqActions.hitReactor(bot, oid);
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
     */
    private static boolean hitReactorRotate(Character bot, int firstOid, int dataId) {
        int oid = firstOid;
        for (int attempt = 0; attempt < 3 && oid >= 0; attempt++) {
            var reactor = bot.getMap().getReactorByOid(oid);
            if (reactor == null || reactor.getPosition() == null) {
                return false;
            }
            PqActions.Approach outcome = PqActions.approachUnder(bot, reactor.getPosition());
            if (outcome == PqActions.Approach.IN_POSITION) {
                PqActions.hitReactor(bot, oid);
                return true;
            }
            if (outcome == PqActions.Approach.TRAVELLING) {
                return true;
            }
            oid = nearestBoxOid(bot, dataId, oid); // STUCK: rotate to another box
        }
        return false; // every candidate failed this tick; try again next tick
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
