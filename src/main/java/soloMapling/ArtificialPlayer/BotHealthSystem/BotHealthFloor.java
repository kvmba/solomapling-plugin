package soloMapling.ArtificialPlayer.BotHealthSystem;

/**
 * The HP floor an artificial player can be knocked down to, but not below.
 *
 * <p>Why it exists: contact damage used to clamp a bot at 1 HP. On a 30000-HP pool that
 * renders as a completely empty bar, so a bot that has been under a mob for a minute looks
 * exactly like a corpse — the one thing the death state is supposed to be distinguishable
 * from. 5% leaves a visible sliver of red, which reads as "nearly dead" instead of "dead".
 *
 * <p>Why 5% and not a flat number: the bot pool spans ~50 HP (level 1) to 30000 (cap), so any
 * flat floor is either invisible on a big pool or a large fraction of a small one.
 *
 * <p>The floor is a pure function of the max HP so it can be unit-tested without a character,
 * and so the two places that need it (damage clamping and the lethal-hit check) can never
 * disagree.
 */
public final class BotHealthFloor {

    /** Fraction of max HP a living artificial player keeps as its minimum. */
    public static final double HP_FLOOR_RATIO = 0.05;

    private BotHealthFloor() {
    }

    /**
     * Lowest HP a living bot may be left at on the given pool.
     *
     * <p>Always at least 1: a floor of 0 would let mob contact kill a bot outright, which is
     * the death flow's job, not the damage clamp's.
     */
    public static int floorFor(int maxHp) {
        if (maxHp <= 0) {
            return 1; // pool unknown (stat race) — never amplify a hit into a kill
        }
        return Math.max(1, (int) Math.ceil(maxHp * HP_FLOOR_RATIO));
    }

    /** True while a bot is at or below the floor — i.e. one bad hit from being finished off. */
    public static boolean atFloor(int hp, int maxHp) {
        return hp <= floorFor(maxHp);
    }
}
