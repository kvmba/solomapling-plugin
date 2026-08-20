package soloMapling.companion.routine;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfflineProgressionPolicyTest {

    @Test
    void truncationConsumesEntireObservedIntervalAndCannotBeClaimedAgain() {
        OfflineProgressionPolicy policy = OfflineProgressionPolicy.conservativeDefaults();
        Instant start = Instant.parse("2026-08-01T00:00:00Z");
        Instant observedAt = start.plus(Duration.ofDays(7));

        OfflineProgressionSettlement first = policy.settle(
                start,
                observedAt,
                50,
                PersonaProgressionProfile.NEUTRAL,
                RoutineActivity.TRAIN);

        assertEquals(Duration.ofDays(7), first.elapsed());
        assertEquals(Duration.ofHours(24), first.creditedElapsed());
        assertEquals(observedAt, first.settledThrough());
        assertTrue(first.wasElapsedCapped());
        assertTrue(first.reason().contains("elapsedTruncated"));
        assertTrue(first.reason().contains("uncreditedRemainderDiscarded=PT144H"));

        OfflineProgressionSettlement immediateRetry = policy.settle(
                first.settledThrough(),
                observedAt,
                50,
                PersonaProgressionProfile.NEUTRAL,
                RoutineActivity.TRAIN);

        assertEquals(Duration.ZERO, immediateRetry.elapsed());
        assertEquals(Duration.ZERO, immediateRetry.creditedElapsed());
        assertEquals(0, immediateRetry.experience());
        assertEquals(0, immediateRetry.mesos());
        assertEquals(observedAt, immediateRetry.settledThrough());
    }

    @Test
    void rewardsAreHardCappedAndDeterministic() {
        EnumMap<RoutineActivity, Integer> multipliers = new EnumMap<>(RoutineActivity.class);
        for (RoutineActivity activity : RoutineActivity.values()) {
            multipliers.put(activity, 10_000);
        }
        OfflineProgressionPolicy policy =
                new OfflineProgressionPolicy(Duration.ofDays(7), 100, 200, multipliers);
        Instant start = Instant.parse("2026-08-01T00:00:00Z");

        OfflineProgressionSettlement first = policy.settle(
                start, start.plus(Duration.ofDays(7)), 250,
                PersonaProgressionProfile.NEUTRAL, RoutineActivity.TRAIN);
        OfflineProgressionSettlement second = policy.settle(
                start, start.plus(Duration.ofDays(7)), 250,
                PersonaProgressionProfile.NEUTRAL, RoutineActivity.TRAIN);

        assertEquals(first, second);
        assertEquals(100, first.experience());
        assertEquals(200, first.mesos());
        assertTrue(first.reason().contains("expCap=100"));
        assertTrue(first.reason().contains("mesoCap=200"));
    }

    @Test
    void sleepingAndConservativePersonaReduceOrdinaryRewards() {
        OfflineProgressionPolicy policy = OfflineProgressionPolicy.conservativeDefaults();
        Instant start = Instant.parse("2026-08-01T00:00:00Z");

        OfflineProgressionSettlement neutralTraining = policy.settle(
                start, start.plus(Duration.ofHours(4)), 20,
                PersonaProgressionProfile.NEUTRAL, RoutineActivity.TRAIN);
        OfflineProgressionSettlement conservativeSleeping = policy.settle(
                start, start.plus(Duration.ofHours(4)), 20,
                new PersonaProgressionProfile(5_000, 5_000), RoutineActivity.SLEEP);

        assertTrue(conservativeSleeping.experience() < neutralTraining.experience());
        assertTrue(conservativeSleeping.mesos() < neutralTraining.mesos());
    }
}
