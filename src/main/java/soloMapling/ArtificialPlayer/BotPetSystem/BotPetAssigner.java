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
            boolean named = rng.nextDouble() < config.namingChance();
            boolean pickupItem = rng.nextDouble() < config.itemPouchChance();
            boolean pickupMeso = rng.nextDouble() < config.mesoMagnetChance();
            specs.add(new PetSpec(itemId, petLevel, named, pickupItem, pickupMeso));
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
