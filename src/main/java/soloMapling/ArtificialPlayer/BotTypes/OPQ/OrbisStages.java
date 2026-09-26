package soloMapling.ArtificialPlayer.BotTypes.OPQ;

import org.gms.client.Character;
import soloMapling.ArtificialPlayer.PartyQuest.PqActions;

import java.awt.Point;
import java.util.List;

/**
 * What a bot does in each Orbis stage room, in the order the tower hands the rooms out.
 *
 * <p>The tower is the hub, so the shape is always the same: walk to the room's portal in the
 * tower, do the room's work, walk back out, and let Eak clear the stage. The work itself is
 * the only part that differs, and it is listed here stage by stage rather than scattered
 * through the state machine.
 *
 * <p>Stage order follows {@code statusStg0..8}. Only the stages a bot can meaningfully
 * contribute to are listed; the quest's own turn-in checks stay with the player, because
 * {@code cm.haveItem} reads the inventory of whoever is talking to the NPC and the statue
 * pieces belong to whoever gathered them.
 */
public final class OrbisStages {

    private OrbisStages() {
    }

    /**
     * A bot's standing order for a stage: which map it is, the tower portal that reaches it, and
     * where to stand in the tower to take that portal.
     */
    public record StageRoom(int mapId, int portalInTower, Point towerSpot) {
    }

    public static StageRoom roomFor(int stage) {
        return switch (stage) {
            case 1 -> new StageRoom(OrbisPqData.STAGE_WALKWAY, 4,
                    OrbisPqData.towerSpotFor(OrbisPqData.STAGE_WALKWAY));
            case 2 -> new StageRoom(OrbisPqData.STAGE_STORAGE, 12,
                    OrbisPqData.towerSpotFor(OrbisPqData.STAGE_STORAGE));
            case 3 -> new StageRoom(OrbisPqData.STAGE_MUSIC, 5,
                    OrbisPqData.towerSpotFor(OrbisPqData.STAGE_MUSIC));
            case 4 -> new StageRoom(OrbisPqData.STAGE_SEALED, 13,
                    OrbisPqData.towerSpotFor(OrbisPqData.STAGE_SEALED));
            case 5 -> new StageRoom(OrbisPqData.STAGE_LOUNGE, 15,
                    OrbisPqData.towerSpotFor(OrbisPqData.STAGE_LOUNGE));
            case 6 -> new StageRoom(OrbisPqData.STAGE_UP, 14,
                    OrbisPqData.towerSpotFor(OrbisPqData.STAGE_UP));
            default -> null;
        };
    }

    // =========================================================================
    // Which stage is in play
    // =========================================================================

    /**
     * The sentinels the stage picker returns for the three pieces of work that have no room of
     * their own: the six scar reactors left in the tower when the party arrives there, and (once
     * the scars are lit) Papa Pixie's room, which the spring's flag ends; then the final statue
     * base. They are deliberately not 1..6 so a caller can tell them from a room stage.
     */
    public static final int SCARS_STAGE = 7;
    public static final int PAPA_STAGE = 70;
    public static final int STATUE_STAGE = 8;

    /**
     * Which stage of the run after the clouds is still in play, from the instance flags alone.
     *
     * <p>The stages run in strict order. Each {@code statusStgN} starts at -1, moves to 0 as the
     * stage opens, and reaches its cleared value when Eak clears it. That cleared value is 1 for
     * every stage except stage 3, whose music box sets it to 0 and Eak then sets it to 2 - so a
     * plain "{@code == 1}" test would report stage 3 as never clearing.
     *
     * <p>Returned as a plain int, taking the flags as an array indexed 1..8, so the whole
     * decision is testable without a live map. -1 means the run is over.
     *
     * @param stg       {@code statusStg1..8} (-1 when the quest has not touched the flag)
     * @param scarsDone whether all six scar reactors in the tower are already lit
     */
    public static int middleStage(int[] stg, boolean scarsDone) {
        for (int stage = 1; stage <= 6; stage++) {
            if (!cleared(stage, stg[stage])) {
                return stage;
            }
        }
        if (stg[7] != 1) {
            // Stage 7 is two pieces of work under one flag: light the six scars in the tower,
            // then settle Papa Pixie's room, whose spring is what actually sets statusStg7.
            return scarsDone ? PAPA_STAGE : SCARS_STAGE;
        }
        if (stg[8] != 1) {
            return STATUE_STAGE;
        }
        return -1;
    }

