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
 * <p>Sources:
 * <ul>
 *   <li>{@code org/gms/net/server/channel/handlers/UseCatchItemHandler.java} - the catch:
 *       {@code mob.getHp() < (maxHp/10)*4}, a 50% roll, one Element Rock spent per attempt, and
 *       {@code updateAriantScore()} on success</li>
 *   <li>{@code org/gms/client/Character.updateAriantScore} - the score is
 *       {@code countItem(ARPQ_SPIRIT_JEWEL)}, read from the inventory</li>
 *   <li>{@code org/gms/server/partyquest/AriantColiseum.java} - the ten-minute clock and the
 *       reward tiers</li>
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

    /** The item that scores, and the item spent to catch. */
    public static final int SPIRIT_JEWEL = 4031868;
    public static final int ELEMENT_ROCK = 2270002;

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

    public static boolean isLobby(int mapId) {
        return org.gms.constants.game.GameConstants.isAriantColiseumLobby(mapId);
    }

    public static boolean isAriantMap(int mapId) {
        return isArena(mapId) || isLobby(mapId);
    }
}
