package soloMapling.companion.routine;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Pure deterministic selection from an explicit allow-list.
 */
public final class EncounterDirector {
    private EncounterDirector() {
    }

    public static Optional<EncounterSelection> select(
            EncounterKey key,
            long seed,
            Instant now,
            Duration cooldown,
            Map<EncounterKey, Instant> lastEncounters,
            List<EncounterCandidate> allowedCandidates,
            int companionLevel,
            RoutineActivity activity) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(cooldown, "cooldown must not be null");
        Objects.requireNonNull(lastEncounters, "lastEncounters must not be null");
        Objects.requireNonNull(allowedCandidates, "allowedCandidates must not be null");
        Objects.requireNonNull(activity, "activity must not be null");
        if (cooldown.isNegative()) {
            throw new IllegalArgumentException("cooldown must not be negative");
        }
        if (companionLevel < 1) {
            throw new IllegalArgumentException("companionLevel must be positive");
        }
        if (isOnCooldown(key, now, cooldown, lastEncounters)) {
            return Optional.empty();
        }

        List<EncounterCandidate> candidates = allowedCandidates.stream()
                .peek(candidate -> Objects.requireNonNull(candidate, "candidate must not be null"))
                .sorted(Comparator.comparingInt(EncounterCandidate::mapId))
                .toList();
        rejectDuplicateMaps(candidates);
        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        long totalWeight = candidates.stream()
                .mapToLong(candidate -> adjustedWeight(candidate, companionLevel, activity))
                .sum();
        long mixed = mix64(seed ^ mix64(key.companionId()) ^ Long.rotateLeft(mix64(key.playerId()), 17));
        long target = Long.remainderUnsigned(mixed, totalWeight);
        EncounterCandidate selected = candidates.getLast();
        long cumulative = 0;
        for (EncounterCandidate candidate : candidates) {
            cumulative += adjustedWeight(candidate, companionLevel, activity);
            if (target < cumulative) {
                selected = candidate;
                break;
            }
        }

        long selectedWeight = adjustedWeight(selected, companionLevel, activity);
        return Optional.of(new EncounterSelection(
                key,
                selected.mapId(),
                seed,
                now,
                now.plus(cooldown),
                "deterministic allow-list selection; adjustedWeight="
                        + selectedWeight + "; totalWeight=" + totalWeight));
    }

    public static boolean isOnCooldown(
            EncounterKey key,
            Instant now,
            Duration cooldown,
            Map<EncounterKey, Instant> lastEncounters) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(cooldown, "cooldown must not be null");
        Objects.requireNonNull(lastEncounters, "lastEncounters must not be null");
        if (cooldown.isNegative()) {
            throw new IllegalArgumentException("cooldown must not be negative");
        }
        Instant lastEncounter = lastEncounters.get(key);
        return lastEncounter != null && now.isBefore(lastEncounter.plus(cooldown));
    }

    public static Duration cooldownRemaining(
            EncounterKey key,
            Instant now,
            Duration cooldown,
            Map<EncounterKey, Instant> lastEncounters) {
        if (!isOnCooldown(key, now, cooldown, lastEncounters)) {
            return Duration.ZERO;
        }
        return Duration.between(now, lastEncounters.get(key).plus(cooldown));
    }

    private static long adjustedWeight(
            EncounterCandidate candidate, int level, RoutineActivity activity) {
        int distance = level < candidate.recommendedMinLevel()
                ? candidate.recommendedMinLevel() - level
                : Math.max(0, level - candidate.recommendedMaxLevel());
        int levelBasisPoints = Math.max(5_000, 10_000 - Math.min(distance, 50) * 100);
        int activityBasisPoints = candidate.preferredActivities().contains(activity) ? 12_000 : 10_000;
        return Math.max(1L,
                (long) candidate.baseWeight() * levelBasisPoints * activityBasisPoints
                        / 100_000_000L);
    }

    private static void rejectDuplicateMaps(List<EncounterCandidate> candidates) {
        HashSet<Integer> mapIds = new HashSet<>();
        for (EncounterCandidate candidate : candidates) {
            if (!mapIds.add(candidate.mapId())) {
                throw new IllegalArgumentException("duplicate allowed map: " + candidate.mapId());
            }
        }
    }

    private static long mix64(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }
}