    /** Whether a stage's flag has reached the value Eak leaves it at when the stage is cleared. */
    private static boolean cleared(int stage, int flag) {
        // Stage 3 is the one stage whose cleared value is not 1 (the music box sets 0, Eak sets 2).
        return stage == 3 ? flag >= 1 : flag == 1;
    }

    // =========================================================================
    // Stage 1 - walkway: kill 9300045..9300047 for 30 of 4001050
    // =========================================================================

    /**
     * Clear the walkway of its statue-piece carriers.
     *
     * <p>Kills are real ({@code BotAttackDriver}), so the drops are the map's own, and they
     * are party drops ({@code droptype=1} when the killer is in a party), which is why the
     * player can pick them up. The bot does not loot: {@code cm.haveItem(4001050, 30)} is
     * checked against whoever turns them in, and Eak is the one who does that.
     */
    public static void huntWalkway(Character bot) {
        huntMobs(bot, OrbisPqData.WALKWAY_MOB_FIRST, OrbisPqData.WALKWAY_MOB_LAST);
    }

    // =========================================================================
    // Stage 2 - storage: the room must be empty before Eak hands over the piece
    // =========================================================================

    /**
     * Empty the storage room.
     *
     * <p>Eak's condition is {@code countMonsters() == 0 && countItems() == 0} - not a kill
     * count - so anything left alive or lying on the floor blocks him. The leftover-items
     * half matters here: the room's own mob drops {@code 4001046}, and a stray drop keeps
     * the check false even after everything is dead.
     */
    public static void clearStorage(Character bot) {
        huntMobs(bot, OrbisPqData.STORAGE_MOB, OrbisPqData.STORAGE_MOB);
        sweepFloor(bot);
    }

    // =========================================================================
    // Stage 4 - sealed room: stand in the three areas
    // =========================================================================

    /**
     * Put the party on the three platforms the quest asked for.
     *
     * <p>The quest picks three numbers summing to 3 (published as {@code stage4_0..2}) and
     * compares them against the player count inside each rectangle, so the party has to
     * stand <em>exactly</em> so - someone in the wrong area makes the check fail rather than
     * just wasting a spot. A bot can read the numbers, which is the whole advantage here:
     * it moves itself to whichever area still needs a body instead of trial and error.
     *
     * <p>{@code index} is this bot's slot among the bots taking part, so several bots spread
     * out instead of all piling onto the same platform.
     */
    public static boolean standOnSealedPlatform(Character bot, int index) {
        int[] want = {
                PqActions.readEimInt(bot, "stage4_0", -1),
                PqActions.readEimInt(bot, "stage4_1", -1),
                PqActions.readEimInt(bot, "stage4_2", -1),
        };
        if (want[0] < 0 || want[1] < 0 || want[2] < 0) {
            return false;
        }
        // Walk the published layout into a list of area indices, one entry per body wanted.
        int[] assignment = new int[OrbisPqData.SEALED_PLAYERS];
        int at = 0;
        for (int area = 0; area < want.length; area++) {
            for (int i = 0; i < want[area] && at < assignment.length; i++) {
                assignment[at++] = area;
            }
        }
        if (at != assignment.length) {
            return false; // the three numbers should sum to 3; anything else is not ours to guess
        }
        if (index < 0 || index >= assignment.length) {
            return false;
        }
        Point spot = OrbisPqData.SEALED_AREAS[assignment[index]];
        PqActions.holdArea(bot, spot, 1_200);
        return true;
    }

