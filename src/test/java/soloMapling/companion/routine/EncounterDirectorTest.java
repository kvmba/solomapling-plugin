package soloMapling.companion.routine;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EncounterDirectorTest {
    private static final Instant NOW = Instant.parse("2026-08-20T10:00:00Z");
    private static final Duration COOLDOWN = Duration.ofHours(2);
    private static final EncounterKey KEY = new EncounterKey(11, 22);
    private static final List<EncounterCandidate> ALLOWED = List.of(
            new EncounterCandidate(100, 10, 10, 20, Set.of(RoutineActivity.TRAIN)),
            new EncounterCandidate(200, 5, 20, 30, Set.of(RoutineActivity.SOCIAL)));

    @Test
    void selectionIsDeterministicAndNeverEscapesCandidates() {
        EncounterSelection first = EncounterDirector.select(
                KEY, 987654321L, NOW, COOLDOWN, Map.of(), ALLOWED, 18,
                RoutineActivity.TRAIN).orElseThrow();
        EncounterSelection second = EncounterDirector.select(
                KEY, 987654321L, NOW, COOLDOWN, Map.of(), ALLOWED.reversed(), 18,
                RoutineActivity.TRAIN).orElseThrow();

        assertEquals(first, second);
        assertTrue(Set.of(100, 200).contains(first.mapId()));
    }

    @Test
    void cooldownIsScopedToCompanionPlayerPairAndBoundaryIsEligible() {
        EncounterKey otherPlayer = new EncounterKey(KEY.companionId(), 23);
        Map<EncounterKey, Instant> history = Map.of(KEY, NOW.minus(Duration.ofMinutes(30)));

        assertTrue(EncounterDirector.isOnCooldown(KEY, NOW, COOLDOWN, history));
        assertFalse(EncounterDirector.isOnCooldown(otherPlayer, NOW, COOLDOWN, history));
        assertEquals(Duration.ofMinutes(90),
                EncounterDirector.cooldownRemaining(KEY, NOW, COOLDOWN, history));
        assertTrue(EncounterDirector.select(
                KEY, 1L, NOW, COOLDOWN, history, ALLOWED, 18,
                RoutineActivity.TRAIN).isEmpty());

        Map<EncounterKey, Instant> atBoundary = Map.of(KEY, NOW.minus(COOLDOWN));
        assertTrue(EncounterDirector.select(
                KEY, 1L, NOW, COOLDOWN, atBoundary, ALLOWED, 18,
                RoutineActivity.TRAIN).isPresent());
    }

    @Test
    void emptyAllowListCannotProduceAnEncounter() {
        assertTrue(EncounterDirector.select(
                KEY, 1L, NOW, COOLDOWN, Map.of(), List.of(), 18,
                RoutineActivity.TRAIN).isEmpty());
    }
}
