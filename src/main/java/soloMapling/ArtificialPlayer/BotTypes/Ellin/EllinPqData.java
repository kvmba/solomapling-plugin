package soloMapling.ArtificialPlayer.BotTypes.Ellin;

/**
 * Ellin PQ ("Ellin Forest") as its own portals define it.
 *
 * <p>A long quest of eight rooms, and the shape alternates between two things: clear the room
 * of monsters, or break what stands in the way. The portals themselves state the conditions -
 * one refuses to open while monsters live, another while the "spine" reactor still stands -
 * so the bot watches the same conditions the doors do rather than trying to count its way
 * through.
 *
 * <p>Between the rooms sits a maze of portals that mostly send the traveller back, the same
 * trick Ludi's tower uses, so progress there is judged by arriving somewhere new rather than
 * by any flag.
 *
 * <p>Sources:
 * <ul>
 *   <li>{@code scripts/event/EllinPQ.js} - {@code minPlayers 4}, {@code maxPlayers 6},
 *       levels 44-55, maps 930000000..930000800</li>
 *   <li>{@code scripts/portal/party6_stage.js} - the room-by-room conditions</li>
 *   <li>{@code wz/Map.wz/Map/Map9/9300001xx..9300006xx.img.xml} - the rooms' monsters and
 *       reactors</li>
 * </ul>
 */
public final class EllinPqData {

    private EllinPqData() {
    }

    public static final int RECRUIT_MAP = 300030100;
    public static final int ENTRY_MAP   = 930000000;
    public static final int LAST_ROOM   = 930000800;

    /** The room that holds the maze of dead-end portals. */
    public static final int MAZE_MAP = 930000300;

    /** The frog room past the maze, whose guardians are caught rather than killed. */
    public static final int FROG_ROOM = 930000400;

    /**
     * The two rooms past the frog room, and the two reactors that carry them.
     *
     * <p>{@code 930000500} holds the Purple Stone of Magic inside box reactors; the stone is what
     * the exit NPC ({@code 2133004}) demands from the LEADER before warping the team on to
     * {@code 930000600}. There, the altar reactor is what actually spawns the Poison Golem - and
     * killing the golem is what clears the run ({@code EllinPQ.monsterKilled}).
     *
     * <p>Sources: {@code wz/Map.wz/Map/Map9/930000500.img.xml} (reactor 3002001),
     * {@code 930000600.img.xml} (reactor 3001000), {@code scripts/reactor/3001000.js}
     * ({@code spawnMonster(9300180)}), {@code scripts/npc/2133004.js} (the stone gate), and
     * {@code reactordrops} V1.0.58 row 374 (3002001 → 4001163).
     */
    public static final int STONE_BOX_ROOM = 930000500;
    public static final int STONE_BOX = 3002001;
    public static final int PURPLE_STONE = 4001163;

    public static final int ALTAR_ROOM = 930000600;
    public static final int ALTAR = 3001000;

    public static final int MIN_PLAYERS = 4;
    public static final int MAX_PLAYERS = 6;
    public static final int MIN_LEVEL = 44;
    public static final int MAX_LEVEL = 55;

    /** The reactor that blocks the way out of the second room until it is broken. */
    public static final int SPINE_REACTOR = 3009000;
    public static final String SPINE_NAME = "spine";

    /** Whether a map is one of the quest's rooms. */
    public static boolean isQuestRoom(int mapId) {
        return mapId >= ENTRY_MAP && mapId <= LAST_ROOM;
    }

    /**
     * The monsters the quest puts in each room, as {first, last} ids; a room with no range is
     * a reactor or maze room.
     */
    public static int[] mobRangeIn(int mapId) {
        return switch (mapId) {
            case 930000100 -> new int[]{9300172, 9300172};
            case 930000200 -> new int[]{9300173, 9300173};
            case 930000400 -> new int[]{9300175, 9300175};
            default -> null;
        };
    }
}
