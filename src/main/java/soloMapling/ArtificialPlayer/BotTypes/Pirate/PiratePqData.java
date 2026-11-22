package soloMapling.ArtificialPlayer.BotTypes.Pirate;

/**
 * Pirate PQ ("Lord Pirate") as its own scripts define it.
 *
 * <p>The medal room is followed by two ship rooms. In the last ship room the doors take
 * keys dropped by the mobs; closing them stops their spawns before Lord Pirate.
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
    public static final int REWARD_MAP  = 925100600;
    public static final int EXIT_MAP    = 925100700;
    public static final int WU_YANG     = 2094001;

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
    private static final int[] ENTRY_BOXES = {2511000, 2512000};
    static final int[] NO_BOXES = {};
    private static final int[] SHIP_BOXES = {2511001, 2512000};
    static final int DOOR_FIRST = 2519000;
    static final int DOOR_LAST = 2519003;
    public static final int DOOR_KEY = 4001117;

    /** The monsters each room declares; the first three levels of the quest use these. */
    public static final int STAGE_1_MOB_LOW = 9300114;
    public static final int STAGE_1_MOB_HIGH = 9300115;
    public static final int STAGE_4_MOB_FIRST = 9300120;
    public static final int STAGE_4_MOB_LAST = 9300126;

    /** Which boxes stand in a room, or an empty array when the room is a fight. */
    public static int[] boxesIn(int mapId) {
        return switch (mapId) {
            case ENTRY_MAP -> ENTRY_BOXES;
            case STAGE_2, STAGE_3 -> SHIP_BOXES;
            default -> NO_BOXES;
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

    /**
     * The run's exclusive items ({@code PiratePQ setEventExclusives}) - the medals the medal
     * stages want (NPC 2094002 grades the leader's inventory) plus the ship's letters. A bot
     * that picks any of them up drops them at the leader's feet.
     */
    public static final int[] QUEST_ITEMS = {DOOR_KEY, 4001120, 4001121, 4001122};
}