    // =========================================================================
    // Stage 5 - lounge: kill 9300041..9300043 for 40 of 4001052, plus the room-604 reactors
    // =========================================================================

    /**
     * Gather the lounge's statue pieces.
     *
     * <p>Three of the four side rooms hold mobs that drop {@code 4001052}; the fourth holds
     * ten reactors ({@code 2002002}) whose scripts drop the same item, so a bot should work
     * whichever room it is standing in rather than assuming the mobs are the only source.
     */
    public static void gatherLounge(Character bot) {
        huntMobs(bot, OrbisPqData.LOUNGE_MOB_FIRST, OrbisPqData.LOUNGE_MOB_LAST);
        int reactor = PqActions.findReactorOid(bot, OrbisPqData.LOUNGE_REACTOR);
        if (reactor >= 0) {
            PqActions.hitReactor(bot, reactor);
        }
    }

    // =========================================================================
    // Stage 6 - levers: turn on the two the quest picked
    // =========================================================================

    /**
     * Pull the two levers the quest wants.
     *
     * <p>The quest publishes its answer as {@code stage6_c}, a five-character string such as
     * {@code "01010"}; each '1' names a lever to push. Reading it removes the search
     * entirely. Turned on means the reactor's state is above zero, and all five share a data
     * id, so they are found by position rather than by id.
     */
    public static boolean pullCorrectLevers(Character bot) {
        String wanted = PqActions.readEimString(bot, "stage6_c");
        if (wanted == null || wanted.length() != OrbisPqData.LEVER_COUNT) {
            return false;
        }
        for (int i = 0; i < wanted.length(); i++) {
            if (wanted.charAt(i) != '1') {
                continue;
            }
            Point lever = OrbisPqData.LEVER_SPOTS[i];
            PqActions.walkTo(bot, lever);
            int oid = nearestReactorTo(bot, OrbisPqData.LEVER_REACTOR, lever);
            if (oid >= 0) {
                PqActions.hitReactor(bot, oid);
            }
        }
        return true;
    }

    // =========================================================================
    // Stage 7 - break the scars (tower), then settle Papa Pixie's room
    // =========================================================================

    /**
     * Break all six scar reactors in the tower.
     *
     * <p>These sit in the tower itself rather than a room, and none of them has a script:
     * Eak's {@code isStatueComplete} only asks whether each reactor's state has moved off
     * zero, so hitting each once is the entire mechanic. Eak will not send the party on to
     * Papa Pixie until all six are done.
     */
    public static void breakScars(Character bot) {
        for (Point spot : OrbisPqData.SCAR_SPOTS) {
            PqActions.walkTo(bot, spot);
            int oid = nearestReactorInRange(bot, OrbisPqData.SCAR_FIRST, OrbisPqData.SCAR_LAST, spot);
            if (oid >= 0) {
                PqActions.hitReactor(bot, oid);
            }
        }
    }

