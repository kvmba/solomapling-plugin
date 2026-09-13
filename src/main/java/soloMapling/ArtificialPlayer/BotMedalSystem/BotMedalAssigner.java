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
 * {@value #P_MAX} cap, reached at {@link #L_CAP}: a bot just off Maple Island wears
 * nothing, a veteran has a one-in-two chance of a title. The bot's level is already
 * final when this runs ({@code BotDecorate.setBotVariables} calls it after
 * {@code BotFame.apply}).
 *
 * <p><b>Tiering</b>: the eligible pool splits into a <i>basic</i> tier
 * ({@code reqLevel == 0}, wear-anywhere titles) and an <i>advanced</i> tier
 * ({@code reqLevel > 0}, titles unlock as the bot levels). The chance of drawing from
 * the advanced tier climbs with level, so low-level bots get plain titles and
 * high-level bots get the fancier ones.
 */
public final class BotMedalAssigner {

    /** Below this level no bot wears a title (newcomers start anonymous). */
    public static final int MIN_LEVEL = 10;

    /** Ceiling on the share of the population wearing a title. */
    public static final double P_MAX = 0.50;

    /** Level at which the wear probability reaches {@link #P_MAX}. */
    public static final int L_CAP = 130;

    /** Advanced (reqLevel > 0) tier only enters play from this level. */
    public static final int ADV_MIN_LEVEL = 30;

    /** Advanced-tier chance floor / ceiling (scaled between {@link #ADV_MIN_LEVEL} and {@link #L_CAP}). */
    private static final double ADV_MIN = 0.05;
    private static final double ADV_MAX = 0.70;

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
        return Math.min(P_MAX, P_MAX * Math.max(0.0, t));
    }

    /** Probability of drawing from the advanced tier at {@code level}. */
    static double advancedChance(int level) {
        if (level < ADV_MIN_LEVEL) {
            return 0.0;
        }
        double t = (double) (level - ADV_MIN_LEVEL) / (L_CAP - ADV_MIN_LEVEL);
        return Math.min(ADV_MAX, ADV_MIN + (ADV_MAX - ADV_MIN) * Math.max(0.0, t));
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
     * the pool is empty. Tiered by {@code reqLevel} with a low-id bias inside the tier.
     */
    static Medal pickFrom(List<Medal> eligible, int level, Random rng) {
        if (eligible == null || eligible.isEmpty()) {
            return null;
        }

        List<Medal> basic = new ArrayList<>();
        List<Medal> advanced = new ArrayList<>();
        for (Medal m : eligible) {
            (m.reqLevel == 0 ? basic : advanced).add(m);
        }

        List<Medal> tier = (rng.nextDouble() < advancedChance(level) && !advanced.isEmpty())
                ? advanced
                : basic;
        if (tier.isEmpty()) {
            tier = eligible; // requested tier has nothing wearable → use whatever is eligible
        }
        return weightedById(tier, rng);
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
