package soloMapling.ArtificialPlayer.BotTypes.Pyramid;

/**
 * Nett's Pyramid (Duarte's challenge) as its own classes define it.
 *
 * <p>The odd one out among the quests here: it is not an event script and not a set of rooms
 * behind portals, but a Java class ({@code Pyramid extends PartyQuest}) that one NPC starts,
 * and its progress is a gauge rather than a set of stage flags.
 *
 * <p>The gauge is the whole mechanic. Killing adds to it, using the class's cool-down adds a
 * little, and a miss takes a large bite out; at zero the run ends in failure. So a bot's job
 * here is not "kill everything" but "kill steadily and only the right things" - the monster
 * the quest marks as forbidden is the one that produces the miss.
 *
 * <p>Sources:
 * <ul>
 *   <li>{@code org/gms/server/partyquest/Pyramid.java} - the gauge arithmetic
 *       ({@code kill()}, {@code cool()}, {@code miss()}), the mode enum and the map walk</li>
 *   <li>{@code org/gms/constants/id/MapId.java} - {@code isNettsPyramid} and the two bases,
 *       which the bot asks for rather than re-deriving</li>
 *   <li>{@code scripts/npc/2103013.js} - Duarte: party of 2 or more with members in the map,
 *       levels 40 to 60</li>
 * </ul>
 */
public final class PyramidPqData {

    private PyramidPqData() {
    }

    /** Duarte, who starts the challenge. */
    public static final int DUARTE = 2103013;

    /** The quest's own bar, straight from Duarte's refusals. */
    public static final int MIN_PARTY = 2;
    public static final int MIN_LEVEL = 40;
    public static final int MAX_LEVEL = 60;

    /** The four difficulties, in the order Duarte lists them. */
    public static final String[] MODES = {"EASY", "NORMAL", "HARD", "HELL"};

    /**
     * The monster the quest warns against attacking, which is what costs gauge rather than
     * adding to it. Duarte names its mark ({@code 04032424}) in the briefing.
     */
    public static final int FORBIDDEN_MARK = 4032424;

    /**
     * Whether a map is one of the pyramid's rooms. The engine already knows the bounds, so
     * the bot asks it rather than carrying a copy that could drift from the map data.
     */
    public static boolean isPyramidMap(int mapId) {
        return org.gms.constants.id.MapId.isNettsPyramid(mapId);
    }

    /** Whether a map is one of the party-mode rooms rather than a solo one. */
    public static boolean isPartyMap(int mapId) {
        return mapId >= org.gms.constants.id.MapId.NETTS_PYRAMID_PARTY_BASE;
    }

    public static String modeName(int mode) {
        return (mode >= 0 && mode < MODES.length) ? MODES[mode] : "?";
    }
}