    /**
     * Work Papa Pixie's room, which is a chain of item-triggered reactors rather than a puzzle.
     *
     * <p>Every reactor here is {@code type 100}: it fires only when the item it wants is DROPPED
     * inside its box (hitting one does nothing). Feeding a pot ({@code 2001000/2001001}) its medal
     * {@code 4001053} makes the room's mobs appear; those mobs drop the medal the pots take, and
     * the darker one drops {@code 4001074} that the traps ({@code 2001016}) take. Feeding a trap a
     * {@code 4001074} ends the room and brings Papa Pixie ({@code 9300039}) out; he drops the Root
     * of Life ({@code 4001054}), and the spring ({@code 2002003}) takes that and is what sets
     * {@code statusStg7}.
     *
     * <p>This is the honest limit of a non-leader bot here: the mobs drop each item on whoever
     * kills them, so the bot loots its own share and feeds the reactors exactly as a player does.
     * If another member carries the item, the helper's copy is what the flag needs; the loop just
     * keeps working the room until the flag moves.
     *
     * <p>Returns true once the stage flag has moved, so the caller can stop looping.
     */
    public static boolean settlePapaRoom(Character bot) {
        if (PqActions.readEimInt(bot, "statusStg7", -1) != -1) {
            // The spring has fired: the flag is set and the final piece has dropped. The bot does
            // NOT pick it up - exactly one drops, and the leader must hold it to open the room's
            // exit (party3_gardenin warps the team only for a leader carrying 4001055). Grabbing it
            // would strand the party here.
            return true;
        }

        // The finish: drop the Root of Life onto the spring, whose script sets the flag.
        if (PqActions.countItem(bot, OrbisPqData.PAPA_SEED) > 0) {
            dropOn(bot, OrbisPqData.PAPA_SPRING_REACTOR, OrbisPqData.PAPA_SPRING_SPOT,
                    OrbisPqData.PAPA_SEED);
            return PqActions.readEimInt(bot, "statusStg7", -1) != -1;
        }

        // With a trap item, end the room: feed a trap and Papa Pixie appears; his death is what
        // produces the Root of Life. Only a feed that actually happened ends the tick - if every
        // trap is already spent the bot must fall through to the fight rather than stall here,
        // because the throw is synthetic and does not consume the item it holds.
        if (PqActions.countItem(bot, OrbisPqData.PAPA_TRAP_ITEM) > 0
                && feedReactor(bot, OrbisPqData.PAPA_TRAP, OrbisPqData.PAPA_TRAP_ITEM)) {
            return false;
        }

        // With a medal, feed a pot, which is what makes the room summon its mobs and drop more.
        if (PqActions.countItem(bot, OrbisPqData.PAPA_MEDAL) > 0) {
            if (feedReactor(bot, OrbisPqData.PAPA_POT, OrbisPqData.PAPA_MEDAL)
                    || feedReactor(bot, OrbisPqData.PAPA_POT_ALT, OrbisPqData.PAPA_MEDAL)) {
                return false;
            }
        }

        // Otherwise fight what is here (Papa, or the mobs a pot summoned) and take their drops.
        // The loot is confined to this branch on purpose: a seed the bot has just dropped on the
        // spring must NOT be picked back up, because the spring reads its stack five seconds later
        // and a pickup in that window cancels the trigger.
        if (mobsPresent(bot, OrbisPqData.PAPA_MOB_LOW, OrbisPqData.PAPA_MOB_HIGH)) {
            huntMobs(bot, OrbisPqData.PAPA_MOB_LOW, OrbisPqData.PAPA_MOB_HIGH);
            PqActions.loot(bot, bot.getPosition(), 4_000,
                    new int[]{OrbisPqData.PAPA_MEDAL, OrbisPqData.PAPA_TRAP_ITEM, OrbisPqData.PAPA_SEED});
        }
        return false;
    }

    /** Drop {@code qty} of an item on the named reactor's trigger box, or return false. */
    private static boolean dropOn(Character bot, int reactorDataId, Point fallbackSpot, int itemId) {
        int oid = findReactorOid(bot, reactorDataId);
        Point at = oid >= 0 ? reactorPos(bot, oid) : fallbackSpot;
        if (at == null) {
            return false;
        }
        PqActions.walkTo(bot, at);
        PqActions.dropStack(bot, itemId, 1, at);
        return true;
    }

    /** Feed the first live reactor of a data id its item; false when none is standing. */
    private static boolean feedReactor(Character bot, int reactorDataId, int itemId) {
        int oid = findReactorOid(bot, reactorDataId);
        if (oid < 0) {
            return false;
        }
        Point at = reactorPos(bot, oid);
        PqActions.walkTo(bot, at);
        PqActions.dropStack(bot, itemId, 1, at);
        return true;
    }

