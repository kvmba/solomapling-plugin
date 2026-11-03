package soloMapling.ArtificialPlayer.BotTypes.Kerning;

import java.awt.Point;
import java.util.List;

/**
 * Kerning PQ ("First Time Together") as the quest defines it.
 *
 * <p>Five stages, and only the first is a fetch: the middle three are the quest's signature
 * area puzzles, where the party has to put a certain number of people on each of a set of
 * ropes, platforms or barrels. To a player that is guesswork guided by the leader's
 * feedback. It is not guesswork underneath: the quest picks a combination up front, stores it
 * in an instance property, and compares the party's placement against it - so a bot that
 * reads that property knows the answer.
 *
 * <p>Sources:
 * <ul>
 *   <li>{@code scripts/event/KerningPQ.js} - {@code minPlayers 3}, {@code maxPlayers 4},
 *       levels 21-30, entry 103000800</li>
 *   <li>{@code scripts/npc/9020001.js} - the whole quest: the stage-1 questions and their
 *       coupon counts, the three area stages' rectangles and combinations, and the
 *       {@code stgNProperty} keys holding the chosen answer</li>
 * </ul>
 */
public final class KerningPqData {

    private KerningPqData() {
    }

    // =========================================================================
    // Maps
    // =========================================================================

    public static final int RECRUIT_MAP = 103000000; // Kerning City
    public static final int STAGE_1 = 103000800;
    public static final int STAGE_2 = 103000801;
    public static final int STAGE_3 = 103000802;
    public static final int STAGE_4 = 103000803;
    public static final int STAGE_5 = 103000804;

    /** The quest's own limits: 3 to 4 players, levels 21 to 30. */
    public static final int MIN_PLAYERS = 3;
    public static final int MAX_PLAYERS = 4;
    public static final int MIN_LEVEL = 21;
    public static final int MAX_LEVEL = 30;

    // =========================================================================
    // Stage 1 - coupons
    // =========================================================================

    public static final int COUPON = 4001007;
    public static final int PASS = 4001008;
    /**
     * Cloto, the stage-1 NPC inside 103000800 (her script is 9020001.js; 9020000 is
     * Lakelis, the entry NPC back in Kerning City who is not present on the stage maps).
     */
    public static final int NPC_CLOTO = 9020001;

    /**
     * Coupon counts the seven questions ask for, in question order. A member is handed one
     * question at random (kept in the instance's grid so it does not change on a re-visit)
     * and needs exactly that many coupons in the inventory when they answer.
     */
    public static final int[] QUESTION_ANSWERS = {10, 35, 20, 25, 25, 30, 8};

    // =========================================================================
    // Stages 2-4 - the area puzzles
    // =========================================================================

    /** The stage-2 layout: four ropes out on the left. */
    public static final List<Point> STAGE_2_SPOTS = List.of(
            new Point(-753, -23), new Point(-719, -257),
            new Point(-584, -251), new Point(-481, -70));

    /** The stage-3 layout: five platforms. */
    public static final List<Point> STAGE_3_SPOTS = List.of(
            new Point(678, -155), new Point(861, -95), new Point(1028, -155),
            new Point(946, -216), new Point(772, -216));

    /** The stage-4 layout: six barrels. */
    public static final List<Point> STAGE_4_SPOTS = List.of(
            new Point(927, -234), new Point(894, -182), new Point(963, -182),
            new Point(862, -130), new Point(927, -130), new Point(998, -130));

    /**
     * The instance property a stage stores its answer in.
     *
     * <p>Only its presence is ever checked - it exists once the intro talk happened, which is
     * what makes a later talk a verdict rather than another intro. Its value is never read.
     */
    public static String answerPropertyFor(int stage) {
        return switch (stage) {
            case 2 -> "stg2Property";
            case 3 -> "stg3Property";
            case 4 -> "stg4Property";
            default -> null;
        };
    }

    /** The spots for an area stage. */
    public static List<Point> spotsFor(int stage) {
        return switch (stage) {
            case 2 -> STAGE_2_SPOTS;
            case 3 -> STAGE_3_SPOTS;
            case 4 -> STAGE_4_SPOTS;
            default -> List.of();
        };
    }

    /**
     * The combination table for an area stage, transcribed from the quest script.
     *
     * <p>Each row is one answer: 1 means that spot wants a body on it, 0 that it must be left
     * empty. The quest compares the party's actual placement against the chosen row position by
     * position, so a wrong guess is wrong for the whole party.
     *
     * <p>The table is NOT used to look the answer up. It is the bot's candidate space: the
     * scripts pick a row index when the leader first talks, and the bots walk the rows in order
     * until one is accepted ({@link soloMapling.ArtificialPlayer.PartyQuest.PositionCombination}).
     */
    public static int[][] combinationsFor(int stage) {
        return switch (stage) {
            case 2 -> STAGE_2_COMBOS;
            case 3 -> STAGE_3_COMBOS;
            case 4 -> STAGE_4_COMBOS;
            default -> new int[0][];
        };
    }

    private static final int[][] STAGE_2_COMBOS = {
            {0, 1, 1, 1}, {1, 0, 1, 1}, {1, 1, 0, 1}, {1, 1, 1, 0},
    };

    private static final int[][] STAGE_3_COMBOS = {
            {0, 0, 1, 1, 1}, {0, 1, 0, 1, 1}, {0, 1, 1, 0, 1}, {0, 1, 1, 1, 0},
            {1, 0, 0, 1, 1}, {1, 0, 1, 0, 1}, {1, 0, 1, 1, 0}, {1, 1, 0, 0, 1},
            {1, 1, 0, 1, 0}, {1, 1, 1, 0, 0},
    };

