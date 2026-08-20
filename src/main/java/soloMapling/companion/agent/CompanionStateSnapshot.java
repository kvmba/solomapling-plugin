package soloMapling.companion.agent;

import java.util.Objects;
import java.util.Set;

/**
 * Minimal immutable planner state. It intentionally contains identifiers and
 * facts only, never engine Character, map, party, or scheduler objects.
 */
public record CompanionStateSnapshot(
        int currentMapId,
        Set<Integer> sameMapCharacterIds,
        boolean inParty,
        Set<Integer> knownMapIds,
        Set<Integer> targetCharacterIds,
        Set<CompanionAction.ActionType> cooldownActions,
        boolean engaged) {

    public CompanionStateSnapshot {
        requireNonNegative(currentMapId, "currentMapId");
        knownMapIds = immutableNonNegativeIds(knownMapIds, "knownMapIds");
        targetCharacterIds = immutablePositiveIds(targetCharacterIds, "targetCharacterIds");
        sameMapCharacterIds =
                immutablePositiveIds(sameMapCharacterIds, "sameMapCharacterIds");
        if (!targetCharacterIds.containsAll(sameMapCharacterIds)) {
            throw new IllegalArgumentException(
                    "sameMapCharacterIds must be a subset of targetCharacterIds");
        }
        Objects.requireNonNull(cooldownActions, "cooldownActions must not be null");
        cooldownActions = Set.copyOf(cooldownActions);
        if (cooldownActions.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("cooldownActions must not contain null");
        }
        if (!knownMapIds.contains(currentMapId)) {
            throw new IllegalArgumentException("knownMapIds must contain currentMapId");
        }
    }

    private static Set<Integer> immutablePositiveIds(Set<Integer> ids, String field) {
        Objects.requireNonNull(ids, field + " must not be null");
        Set<Integer> copy = Set.copyOf(ids);
        if (copy.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException(field + " must contain only positive ids");
        }
        return copy;
    }

    private static Set<Integer> immutableNonNegativeIds(Set<Integer> ids, String field) {
        Objects.requireNonNull(ids, field + " must not be null");
        Set<Integer> copy = Set.copyOf(ids);
        if (copy.stream().anyMatch(id -> id == null || id < 0)) {
            throw new IllegalArgumentException(field + " must contain only non-negative ids");
        }
        return copy;
    }

    private static void requireNonNegative(int id, String field) {
        if (id < 0) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
    }
}
