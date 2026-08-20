package soloMapling.companion.agent;

import soloMapling.companion.persistence.CompanionKnowledge;

import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Builds planner authority only from current perception and persisted,
 * companion-owned map knowledge. It deliberately has no global map or
 * character input.
 */
public final class PerceptionPolicy {

    private static final String MAP_CATEGORY = "map";
    private static final String MAP_KEY_PREFIX = "map:";

    public PerceptionScope build(
            int companionCharacterId,
            int currentMapId,
            Collection<Integer> sameMapCharacterIds,
            Collection<CompanionKnowledge> learnedMapKnowledge
    ) {
        requirePositive(companionCharacterId, "companionCharacterId");
        requireNonNegative(currentMapId, "currentMapId");
        Objects.requireNonNull(
                sameMapCharacterIds, "sameMapCharacterIds must not be null");
        Objects.requireNonNull(
                learnedMapKnowledge, "learnedMapKnowledge must not be null");

        TreeSet<Integer> targets = new TreeSet<>();
        for (Integer characterId : sameMapCharacterIds) {
            if (characterId == null || characterId <= 0) {
                throw new IllegalArgumentException(
                        "sameMapCharacterIds must contain only positive ids");
            }
            if (characterId != companionCharacterId) {
                targets.add(characterId);
            }
        }

        TreeSet<Integer> maps = new TreeSet<>();
        maps.add(currentMapId);
        for (CompanionKnowledge knowledge : learnedMapKnowledge) {
            Objects.requireNonNull(
                    knowledge, "learnedMapKnowledge must not contain null");
            if (knowledge.companionCharacterId() != companionCharacterId) {
                throw new IllegalArgumentException(
                        "learned map knowledge must belong to the companion");
            }
            if (!knowledge.enabled() || !MAP_CATEGORY.equals(knowledge.category())) {
                throw new IllegalArgumentException(
                        "learnedMapKnowledge must contain only enabled map knowledge");
            }
            maps.add(parseMapId(knowledge.knowledgeKey()));
        }

        return new PerceptionScope(immutableSorted(maps), immutableSorted(targets));
    }

    private static int parseMapId(String knowledgeKey) {
        String encoded = knowledgeKey.startsWith(MAP_KEY_PREFIX)
                ? knowledgeKey.substring(MAP_KEY_PREFIX.length())
                : knowledgeKey;
        try {
            int mapId = Integer.parseInt(encoded);
            requireNonNegative(mapId, "learned map id");
            return mapId;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "map knowledge key must be an integer or map:<integer>", exception);
        }
    }

    private static Set<Integer> immutableSorted(TreeSet<Integer> ids) {
        return Collections.unmodifiableSet(ids);
    }

    private static void requirePositive(int value, String field) {
        if (value <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }

    private static void requireNonNegative(int value, String field) {
        if (value < 0) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
    }

    public record PerceptionScope(
            Set<Integer> knownMapIds,
            Set<Integer> targetCharacterIds
    ) {
        public PerceptionScope {
            Objects.requireNonNull(knownMapIds, "knownMapIds must not be null");
            Objects.requireNonNull(
                    targetCharacterIds, "targetCharacterIds must not be null");
            TreeSet<Integer> maps = new TreeSet<>(knownMapIds);
            if (maps.size() != knownMapIds.size()
                    || maps.stream().anyMatch(id -> id == null || id < 0)) {
                throw new IllegalArgumentException(
                        "knownMapIds must contain only non-negative ids");
            }
            TreeSet<Integer> targets = new TreeSet<>(targetCharacterIds);
            if (targets.size() != targetCharacterIds.size()
                    || targets.stream().anyMatch(id -> id == null || id <= 0)) {
                throw new IllegalArgumentException(
                        "targetCharacterIds must contain only positive ids");
            }
            knownMapIds = immutableSorted(maps);
            targetCharacterIds = immutableSorted(targets);
        }
    }
}
