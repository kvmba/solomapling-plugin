package soloMapling.ArtificialPlayer.BotPetSystem;

import org.gms.client.BotTier;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Pure policy: given a bot's strength, decide whether it carries pets and how
 * many. No host types, no I/O — so the whole probability curve is unit-testable.
 *
 * <p>Strength blends level (dominant) with the bot's performance tier, so a
 * level-100 D-tier bot carries less often than a level-100 S-tier one. The
 * carry chance rises with strength and caps at {@code pMax} (30% by default);
 * the count distribution shifts toward 2–3 pets as strength grows.</p>
 */
public final class BotPetAssigner {

    // Closeness / fullness roll bounds (see rollTameness).
    static final int TAMENESS_MIN = 0;
    static final int TAMENESS_MAX = 300;
    private static final double TAMENESS_LEVEL_CAP = 120.0;
    private static final double TAMENESS_LEVEL_WEIGHT = 0.7;
    private static final double TAMENESS_STRENGTH_WEIGHT = 0.3;
    /** Spread around the level-based midpoint: a weak bot can still have a dear pet. */
    private static final int TAMENESS_SPREAD = 60;
    static final int FULLNESS_MIN = 60;
    static final int FULLNESS_MAX = 100;

    private BotPetAssigner() {
    }

    /**
     * @param level  the bot's level
     * @param tier   the bot's performance tier (may be null -> treated as the lowest)
     * @param pool   pet ids to draw from (already validated as pets)
     * @param config tuning
     * @param rng    randomness (injectable for tests)
     * @return 0..3 {@link PetSpec}s, distinct item ids
     */
    public static List<PetSpec> assign(
            int level,
            BotTier tier,
            List<Integer> pool,
            BotPetConfig config,
            Random rng) {

        if (!config.enabled() || pool == null || pool.isEmpty() || level < config.minLevel()) {
            return List.of();
        }

        double strength = strength(level, tier, config);
        double p = config.pMax() * Math.pow(strength, config.exponent());
        if (p <= 0 || rng.nextDouble() >= Math.min(p, config.pMax())) {
            return List.of();
        }

        int count = rollCount(strength, config, rng);
        List<Integer> ids = drawDistinct(pool, count, rng);

        List<PetSpec> specs = new ArrayList<>(ids.size());
        for (int itemId : ids) {
            int petLevel = petLevel(strength, config);
            int tameness = rollTameness(level, strength, rng);
            int fullness = FULLNESS_MIN + rng.nextInt(FULLNESS_MAX - FULLNESS_MIN + 1);
            boolean named = rng.nextDouble() < config.namingChance();
            boolean pickupItem = rng.nextDouble() < config.itemPouchChance();
            boolean pickupMeso = rng.nextDouble() < config.mesoMagnetChance();
            specs.add(new PetSpec(itemId, petLevel, tameness, fullness,
                    named, pickupItem, pickupMeso));
        }
        return specs;
    }

    /**
     * Strength in [0, 1]: {@code (1 - w) * level/levelCap + w * tierScore}, where
     * {@code tierScore} maps D..S to 0.0..1.0. Level is clamped to the cap.
     */
    public static double strength(int level, BotTier tier, BotPetConfig config) {
        double levelPart = config.levelCap() <= 0
                ? 1.0
                : Math.min(1.0, (double) level / config.levelCap());
        double tierPart = tierScore(tier);
        double w = config.tierWeight();
        return (1.0 - w) * levelPart + w * tierPart;
    }

    /** D=0.0, C=0.25, B=0.5, A=0.75, S=1.0; null/unknown -> 0.0. */
    static double tierScore(BotTier tier) {
        if (tier == null) {
            return 0.0;
        }
        return switch (tier) {
            case S -> 1.0;
            case A -> 0.75;
            case B -> 0.5;
            case C -> 0.25;
            case D -> 0.0;
        };
    }

    /** Weighted draw of 1..3 from the band whose {@code sMax} exceeds the strength. */
    static int rollCount(double strength, BotPetConfig config, Random rng) {
        List<BotPetConfig.CountBand> bands = config.countBands();
        BotPetConfig.CountBand band = bands.get(bands.size() - 1);
        for (BotPetConfig.CountBand candidate : bands) {
            if (strength < candidate.sMax()) {
                band = candidate;
                break;
            }
        }
        int total = band.weightFor(1) + band.weightFor(2) + band.weightFor(3);
        if (total <= 0) {
            return 1;
        }
        int roll = rng.nextInt(total);
        if (roll < band.weightFor(1)) {
            return 1;
        }
        roll -= band.weightFor(1);
        if (roll < band.weightFor(2)) {
            return 2;
        }
        return 3;
    }

    /**
     * Closeness 0..300, informed by the owner: a higher-level bot generally keeps a
     * better-cared-for pet, but it stays a wide spread (a low roll on a strong bot, or
     * a high roll on a weak one, is normal) — it reads as "how long this pet has been
     * with its owner", not a fixed stat.
     */
    static int rollTameness(int level, double strength, Random rng) {
        double levelPart = Math.min(1.0, (double) level / TAMENESS_LEVEL_CAP);
        double mid = (TAMENESS_MAX - TAMENESS_MIN)
                * (TAMENESS_LEVEL_WEIGHT * levelPart + TAMENESS_STRENGTH_WEIGHT * strength);
        int rolled = (int) Math.round(mid) + rng.nextInt(2 * TAMENESS_SPREAD + 1) - TAMENESS_SPREAD;
        return Math.max(TAMENESS_MIN, Math.min(TAMENESS_MAX, rolled));
    }

    /** Pet level, deliberately low and only mildly scaled by strength. */
    static int petLevel(double strength, BotPetConfig config) {
        int level = config.petLevelBase() + (int) Math.floor(strength * config.petLevelPerStrength());
        return Math.max(1, Math.min(config.petLevelMax(), level));
    }

    static List<Integer> drawDistinct(List<Integer> pool, int count, Random rng) {
        List<Integer> copy = new ArrayList<>(pool);
        int n = Math.min(count, copy.size());
        List<Integer> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(copy.remove(rng.nextInt(copy.size())));
        }
        return out;
    }
}
