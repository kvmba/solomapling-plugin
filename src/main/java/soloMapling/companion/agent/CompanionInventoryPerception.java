package soloMapling.companion.agent;

import org.gms.client.Character;
import org.gms.client.inventory.Equip;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.Item;
import org.gms.constants.inventory.EquipType;
import org.gms.server.ItemInformationProvider;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds a bounded snapshot of one companion's own native inventory. */
public final class CompanionInventoryPerception {
    private static final int MAX_FACTS = 512;

    private CompanionInventoryPerception() {
    }

    public static List<CompanionInventoryItem> snapshot(Character companion) {
        if (companion == null) {
            return List.of();
        }
        ItemInformationProvider provider = ItemInformationProvider.getInstance();
        List<CompanionInventoryItem> facts = new ArrayList<>();
        appendEquipment(facts, companion, InventoryType.EQUIPPED, true, provider);
        appendEquipment(facts, companion, InventoryType.EQUIP, false, provider);

        Map<String, MutableStack> stacks = new LinkedHashMap<>();
        for (InventoryType type : List.of(
                InventoryType.USE, InventoryType.SETUP, InventoryType.ETC, InventoryType.CASH)) {
            for (Item item : companion.getInventory(type).list()) {
                if (item == null || item.getQuantity() <= 0) {
                    continue;
                }
                String key = type.name() + ":" + item.getItemId();
                stacks.computeIfAbsent(key, ignored -> new MutableStack(item, type))
                        .quantity += item.getQuantity();
            }
        }
        for (MutableStack stack : stacks.values()) {
            Item item = stack.item;
            facts.add(new CompanionInventoryItem(
                    item.getItemId(), itemName(provider, item.getItemId()),
                    stack.type.name(), item.getPosition(), stack.quantity,
                    false, false, tradeable(provider, item), "", 0));
        }
        facts.sort(Comparator
                .comparing((CompanionInventoryItem item) -> !item.equipped())
                .thenComparing((CompanionInventoryItem item) -> !item.equipment())
                .thenComparing(CompanionInventoryItem::inventoryType)
                .thenComparingInt(CompanionInventoryItem::slot)
                .thenComparingInt(CompanionInventoryItem::itemId));
        return facts.size() <= MAX_FACTS
                ? List.copyOf(facts)
                : List.copyOf(facts.subList(0, MAX_FACTS));
    }

    private static void appendEquipment(
            List<CompanionInventoryItem> facts,
            Character companion,
            InventoryType type,
            boolean equipped,
            ItemInformationProvider provider) {
        for (Item item : companion.getInventory(type).list()) {
            if (!(item instanceof Equip equip)) {
                continue;
            }
            EquipType equipType = EquipType.getEquipTypeById(item.getItemId());
            facts.add(new CompanionInventoryItem(
                    item.getItemId(), itemName(provider, item.getItemId()),
                    type.name(), item.getPosition(), 1, equipped, true,
                    !equipped && tradeable(provider, item),
                    equipType == null ? "UNKNOWN" : equipType.name(),
                    score(equip)));
        }
    }

    private static int score(Equip equip) {
        return 100 * (equip.getWatk() + equip.getMatk())
                + 10 * (equip.getStr() + equip.getDex() + equip.getInt() + equip.getLuk())
                + equip.getWdef() + equip.getMdef();
    }

    private static boolean tradeable(ItemInformationProvider provider, Item item) {
        return !item.isUntradeable()
                && !provider.isCash(item.getItemId())
                && !provider.isQuestItem(item.getItemId())
                && !provider.isDropRestricted(item.getItemId())
                && item.getOwner().isEmpty()
                && item.getItemLog().isEmpty();
    }

    private static String itemName(ItemInformationProvider provider, int itemId) {
        String name = provider.getName(itemId);
        return name == null || name.isBlank() ? "item-" + itemId : name;
    }

    private static final class MutableStack {
        private final Item item;
        private final InventoryType type;
        private int quantity;

        private MutableStack(Item item, InventoryType type) {
            this.item = item;
            this.type = type;
        }
    }
}
