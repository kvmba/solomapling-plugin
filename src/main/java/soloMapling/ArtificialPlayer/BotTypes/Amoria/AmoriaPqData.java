package soloMapling.ArtificialPlayer.BotTypes.Amoria;

import java.awt.Point;
import java.util.List;

/**
 * Amoria PQ ("Amorian Challenge") as its own scripts define it.
 *
 * <p>Six stages, and the quest numbers them off the map: the stage NPC computes
 * {@code (mapId - 670010200) / 100 + 1}, so a room tells you its own stage.
 *
 * <p>The rope stages are area puzzles: the quest picks a combination over a set of areas and
 * compares it against where the party stands, so the bot reads the published counts and stands on
 * a spot. Stage 2 indexes the three areas of its sub-room ({@code 670010300/301/302}); stage 3
 * indexes the nine ropes on {@code 670010400}.
 *
 * <p>Sources:
 * <ul>
 *   <li>{@code scripts/event/AmoriaPQ.js} - {@code minPlayers 6}, {@code maxPlayers 6},
 *       levels 40+, maps 670010200..670010800, the gender-mask eligibility check</li>
 *   <li>{@code scripts/npc/9201044.js} - Amos, who holds every stage check, the stage
 *       derivation, and both combination generators</li>
 *   <li>{@code wz/Map.wz/Map/Map6/670010400.img.xml} - the nine rope areas the combination
 *       indexes</li>
 * </ul>
 */
public final class AmoriaPqData {

    private AmoriaPqData() {
    }

    // =========================================================================
    // Maps
    // =========================================================================

    public static final int RECRUIT_MAP = 670010100;
    public static final int ENTRY_MAP   = 670010200;
    public static final int CLEAR_MAP   = 670010800;

    /** The quest's own stage arithmetic, straight out of the NPC script. */
    public static int stageOf(int mapId) {
        return (mapId - ENTRY_MAP) / 100 + 1;
    }

    /** The quest demands a full party: six, no more and no fewer. */
    public static final int MIN_PLAYERS = 6;
    public static final int MAX_PLAYERS = 6;
    public static final int MIN_LEVEL = 40;

    // =========================================================================
    // Stage 2 - nine ropes, five bodies
    // =========================================================================

    /**
     * Stage 2's three areas, on the {@code 670010300/301/302} sub-rooms the stage-1 gate opens
     * onto, as the centres a body has to occupy. {@code generateCombo1} builds its combination by
     * drawing from only three slots, so stage 2 is effectively a three-area puzzle.
     */
    public static final List<Point> STAGE_2_SPOTS = List.of(
            new Point(-629, -1772), new Point(-45, -1861), new Point(540, -1996));

    /**
     * Stage 3's nine rope areas on {@code 670010400}, as the centres a body has to occupy.
     * {@code generateCombo2} names five of these nine, and these centres are exactly the map's
     * nine {@code area} rectangles.
     */
    public static final List<Point> ROPE_SPOTS = List.of(
            new Point(1465, 48), new Point(1631, 49), new Point(1792, 49),
            new Point(1952, 48), new Point(1378, 201), new Point(1543, 201),
            new Point(1708, 202), new Point(1871, 200), new Point(2030, 201));

    public static final String STAGE_2_COMBO = "stage2combo";
    public static final String STAGE_3_COMBO = "stage3combo";

    /** Five bodies have to be on the ropes; the quest checks this before accepting a try. */
    public static final int BODIES_ON_ROPES = 5;

    /**
     * What stage 4's collection room asks for: 50 of the 4031597 pieces, in the leader's
     * inventory when he talks to Amos (NPC 9201045, the stage-4 branch).
     */
    public static final int STATUE_PIECE = 4031597;
    public static final int STATUE_PIECES_WANTED = 50;

    /**
     * Stage 1's Magik Mirror reactor (6702000, on the stage-1 hall floor): its drop table
     * yields 4031595 - the pass 9201047 asks the leader for. The mirror fight's mobs sit
     * behind the gender doors the bots cannot pass, so breaking the mirror is the bot's
     * whole contribution to this stage.
     */
    public static final int MIRROR_REACTOR = 6702000;

    /** The instance property a rope stage stores its answer in. */
    public static String comboPropertyFor(int stage) {
        return switch (stage) {
            case 2 -> STAGE_2_COMBO;
            case 3 -> STAGE_3_COMBO;
            default -> null;
        };
    }

    /**
     * The body spots a rope stage's combination indexes, in the order the counts map to them, or
     * empty when the stage is not a rope puzzle this bot handles.
     */
    public static List<Point> spotsFor(int stage) {
        return switch (stage) {
            case 2 -> STAGE_2_SPOTS;
            case 3 -> ROPE_SPOTS;
            default -> List.of();
        };
    }

