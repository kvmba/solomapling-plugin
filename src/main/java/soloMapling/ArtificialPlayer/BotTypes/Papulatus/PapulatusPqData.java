package soloMapling.ArtificialPlayer.BotTypes.Papulatus;

/**
 * PapulatusBattle ("Vs Papulatus") as its own scripts define it.
 *
 * <p>The simplest shape of the boss events: the party is warped into one map, the leader drops
 * a Piece of Cracked Dimension on the Time Sphere to summon the boss, and the run ends when the
 * final form falls. There are no rooms, no portals to walk and no turn-in - just damage until
 * the revive chain runs out.
 *
 * <p>Sources:
 * <ul>
 *   <li>{@code scripts/event/PapulatusBattle.js} - {@code minPlayers 1}, {@code maxPlayers 6},
 *       levels 1-255, {@code recruitMap 220080000}, {@code entryMap 220080001}, 45 minutes,
 *       {@code maxLobbies 1}; the clear check {@code isPapulatus} accepts only {@code 8500002}</li>
 *   <li>{@code scripts/portal/Populatus00.js} - the lobby's {@code in00} gate admits the LEADER
 *       only; {@code startInstance} then warps every eligible member in via {@code playerEntry},
 *       so a bot never walks a door at any point of this run</li>
 *   <li>{@code scripts/reactor/2201004.js} + {@code wz/Reactor.wz/2201004.img.xml} - the summon:
 *       item trigger 100, one {@code 4031179} inside the sphere's trigger box spawns
 *       {@code 8500000}</li>
 *   <li>{@code wz/Mob.wz/8500000/1/2.img.xml} revive sections - the three-stage chain
 *       {@code 8500000 → 8500001 → 8500002} the engine replays on each death</li>
 *   <li>{@code scripts/npc/2041025.js} - the in-map Machine, the only way OUT: it warps its
 *       talker to {@code 220080000} one body at a time (the event never warps the team out)</li>
 *   <li>{@code wz/Quest.wz/Check.img.xml} quest 7103 - {@code lvmin 80}, the gate to the piece
 *       that makes the summon possible, and the floor this lobby's level band is drawn from</li>
 * </ul>
 */
public final class PapulatusPqData {

    private PapulatusPqData() {
    }

    /** The lobby the party assembles in, and the map every run returns to. */
    public static final int LOBBY_MAP = 220080000; // 时间塔的隐秘场所 (Deep Inside the Clocktower)

    /** The one map the whole fight plays in; also the event's {@code minMapId}/{@code maxMapId}. */
    public static final int BOSS_MAP = 220080001; // 时间塔的本源 (Origin of Clocktower)

    /** The quest allows a solo attempt, which is why its floor is one. */
    public static final int MIN_PLAYERS = 1;
    public static final int MAX_PLAYERS = 6;

    /** The lobby's level band: 7103's own floor (the summon piece's gate) up to a plausible ceiling. */
    public static final int MIN_LEVEL = 80;
    public static final int MAX_LEVEL = 160;

    /** The summon piece (裂开的次元碎片). The LEADER drops it; a bot can neither hold nor drop one. */
    public static final int SUMMON_ITEM = 4031179;

    /** The Time Sphere, whose item trigger spawns the first form. */
    public static final int SUMMON_REACTOR = 2201004;

    /** The in-map Machine: the only exit, and it serves one talker at a time. */
    public static final int EXIT_NPC = 2041025;

    /** Whether a map is inside the run. The single fight map is the whole of it. */
    public static boolean isQuestRoom(int mapId) {
        return mapId == BOSS_MAP;
    }
}
