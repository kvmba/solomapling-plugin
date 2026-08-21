package soloMapling.companion.agent;

import java.util.Objects;

/** A bounded, factual equipment upgrade target for conversation and planning. */
public record CompanionGearGoal(
        int itemId,
        String itemName,
        String equipType,
        int requiredLevel,
        int monsterId,
        String monsterName,
        int mapId,
        double dropChance,
        boolean boss) {

    public CompanionGearGoal {
        if (itemId <= 0) {
            throw new IllegalArgumentException("itemId must be positive");
        }
        itemName = requireText(itemName, "itemName");
        equipType = requireText(equipType, "equipType");
        if (requiredLevel < 0 || monsterId < 0 || mapId < -1
                || !Double.isFinite(dropChance) || dropChance < 0) {
            throw new IllegalArgumentException("gear goal numeric fields are out of range");
        }
        monsterName = Objects.requireNonNullElse(monsterName, "").trim();
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized.length() <= 120 ? normalized : normalized.substring(0, 120);
    }
}
