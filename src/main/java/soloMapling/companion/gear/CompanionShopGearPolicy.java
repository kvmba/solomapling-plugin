package soloMapling.companion.gear;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Selects one affordable upgrade from a real NPC shop catalog.
 */
public final class CompanionShopGearPolicy {
    private CompanionShopGearPolicy() {
    }

    public record ShopOffer(short shopSlot, int price, CompanionGearPolicy.GearItem item) {
    }

    public static int potionMesosReserve(int level, int mesos) {
        int levelReserve = Math.max(1_000, Math.max(0, level) * 250);
        int proportionalReserve = Math.max(0, mesos) / 5;
        return Math.min(Math.max(levelReserve, proportionalReserve), Math.max(0, mesos));
    }

    public static Optional<ShopOffer> chooseUpgrade(
            int level,
            int gender,
            int jobMask,
            int mesos,
            int reserve,
            Collection<ShopOffer> catalog,
            Collection<CompanionGearPolicy.GearItem> equipped) {
        if (CompanionGearPolicy.modeForLevel(level) != CompanionGearPolicy.Mode.SHOP) {
            return Optional.empty();
        }
        int budget = Math.max(0, mesos - Math.max(0, reserve));
        List<ShopOffer> affordable = catalog.stream()
                .filter(offer -> offer != null && offer.item() != null)
                .filter(offer -> offer.price() >= 0 && offer.price() <= budget)
                .toList();
        Optional<CompanionGearPolicy.GearItem> choice = CompanionGearPolicy.bestUpgrade(
                affordable.stream().map(ShopOffer::item).toList(),
                equipped, level, gender, jobMask);
        if (choice.isEmpty()) {
            return Optional.empty();
        }
        int itemId = choice.orElseThrow().itemId();
        return affordable.stream()
                .filter(offer -> offer.item().itemId() == itemId)
                .min((left, right) -> {
                    int price = Integer.compare(left.price(), right.price());
                    return price != 0 ? price
                            : Short.compare(left.shopSlot(), right.shopSlot());
                });
    }
}
