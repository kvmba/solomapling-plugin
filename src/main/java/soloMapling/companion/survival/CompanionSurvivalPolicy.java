package soloMapling.companion.survival;

import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;

/**
 * Pure deterministic policy for companion potion use and restocking.
 */
public final class CompanionSurvivalPolicy {
    public static final int HP_USE_BASIS_POINTS = 6_000;
    public static final int MP_USE_BASIS_POINTS = 3_500;
    public static final int LOW_STOCK = 12;
    public static final int TARGET_STOCK = 60;
    public static final int INVENTORY_RESERVE_SLOTS = 2;

    private CompanionSurvivalPolicy() {
    }

    public enum Resource {
        HP,
        MP
    }

    public record Potion(
            int itemId,
            int quantity,
            int hpRestore,
            int mpRestore,
            int unitPrice,
            short shopSlot) {
        public Potion {
            if (itemId <= 0 || quantity < 0 || hpRestore < 0 || mpRestore < 0
                    || unitPrice < 0 || shopSlot < -1) {
                throw new IllegalArgumentException("invalid potion candidate");
            }
            if (hpRestore == 0 && mpRestore == 0) {
                throw new IllegalArgumentException("a potion must restore HP or MP");
            }
        }

        public int restore(Resource resource) {
            return resource == Resource.HP ? hpRestore : mpRestore;
        }
    }

    /**
     * Whether a potion should be used now.
     *
     * <p>{@code current == 0} is a USE case, not an exclusion: the host clamps HP at zero
     * ({@code AbstractCharacterObject.setHp}) but heals normally from there ({@code addHP} →
     * {@code updateHp} only clamps the bounds), so a companion drained to 0 HP by a decHP map
     * (Aqua Road / El Nath / Orbis Tower) is exactly the character that most needs the sip.
     * Rejecting zero here - and again on the controller's {@code isAlive()} check - left it with
     * no recovery path at all: the death episode refuses companions, BotPotionSim skips them,
     * and no server-side regen reaches a headless bot.
     */
    public static boolean shouldUse(
            Resource resource, int current, int maximum) {
        if (maximum <= 0 || current < 0 || current >= maximum) {
            return false;
        }
        int threshold = resource == Resource.HP
                ? HP_USE_BASIS_POINTS : MP_USE_BASIS_POINTS;
        return (long) current * 10_000L <= (long) maximum * threshold;
    }

    public static Optional<Potion> chooseForUse(
            Resource resource,
            int current,
            int maximum,
            Collection<Potion> inventory) {
        if (!shouldUse(resource, current, maximum) || inventory == null) {
            return Optional.empty();
        }
        int deficit = maximum - current;
        Comparator<Potion> enoughComparator = Comparator
                .comparingInt((Potion potion) -> potion.restore(resource) - deficit)
                .thenComparingInt(Potion::unitPrice)
                .thenComparingInt(Potion::itemId);
        Optional<Potion> enough = inventory.stream()
                .filter(potion -> potion.quantity() > 0)
                .filter(potion -> potion.restore(resource) >= deficit)
                .min(enoughComparator);
        if (enough.isPresent()) {
            return enough;
        }
        return inventory.stream()
                .filter(potion -> potion.quantity() > 0)
                .filter(potion -> potion.restore(resource) > 0)
                .max(Comparator
                        .comparingInt((Potion potion) -> potion.restore(resource))
                        .thenComparing(Comparator.comparingInt(Potion::unitPrice).reversed())
                        .thenComparing(Comparator.comparingInt(Potion::itemId).reversed()));
    }

    public static boolean needsRestock(int stock) {
        return stock < LOW_STOCK;
    }

    public static int restockQuantity(int stock) {
        return Math.max(0, TARGET_STOCK - Math.max(0, stock));
    }

    public static Optional<Potion> chooseForPurchase(
            Resource resource,
            int maximum,
            int availableMesos,
            Collection<Potion> catalog) {
        if (maximum <= 0 || availableMesos <= 0 || catalog == null) {
            return Optional.empty();
        }
        int usefulDose = Math.max(1, maximum / 5);
        Comparator<Potion> byCostEfficiency = Comparator
                .comparingLong((Potion potion) ->
                        (long) potion.unitPrice() * 1_000_000L
                                / Math.max(1, Math.min(maximum, potion.restore(resource))))
                .thenComparingInt(Potion::unitPrice)
                .thenComparingInt(Potion::itemId);
        Optional<Potion> useful = catalog.stream()
                .filter(potion -> potion.shopSlot() >= 0)
                .filter(potion -> potion.unitPrice() > 0
                        && potion.unitPrice() <= availableMesos)
                .filter(potion -> potion.restore(resource) >= usefulDose)
                .min(byCostEfficiency);
        if (useful.isPresent()) {
            return useful;
        }
        return catalog.stream()
                .filter(potion -> potion.shopSlot() >= 0)
                .filter(potion -> potion.unitPrice() > 0
                        && potion.unitPrice() <= availableMesos)
                .filter(potion -> potion.restore(resource) > 0)
                .max(Comparator
                        .comparingInt((Potion potion) -> potion.restore(resource))
                        .thenComparing(Comparator.comparingInt(Potion::unitPrice).reversed())
                        .thenComparing(Comparator.comparingInt(Potion::itemId).reversed()));
    }

    public static int affordableQuantity(
            int desiredQuantity, int unitPrice, int availableMesos) {
        if (desiredQuantity <= 0 || unitPrice <= 0 || availableMesos <= 0) {
            return 0;
        }
        return Math.min(desiredQuantity, availableMesos / unitPrice);
    }

    public static boolean inventoryPressure(int freeSlots) {
        return freeSlots <= INVENTORY_RESERVE_SLOTS;
    }

    /**
     * Whether a USE-inventory item may be sold to clear a slot.
     *
     * <p>Whitelist, not blacklist: the USE tab is where scrolls, return scrolls and other
     * consumables land alongside potions (the host files every 204xxxx item there), and the old
     * filter - "not rechargeable and no hp/mp effect" - let all of them go at NPC price under
     * inventory pressure, which reads as a companion dumping its own valuables. Only the potions
     * this system itself recognises are sellable, so the tab's real contents are never collateral.
     *
     * @param hpRestore the item's flat HP restore, 0 when it restores none
     * @param mpRestore the item's flat MP restore, 0 when it restores none
     */
    public static boolean isSellableUseItem(int hpRestore, int mpRestore) {
        return hpRestore > 0 || mpRestore > 0;
    }
}
