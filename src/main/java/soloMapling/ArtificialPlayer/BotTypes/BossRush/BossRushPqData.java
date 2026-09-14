package soloMapling.ArtificialPlayer.BotTypes.BossRush;

/**
 * Boss Rush PQ as its own scripts define it.
 *
 * <p>The simplest of the party quests to automate, and for once that is the whole story: the
 * party is dropped into a room, the room is full of boss-grade monsters, and the portal to
 * the next room only opens once nothing is left alive. There is no puzzle, no gathering, no
 * turn-in - just damage, room after room, with resting spots between every five.
 *
 * <p>Sources:
 * <ul>
 *   <li>{@code scripts/event/BossRushPQ.js} - {@code minPlayers 1}, {@code maxPlayers 6}</li>
 *   <li>{@code scripts/npc/9000037.js} - the host, who reads progress off the map id</li>
 *   <li>{@code scripts/portal/raid_stage.js} - the rule that makes this tractable: the portal
 *       checks {@code getMonsters().isEmpty()} and otherwise refuses, which is exactly the
 *       condition a bot needs to watch</li>
 * </ul>
 */
public final class BossRushPqData {

    private BossRushPqData() {
    }

    /** The host's own bounds, from his progress check. */
    public static final int FIRST_STAGE = 970030001;
    public static final int LAST_STAGE = 970030010;

    /** Warp-in map and the resting lobbies between stretches of five rooms. */
    public static final int HOST_MAP = 970030000;

    /** The quest allows a solo attempt, which is why its floor is one. */
    public static final int MIN_PLAYERS = 1;
    public static final int MAX_PLAYERS = 6;

    /**
     * Whether a map is one of the quest's fighting rooms. The host tests a range rather than
     * a list, and the rooms are not contiguous - the resting spots and the higher-level
     * lobbies sit inside it - so the same test is the honest one to use.
     */
    public static boolean isFightRoom(int mapId) {
        return mapId >= FIRST_STAGE && mapId <= 970042711;
    }

    /** How many rooms stand between one resting spot and the next. */
    public static final int ROOMS_PER_REST = 5;

    /** Whether a map is a resting spot, where the party is between stretches. */
    public static boolean isRestSpot(int mapId) {
        return mapId >= 970032700 && mapId < 970032800;
    }
}
