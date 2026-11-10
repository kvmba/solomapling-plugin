package soloMapling.ArtificialPlayer.BotTypes.Ariant;

/**
 * Ariant Coliseum as the engine actually implements it.
 *
 * <p>Not a boss rush and not a puzzle: it is a catching game. The arena spawns scorpions, a
 * player wears each one down and then uses an Element Rock on it, and a successful catch turns
 * into a Spirit Jewel in the inventory - which is the score. The arena reads the score off the
 * inventory rather than from a kill count, so a party that kills everything and picks nothing
 * up scores zero.
 *
 * <p>That inversion is worth stating plainly because it is the opposite of every other quest
 * here: the bot's job is not to finish monsters but to <em>stop</em> at the right moment. The
 * catch is only offered below 40% health and succeeds half the time, so a bot that fights the
 * way it does elsewhere would kill the scorpions it was supposed to catch.
 *
 * <p>One structural difference from every other quest bot here: Ariant is an
 * <em>expedition</em>, not a party, and the engine refuses to start a match while any
 * competitor is in a party ({@code NPCConversationManager.startAriantBattle}). So a bot can
 * never be recruited the usual way - it enrols in the player's expedition instead
 * ({@link AriantExpedition}) and is warped into the arena with the team.
 *
 * <p>Sources:
 * <ul>
 *   <li>{@code org/gms/net/server/channel/handlers/UseCatchItemHandler.java} - the catch:
 *       {@code mob.getHp() < (maxHp/10)*4}, a 50% roll, one Element Rock spent per attempt, and
 *       {@code updateAriantScore()} on success</li>
 *   <li>{@code org/gms/client/Character.updateAriantScore} - the score is
 *       {@code countItem(ARPQ_SPIRIT_JEWEL)}, read from the inventory</li>
 *   <li>{@code org/gms/server/partyquest/AriantColiseum.java} - the ten-minute clock and the
 *       reward tiers</li>
 *   <li>{@code scripts/npc/2101014.js} - the lobby that creates an arena expedition</li>
 *   <li>{@code scripts/npc/2101017.js} - the queue hall that starts the match, and the arena
 *       NPC that hands out the rocks and bombs</li>
 *   <li>{@code ExpeditionType.ARIANT} - 2 to 7 members, levels 20 to 30</li>
 * </ul>
 */
public final class AriantPqData {

    private AriantPqData() {
    }

    public static final String EXPEDITION_TYPE = "ARIANT";

    /** The expedition's own limits. */
    public static final int MIN_MEMBERS = 2;
    public static final int MAX_MEMBERS = 7;
    public static final int MIN_LEVEL = 20;
    public static final int MAX_LEVEL = 30;

    /** The run is ten minutes. */
    public static final long RUN_MINUTES = 10;

    // =========================================================================
    // Maps
    // =========================================================================

    /** The public lobby (Cesar's room), where an arena expedition is created. */
    public static final int LOBBY_MAP = 980010000;

    /**
     * The three queue halls the created expeditions wait in: 980010100 / 980010200 /
     * 980010300, one per arena edition. The NPC that starts a match stands only in these.
     */
    public static final int QUEUE_FIRST = 980010100;

    /** The item that scores, and the item spent to catch. */
    public static final int SPIRIT_JEWEL = 4031868;
    public static final int ELEMENT_ROCK = 2270002;

    /**
     * What the arena's own NPC gives a competitor on arrival ({@code 2101017.js}: fifty Element
     * Rocks and five bombs). A bot cannot run that conversation and without the rocks it could
     * never score, so the rocks are granted once per run (see {@code AriantPQBot}). The bombs are
     * not granted: the bot never throws one, so conjuring them would be set dressing.
     */
    public static final int SUPPLY_ROCKS = 50;

    /** The monster the arena spawns to be caught. */
    public static final int SCORPION = 9300157;

    /**
     * The health fraction below which a catch is offered, as the handler computes it:
     * {@code hp < (maxHp / 10) * 4}, i.e. forty percent - with integer division, so it is
     * slightly under forty for a max health that does not divide evenly. Kept as the same
     * expression rather than a tidy 0.4 so the bot and the server agree at the boundary.
     *
     * <p>The positivity guard is the caller's addition, not the handler's: the handler only
     * ever sees a live monster, while a bot iterating the map's list will also see any whose
     * health has already been driven to zero or below by an earlier swing, and those are the
     * opposite of catchable.
     */
    public static boolean catchableAt(int currentHp, int maxHp) {
        return maxHp > 0 && currentHp > 0 && currentHp < (maxHp / 10) * 4;
    }

    /** Whether a level may enter. */
    public static boolean levelEligible(int level) {
        return level >= MIN_LEVEL && level <= MAX_LEVEL;
    }

    /** The arena, per the engine's own test. */
    public static boolean isArena(int mapId) {
        return org.gms.constants.game.GameConstants.isAriantColiseumArena(mapId);
    }

    /** The lobby family, per the engine's own test. */
    public static boolean isLobby(int mapId) {
        return org.gms.constants.game.GameConstants.isAriantColiseumLobby(mapId);
    }
    /**
     * The queue halls (9800101xx / 9800102xx / 9800103xx), which the engine also calls
     * "lobbies". They are part of a run's build-up rather than a recruiting room: an
     * expedition waits there for its last member before the match starts, so a bot enrolled in
     * one must stay put instead of walking back to {@link #LOBBY_MAP}.
     *
     * <p>Two other maps of this family are deliberately excluded: the public lobby itself, and
     * the kings room the arena warps the competitors to when the clock expires (980010010). The
     * kings room is where a run <em>ends</em>; treating it as a queue hall would keep a finished
     * bot standing in a closed expedition instead of walking home.
     */
    public static boolean isQueueLobby(int mapId) {
        return mapId >= QUEUE_FIRST && mapId <= QUEUE_FIRST + 200 && mapId % 100 == 0;
    }

    /** The queue hall for arena edition {@code index} (0..2), or -1 outside that range. */
    public static int queueMapFor(int index) {
        return index >= 0 && index < 3 ? QUEUE_FIRST + 100 * index : -1;
    }

    /** That edition's index, or -1 when the map is not a queue hall. */
    public static int queueIndex(int mapId) {
        return isQueueLobby(mapId) ? (mapId - QUEUE_FIRST) / 100 : -1;
    }

    /**
     * That edition's index for an arena map, or -1 when it is not one of the three arenas.
     *
     * <p>The arena ids are the queue ids + 1: 980010101 / 980010201 / 980010301. Written from
     * the id arithmetic rather than through {@link #isArena} so this stays a pure function: the
     * engine's own test lives in {@code GameConstants}, whose static initializer reads WZ, and
     * the lobby tests must not need a WZ tree to ask which arena a bot is in.
     */
    public static int arenaEdition(int mapId) {
        int queue = mapId - 1;
        return isQueueLobby(queue) ? queueIndex(queue) : -1;
    }

    public static boolean isAriantMap(int mapId) {
        return isArena(mapId) || isLobby(mapId);
    }
}
