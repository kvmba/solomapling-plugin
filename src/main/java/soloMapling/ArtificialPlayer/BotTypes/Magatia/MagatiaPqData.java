package soloMapling.ArtificialPlayer.BotTypes.Magatia;

/**
 * Magatia PQ ("Romeo and Juliet", both the Alcadno and Zenumist versions) as its own scripts
 * define it.
 *
 * <p>The longest quest here: seven stages, and it changes character as it goes. The early
 * rooms are fights and box-breaking, the middle ones are the "line the party up" puzzles this
 * server likes, stage 6 publishes a combination per slot, and the last one is an escort -
 * Yulete has to be talked to and then lives or dies by what the party does around him.
 *
 * <p>Both versions share every mechanic and differ only in which flavour text and which
 * reward they carry, so one bot covers them.
 *
 * <p>Sources:
 * <ul>
 *   <li>{@code scripts/event/MagatiaPQ_A.js} (and {@code _Z}) - the seven stages, the
 *       {@code statusStgN} flags, {@code stage6_combN}, and the Yulete properties</li>
 *   <li>{@code scripts/npc/2112003.js} etc. - the stage NPCs</li>
 *   <li>{@code scripts/npc/2112007.js} / {@code 2112013.js} - the stage-1 gates</li>
 *   <li>{@code scripts/portal/jnr4_r1.js} - the stage-5 area check</li>
 * </ul>
 */
public final class MagatiaPqData {

    private MagatiaPqData() {
    }

    // =========================================================================
    // Maps
    // =========================================================================

    public static final int RECRUIT_MAP_A = 261000021; // Alcadno
    public static final int RECRUIT_MAP_Z = 261000011; // Zenumist

    // The two versions run in parallel map ranges: Alcadno in 92611xxxx, Zenumist in 92610xxxx.
    // They share every mechanic (same statusStgN flags, same stage6_combN keys), so one bot plays
    // either - but it has to recognise both ranges, or a Zenumist run looks like "not in a quest".
    public static final int ENTRY_MAP_A = 926110000;
    public static final int LAST_MAP_A  = 926110600;
    public static final int ENTRY_MAP_Z = 926100000;
    public static final int LAST_MAP_Z  = 926100600;

    /** The room whose monsters carry the stage-1/2 items (Alcadno range). */
    public static final int MOB_ROOM_1 = 926110100;
    public static final int MOB_ROOM_2 = 926110200;

    /** The three sub-rooms that carry the stage-1 pair (Alcadno range). */
    public static final int SUB_ROOM_201 = 926110201;
    public static final int SUB_ROOM_202 = 926110202;
    public static final int SUB_ROOM_203 = 926110203;

    public static final int MIN_PLAYERS = 4;
    public static final int MAX_PLAYERS = 4;
    public static final int MIN_LEVEL = 40;

    // =========================================================================
    // Stage 6 - the combination that is published per slot
    // =========================================================================

    /**
     * The stage-6 combination properties: the quest writes four of them ({@code stage6_comb1..4}),
     * each a ten-digit string of digits 0-3 naming the correct column per row. The bot reads and
     * repeats them rather than guessing a pattern.
     */
    public static final int STAGE_6_SLOTS = 4;

    public static String stage6Key(int slot) {
        return "stage6_comb" + (slot + 1);
    }

    // =========================================================================
    // Yulete's escort
    // =========================================================================

    /** The properties the escort stage sets as it plays out. */
    public static final String YULETE_TIMEOUT = "yuleteTimeout";
    public static final String YULETE_TALKED = "yuleteTalked";
    public static final String YULETE_PASSED = "yuletePassed";

    /** Whether the party has done what the escort wants; a fail means the run is over. */
    public static final String ESCORT_FAIL = "escortFail";

    /**
     * A map is one of the quest's rooms if it falls in either version's run: the Alcadno range
     * ({@code 92611xxxx}) or the Zenumist one ({@code 92610xxxx}). Both are needed - the bot is
     * spawned from both lobbies and Eak's stages are identical, only the map ids differ.
     */
    public static boolean isQuestRoom(int mapId) {
        return (mapId >= ENTRY_MAP_A && mapId <= LAST_MAP_A)
                || (mapId >= ENTRY_MAP_Z && mapId <= LAST_MAP_Z);
    }
}