    /**
     * Stage 3's search space: five of the nine ropes, one body each.
     *
     * <p>{@code generateCombo2} names exactly five of nine areas, so this is the same shape as
     * Ludi's crates and uses the same search
     * ({@link soloMapling.ArtificialPlayer.PartyQuest.PositionCombination}). The answer is stored
     * in {@code stage3combo} and is read only by the round's peek, starting at 0%.
     */
    public static List<soloMapling.ArtificialPlayer.PartyQuest.PositionCombination.Candidate>
            stage3Candidates() {
        List<soloMapling.ArtificialPlayer.PartyQuest.PositionCombination.Candidate> out =
                new java.util.ArrayList<>();
        for (int combo = 1; combo < (1 << ROPE_SPOTS.size()); combo++) {
            if (Integer.bitCount(combo) != BODIES_ON_ROPES) {
                continue;
            }
            List<Point> spots = new java.util.ArrayList<>(BODIES_ON_ROPES);
            for (int area = 0; area < ROPE_SPOTS.size(); area++) {
                if ((combo & (1 << area)) != 0) {
                    spots.add(ROPE_SPOTS.get(area));
                }
            }
            out.add(new soloMapling.ArtificialPlayer.PartyQuest.PositionCombination.Candidate(
                    soloMapling.ArtificialPlayer.PartyQuest.PositionCombination
                            .maskToPattern(combo), spots));
        }
        return out;
    }

    /**
     * Stage 2's search space: the ways five bodies can be spread over its three areas.
     *
     * <p>{@code generateCombo1} builds a COUNT vector (the number of bodies per area, summing to
     * five), so a pattern here carries counts rather than single bits. The space is the 21
     * compositions of 5 into 3 parts - small enough to walk, unlike a per-body assignment.
     */
    public static List<soloMapling.ArtificialPlayer.PartyQuest.PositionCombination.Candidate>
            stage2Candidates() {
        List<soloMapling.ArtificialPlayer.PartyQuest.PositionCombination.Candidate> out =
                new java.util.ArrayList<>();
        for (long[] pattern : soloMapling.ArtificialPlayer.PartyQuest.PositionCombination
                .countCandidates(STAGE_2_SPOTS.size(), BODIES_ON_ROPES)) {
            List<Point> spots = new java.util.ArrayList<>(BODIES_ON_ROPES);
            for (int area : soloMapling.ArtificialPlayer.PartyQuest.PositionCombination
                    .areasOf(pattern[0])) {
                if (area < STAGE_2_SPOTS.size()) {
                    spots.add(STAGE_2_SPOTS.get(area));
                }
            }
            out.add(new soloMapling.ArtificialPlayer.PartyQuest.PositionCombination.Candidate(
                    pattern[0], spots));
        }
        return out;
    }

    /**
     * The rope rooms' area rectangles, in the script's own order.
     *
     * <p>Amos reads the map's own {@code area} list ({@code map.getArea(y)}), so these come
     * from WZ: three vertical strips on the stage-2 sub-rooms, nine rope strips on stage 3.
     */
    public static List<java.awt.Rectangle> areasFor(int stage) {
        return switch (stage) {
            case 2 -> STAGE_2_AREAS;
            case 3 -> STAGE_3_AREAS;
            default -> List.of();
        };
    }

    private static final List<java.awt.Rectangle> STAGE_2_AREAS = List.of(
            new java.awt.Rectangle(-672, -1892, 87, 241),
            new java.awt.Rectangle(-92, -2010, 94, 298),
            new java.awt.Rectangle(495, -2130, 90, 269));

    private static final List<java.awt.Rectangle> STAGE_3_AREAS = List.of(
            new java.awt.Rectangle(1420, 43, 90, 11), new java.awt.Rectangle(1587, 43, 89, 12),
            new java.awt.Rectangle(1748, 44, 88, 10), new java.awt.Rectangle(1908, 43, 89, 11),
            new java.awt.Rectangle(1333, 196, 91, 11), new java.awt.Rectangle(1500, 195, 87, 12),
            new java.awt.Rectangle(1663, 196, 90, 12), new java.awt.Rectangle(1826, 194, 90, 12),
            new java.awt.Rectangle(1985, 196, 91, 11));

    // =========================================================================
    // Combinations
    // =========================================================================

    /**
     * The counts the quest stored for a stage, in slot order, or null when it has not picked
     * yet - which is exactly when the NPC generates one, so a null means "ask Amos first".
     */
    public static int[] chosenCounts(String published) {
        if (published == null || published.isBlank()) {
            return null;
        }
        String[] parts = published.split(",");
        int[] counts = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                counts[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return counts;
    }

    /**
     * Which spot this body should take, given its rank among the bots.
     *
     * <p>Counted from the far end so the bots do not crowd the slots the player is likeliest
     * to walk to - the quest counts every body in the instance, the real one included, and
     * wants exactly five standing on the areas.
     */
    public static Point mySpot(int[] counts, List<Point> spots, int bodyIndex) {
        if (counts == null || spots == null || bodyIndex < 0) {
            return null;
        }
        java.util.ArrayList<Point> slots = new java.util.ArrayList<>();
        for (int i = 0; i < counts.length && i < spots.size(); i++) {
            for (int n = 0; n < counts[i]; n++) {
                slots.add(spots.get(i));
            }
        }
        if (bodyIndex >= slots.size()) {
            return null;
        }
        return slots.get(slots.size() - 1 - bodyIndex);
    }
}
