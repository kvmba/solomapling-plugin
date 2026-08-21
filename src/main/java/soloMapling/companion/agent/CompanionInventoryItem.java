package soloMapling.companion.agent;

import java.util.Objects;

/** Engine-free inventory fact disclosed to the planner. */
public record CompanionInventoryItem(
        int itemId,
        String name,
        String inventoryType,
        short slot,
        int quantity,
        boolean equipped,
        boolean equipment,
        boolean tradeable,
        String equipType,
        int score) {

    public CompanionInventoryItem {
        if (itemId <= 0) {
            throw new IllegalArgumentException("itemId must be positive");
        }
        name = requireText(name, "name");
        inventoryType = requireText(inventoryType, "inventoryType");
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        equipType = Objects.requireNonNullElse(equipType, "");
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
