package soloMapling.ArtificialPlayer.BotTypes.Ludi;

import java.awt.Point;
import java.util.List;

/**
 * Ludi PQ ("Dimensional Schism") as the quest defines it.
 *
 * <p>Nine stages over as many maps, and most of them are collect-the-drops: the party kills
 * what is in the room and brings back a set number of {@code 4001022} passes. Two stages are
 * not, and those are the ones worth being careful about - the tower climb, and the crate
 * combination where the party has to stand exactly five people on the right boxes.
 *
 * <p>Sources:
 * <ul>
 *   <li>{@code scripts/event/LudiPQ.js} - {@code minPlayers 5}, {@code maxPlayers 6},
 *       levels 35-50, entry 922010100</li>
 *   <li>{@code scripts/npc/2040036..2040047.js} - the per-stage NPCs, each holding that
 *       stage's requirement</li>
 *   <li>{@code scripts/portal/lpq0..4.js} - the inter-stage portals and their stage-clear
 *       gates</li>
 *   <li>{@code wz/Map.wz/Map/Map9/922010800.img.xml} - the nine crate areas</li>
 * </ul>
 */
public final class LudiPqData {

    private LudiPqData() {
    }

    // =========================================================================
    // Maps
    // =========================================================================

    public static final int RECRUIT_MAP = 221024500; // Ludibrium: where the recruiter stands
    public static final int ENTRY_MAP   = 922010100;
    public static final int STAGE_2 = 922010200;
    public static final int STAGE_3 = 922010300;
    public static final int STAGE_4 = 922010400;
    public static final int STAGE_5 = 922010500;
    public static final int STAGE_6 = 922010600; // the climb
    public static final int STAGE_7 = 922010700;
    public static final int STAGE_8 = 922010800; // the crate combination
    public static final int STAGE_9 = 922010900; // the boss

    /** The quest's own limits: 5 to 6 players, levels 35 to 50. */
    public static final int MIN_PLAYERS = 5;
    public static final int MAX_PLAYERS = 6;
    public static final int MIN_LEVEL = 35;
    public static final int MAX_LEVEL = 50;

    // =========================================================================
    // Items
    // =========================================================================

    /** The pass every collection stage asks for; mobs drop it one at a time. */
    public static final int PASS = 4001022;
    /** The boss trophy, one of which ends the run. */
    public static final int ALISHAR_TROPHY = 4001023;

    /**
     * How many passes each stage asks for, straight out of the stage NPC scripts
     * ({@code 2040036..2040044}). Stage 1 wants 25 like the rest of the collection stages;
     * stages 6 and 8 have their own mechanics and no pass requirement.
     */
    public static int passesWanted(int stage) {
        return switch (stage) {
            case 1 -> 25;
            case 2 -> 15;
            case 3 -> 32;
            case 4 -> 6;
            case 5 -> 24;
            case 7 -> 3;
            default -> 0;
        };
    }

    /** The mob that drops the passes in every collection stage. */
    public static final int PASS_MOB = 9300005;

    // =========================================================================
    // Stage 8 - the crate combination
    // =========================================================================

    /**
     * The nine crate areas, as the centres a body has to occupy. The quest stores its answer
     * as nine comma-separated 0/1 values in {@code stage8combo} - exactly five of them 1 -
     * and requires that five players are on crates and that the pattern matches.
     *
     * <p>Five is not a suggestion: the check is {@code playersOnCombo == 5}, so a party that
     * puts four or six people on crates fails even with the right boxes. That is also why the
     * party minimum is five.
     */
    public static final List<Point> CRATE_SPOTS = List.of(
            new Point(-217, -220), new Point(-150, -221), new Point(-217, -182),
            new Point(-150, -182), new Point(-83, -183), new Point(-216, -143),
            new Point(-149, -143), new Point(-84, -144), new Point(-13, -143));

    public static final String COMBO_PROPERTY = "stage8combo";
    public static final int CRATES_TO_STAND_ON = 5;

    /**
     * The crate this body should stand on, or null when this body is not one of the five.
     *
     * <p>The quest counts every player in the instance, the real one included, so the bots
     * cannot simply take the first five crates: that would leave the player nowhere to stand
     * and the count would come out wrong. The bots take from the end of the list, leaving the
     * head for whoever else is filling the pattern.
     */
    public static Point myCrate(String combo, int bodyIndex) {
        List<Point> wanted = cratesIn(combo);
        if (bodyIndex < 0 || bodyIndex >= wanted.size()) {
            return null;
        }
        return wanted.get(wanted.size() - 1 - bodyIndex);
    }

    /** Which crates the quest's stored combination wants, in area order. */
    public static List<Point> cratesIn(String combo) {
        java.util.ArrayList<Point> wanted = new java.util.ArrayList<>();
        if (combo == null) {
            return wanted;
        }
        String[] parts = combo.split(",");
        for (int i = 0; i < parts.length && i < CRATE_SPOTS.size(); i++) {
            if ("1".equals(parts[i].trim())) {
                wanted.add(CRATE_SPOTS.get(i));
            }
        }
        return wanted;
    }

    // =========================================================================
    // Stage 6 - the climb
    // =========================================================================

    /**
     * The tower's hidden portals, bottom row first. One of them is the way up and the rest
     * drop the climber back down, so a party climbs by trying; the bot can simply walk the
     * row and step into each in turn.
     */
    public static final int CLIMB_PORTAL_FIRST = 2;  // h000
    public static final int CLIMB_PORTAL_LAST  = 22; // h020
}
