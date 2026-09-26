package soloMapling.ArtificialPlayer.BotTypes.Ludi;

import org.gms.client.Character;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAuraState;
import org.gms.constants.skills.Rogue;
import soloMapling.ArtificialPlayer.PartyQuest.PqActions;
import soloMapling.Environment.BotMessages;
import soloMapling.ArtificialPlayer.BotClientBinding;
import org.gms.client.inventory.manipulator.InventoryManipulator;
import org.gms.constants.inventory.ItemConstants;

import java.awt.Point;
import java.util.List;

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
        int wanted = LudiPqData.passesWanted(stage);
        if (wanted > 0 && PqActions.countItem(bot, LudiPqData.PASS) >= wanted) {
            return; // this bot is carrying its share; more hands are not needed
        }
        PqActions.seekAndAttack(bot);
        PqActions.loot(bot, bot.getPosition(), 2_000, new int[]{LudiPqData.PASS});
        // Hand over only what this bot really carried, only when the leader is in the room
        // to receive it (the hand-off drop is addressed to him; with him elsewhere it would
        // sit owned and unlootable on the floor).
        // The leader's piles despawn if he does not sweep them in time; recover ours and
        // re-drop them next to him on a later tick.
        PqActions.recoverUngatheredHandoffs(bot, LudiPqData.PASS);
        Character leader = PqActions.partyLeader(bot);
        if (leader != null && leader != bot && leader.getMapId() == bot.getMapId()
                && PqActions.handItemsToLeader(bot, LudiPqData.PASS) > 0) {
            PqActions.say(bot, BotMessages.get("pq.passes_dropped"));
        }
    }

    // =========================================================================
    // Stage 2 - the box tower

    /**
     * Break every pass box on the stage-2 tower. The room's eleven boxes (2202003) are the
     * only pass source - there are no mobs to hunt here - so the stage is a climb past each
     * box, breaking it on the way. The bonus box (2200002) is free mesos on the way out.
     */
    public static void breakTowerBoxes(Character bot) {
        // One box per macro tick: the bot walks to the nearest box still standing and breaks
        // it. Eleven boxes is a handful of ticks, which keeps the tower climb visible.
        int box = nearestBoxOid(bot, LudiPqData.BOX_STAGE2);
        if (box >= 0) {
            hitReactorAt(bot, box);
        } else {
            // Every box is gone but the stage is not cleared yet (the leader still has to
            // turn the passes in): gather by the stage NPC instead of idling at the spawn.
            PqActions.waitNearStageNpc(bot);
        }
        int bonus = PqActions.findReactorOid(bot, LudiPqData.BOX_STAGE2_BONUS);
        if (bonus >= 0) {
            hitReactorAt(bot, bonus);
        }
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
            hitReactorAt(bot, crate);
        }
        PqActions.seekAndAttack(bot);
        PqActions.loot(bot, bot.getPosition(), 2_000, new int[]{LudiPqData.PASS});
        // No crates left and nothing alive to fight: the stage is waiting on the leader's
        // turn-in, so gather by the stage NPC instead of idling at the spawn.
        if (crate < 0
                && bot.getMap().getAllMonsters().stream().noneMatch(m -> m.isAlive())) {
            PqActions.waitNearStageNpc(bot);
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
        // Hand the passes on at the door mouth (the bot walks to the room's exit portal,
        // drops everything it carried, and leaves the pile for the leader).
        Character leader = PqActions.partyLeader(bot);
        if (leader != null && leader != bot) {
            handAllAtDoor(bot, leader);
        }
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
            hitReactorAt(bot, box);
        }
        PqActions.loot(bot, bot.getPosition(), 2_000, new int[]{LudiPqData.PASS});
        Character leader = PqActions.partyLeader(bot);
        if (leader != null && leader != bot) {
            handAllAtDoor(bot, leader);
        }
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

    /** How many stage-5 guards (9300013) are alive within the seek box. */
    public static int nearbyGuardCount(Character bot) {
        return (int) bot.getMap().getAllMonsters().stream()
                .filter(m -> m.isAlive() && m.getId() == LudiPqData.GUARD_MOB)
                .filter(m -> Math.abs(m.getPosition().x - bot.getPosition().x) <= 900
                        && Math.abs(m.getPosition().y - bot.getPosition().y) <= 3_200)
                .count();
    }

    /** Drop the bot's whole pass stock at the room's exit portal mouth, for the leader. */
    private static void handAllAtDoor(Character bot, Character leader) {
        int carried = PqActions.countItem(bot, LudiPqData.PASS);
        if (carried <= 0) {
            return;
        }
        org.gms.server.maps.Portal out = exitPortalOf(bot);
        if (out == null) {
            return;
        }
        PqActions.walkTo(bot, out.getPosition());
        if (PqActions.countItem(bot, LudiPqData.PASS) <= 0) {
            return;
        }
        // A direct transfer: real inventory removal + an addressed pile at the leader's feet.
        BotClientBinding.runWithBoundPlayer(bot, () ->
                InventoryManipulator.removeById(bot.getClient(),
                        ItemConstants.getInventoryType(LudiPqData.PASS),
                        LudiPqData.PASS, carried, true, false));
        PqActions.giveItemToQuiet(bot, leader, LudiPqData.PASS, carried);
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
        int best = -1;
        double bestSq = Double.MAX_VALUE;
        for (int oid : PqActions.findAllReactorOids(bot, dataId)) {
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
     * Strike a box. The walk towards it is fire-and-forget (the movement engine keeps walking
     * after this tick), and the strike lands immediately - the host's reactor hit has no reach
     * check, so blocking the macro tick on the walk only adds dead seconds between boxes.
     */
    private static void hitReactorAt(Character bot, int oid) {
        var reactor = bot.getMap().getReactorByOid(oid);
        if (reactor == null || reactor.getPosition() == null) {
            return;
        }
        PqActions.walkUnderNonBlocking(bot, reactor.getPosition());
        PqActions.hitReactor(bot, oid);
    }

    // =========================================================================
    // Stage 6 - the climb
    // =========================================================================

    /**
     * Work up the tower by trying its portals.
     *
     * <p>The portal row is laid out bottom to top, and stepping into one either lifts the
     * climber or drops him back where he started - the map data gives every portal the same
     * target, so there is nothing to read and no way to know in advance. The bot walks the
     * row and steps into each in turn, which is what a party does, and the map change that
     * follows tells it whether that worked.
     *
     * <p>Progress is judged by height: the tower is one map, so arriving higher up than
     * before is the only observable that distinguishes a working portal from a dead one.
     */
    public static boolean climbTower(Character bot, int previousY) {
        int hereY = bot.getPosition().y;
        for (int portalId = LudiPqData.CLIMB_PORTAL_FIRST;
             portalId <= LudiPqData.CLIMB_PORTAL_LAST; portalId++) {
            PqActions.takePortal(bot, portalId);
            PqActions.holdArea(bot, bot.getPosition(), 600);
            // A working portal leaves the bot higher than it was (the tower's y decreases as
            // it goes up); a dead one puts it back at the bottom.
            if (bot.getPosition().y < hereY) {
                return true;
            }
        }
        // Nothing lifted us this pass. The stage flag is the only other thing that can say
        // the climb is over - the party may have finished it while this bot was retrying.
        return stageCleared(bot, 6) || previousY > hereY;
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
