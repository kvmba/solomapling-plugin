package soloMapling.companion.routine;

import java.util.Objects;
import java.util.Set;

/**
 * An explicitly allowed map and its soft selection hints.
 */
public record EncounterCandidate(
        int mapId,
        int baseWeight,
        int recommendedMinLevel,
        int recommendedMaxLevel,
        Set<RoutineActivity> preferredActivities) {

    public EncounterCandidate {
        if (mapId < 0) {
            throw new IllegalArgumentException("mapId must not be negative");
        }
        if (baseWeight < 1 || baseWeight > 1_000_000) {
            throw new IllegalArgumentException("baseWeight must be between 1 and 1000000");
        }
        if (recommendedMinLevel < 1 || recommendedMaxLevel < recommendedMinLevel) {
            throw new IllegalArgumentException("invalid recommended level range");
        }
        Objects.requireNonNull(preferredActivities, "preferredActivities must not be null");
        if (preferredActivities.stream().anyMatch(Objects::isNull)) {
            throw new NullPointerException("preferredActivities must not contain null");
        }
        preferredActivities = Set.copyOf(preferredActivities);
    }
}
