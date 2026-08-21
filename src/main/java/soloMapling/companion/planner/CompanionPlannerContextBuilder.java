package soloMapling.companion.planner;

import soloMapling.companion.memory.MemoryRecord;
import soloMapling.companion.memory.MemorySelector;
import soloMapling.companion.persistence.CompanionRelationship;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** Builds a bounded planner context exclusively from caller-provided facts. */
public final class CompanionPlannerContextBuilder {

    public CompanionPlannerContext build(CompanionPlannerInput input) {
        Set<Integer> allowedCharacterIds = input.state().targetCharacterIds();
        Set<Integer> allowedMapIds = input.state().knownMapIds();
        List<MemoryRecord> memories = MemorySelector.select(
                input.memoryCandidates().stream()
                        .filter(memory -> memory != null
                                && allowedActor(memory.actorKey(), allowedCharacterIds)
                                && allowedMap(memory.mapKey(), allowedMapIds))
                        .toList(),
                input.memoryLimit(),
                input.minimumMemoryStrength(),
                input.memoryContext(),
                input.memoryParameters());
        List<CompanionRelationship> relationships = input.relationships().stream()
                .filter(relationship ->
                        allowedCharacterIds.contains(relationship.relatedCharacterId()))
                .sorted(Comparator
                        .comparingInt(CompanionRelationship::relatedCharacterId)
                        .thenComparingLong(CompanionRelationship::id))
                .toList();
        return new CompanionPlannerContext(
                input.profile(), input.persona(), input.state(), memories, relationships,
                input.gearAdvice());
    }

    private static boolean allowedActor(String actorKey, Set<Integer> allowedCharacterIds) {
        return actorKey == null
                || parseInteger(actorKey, false)
                .map(allowedCharacterIds::contains)
                .orElse(false);
    }

    private static boolean allowedMap(String mapKey, Set<Integer> allowedMapIds) {
        return mapKey == null
                || parseInteger(mapKey, true)
                .map(allowedMapIds::contains)
                .orElse(false);
    }

    private static java.util.Optional<Integer> parseInteger(String value, boolean allowZero) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 0 || (!allowZero && parsed == 0)) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(parsed);
        } catch (NumberFormatException exception) {
            return java.util.Optional.empty();
        }
    }
}
