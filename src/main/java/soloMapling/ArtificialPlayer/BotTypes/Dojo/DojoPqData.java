package soloMapling.ArtificialPlayer.BotTypes.Dojo;

/**
 * Mu Lung Dojo (the party challenge) as its own scripts define it.
 *
 * <p>An endurance run up a tower of bosses. The party is granted one of five dojo slots for the
 * channel, warped into its rooms, and climbs as far as it can before the clock or the party
 * gives out. There is no puzzle and no turn-in: the work is fighting, room after room, and the
 * score is how far up the party got.
 *
 * <p>Two things about it differ from the quests and are worth knowing before entering. The
 * slots are a shared resource the channel hands out and reclaims, so a bot party occupies one
 * that a player party then cannot have. And the level rule is not a floor and a ceiling but a
 * <em>spread</em>: every member has to be within thirty levels of the others, so a bot outside
 * the player's band blocks the whole party from entering.
 *
 * <p>Sources:
 * <ul>
 *   <li>{@code scripts/npc/2091005.js} - the dojo master: solo or party, the 30-level spread
 *       rule, {@code ingressDojo(true, party, 0)} for a slot, {@code warpParty} into it</li>
 *   <li>{@code org/gms/constants/id/MapId.java} - {@code isPartyDojo} and the map range</li>
 *   <li>{@code org/gms/net/server/channel/Channel.java} - {@code ingressDojo} and the five
 *       party slots versus the fifteen solo ones</li>
 * </ul>
 */
public final class DojoPqData {

    private DojoPqData() {
    }

    /** The master who hands out slots. */
    public static final int DOJO_MASTER = 2091005;

    /** The hall where the choice is made. */
    public static final int DOJO_HALL = 925020001;

    /**
     * The rule that decides whether a party may enter at all: no member may be more than this
     * many levels from another. It is a spread, not a range, so a well-levelled bot is still a
     * blocker if it sits outside the player's band.
     */
    public static final int MAX_LEVEL_SPREAD = 30;

    /** The channel hands out five party dojos, and fifteen solo ones. */
    public static final int PARTY_SLOTS = 5;
    public static final int SOLO_SLOTS = 15;

    /**
     * Whether a party of these levels could enter. Reports the spread rather than a boolean so
     * a caller can say why - a party refused for being too broad is a different problem from
     * one refused because the slots are gone.
     */
    public static boolean levelsWithinSpread(java.util.List<Integer> levels) {
        if (levels == null || levels.isEmpty()) {
            return false;
        }
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int level : levels) {
            min = Math.min(min, level);
            max = Math.max(max, level);
        }
        return max - min <= MAX_LEVEL_SPREAD;
    }

    /** Whether a map is one of the party dojo rooms. */
    public static boolean isPartyDojo(int mapId) {
        return org.gms.constants.id.MapId.isPartyDojo(mapId);
    }

    /**
     * Whether a bot's level would keep a given party from entering - the check worth running
     * before a bot is added rather than after a refused entry.
     */
    public static boolean wouldBlockParty(int botLevel, java.util.List<Integer> partyLevels) {
        java.util.ArrayList<Integer> withBot = new java.util.ArrayList<>(partyLevels);
        withBot.add(botLevel);
        return !levelsWithinSpread(withBot);
    }
}
