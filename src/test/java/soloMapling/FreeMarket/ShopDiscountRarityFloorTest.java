package soloMapling.FreeMarket;

import org.gms.client.inventory.Item;
import org.gms.server.maps.PlayerShopItem;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Whole-store gimmicks (1-meso shop, quitting/cheap sale) used to rewrite every
 * listing, so a single lucky roll could place a 50m White Scroll on the shelf for
 * 1 meso. They must now leave stock at/above the rarity floor untouched.
 *
 * <p>The merchant is allocated without its constructor (which needs a live
 * {@code Character}/Spring context) and only its {@code items} list is injected -
 * that is all the discount helpers touch.
 */
class ShopDiscountRarityFloorTest {

    private static final int RARE_FLOOR = 10_000_000;

    private static HiredMerchantArtificial merchantWith(int... prices) throws Exception {
        Unsafe unsafe = unsafe();
        HiredMerchantArtificial merchant =
                (HiredMerchantArtificial) unsafe.allocateInstance(HiredMerchantArtificial.class);

        List<PlayerShopItem> items = new ArrayList<>();
        for (int price : prices) {
            items.add(new PlayerShopItem(new Item(2000004, (short) 0, (short) 1), (short) 1, price));
        }

        Field itemsField = itemsField();
        itemsField.setAccessible(true);
        itemsField.set(merchant, items);
        return merchant;
    }

    private static List<PlayerShopItem> itemsOf(HiredMerchantArtificial merchant) throws Exception {
        Field itemsField = itemsField();
        itemsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<PlayerShopItem> list = (List<PlayerShopItem>) itemsField.get(merchant);
        return list;
    }

    private static Field itemsField() {
        for (Class<?> c = HiredMerchantArtificial.class; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField("items");
            } catch (NoSuchFieldException ignored) {
                // declared on the gms HiredMerchant superclass - keep walking up
            }
        }
        throw new IllegalStateException("items field not found in hierarchy");
    }

    private static Unsafe unsafe() throws Exception {
        Field f = Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return (Unsafe) f.get(null);
    }

    @Test
    void oneMesoShopSparesRareStock() throws Exception {
        HiredMerchantArtificial merchant = merchantWith(1_000, 50_000_000);
        ArtificialShopGenerator.setOneMesoShop(merchant);

        List<PlayerShopItem> items = itemsOf(merchant);
        assertEquals(1, items.get(0).getPrice(), "cheap stock should go to 1 meso");
        assertEquals(50_000_000, items.get(1).getPrice(), "rare stock must be spared");
    }

    @Test
    void wholesaleDiscountSparesRareStock() throws Exception {
        HiredMerchantArtificial merchant = merchantWith(1_000_000, 50_000_000);
        ArtificialShopGenerator.applyQuittingSaleDiscount(merchant); // x0.7

        List<PlayerShopItem> items = itemsOf(merchant);
        assertEquals(700_000, items.get(0).getPrice(), "cheap stock should be discounted");
        assertEquals(50_000_000, items.get(1).getPrice(), "rare stock must not be discounted");
    }

    @Test
    void floorBoundaryIsInclusive() throws Exception {
        HiredMerchantArtificial merchant = merchantWith(RARE_FLOOR - 1, RARE_FLOOR);
        ArtificialShopGenerator.applyCheapSaleDiscount(merchant); // x0.85

        List<PlayerShopItem> items = itemsOf(merchant);
        assertEquals((int) ((RARE_FLOOR - 1) * 0.85), items.get(0).getPrice(),
                "just below the floor is still discounted");
        assertEquals(RARE_FLOOR, items.get(1).getPrice(),
                "exactly at the floor is protected");
    }
}
