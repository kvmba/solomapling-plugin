package soloMapling.companion.agent;

import java.util.Objects;
import java.util.List;
import java.util.Optional;
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
        boolean engaged,
        List<CompanionInventoryItem> inventoryItems,
        Optional<CompanionGearGoal> gearGoal,
        Set<Integer> giftableItemIds) {

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
        Objects.requireNonNull(inventoryItems, "inventoryItems must not be null");
        inventoryItems = List.copyOf(inventoryItems);
        if (inventoryItems.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("inventoryItems must not contain null");
        }
        gearGoal = Objects.requireNonNull(gearGoal, "gearGoal must not be null");
        giftableItemIds = immutablePositiveIds(giftableItemIds, "giftableItemIds");
        Set<Integer> ownedItemIds = inventoryItems.stream()
                .filter(item -> !item.equipped())
                .map(CompanionInventoryItem::itemId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!ownedItemIds.containsAll(giftableItemIds)) {
            throw new IllegalArgumentException("giftableItemIds must refer to unequipped inventory items");
        }
    }

    public CompanionStateSnapshot(
            int currentMapId,
            Set<Integer> sameMapCharacterIds,
            boolean inParty,
            Set<Integer> knownMapIds,
            Set<Integer> targetCharacterIds,
            Set<CompanionAction.ActionType> cooldownActions,
            boolean engaged) {
        this(currentMapId, sameMapCharacterIds, inParty, knownMapIds,
                targetCharacterIds, cooldownActions, engaged, List.of(), Optional.empty(), Set.of());
    }

    public CompanionStateSnapshot withGiftableItemIds(Set<Integer> itemIds) {
        return new CompanionStateSnapshot(
                currentMapId, sameMapCharacterIds, inParty, knownMapIds,
                targetCharacterIds, cooldownActions, engaged, inventoryItems, gearGoal, itemIds);
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
