package soloMapling.ArtificialPlayer.BotStatusSystem;

import org.gms.client.Disease;

/*
 * Static "what a mob disease does to a bot" table, plus the behaviour knobs. Pure data + predicates so
 * the mapping is testable without a character or a running server.
 *
 * Bots cannot be hit by the engine's real debuff path: that only fires when the mob's CONTROLLER
 * client sends MOVE_LIFE / TAKE_DAMAGE (Monster.getNextControllerCandidate excludes artificial
 * players, and a headless BotClient has no socket). So the plugin models the effect itself - this
 * table says which diseases freeze / disarm / slow / weaken / blind / poison, and by how much.
 *
 * Only the diseases a bot visibly reacts to are modelled here (STUN / SEDUCE / SEAL / SLOW / WEAKEN /
 * DARKNESS / POISON). Any other disease the plugin shows (CURSE, ...) is carried purely as a foreign
 * packet and has no behaviour - see BotDebuffState.
 */
public final class BotDebuffTable {

    private BotDebuffTable() {
    }

    // SLOW: movement speed factor while active (0.5 = walk half speed). Applied as a force scale in
    // the ground physics, so the equilibrium walk speed scales by roughly this factor.
    public static final double SLOW_MOVE_FACTOR = 0.55;

    // WEAKEN: the bot hits softer and takes more.
    public static final double WEAKEN_OUT_FACTOR = 0.60;   // damage dealt multiplier
    public static final double WEAKEN_TAKEN_FACTOR = 1.35; // contact damage taken multiplier

    // DARKNESS: chance a swing whiffs (all damage lines 0 -> the viewer sees a MISS).
    public static final double DARKNESS_MISS_CHANCE = 0.90;

    // POISON: one tick of this fraction of max HP, every POISON_TICK_MS, never below the health floor.
    public static final double POISON_MAX_HP_FRACTION = 0.04;
    public static final long POISON_TICK_MS = 3_000L;

    // How long a bot-side disease lasts when the engine skill has no resolvable duration.
    public static final long DEFAULT_DURATION_MS = 5_000L;

    // Mirrors Character.giveDebuff's cap: a bot carries at most this many diseases at once.
    public static final int MAX_ACTIVE = 2;

    /** Whether the disease itself is one a bot can suffer (used to accept or reject an apply). */
    public static boolean isDebuff(Disease d) {
        return d != null && d != Disease.NULL;
    }

    /** FROZEN (STUN / SEDUCE): the bot cannot move and cannot attack. */
    public static boolean freezes(Disease d) {
        return d == Disease.STUN || d == Disease.SEDUCE;
    }

    /** SEALED (SEAL): the bot cannot attack, but may still walk. */
    public static boolean seals(Disease d) {
        return d == Disease.SEAL;
    }

    /** Any disease that disarms the bot (frozen also disarms). */
    public static boolean blocksAttack(Disease d) {
        return freezes(d) || seals(d);
    }

    /** SLOW: walk speed is scaled down. */
    public static boolean slows(Disease d) {
        return d == Disease.SLOW;
    }

    /** WEAKEN: dealt damage down, taken damage up. */
    public static boolean weakens(Disease d) {
        return d == Disease.WEAKEN;
    }

    /** DARKNESS: swings may whiff. */
    public static boolean blinds(Disease d) {
        return d == Disease.DARKNESS;
    }

    /** POISON: periodic HP loss. */
    public static boolean poisons(Disease d) {
        return d == Disease.POISON;
    }
}
