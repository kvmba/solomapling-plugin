package soloMapling.ArtificialPlayer.BotMedalSystem;

import org.gms.client.Character;
import soloMapling.ArtificialPlayer.BotMedalSystem.BotMedalPool.Medal;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Decides whether a bot wears a 称号 (medal) and, if so, which one — as a set of
 * pure functions so the distribution and the legality of the pick are unit-testable
 * without a live server.
 *
 * <p><b>Wear probability</b> rises with level from {@link #MIN_LEVEL} (10) to a
 * {@value #P_MAX} cap, reached at {@link #L_CAP}. Bots in this server only span levels
 * {@code 10..80} (see {@code BotDecorate.generateBotLevel}), so the cap is reached
 * early and roughly {@value #P_MAX} of the population wears a title — "about 60%",
 * the product brief.
 *
 * <p><b>Value tiering</b>: medals are split by their WZ-derived {@link
 * BotMedalPool.Medal#value} into <i>low</i> / <i>mid</i> / <i>high</i>. A draw is
 * weighted {@code 6:3:1} across those tiers, so the plain titles dominate (~60% of
 * everything handed out) while the fancier ones stay rare — "配发低价值为主".
 */
public final class BotMedalAssigner {

    /** Below this level no bot wears a title (newcomers start anonymous). */
    public static final int MIN_LEVEL = 10;

    /** Ceiling on the share of the population wearing a title. */
    public static final double P_MAX = 0.65;

    /** Level at which the wear probability reaches {@link #P_MAX}. */
    public static final int L_CAP = 20;

    /** Tier draw weights: low : mid : high value. */
    private static final double W_LOW = 6.0;
    private static final double W_MID = 3.0;
    private static final double W_HIGH = 1.0;

    /** Within a tier, lower ids (older/classic titles) are slightly favoured. */
    private static final double ID_DECAY = 0.0015;

    private BotMedalAssigner() {
    }

    /** Share of bots at {@code level} that wear a title; 0 below {@link #MIN_LEVEL}, capped at {@link #P_MAX}. */
    public static double wearChance(int level) {
        if (level < MIN_LEVEL) {
            return 0.0;
        }
        double t = (double) (level - MIN_LEVEL) / (L_CAP - MIN_LEVEL);
        return Math.min(P_MAX, P_MAX * Math.max(0.0, Math.min(1.0, t)));
    }

    /**
     * Pick a legal, wearable medal for the bot, or null when it wears none this
     * decoration pass. Uses the live {@link BotMedalPool} and thread-local randomness.
     */
    public static Integer pick(Character bot) {
        List<Medal> eligible = BotMedalPool.eligibleFor(bot);
        Medal medal = pickFrom(eligible, bot.getLevel(), ThreadLocalRandom.current());
        return medal == null ? null : medal.id;
    }

    /**
     * Pure core: choose one medal from an already level/job-filtered pool, or null if
     * the pool is empty. Weighted {@code 6:3:1} across the low/mid/high value tiers
     * (missing tiers drop out and the rest renormalise), with a low-id bias inside the
     * chosen tier.
     *
     * @param level unused for the draw (kept so callers/tests share one signature)
     */
    static Medal pickFrom(List<Medal> eligible, int level, Random rng) {
        if (eligible == null || eligible.isEmpty()) {
            return null;
        }

        List<Medal> low = new ArrayList<>();
        List<Medal> mid = new ArrayList<>();
        List<Medal> high = new ArrayList<>();
        for (Medal m : eligible) {
            if (m.value < BotMedalPool.VALUE_LOW_MAX) {
                low.add(m);
            } else if (m.value < BotMedalPool.VALUE_MID_MAX) {
                mid.add(m);
            } else {
                high.add(m);
            }
        }

        List<Medal> tier = pickTier(low, mid, high, rng);
        if (tier.isEmpty()) {
            tier = eligible; // no tier matched (shouldn't happen) → use whatever is eligible
        }
        return weightedById(tier, rng);
    }

    /** Weighted 6:3:1 draw across the non-empty value tiers; falls back to {@code low}. */
    private static List<Medal> pickTier(List<Medal> low, List<Medal> mid, List<Medal> high, Random rng) {
        double total = 0.0;
        if (!low.isEmpty()) total += W_LOW;
        if (!mid.isEmpty()) total += W_MID;
        if (!high.isEmpty()) total += W_HIGH;
        if (total <= 0.0) {
            return low;
        }
        double roll = rng.nextDouble() * total;
        if (!low.isEmpty()) {
            roll -= W_LOW;
            if (roll <= 0.0) return low;
        }
        if (!mid.isEmpty()) {
            roll -= W_MID;
            if (roll <= 0.0) return mid;
        }
        return high.isEmpty() ? mid : high;
    }

    /** Weighted draw favouring lower ids so classic titles recur. */
    private static Medal weightedById(List<Medal> tier, Random rng) {
        if (tier.size() == 1) {
            return tier.get(0);
        }
        int firstId = tier.get(0).id;
        double total = 0.0;
        double[] weights = new double[tier.size()];
        for (int i = 0; i < tier.size(); i++) {
            double w = 1.0 / (1.0 + (tier.get(i).id - firstId) * ID_DECAY);
            weights[i] = w;
            total += w;
        }
        double roll = rng.nextDouble() * total;
        double acc = 0.0;
        for (int i = 0; i < tier.size(); i++) {
            acc += weights[i];
            if (roll <= acc) {
                return tier.get(i);
            }
        }
        return tier.get(tier.size() - 1);
    }
}
