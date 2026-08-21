package soloMapling.companion.routine;

import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Pure, conservative settlement policy for ordinary EXP and mesos only.
 */
public final class OfflineProgressionPolicy {
    private static final BigInteger BASIS_POINTS_SQUARED = BigInteger.valueOf(100_000_000L);
    private static final BigInteger SECONDS_PER_HOUR = BigInteger.valueOf(3_600L);

    private final Duration maxCreditedElapsed;
    private final long maxExperience;
    private final long maxMesos;
    private final Map<RoutineActivity, Integer> activityBasisPoints;

    public OfflineProgressionPolicy(
            Duration maxCreditedElapsed,
            long maxExperience,
            long maxMesos,
            Map<RoutineActivity, Integer> activityBasisPoints) {
        this.maxCreditedElapsed = Objects.requireNonNull(
                maxCreditedElapsed, "maxCreditedElapsed must not be null");
        if (maxCreditedElapsed.isNegative() || maxCreditedElapsed.isZero()) {
            throw new IllegalArgumentException("maxCreditedElapsed must be positive");
        }
        if (maxExperience < 0 || maxMesos < 0) {
            throw new IllegalArgumentException("reward caps must not be negative");
        }
        Objects.requireNonNull(activityBasisPoints, "activityBasisPoints must not be null");
        EnumMap<RoutineActivity, Integer> copy = new EnumMap<>(RoutineActivity.class);
        for (RoutineActivity activity : RoutineActivity.values()) {
            Integer value = activityBasisPoints.get(activity);
            if (value == null || value < 0 || value > 10_000) {
                throw new IllegalArgumentException(
                        "every activity requires a multiplier between 0 and 10000: " + activity);
            }
            copy.put(activity, value);
        }
        this.maxExperience = maxExperience;
        this.maxMesos = maxMesos;
        this.activityBasisPoints = Map.copyOf(copy);
    }

    public static OfflineProgressionPolicy conservativeDefaults() {
        EnumMap<RoutineActivity, Integer> multipliers = new EnumMap<>(RoutineActivity.class);
        multipliers.put(RoutineActivity.OFFLINE, 500);
        multipliers.put(RoutineActivity.SLEEP, 1_000);
        multipliers.put(RoutineActivity.TOWN, 2_000);
        multipliers.put(RoutineActivity.TRAIN, 10_000);
        multipliers.put(RoutineActivity.SHOP, 4_000);
        multipliers.put(RoutineActivity.SOCIAL, 2_500);
        multipliers.put(RoutineActivity.TRAVEL, 3_000);
        multipliers.put(RoutineActivity.REST, 1_500);
        return new OfflineProgressionPolicy(
                Duration.ofHours(24), 25_000L, 100_000L, multipliers);
    }

    public OfflineProgressionSettlement settle(
            Instant offlineSince,
            Instant observedAt,
            int level,
            PersonaProgressionProfile persona,
            RoutineActivity activity) {
        Objects.requireNonNull(offlineSince, "offlineSince must not be null");
        Objects.requireNonNull(observedAt, "observedAt must not be null");
        Objects.requireNonNull(persona, "persona must not be null");
        Objects.requireNonNull(activity, "activity must not be null");
        if (level < 1) {
            throw new IllegalArgumentException("level must be positive");
        }
        if (observedAt.isBefore(offlineSince)) {
            throw new IllegalArgumentException("observedAt must not precede offlineSince");
        }

        Duration elapsed = Duration.between(offlineSince, observedAt);
        Duration credited = elapsed.compareTo(maxCreditedElapsed) > 0
                ? maxCreditedElapsed : elapsed;
        // The complete observed interval is consumed even when only its capped prefix earns
        // rewards. Advancing by credited alone would let callers repeatedly settle the
        // discarded remainder in cap-sized chunks.
        Instant settledThrough = observedAt;
        int activityMultiplier = activityBasisPoints.get(activity);

        long expPerHour = 8L + Math.min(level, 250) * 2L;
        long mesoPerHour = 25L + Math.min(level, 250) * 5L;
        long experience = boundedReward(
                expPerHour, credited.getSeconds(), activityMultiplier,
                persona.diligenceBasisPoints(), maxExperience);
        long mesos = boundedReward(
                mesoPerHour, credited.getSeconds(), activityMultiplier,
                persona.thriftBasisPoints(), maxMesos);

        Duration discarded = elapsed.minus(credited);
        String reason = "ordinary offline progression; activity=" + activity
                + "; elapsedCap=" + maxCreditedElapsed
                + "; expCap=" + maxExperience
                + "; mesoCap=" + maxMesos
                + (credited.equals(elapsed)
                        ? "; elapsedWithinCap"
                        : "; elapsedTruncated; uncreditedRemainderDiscarded=" + discarded);
        return new OfflineProgressionSettlement(
                experience, mesos, elapsed, credited, settledThrough, reason);
    }

    public Duration maxCreditedElapsed() {
        return maxCreditedElapsed;
    }

    public long maxExperience() {
        return maxExperience;
    }

    public long maxMesos() {
        return maxMesos;
    }

    private static long boundedReward(
            long hourlyRate,
            long seconds,
            int activityBasisPoints,
            int personaBasisPoints,
            long cap) {
        BigInteger reward = BigInteger.valueOf(hourlyRate)
                .multiply(BigInteger.valueOf(seconds))
                .multiply(BigInteger.valueOf(activityBasisPoints))
                .multiply(BigInteger.valueOf(personaBasisPoints))
                .divide(SECONDS_PER_HOUR.multiply(BASIS_POINTS_SQUARED));
        return reward.min(BigInteger.valueOf(cap)).longValueExact();
    }
}
