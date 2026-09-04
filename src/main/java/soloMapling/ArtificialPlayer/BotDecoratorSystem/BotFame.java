package soloMapling.ArtificialPlayer.BotDecoratorSystem;

import org.gms.client.BotTier;
import org.gms.client.Character;

import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 人气度 (fame) generation for artificial players.
 *
 * <p>Bots used to inherit their internal character id as fame (a debug leftover
 * in {@code BotGeneration}), so every artificial player walked around with a
 * five-digit reputation. This replaces that with a level- and tier-aware roll
 * so the population reads like a real server:
 *
 * <ul>
 *   <li>Beginners sit in the low single digits up to ~20, and can be slightly
 *       negative — nobody has famed them yet.</li>
 *   <li>The ceiling grows with level but never passes {@link #MAX_FAME}, and
 *   only a thin tail of high-level bots ever gets close to it.</li>
 *   <li>A minority carry a negative reputation (fame wars, scamming, defaming
 *   after trades), so negative numbers do show up in the population.</li>
 * </ul>
 *
 * <p>Fame also gates {@code reqPOP} equipment on the host (e.g. the Deputy
 * Star), so keeping low-level bots' fame low is what stops a level 12 from
 * wearing reputation-locked gear.
 */
public final class BotFame {

    /** Hard ceiling - no bot is more famous than this, at any level or tier. */
    public static final int MAX_FAME = 300;

    /** Hard floor - even the most defamed bot stops here. */
    public static final int MIN_FAME = -50;

    /** Share of the population carrying a negative reputation. */
    private static final double NEGATIVE_RATE = 0.15;

    /**
     * Exponent applied to the positive roll. Anything above 1 pushes the mass
     * toward zero, so most bots carry a handful of fame points and only a few
     * approach the ceiling - the long tail real servers show, rather than a flat
     * spread where every high-level bot looks equally famous.
     */
    private static final double POSITIVE_SKEW = 1.5;

    /** Positive fame ceiling for a level 1 bot (the "just left Maple Island" crowd). */
    private static final int POS_CAP_LV1 = 20;
    /**
     * Positive fame ceiling for a level 200 bot at B tier. Tiers scale this
     * up/down, and the hardest scaling tier is what lands on {@link #MAX_FAME}.
     */
    private static final int POS_CAP_LV200 = 240;

    /** Negative fame floor at level 1. */
    private static final int NEG_CAP_LV1 = -10;
    /** Negative fame floor at level 200. */
    private static final int NEG_CAP_LV200 = -40;

    private static final int MAX_LEVEL = 200;

    private BotFame() {
    }

    /**
     * Rolls a fame value for a bot of the given level and tier.
     *
     * @param level the bot's level (clamped to 1..200)
     * @param tier  the bot's decoration tier; null falls back to the default tier
     * @return a fame value within [{@link #MIN_FAME}, {@link #MAX_FAME}]
     */
    public static int generate(int level, BotTier tier) {
        return generate(level, tier, ThreadLocalRandom.current());
    }

    /** Visible for testing: same roll, injectable randomness. */
    static int generate(int level, BotTier tier, Random rng) {
        int lv = Math.min(Math.max(level, 1), MAX_LEVEL);
        double t = (lv - 1.0) / (MAX_LEVEL - 1.0);   // 0 at level 1, 1 at level 200

        // Fame is much harder to accumulate than levels are: reaching level 100
        // is a fraction of the grind to 200, but a level 100 character has done
        // a fraction of a fraction of the social grind. Squaring the progress
        // keeps the mid-levels modest (a level 50 bot caps in the 30s) while
        // still arriving at the full ceiling at the top end.
        double growth = t * t;

        double scale = tierScale(tier);
        if (rng.nextDouble() < NEGATIVE_RATE) {
            double cap = (NEG_CAP_LV1 + (NEG_CAP_LV200 - NEG_CAP_LV1) * growth) * scale;
            // Linear spread: getting defamed costs a point or two at a time, so
            // unlike the positive side there's no reason to crowd the shallow end.
            return clamp(-(int) Math.round(Math.abs(cap) * rng.nextDouble()));
        }
        double cap = (POS_CAP_LV1 + (POS_CAP_LV200 - POS_CAP_LV1) * growth) * scale;
        return clamp((int) Math.round(cap * Math.pow(rng.nextDouble(), POSITIVE_SKEW)));
    }

    /**
     * Rolls and applies a fame value, replacing whatever the template character
     * carried. Call after the bot's level and tier are set - both feed the roll.
     * Safe to call repeatedly (e.g. after a level override); each call re-rolls.
     */
    public static void apply(Character bot) {
        bot.setFame(generate(bot.getLevel(), bot.getTier()));
    }

    /** Elite bots are known names; the bottom tier is anonymous. */
    private static double tierScale(BotTier tier) {
        return switch (tier == null ? BotTier.C : tier) {
            case S -> 1.35;
            case A -> 1.15;
            case B -> 1.00;
            case C -> 0.85;
            case D -> 0.65;
        };
    }

    private static int clamp(int fame) {
        return Math.min(Math.max(fame, MIN_FAME), MAX_FAME);
    }
}
