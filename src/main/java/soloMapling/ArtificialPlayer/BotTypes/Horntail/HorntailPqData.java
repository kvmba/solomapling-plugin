package soloMapling.ArtificialPlayer.BotTypes.Horntail;

/**
 * Horntail PQ (the "Cave of Trial") as its own scripts define it.
 *
 * <p>Five rooms, five crystal keys, one each. Each room holds a pair of monsters and one of
 * them carries that room's key, so the rooms are the same instruction repeated - kill what is
 * here until the key falls out - with the keys differing by room. Aura then takes all five at
 * once, so the turn-in is a single conversation at the end rather than five along the way.
 *
 * <p>The last room branches: a light reactor decides whether the party leaves to the light or
 * the dark side, and its state is what the exit portal reads. That choice belongs to the
 * leader, who is the only one the portal lets through, so the bot follows rather than picking.
 *
 * <p>Sources:
 * <ul>
 *   <li>{@code scripts/event/HorntailPQ.js} - {@code minPlayers 6}, {@code maxPlayers 6},
 *       levels 120+, maps 240050100..240050310, and the five-key item set</li>
 *   <li>{@code scripts/npc/2083001.js} - Aura, who checks for all five keys and removes them
 *       together</li>
 *   <li>{@code scripts/portal/hontale_C.js} - the light/dark branch</li>
 *   <li>{@code db/.../drop_data_insert_old_data.sql} - which monster carries which key</li>
 * </ul>
 */
public final class HorntailPqData {

    private HorntailPqData() {
    }

    public static final int RECRUIT_MAP = 240050000;
    public static final int ENTRY_MAP   = 240050100;
    public static final int LAST_ROOM   = 240050105;

    /** The light and dark sides the last room can lead to. */
    public static final int LIGHT_ROOM = 240050300;
    public static final int DARK_ROOM  = 240050310;

    public static final int MIN_PLAYERS = 6;
    public static final int MAX_PLAYERS = 6;
    public static final int MIN_LEVEL = 120;

    /** Aura, who takes the five keys together. */
    public static final int AURA = 2083001;
    /** The reactor whose state decides which side the party leaves to. */
    public static final String LIGHT_REACTOR = "light";

    /**
     * The five crystal keys, room by room: room {@code 240050100 + i} is the home of key
     * {@code KEY_FIRST + i}, and its carrier is {@code CARRIER_FIRST + 2*i} - the two monsters
     * in each room are consecutive ids and only the first of each pair carries the key, which
     * is exactly what the drop table says.
     */
    public static final int KEY_FIRST = 4001087;
    public static final int KEY_COUNT = 5;
    public static final int ROOM_KEY_MOB_FIRST = 9300066;
    public static final int ROOM_KEY_MOB_STEP = 2;

    /** The key the room at {@code index} (0-based) is after. */
    public static int keyForRoom(int index) {
        return KEY_FIRST + index;
    }

    /** The monster that carries the room's key. */
    public static int carrierForRoom(int index) {
        return ROOM_KEY_MOB_FIRST + index * ROOM_KEY_MOB_STEP;
    }

    /** How many keys a room's worth of work is, from the room's own index. */
    public static int roomIndexOf(int mapId) {
        int index = mapId - ENTRY_MAP;
        return (index >= 0 && index < KEY_COUNT) ? index : -1;
    }

    /** Whether the party is standing in one of the key rooms. */
    public static boolean isKeyRoom(int mapId) {
        return roomIndexOf(mapId) >= 0;
    }

    public static boolean isQuestRoom(int mapId) {
        return mapId >= ENTRY_MAP && mapId <= DARK_ROOM;
    }
}