    /**
     * The oid of the first reactor of a data id that is still armed, or -1 when none is.
     *
     * <p>"Armed" is the engine's own test for an item-triggered reactor: {@code getReactorType()
     * == 100}. Once one has fired it advances past its item state and reads a different type, so
     * it must be skipped - feeding or hitting a spent one does nothing, and a loop that kept
     * picking it would never move on.
     */
    private static int findReactorOid(Character bot, int dataId) {
        return bot.getMap().getAllReactors().stream()
                .filter(r -> r.getId() == dataId && r.isAlive() && r.getReactorType() == 100)
                .mapToInt(r -> r.getObjectId())
                .findFirst().orElse(-1);
    }

    /** A reactor's position, or null when it is gone. */
    private static Point reactorPos(Character bot, int oid) {
        var reactor = bot.getMap().getReactorByOid(oid);
        return reactor != null ? reactor.getPosition() : null;
    }

    // =========================================================================
    // Stage 8 - the last piece onto the statue base
    // =========================================================================

    /**
     * Put the final piece on the statue base.
     *
     * <p>The base ({@code 2006001}, "minerva") is item-triggered on one {@code 4001055}; dropping
     * it inside the base's box is what spawns Minerva, clears the quest and sets
     * {@code statusStg8}. Hitting the base would do nothing, so this walks to it and drops. The
     * landing point already accounts for the drop re-seating itself 85px down onto the floor,
     * which is inside the box - see {@link OrbisPqData#STATUE_BASE_SPOT}.
     */
    public static void placeFinalPiece(Character bot) {
        if (PqActions.countItem(bot, OrbisPqData.STATUE_PIECE_8) <= 0) {
            return;
        }
        int oid = findReactorOid(bot, OrbisPqData.STATUE_BASE_REACTOR);
        Point at = oid >= 0 ? reactorPos(bot, oid) : OrbisPqData.STATUE_BASE_SPOT;
        if (at == null) {
            return;
        }
        PqActions.walkTo(bot, at);
        PqActions.dropStack(bot, OrbisPqData.STATUE_PIECE_8, 1, at);
    }

    // =========================================================================
    // helpers
    // =========================================================================

    /**
     * Attack in place while any of the given mobs are alive nearby.
     *
     * <p>Deliberately does not pick up: the statue pieces are the party's turn-in currency,
     * and Eak checks the inventory of the player who talks to him.
     */
    private static void huntMobs(Character bot, int firstMobId, int lastMobId) {
        for (int pass = 0; pass < 60; pass++) {
            if (!mobsPresent(bot, firstMobId, lastMobId)) {
                return;
            }
            PqActions.seekAndAttack(bot);
        }
    }

    /** Pick up anything on the floor, so a room-clear condition can be met. */
    private static void sweepFloor(Character bot) {
        PqActions.loot(bot, bot.getPosition(), 4_000,
                new int[]{OrbisPqData.STATUE_PIECE_1, OrbisPqData.STATUE_PIECE_5,
                        OrbisPqData.STATUE_PIECE_8, OrbisPqData.CLOUD_PIECE});
    }

    private static boolean mobsPresent(Character bot, int firstMobId, int lastMobId) {
        return bot.getMap().getAllMonsters().stream()
                .anyMatch(m -> m.getId() >= firstMobId && m.getId() <= lastMobId);
    }

    private static int nearestReactorTo(Character bot, int dataId, Point spot) {
        return nearestReactorInRange(bot, dataId, dataId, spot);
    }

    private static int nearestReactorInRange(Character bot, int firstId, int lastId, Point spot) {
        return bot.getMap().getAllReactors().stream()
                .filter(r -> r.getId() >= firstId && r.getId() <= lastId)
                .min((a, b) -> Double.compare(
                        a.getPosition().distance(spot), b.getPosition().distance(spot)))
                .map(r -> r.getObjectId())
                .orElse(-1);
    }
}
