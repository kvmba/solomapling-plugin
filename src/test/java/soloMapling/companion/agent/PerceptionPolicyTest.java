package soloMapling.companion.agent;

import org.junit.jupiter.api.Test;
import soloMapling.companion.persistence.CompanionKnowledge;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PerceptionPolicyTest {

    private static final int COMPANION_ID = 10;
    private static final Instant CREATED_AT = Instant.parse("2026-08-20T10:00:00Z");

    private final PerceptionPolicy policy = new PerceptionPolicy();

    @Test
    void grantsOnlyCurrentLearnedMapsAndSameMapCharacters() {
        PerceptionPolicy.PerceptionScope scope = policy.build(
                COMPANION_ID,
                0,
                List.of(COMPANION_ID, 30, 20),
                List.of(mapKnowledge(1, "map:100000000"),
                        mapKnowledge(2, "200000000")));

        assertEquals(Set.of(0, 100000000, 200000000), scope.knownMapIds());
        assertEquals(Set.of(20, 30), scope.targetCharacterIds());
        assertThrows(
                UnsupportedOperationException.class,
                () -> scope.knownMapIds().add(999999999));
    }

    @Test
    void refusesForeignDisabledOrNonMapKnowledge() {
        assertThrows(IllegalArgumentException.class, () -> policy.build(
                COMPANION_ID, 1, Set.of(), List.of(new CompanionKnowledge(
                        1, 99, "map:2", "map", "foreign", null, 0, true,
                        CREATED_AT, CREATED_AT))));
        assertThrows(IllegalArgumentException.class, () -> policy.build(
                COMPANION_ID, 1, Set.of(), List.of(new CompanionKnowledge(
                        1, COMPANION_ID, "map:2", "map", "disabled", null, 0, false,
                        CREATED_AT, CREATED_AT))));
        assertThrows(IllegalArgumentException.class, () -> policy.build(
                COMPANION_ID, 1, Set.of(), List.of(new CompanionKnowledge(
                        1, COMPANION_ID, "map:2", "person", "wrong category", null,
                        0, true, CREATED_AT, CREATED_AT))));
    }

    @Test
    void rejectsNegativeMapsAndNonPositiveCharacterIds() {
        assertThrows(IllegalArgumentException.class, () -> policy.build(
                COMPANION_ID, -1, Set.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> policy.build(
                COMPANION_ID, 0, Set.of(0), List.of()));
        assertThrows(IllegalArgumentException.class, () -> policy.build(
                COMPANION_ID, 0, Set.of(), List.of(mapKnowledge(1, "map:-1"))));
    }

    private static CompanionKnowledge mapKnowledge(long id, String key) {
        return new CompanionKnowledge(
                id,
                COMPANION_ID,
                key,
                "map",
                "learned map",
                "observation",
                0,
                true,
                CREATED_AT,
                CREATED_AT);
    }
}
