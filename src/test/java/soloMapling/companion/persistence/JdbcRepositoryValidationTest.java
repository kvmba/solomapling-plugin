package soloMapling.companion.persistence;

import org.junit.jupiter.api.Test;
import soloMapling.companion.memory.MemoryType;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertThrows;

class JdbcRepositoryValidationTest {

    private static final Instant NOW = Instant.parse("2026-08-20T10:00:00Z");

    @Test
    void memoryRepositoryRejectsInvalidQueriesBeforeOpeningAConnection() {
        JdbcCompanionMemoryRepository repository = new JdbcCompanionMemoryRepository();

        assertThrows(IllegalArgumentException.class, () -> repository.findCandidates(
                0, null, null, MemoryType.EPISODIC, false, null, null, 10));
        assertThrows(IllegalArgumentException.class, () -> repository.findCandidates(
                1, null, null, null, false, NOW, NOW.minusSeconds(1), 10));
        assertThrows(IllegalArgumentException.class, () -> repository.updateStrength(1, 1.01));
        assertThrows(NullPointerException.class, () -> repository.markRecalled(1, null));
    }

    @Test
    void relationshipRepositoryRejectsOutOfRangeColumns() {
        JdbcCompanionRelationshipRepository repository =
                new JdbcCompanionRelationshipRepository();

        assertThrows(IllegalArgumentException.class, () -> repository.upsertInteraction(
                1, 2, "friend", Short.MAX_VALUE + 1, 0, 0, "", NOW));
        assertThrows(IllegalArgumentException.class, () -> repository.upsertInteraction(
                1, 2, " ".repeat(33), 0, 0, 0, "", NOW));
    }

    @Test
    void activityRepositoryValidatesRequiredAndSizedFields() {
        JdbcCompanionActivityRepository repository = new JdbcCompanionActivityRepository();

        assertThrows(IllegalArgumentException.class, () -> repository.append(
                1, "", "success", "system", null, "", null, NOW));
        assertThrows(IllegalArgumentException.class, () -> repository.append(
                1, "talk", "success", "system", null, "x".repeat(513), null, NOW));
        assertThrows(NullPointerException.class, () -> repository.append(
                1, "talk", "success", "system", null, "", null, null));
    }
}
