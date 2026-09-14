package soloMapling.ArtificialPlayer.BotTypes.Amoria;

import java.awt.Point;
import java.util.List;

/**
 * Amoria PQ ("Amorian Challenge") as its own scripts define it.
 *
 * <p>Six stages, and the quest numbers them off the map: the stage NPC computes
 * {@code (mapId - 670010200) / 100 + 1}, so a room tells you its own stage. That is worth
 * reading rather than re-deriving, because two of the stages are combination puzzles whose
 * rules differ from each other in a way a generic "stand on the platform" bot would get
 * wrong.
 *
 * <p>Stage 2 is the area puzzle again - nine ropes, five people, the combination stored as
 * nine counts in {@code stage2combo}. Stage 3 looks the same but is not: it checks how many
 * of each quest item the party is carrying ({@code 4000000 + i}) against
 * {@code stage3combo}, so standing on the right rope is not enough - the item has to be in
 * the inventory. Stage 3's map has no areas at all, which is the giveaway.
 *
 * <p>Sources:
 * <ul>
 *   <li>{@code scripts/event/AmoriaPQ.js} - {@code minPlayers 6}, {@code maxPlayers 6},
 *       levels 40+, maps 670010200..670010800</li>
 *   <li>{@code scripts/npc/9201044.js} - Amos, who holds every stage check, the stage
 *       derivation, and both combination generators</li>
 *   <li>{@code wz/Map.wz/Map/Map6/670010400.img.xml} - the nine rope areas</li>
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
     * The nine rope areas on 670010400, as the centres a body has to occupy. The combination
     * is nine counts - {@code generateCombo1} fills it by repeatedly drawing from three
     * slots - so a spot may want more than one body.
     */
    public static final List<Point> ROPE_SPOTS = List.of(
            new Point(1465, 48), new Point(1631, 49), new Point(1792, 49),
            new Point(1952, 48), new Point(1378, 201), new Point(1543, 201),
            new Point(1708, 202), new Point(1871, 200), new Point(2030, 201));

    public static final String STAGE_2_COMBO = "stage2combo";
    public static final String STAGE_3_COMBO = "stage3combo";

    /** Five bodies have to be on the ropes; the quest checks this before accepting a try. */
    public static final int BODIES_ON_ROPES = 5;

    // =========================================================================
    // Stage 3 - the same look, a different rule
    // =========================================================================

    /**
     * The quest items stage 3 counts, one per slot: {@code 4000000 + i}. This stage has no
     * areas on its map, so it is the items that decide - the party has to be holding the
     * counts the combination names, slot by slot.
     */
    public static final int STAGE_3_ITEM_BASE = 4000000;

    public static int stageThreeItemFor(int slot) {
        return STAGE_3_ITEM_BASE + slot;
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
     * Which rope this body should take, given its rank among the bots.
     *
     * <p>Counted from the far end so the bots do not crowd the slots the player is likeliest
     * to walk to - the quest counts every body in the instance, the real one included, and
     * wants exactly five on the ropes.
     */
    public static Point myRope(int[] counts, int bodyIndex) {
        if (counts == null || bodyIndex < 0) {
            return null;
        }
        java.util.ArrayList<Point> slots = new java.util.ArrayList<>();
        for (int i = 0; i < counts.length && i < ROPE_SPOTS.size(); i++) {
            for (int n = 0; n < counts[i]; n++) {
                slots.add(ROPE_SPOTS.get(i));
            }
        }
        if (bodyIndex >= slots.size()) {
            return null;
        }
        return slots.get(slots.size() - 1 - bodyIndex);
    }
}
