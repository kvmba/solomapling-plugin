package soloMapling.ArtificialPlayer.PartyQuest;

import java.util.ArrayList;
import java.util.List;

/**
 * Where each party quest recruits, and who is eligible once they are there.
 *
 * <p>A quest bot is useless unless a player can actually find it, and the quests' own eligibility
 * rules are what decide that: {@code getEligibleParty} keeps only members standing in the
 * quest's recruit map and inside its level band, and it does so from a snapshot taken when the
 * leader starts the run. A bot three maps away or six levels out is invisible to that check no
 * matter how well it plays.
 *
 * <p>So this is the list the spawner works from, and every value in it was read out of the
 * quests' event scripts - the same files the eligibility check itself reads - rather than
 * invented to match. The levels matter as much as the maps: a level-70 bot standing in Kerning's
 * lobby is exactly as recruitable as one standing in Henesys.
 *
 * <p>Two entries are deliberately awkward and worth knowing about. Boss Rush is entered from its
 * own instance lobby rather than a town, and Horntail's is a cave the party is warped into; both
 * are still ordinary maps a bot can be placed on, but neither is somewhere a bot would otherwise
 * be standing.
 */
public final class PqRecruitPoints {

    private PqRecruitPoints() {
    }

    /**
     * One quest's recruiting requirements.
     *
     * @param name          the event name, for logging
     * @param recruitMap    where members have to be standing to count
     * @param minLevel      the quest's level floor
     * @param maxLevel      the quest's level ceiling
     * @param minPlayers    how many members the quest insists on
     * @param maxPlayers    how many it will take
     */
    public record Point(String name, int recruitMap, int minLevel, int maxLevel,
                        int minPlayers, int maxPlayers) {

        /** Whether a level would pass this quest's own eligibility test. */
        public boolean levelEligible(int level) {
            return level >= minLevel && level <= maxLevel;
        }

        /**
         * How many bots to place so a lone player can start this quest.
         *
         * <p>The maximum rather than the minimum: quests generally take the whole party into the
         * instance and a fuller party is a better test of the bot behaviour, so the spawner fills
         * to the cap. A quest that will not take more than it asked for still gets the minimum,
         * since that is what its eligibility check wants.
         */
        public int botsToOffer(int playerCount) {
            return Math.max(0, maxPlayers - playerCount);
        }
    }

    /**
     * The table, read from each quest's event script. Kept in one place so the spawner and the
     * bots cannot disagree about where a quest recruits.
     */
    public static final List<Point> ALL = List.of(
            new Point("HenesysPQ",    100000200,  10, 255, 3, 6),
            new Point("KerningPQ",    103000000,  21,  30, 3, 4),
            new Point("LudiPQ",       221024500,  35,  50, 5, 6),
            new Point("PiratePQ",     251010404,  55, 100, 3, 6),
            new Point("AmoriaPQ",     670010100,  40, 255, 6, 6),
            new Point("EllinPQ",      300030100,  44,  55, 4, 6),
            // Magatia runs two parallel versions that recruit in different towns: Alcadno and
            // Zenumist. Same mechanics, same bot, different lobby and map range, so both towns
            // need a row or the Zenumist one stands empty.
            new Point("MagatiaPQ",    261000021,  71,  85, 4, 4),
            new Point("MagatiaPQ_Z",  261000011,  71,  85, 4, 4),
            new Point("ZakumPQ",      211042300,  50, 255, 1, 6),
            new Point("HorntailPQ",   240050000, 120, 255, 6, 6),
            new Point("BossRushPQ",   970030000,   1, 255, 1, 6),
            // Duarte's menu map (the Pyramid Dunes), where Nett's Pyramid is applied for. The
            // party version needs two members standing here, so a bot has to be one of them.
            // Level band 40-60 is Duarte's own; the spawn's low-third draw leaves bots 40-49.
            new Point("Pyramid",      926010000,  40,  60, 2, 6),
            // Mu Lung Dojo's hall, where the master takes a party. Its rule is a 30-LEVEL SPREAD,
            // not an absolute band, so the numbers here only bound the spawn: the low-third draw
            // keeps the bots within ten levels of each other, and a player within the spread can
            // take them. (A player far outside the bots' levels simply cannot use them - the
            // master refuses a too-broad party - which is the quest's own rule, not a bot fault.)
            new Point("Dojo",         925020001,  25,  55, 2, 5));

    /**
     * The Orbis point, kept apart because its bot predates this table and is spawned by its own
     * long-standing path. Listed here anyway so a caller asking "where does everything recruit"
     * gets one answer rather than having to know about the exception.
     */
    public static final Point ORBIS = new Point("OrbisPQ", 200080101, 51, 70, 5, 6);

    public static Point byName(String name) {
        for (Point point : ALL) {
            if (point.name().equals(name)) {
                return point;
            }
        }
        return "OrbisPQ".equals(name) ? ORBIS : null;
    }

    /**
     * The points that share a recruit map, so a spawner can place bots for several quests in one
     * trip instead of walking the same town repeatedly.
     */
    public static List<Point> sharingMap(int mapId) {
        List<Point> shared = new ArrayList<>();
        for (Point point : ALL) {
            if (point.recruitMap() == mapId) {
                shared.add(point);
            }
        }
        if (ORBIS.recruitMap() == mapId) {
            shared.add(ORBIS);
        }
        return shared;
    }

    /**
     * A level that satisfies every quest recruiting on this map, or -1 when no single level can.
     *
     * <p>Only useful where several quests share a lobby; where none do, any level in the one
     * quest's band will do.
     */
    public static int sharedLevelBandLow(int mapId) {
        int low = Integer.MIN_VALUE;
        for (Point point : sharingMap(mapId)) {
            low = Math.max(low, point.minLevel());
        }
        return low == Integer.MIN_VALUE ? -1 : low;
    }

    /** The top of that shared band, or -1 when nothing recruits here. */
    public static int sharedLevelBandHigh(int mapId) {
        int high = Integer.MAX_VALUE;
        for (Point point : sharingMap(mapId)) {
            high = Math.min(high, point.maxLevel());
        }
        return high == Integer.MAX_VALUE ? -1 : high;
    }
}
