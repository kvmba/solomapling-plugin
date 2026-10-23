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

    // =========================================================================
    // The room: what "cleared" means, and what raises the boss
    // =========================================================================

    /** {@code dojang_1st.js}: every boss revives into this transparent marker on death. */
    public static final int CLEAR_MARKER = org.gms.constants.id.MobId.TRANSPARENT_ITEM;

    /** {@code dojang_1st.js}: {@code getMonsterLifeFactory(9300183 + realstage)}. */
    private static final int FIRST_BOSS = 9300183;

    /** {@code dojang_1st.js} stops at 38; {@code MapId.isPartyDojo} accepts up to the same. */
    public static final int LAST_STAGE = 38;

    /** {@code dojang_1st.js}: {@code stage = Math.floor(mapId / 100) % 100}. */
    public static int stageOf(int mapId) {
        return (mapId / 100) % 100;
    }

    /**
     * Whether this room is a rest floor ({@code dojang_1st.js}: {@code stage % 6 == 0}).
     *
     * <p>A rest floor has no boss by design - the script returns before spawning anything, and
     * {@code dojang_next} is the door that moves the party up. So an empty rest room is CLEARED,
     * not "waiting for a spawn".
     */
    public static boolean isRestingFloor(int mapId) {
        int stage = stageOf(mapId);
        return stage > 0 && stage % 6 == 0;
    }

    /**
     * The boss this room raises, or {@code -1} on a rest floor / outside the tower.
     *
     * <p>Verbatim the script's arithmetic: {@code realstage = stage - (stage / 6 | 0)}, so the
     * six-floor cycle skips the rest floor in the mob table ({@code 9300183..9300215}).
     */
    public static int bossIdFor(int mapId) {
        int stage = stageOf(mapId);
        if (stage <= 0 || stage > LAST_STAGE || isRestingFloor(mapId)) {
            return -1;
        }
        return FIRST_BOSS + (stage - stage / 6);
    }

    // =========================================================================
    // The door that actually moves a body
    // =========================================================================

    /**
     * The two script doors every dojo room carries, and how a socketless bot gets through them.
     *
     * <p>{@code out00}/{@code dojang_up} is the player's climb: standing on the room floor it
     * checks for the clear marker, sends {@code DOJO_WARP_UP} (a client-side warp animation) and
     * calls {@code map.setReactorState()} to open the door reactor. The body reaches the top
     * platform through the CLIENT's animation - the server never moves it - so a bot that walks
     * this door only gets the door opened, which is still half of what it needs.
     *
     * <p>{@code out001}/{@code dojang_next} is the server-side door on that top platform: the
     * script checks the door reactor (or an empty room) and warps the body itself
     * ({@code pi.warp(mapId + 100)}); on the floor below a rest floor it warps the whole party
     * standing in the five rooms below. So a bot must (1) open the reactor the way
     * {@code dojang_up} does and (2) put itself on the platform, because the room has no rope,
     * no ladder, and 372px of wall between its floor and that platform - far more than the
     * {@code 555^2 / (2 * 2000) = 77px} a jump clears.
     *
     * <p>Sources: {@code scripts/portal/dojang_up.js}, {@code scripts/portal/dojang_next.js},
     * {@code scripts/map/onFirstUserEnter/dojang_1st.js}, and the portals/footholds of
     * {@code wz/Map.wz/Map/Map9/925020100.img.xml} (which the party rooms {@code 9250301xx}
     * link to).
     */
    public static final String SERVER_DOOR = "out001";

    /** The script that door runs; named so the selection cannot drift from the WZ data. */
    public static final String SERVER_DOOR_SCRIPT = "dojang_next";

    /** The door reactor {@code setReactorState()} opens; {@code dojang_next} reads it by name. */
    public static final String DOOR_REACTOR = "door";

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
