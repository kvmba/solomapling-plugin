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
