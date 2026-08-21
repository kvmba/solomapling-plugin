package soloMapling.companion.execution;

import org.gms.client.Character;
import org.gms.client.inventory.Equip;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.Item;
import soloMapling.companion.gear.GearDropSourceProvider;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Runtime-installed host capabilities used by persistent companions. */
public final class CompanionRuntimeCapabilities {
    @FunctionalInterface
    public interface GiftDropper {
        GiftResult drop(
                int sourceCharacterId,
                int targetCharacterId,
                int inventoryType,
                int slot,
                int quantity);
    }

    public record GiftResult(boolean success, String code) {
    }

    private static volatile GiftDropper giftDropper;
    private static volatile GearDropSourceProvider dropSources = ignored -> List.of();

    private CompanionRuntimeCapabilities() {
    }

    public static void install(GiftDropper dropper, GearDropSourceProvider sources) {
        giftDropper = Objects.requireNonNull(dropper, "dropper");
        dropSources = Objects.requireNonNull(sources, "sources");
    }

    public static void clear() {
        giftDropper = null;
        dropSources = ignored -> List.of();
    }

    public static GearDropSourceProvider dropSources() {
        return dropSources;
    }

    public static GiftResult dropGift(Character source, Character target, int itemId) {
        GiftDropper dropper = giftDropper;
        if (dropper == null) {
            return new GiftResult(false, "HOST_ITEM_ACTIONS_UNAVAILABLE");
        }
        Item item = source.getInventory(InventoryType.EQUIP).list().stream()
                .filter(candidate -> candidate instanceof Equip
                        && candidate.getItemId() == itemId
                        && candidate.getPosition() > 0)
                .min(Comparator
                        .comparingInt((Item candidate) -> equipScore((Equip) candidate))
                        .thenComparingInt(Item::getPosition))
                .orElse(null);
        if (item == null) {
            return new GiftResult(false, "ITEM_NOT_FOUND");
        }
        return dropper.drop(
                source.getId(), target.getId(), InventoryType.EQUIP.getType(),
                item.getPosition(), 1);
    }

    private static int equipScore(Equip equip) {
        return 100 * (equip.getWatk() + equip.getMatk())
                + 10 * (equip.getStr() + equip.getDex() + equip.getInt() + equip.getLuk())
                + equip.getWdef() + equip.getMdef();
    }
}
