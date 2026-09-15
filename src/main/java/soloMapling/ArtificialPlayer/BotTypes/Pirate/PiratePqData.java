package soloMapling.ArtificialPlayer.BotTypes.Pirate;

/**
 * Pirate PQ ("Lord Pirate") as its own scripts define it.
 *
 * <p>Five stages, all of the same kind: the room is full of boxes and breaking them is what
 * opens the way on. Stages 2 and 3 are boxes standing in rows; stage 4 puts the mobs inside
 * the boxes rather than on the floor, so the boxes have to be broken before there is anything
 * to fight; stage 5 is Lord Pirate himself.
 *
 * <p>The quest tracks which stage the party is on by watching the map the leader walks into
 * ({@code changedMapInside} bumps {@code curStage} per room), which means the bot can use the
 * same signal - the map it is standing in - and does not have to read a flag that lags behind
 * the room change.
 *
 * <p>Sources:
 * <ul>
 *   <li>{@code scripts/event/PiratePQ.js} - {@code minPlayers 3}, {@code maxPlayers 6}, the
 *       {@code curStage} progression, levels 55-100</li>
 *   <li>{@code wz/Map.wz/Map/Map9/9251001xx..9251004xx.img.xml} - the box reactors and the
 *       mobs each room declares</li>
 * </ul>
 */
public final class PiratePqData {

    private PiratePqData() {
    }

    // =========================================================================
    // Maps
    // =========================================================================

    public static final int RECRUIT_MAP = 251010404; // Herb Town
    public static final int ENTRY_MAP   = 925100000;
    public static final int STAGE_1     = 925100100; // boxes, then the mobs they release
    public static final int STAGE_2     = 925100200; // two rows of boxes
    public static final int STAGE_3     = 925100300; // two rows of boxes
    public static final int STAGE_4     = 925100400; // boxes with monsters inside
    public static final int STAGE_5     = 925100500; // Lord Pirate

    public static final int MIN_PLAYERS = 3;
    public static final int MAX_PLAYERS = 6;
    public static final int MIN_LEVEL = 55;
    public static final int MAX_LEVEL = 100;

    // =========================================================================
    // Boxes
    // =========================================================================

    /**
     * The box reactors, by the room they stand in. Breaking all of a room's boxes is what the
     * room is for; the mobs inside come out as they break.
     *
     * <p>Room 925100100 has no reactors of its own - its monsters are spawned by the event
     * script into the room directly - so it is fought, not broken.
     */
    public static final int[] STAGE_2_BOXES = {2518000, 2511001, 2512000};
    public static final int[] STAGE_3_BOXES = {2518000, 2511001, 2512000};
    public static final int[] STAGE_4_BOXES = {2519000, 2519001, 2519002, 2519003};

    /** The monsters each room declares; the first three levels of the quest use these. */
    public static final int STAGE_1_MOB_LOW = 9300114;
    public static final int STAGE_1_MOB_HIGH = 9300115;
    public static final int STAGE_4_MOB_FIRST = 9300120;
    public static final int STAGE_4_MOB_LAST = 9300126;

    /** Which boxes stand in a room, or an empty array when the room is a fight. */
    public static int[] boxesIn(int mapId) {
        return switch (mapId) {
            case STAGE_2 -> STAGE_2_BOXES;
            case STAGE_3 -> STAGE_3_BOXES;
            case STAGE_4 -> STAGE_4_BOXES;
            default -> new int[0];
        };
    }

    /** The mob range a room declares, as {first, last} ids, or null when it is a box room. */
    public static int[] mobRangeIn(int mapId) {
        return switch (mapId) {
            case STAGE_1 -> new int[]{STAGE_1_MOB_LOW, STAGE_1_MOB_HIGH};
            case STAGE_4 -> new int[]{STAGE_4_MOB_FIRST, STAGE_4_MOB_LAST};
            default -> null;
        };
    }
}
