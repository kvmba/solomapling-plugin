package soloMapling.ArtificialPlayer.BotTypes.Carnival;

import java.util.List;

/**
 * Monster Carnival (CPQ) as the engine lays it out, for a bot playing the opposing team.
 *
 * <p>This is the one content type here that is not cooperative. Two parties are pitted against
 * each other ({@code p1.setEnemy(p2)}), each summons monsters into the shared arena to earn
 * Carnival Points, and the side with more wins. A bot "playing" it is therefore not helping a
 * player - it is standing in for the other team, and the useful thing for it to do is fight
 * like a team of its own level would.
 *
 * <p>The two versions differ only in level band and in which maps they use: CPQ1 for levels
 * 30-50 across {@code 9800001xx}, CPQ2 for 51-70 across {@code 980030xxx} and
 * {@code 980031-33xxx}. Both are recognised by the engine's own predicates, which is what the
 * bot uses rather than a list of its own.
 *
 * <p>Sources:
 * <ul>
 *   <li>{@code org/gms/server/partyquest/MonsterCarnival.java} - the run itself, the enemy
 *       pairing, the red/blue split and the CP counters</li>
 *   <li>{@code org/gms/server/partyquest/MonsterCarnivalParty.java} - {@code summons}, the
 *       eight monsters each side may call, and {@code canSummon()}</li>
 *   <li>{@code org/gms/server/maps/MapleMap.java} - {@code isCPQMap()} / {@code isCPQMap2()} /
 *       {@code isCPQLobby()}, which the bot reuses instead of hard-coding map ids</li>
 *   <li>{@code Character.getCP()} / {@code gainCP(int)} - the score, awarded by the engine on
 *       each monster killed</li>
 * </ul>
 */
public final class CarnivalPqData {

    private CarnivalPqData() {
    }

    /** Levels the two versions accept, from {@code NPCConversationManager.isCPQParty}. */
    public static final int CPQ1_MIN_LEVEL = 30;
    public static final int CPQ1_MAX_LEVEL = 50;
    public static final int CPQ2_MIN_LEVEL = 51;
    public static final int CPQ2_MAX_LEVEL = 70;

    /** Each side may call eight monsters over a run. */
    public static final int SUMMONS_PER_RUN = 8;

    /** A run lasts ten minutes; the engine extends it by five for each tie. */
    public static final long RUN_MILLIS = 10 * 60 * 1000;

    /** Where the party waits before a match, and where both sides are sent afterwards. */
    public static final int LOBBY_MAP = 980000000;
    public static final int LOBBY_MAP_CPQ2 = 980030000;

    /**
     * Whether a map is an arena rather than a lobby, using the engine's own test so a map
     * added to either version later is picked up without an edit here.
     */
    public static boolean isArena(org.gms.server.maps.MapleMap map) {
        return map != null && (map.isCPQMap() || map.isCPQMap2());
    }

    /** Whether a map is one of the pre-match lobbies. */
    public static boolean isLobby(org.gms.server.maps.MapleMap map) {
        return map != null && map.isCPQLobby();
    }

    /** Which lobby a party of this level would be sent to. */
    public static int lobbyForLevel(int level) {
        return level >= CPQ2_MIN_LEVEL ? LOBBY_MAP_CPQ2 : LOBBY_MAP;
    }

    /** Whether a level can enter either version at all. */
    public static boolean levelEligible(int level) {
        return (level >= CPQ1_MIN_LEVEL && level <= CPQ1_MAX_LEVEL)
                || (level >= CPQ2_MIN_LEVEL && level <= CPQ2_MAX_LEVEL);
    }

    /**
     * The monsters a side may call, and what each costs in Carnival Points.
     *
     * <p>Not a fixed list: the arena map declares its own, WZ-side
     * ({@code MapFactory} reads {@code mobGenMax} and the per-mob {@code spendCP} from the
     * map), and the client's summon request is an index into that list. So the bot asks the
     * map rather than carrying ids it would have to keep in step.
     */
    public static List<org.gms.util.Pair<Integer, Integer>> summonableOn(
            org.gms.server.maps.MapleMap map) {
        return map == null ? List.of() : map.getMobsToSpawn();
    }

    /**
     * The debuff a side may cast, likewise declared by the map rather than known here.
     */
    public static List<Integer> debuffsOn(org.gms.server.maps.MapleMap map) {
        return map == null ? List.of() : map.getSkillIds();
    }

    /**
     * Whether a summon is affordable and still allowed.
     *
     * <p>The engine checks the same two things in {@code MonsterCarnivalHandler} - CP against
     * the entry's cost, and the side's remaining summon allowance - so a bot that asks
     * anything else will send a request the server rejects.
     */
    public static boolean canSummon(int availableCp, int cost, boolean sideHasSummonsLeft) {
        return sideHasSummonsLeft && cost > 0 && availableCp >= cost;
    }
}
