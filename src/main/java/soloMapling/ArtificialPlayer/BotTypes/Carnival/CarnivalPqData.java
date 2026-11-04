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
 *   <li>{@code org/gms/server/maps/MapleMap.java} - {@code isCPQMap()} / {@code isCPQMap2()},
 *       which the bot reuses instead of hard-coding map ids</li>
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

    /**
     * Where the party waits before a match, and where both sides are sent afterwards.
     *
     * <p>Deliberately no "summons per run" or "run length" constant here: both belong to the
     * arena map, which declares its own {@code mobGenMax} and {@code timeDefault}. A copy in the
     * plugin would be a second source of truth that drifts the moment a map is retuned.
     */
    public static final int LOBBY_MAP = 980000000;
    public static final int LOBBY_MAP_CPQ2 = 980030000;

    /**
     * Whether a map is an arena rather than a lobby, using the engine's own test so a map
     * added to either version later is picked up without an edit here.
     */
    public static boolean isArena(org.gms.server.maps.MapleMap map) {
        return map != null && (map.isCPQMap() || map.isCPQMap2());
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
     * The party the bot fills: a full CPQ team, always.
     *
     * <p>Six is the game's own party cap and every arena's {@code maxPlayers}, so a bot team is
     * never the reason a match is refused. Note that the engine does <em>not</em> require the
     * two sides to be the same size - only the NPC script's challenge dialog does - so a full bot
     * team can meet a smaller player party (see {@code CarnivalArena}).
     */
    public static final int BOT_TEAM_SIZE = 6;

    /**
     * How many fields each edition runs.
     *
     * <p>Six for CPQ1 ({@code 2042001.js} lists {@code i < 6}) and three for CPQ2
     * ({@code sendCPQMapLists2} loops {@code i < 3}). Kept apart because a single "six" would
     * have the poll look for three CPQ2 fields that do not ship - opening on those ids would warp
     * a party into a map that is not an arena.
     */
    public static final int FIELD_COUNT = 6;
    public static final int FIELD_COUNT_CPQ2 = 3;

    /** How many fields an edition runs. */
    public static int fieldCount(boolean cpq2) {
        return cpq2 ? FIELD_COUNT_CPQ2 : FIELD_COUNT;
    }

    /** The lobby a field's teams register in: {@code 980000100 + 100*field}. */
    public static int lobbyForField(int field) {
        return 980000100 + 100 * field;
    }

    /** The arena a field's match is played in: the lobby map plus one. */
    public static int arenaForField(int field) {
        return lobbyForField(field) + 1;
    }

    /**
     * The CPQ2 equivalent of {@link #lobbyForField}: {@code 980031000 + 1000*field}.
     *
     * <p>CPQ2's fields are spaced by a thousand, not a hundred (the arena sits at
     * {@code +100}, the winner and loser rooms at {@code +200}/{@code +300}), which is why this
     * is a separate function rather than an offset on the CPQ1 one.
     */
    public static int lobbyForField2(int field) {
        return 980031000 + 1000 * field;
    }

    /** The CPQ2 arena for a field. */
    public static int arenaForField2(int field) {
        return lobbyForField2(field) + 100;
    }

    /** Which field a CPQ lobby map belongs to, or -1 when it is not one. */
    public static int fieldOfLobby(int mapId) {
        if (mapId >= 980000100 && mapId <= 980000600 && (mapId - 980000100) % 100 == 0) {
            return (mapId - 980000100) / 100;
        }
        // CPQ2 ships three fields at 980031xxx / 980032xxx / 980033xxx, a thousand apart.
        if (mapId >= 980031000 && mapId <= 980033000 && (mapId - 980031000) % 1000 == 0) {
            return (mapId - 980031000) / 1000;
        }
        return -1;
    }

    /**
     * Whether a map belongs to the carnival's second edition.
     *
     * <p>Its maps run from the CPQ2 hall ({@code 980030000}) through the third field's loser room
     * ({@code 980033400}); the upper bound is the last map the edition actually ships, not a
     * generous round number, so a future map outside the range is not silently treated as CPQ2.
     */
    public static boolean isSecondEdition(int mapId) {
        return mapId >= 980030000 && mapId <= 980033400;
    }

    /**
     * Whether a map is one of the two post-match rooms.
     *
     * <p>Both editions park the finished teams in a pair of town-like rooms next to the arena: the
     * engine's {@code finish()} uses {@code arena + 1} and {@code arena + 2} (i.e. {@code +2} and
     * {@code +3} off the lobby) for CPQ1, {@code +200}/{@code +300} for CPQ2, while the map
     * predicates name the opposite rooms as "winner"/"loser" - the host disagrees with itself
     * there, so both are accepted and the distinction is never relied on. What matters for a bot is
     * only that it is not still on the field: it reads the result for a beat and walks itself home.
     *
     * <p>Both rooms have a carnival NPC that warps back to the hall, which is why a bot standing
     * in one must not be treated as having quit the run.
     */
    public static boolean isRewardMap(org.gms.server.maps.MapleMap map) {
        if (map == null) {
            return false;
        }
        return map.isCPQWinnerMap() || map.isCPQLoserMap();
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