    private static final int[][] STAGE_4_COMBOS = {
            {0, 0, 0, 1, 1, 1}, {0, 0, 1, 0, 1, 1}, {0, 0, 1, 1, 0, 1}, {0, 0, 1, 1, 1, 0},
            {0, 1, 0, 0, 1, 1}, {0, 1, 0, 1, 0, 1}, {0, 1, 0, 1, 1, 0}, {0, 1, 1, 0, 0, 1},
            {0, 1, 1, 0, 1, 0}, {0, 1, 1, 1, 0, 0}, {1, 0, 0, 0, 1, 1}, {1, 0, 0, 1, 0, 1},
            {1, 0, 0, 1, 1, 0}, {1, 0, 1, 0, 0, 1}, {1, 0, 1, 0, 1, 0}, {1, 0, 1, 1, 0, 0},
            {1, 1, 0, 0, 0, 1}, {1, 1, 0, 0, 1, 0}, {1, 1, 0, 1, 0, 0}, {1, 1, 1, 0, 0, 0},
    };

    /** Every row of an area stage as a candidate: the pattern of spots it wants, and where. */
    public static List<soloMapling.ArtificialPlayer.PartyQuest.PositionCombination.Candidate>
            candidatesFor(int stage) {
        List<Point> spots = spotsFor(stage);
        List<soloMapling.ArtificialPlayer.PartyQuest.PositionCombination.Candidate> out =
                new java.util.ArrayList<>();
        for (int[] row : combinationsFor(stage)) {
            int mask = 0;
            List<Point> wanted = new java.util.ArrayList<>();
            for (int i = 0; i < row.length && i < spots.size(); i++) {
                if (row[i] > 0) {
                    mask |= 1 << i;
                    wanted.add(spots.get(i));
                }
            }
            out.add(new soloMapling.ArtificialPlayer.PartyQuest.PositionCombination.Candidate(
                    soloMapling.ArtificialPlayer.PartyQuest.PositionCombination.maskToPattern(mask),
                    wanted));
        }
        return out;
    }

    /**
     * The stage's area rectangles, in the script's own order.
     *
     * <p>{@code 9020001.js} declares them as literals ({@code stage2Rects} ...) and compares
     * against those, not against the map's {@code area} data - so they are transcribed here
     * rather than read from WZ. Each is a narrow strip; its spots sit on the strip's upper edge.
     */
    public static List<java.awt.Rectangle> areasFor(int stage) {
        return switch (stage) {
            case 2 -> STAGE_2_AREAS;
            case 3 -> STAGE_3_AREAS;
            case 4 -> STAGE_4_AREAS;
            default -> List.of();
        };
    }

    private static final List<java.awt.Rectangle> STAGE_2_AREAS = List.of(
            new java.awt.Rectangle(-755, -132, 4, 218), new java.awt.Rectangle(-721, -340, 4, 166),
            new java.awt.Rectangle(-586, -326, 4, 150), new java.awt.Rectangle(-483, -181, 4, 222));

    private static final List<java.awt.Rectangle> STAGE_3_AREAS = List.of(
            new java.awt.Rectangle(608, -180, 140, 50), new java.awt.Rectangle(791, -117, 140, 45),
            new java.awt.Rectangle(958, -180, 140, 50), new java.awt.Rectangle(876, -238, 140, 45),
            new java.awt.Rectangle(702, -238, 140, 45));

    private static final List<java.awt.Rectangle> STAGE_4_AREAS = List.of(
            new java.awt.Rectangle(910, -236, 35, 5), new java.awt.Rectangle(877, -184, 35, 5),
            new java.awt.Rectangle(946, -184, 35, 5), new java.awt.Rectangle(845, -132, 35, 5),
            new java.awt.Rectangle(910, -132, 35, 5), new java.awt.Rectangle(981, -132, 35, 5));

    /**
     * The row the quest chose, or null when it has not been picked yet.
     *
     * <p>A null means the party has not yet talked to Cloto on that stage, which is what makes
     * the quest pick; the bot has to wait for that rather than guess, because the chosen row
     * is exactly what it is trying to learn.
     */
    public static int[] chosenCombination(int stage, String published) {
        if (published == null) {
            return null;
        }
        int index;
        try {
            index = Integer.parseInt(published.trim());
        } catch (NumberFormatException e) {
            return null;
        }
        int[][] table = combinationsFor(stage);
        if (index < 0 || index >= table.length) {
            return null;
        }
        return table[index];
    }

    /**
     * The spots that need a body, in order - the whole party's plan, which the bot announces
     * because the player has to take some of them.
     *
     * <p>The quest counts every player in the instance, the real one included, so a bot that
     * silently took a spot could push the count past what the row asks for and fail the
     * check for everybody. Handing the plan to the party and letting each body claim one is
     * the only arrangement that works with a human in it.
     */
    public static List<Point> wantedSpots(int[] combination, List<Point> spots) {
        java.util.ArrayList<Point> wanted = new java.util.ArrayList<>();
        if (combination == null) {
            return wanted;
        }
        for (int i = 0; i < combination.length && i < spots.size(); i++) {
            for (int n = 0; n < combination[i]; n++) {
                wanted.add(spots.get(i));
            }
        }
        return wanted;
    }

    /**
     * Which of the wanted spots this bot should take, counting from the end so the bots fill
     * the spots the player is least likely to be walking to.
     *
     * <p>Claiming from the tail and leaving the head for the player keeps the two from
     * chasing the same spot; {@code index} is this bot's rank among the bots on the stage.
     */
    public static Point mySpotFor(int[] combination, List<Point> spots, int index) {
        List<Point> wanted = wantedSpots(combination, spots);
        if (index < 0 || index >= wanted.size()) {
            return null;
        }
        return wanted.get(wanted.size() - 1 - index);
    }
}
