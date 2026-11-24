package soloMapling.ArtificialPlayer.BotTypes.OPQ;

import org.gms.client.Character;
import org.gms.server.maps.Reactor;
import soloMapling.ArtificialPlayer.PartyQuest.PositionCombination;
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

    static final int[] WALKWAY_LOOT = {OrbisPqData.STATUE_PIECE_1};

    // =========================================================================
    // Stage 2 - storage: the room must be empty before Eak hands over the piece
    // =========================================================================

    static final int[] STORAGE_LOOT = {OrbisPqData.PAPA_TRAP_ITEM, OrbisPqData.STATUE_PIECE_1,
            OrbisPqData.STATUE_PIECE_5, OrbisPqData.STATUE_PIECE_8, OrbisPqData.CLOUD_PIECE};

    // =========================================================================
    // Stage 4 - sealed room: stand in the three areas
    // =========================================================================

    /**
     * Put this bot on the platform the party's current candidate assigns to it.
     *
     * <p>The quest picks three numbers summing to 3 and compares them against the player count
     * inside each rectangle, so the party has to stand <em>exactly</em> so. Those numbers are
     * published as {@code stage4_0..2}; this searches the space instead - every spread of three
     * bodies over three platforms is a candidate, walked in a fixed order
     * ({@link PositionCombination}), and a candidate leaves the walk only when a talk provably
     * judged it wrong - with each round allowed to peek at the published numbers, 0% on the
     * first and 0.5% more after every failed one.
     *
     * <p>{@code index} is this bot's slot among the bots taking part.
     *
     * @return the candidate to announce to the players who still need to take a spot
     */
    public static PositionCombination.Plan standOnSealedPlatform(Character bot, int index) {
        return PositionCombination.standOn(bot, index, sealedCandidates(),
                OrbisPqData.SEALED_AREA_RECTS, null,
                PositionCombination.countsAnswer("stage4_0", "stage4_1", "stage4_2"),
                () -> PqActions.waitNearPuzzleNpc(bot, OrbisPqData.EAK));
    }

    /**
     * Every way to put the room's three bodies on its three platforms.
     *
     * <p>The script accepts any layout whose total is exactly three
     * ({@code total != 3 -> "There needs to be exactly 3 players on these platforms"}), so the
     * space is the ten compositions of 3 into 3 parts - (3,0,0), (2,1,0) and so on - NOT the six
     * one-per-platform permutations. Getting this wrong would hide most of the answers: a party
     * that has to put all three bodies on one platform would never present it.
     */
    static List<PositionCombination.Candidate> sealedCandidates() {
        List<PositionCombination.Candidate> out = new java.util.ArrayList<>();
        for (long[] pattern : PositionCombination.countCandidates(
                OrbisPqData.SEALED_PLAYERS, OrbisPqData.SEALED_PLAYERS)) {
            List<Point> spots = new java.util.ArrayList<>(OrbisPqData.SEALED_PLAYERS);
            for (int area : PositionCombination.areasOf(pattern[0])) {
                spots.add(OrbisPqData.SEALED_AREAS[area]);
            }
            out.add(new PositionCombination.Candidate(pattern[0], spots));
        }
        return out;
    }

    // =========================================================================
    // Stage 5 - lounge: kill 9300041..9300043 for 40 of 4001052, plus the room-604 reactors
    // =========================================================================

    static final int[] LOUNGE_LOOT = {OrbisPqData.STATUE_PIECE_5};

    /** Work this visit's drops before breaking another reactor or walking out. */
    public static boolean gatherLounge(Character bot) {
        if (PqActions.reactorBeatArmed(bot)
                || PqActions.collectPartyQuestLoot(bot, LOUNGE_LOOT) != PqActions.LootBeat.NONE
                || bot.getMap().countMonsters() > 0) return false;
        return !PqActions.workActiveReactors(bot, OrbisPqData.LOUNGE_REACTOR);
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
            Reactor reactor = nearestReactorTo(bot, OrbisPqData.LEVER_REACTOR, lever);
            if (reactor != null && reactor.getState() == 0
                    && !PqActions.hitReactor(bot, reactor.getObjectId())) {
                return false;
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
            Reactor reactor = nearestReactorInRange(bot, OrbisPqData.SCAR_FIRST, OrbisPqData.SCAR_LAST, spot);
            if (reactor != null && reactor.getState() == 0
                    && !PqActions.hitReactor(bot, reactor.getObjectId())) {
                return;
            }
        }
    }

    // Leave the spring's final statue piece for the leader: the room's exit checks their inventory.
    static final int[] PAPA_LOOT = {OrbisPqData.PAPA_MEDAL, OrbisPqData.PAPA_TRAP_ITEM, OrbisPqData.PAPA_SEED};

    /**
     * Feed Papa Pixie's item-triggered reactors; the combat sweep supplies their items.
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
     */
    static void feedPapaReactors(Character bot) {
        if (feedReactor(bot, OrbisPqData.PAPA_SPRING_REACTOR, OrbisPqData.PAPA_SEED)
                || feedReactor(bot, OrbisPqData.PAPA_TRAP, OrbisPqData.PAPA_TRAP_ITEM)
                || feedReactor(bot, OrbisPqData.PAPA_POT, OrbisPqData.PAPA_MEDAL)) return;
        feedReactor(bot, OrbisPqData.PAPA_POT_ALT, OrbisPqData.PAPA_MEDAL);
    }

    /** Feed the first live reactor of a data id its item; false when none is standing. */
    private static boolean feedReactor(Character bot, int reactorDataId, int itemId) {
        int oid = findReactorOid(bot, reactorDataId);
        if (oid < 0) {
            return false;
        }
        return PqActions.feedReactor(bot, oid, itemId);
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
        feedReactor(bot, OrbisPqData.STATUE_BASE_REACTOR, OrbisPqData.STATUE_PIECE_8);
    }

    // =========================================================================
    // helpers
    // =========================================================================

    /** One combat beat. A pickup walk owns steering but must not suppress a swing in range. */
    static void fightAndCollect(Character bot, int[] loot) {
        if (PqActions.collectPartyQuestLoot(bot, loot) == PqActions.LootBeat.NONE) {
            PqActions.seekAndAttack(bot);
        } else {
            PqActions.attack(bot);
        }
    }

    private static Reactor nearestReactorTo(Character bot, int dataId, Point spot) {
        return nearestReactorInRange(bot, dataId, dataId, spot);
    }

    private static Reactor nearestReactorInRange(Character bot, int firstId, int lastId, Point spot) {
        return bot.getMap().getAllReactors().stream()
                .filter(r -> r.getId() >= firstId && r.getId() <= lastId)
                .min((a, b) -> Double.compare(
                        a.getPosition().distance(spot), b.getPosition().distance(spot)))
                .orElse(null);
    }
}
