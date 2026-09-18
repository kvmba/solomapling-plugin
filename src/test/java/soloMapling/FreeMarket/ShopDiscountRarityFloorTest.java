package soloMapling.FreeMarket;

import org.gms.client.inventory.Item;
import org.gms.server.maps.PlayerShopItem;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whole-store gimmicks (1-meso shop, quitting/cheap sale) used to rewrite every
 * listing, so a single lucky roll could place a 50m White Scroll on the shelf for
 * 1 meso. They must now leave the store's priciest slice (its top decile by
 * listing price) untouched.
 *
 * <p>The merchant is allocated without its constructor (which needs a live
 * {@code Character}/Spring context) and only its {@code items} list is injected -
 * that is all the discount helpers touch.
 */
class ShopDiscountRarityFloorTest {

    // A curated-rare id (Zakum Helmet (2)) and a plain, uncurated id (Elixir).
    private static final int CURATED_RARE_ID = 1002390;
    private static final int PLAIN_ID = 2000004;

    static {
        // rareStockMask consults the curated-rare table by id; load it the way
        // the plugin does at startup.
        soloMapling.itemPool.DesirableEquipList.load();
    }

    private static HiredMerchantArtificial merchantWith(int... prices) throws Exception {
        HiredMerchantArtificial merchant =
                (HiredMerchantArtificial) unsafe().allocateInstance(HiredMerchantArtificial.class);

        List<PlayerShopItem> items = new ArrayList<>();
        for (int price : prices) {
            items.add(new PlayerShopItem(new Item(PLAIN_ID, (short) 0, (short) 1), (short) 1, price));
        }

        Field itemsField = itemsField();
        itemsField.setAccessible(true);
        itemsField.set(merchant, items);
        return merchant;
    }

    /** Build a store where each item gets its own id/price pair. */
    private static HiredMerchantArtificial merchantOf(int[][] idPricePairs) throws Exception {
        HiredMerchantArtificial merchant =
                (HiredMerchantArtificial) unsafe().allocateInstance(HiredMerchantArtificial.class);
        List<PlayerShopItem> items = new ArrayList<>();
        for (int[] pair : idPricePairs) {
            items.add(new PlayerShopItem(new Item(pair[0], (short) 0, (short) 1), (short) 1, pair[1]));
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
    void oneMesoShopSparesThePriciestStock() throws Exception {
        HiredMerchantArtificial merchant = merchantWith(1_000, 50_000_000);
        ArtificialShopGenerator.setOneMesoShop(merchant);

        List<PlayerShopItem> items = itemsOf(merchant);
        assertEquals(1, items.get(0).getPrice(), "cheap stock should go to 1 meso");
        assertEquals(50_000_000, items.get(1).getPrice(), "the priciest item must be spared");
    }

    @Test
    void wholesaleDiscountSparesThePriciestStock() throws Exception {
        HiredMerchantArtificial merchant = merchantWith(1_000_000, 50_000_000);
        ArtificialShopGenerator.applyQuittingSaleDiscount(merchant); // x0.7

        List<PlayerShopItem> items = itemsOf(merchant);
        assertEquals(700_000, items.get(0).getPrice(), "cheap stock should be discounted");
        assertEquals(50_000_000, items.get(1).getPrice(), "the priciest item must not be discounted");
    }

    @Test
    void subTenMillionRareStockIsAlsoProtected() throws Exception {
        // An S-rank dark scroll / mastery book sitting well below the old 10m
        // hard floor used to be slashed to 1 meso. It is the store's priciest
        // item, so it is now protected.
        HiredMerchantArtificial merchant = merchantWith(5_000, 20_000, 4_500_000);
        ArtificialShopGenerator.setOneMesoShop(merchant);

        List<PlayerShopItem> items = itemsOf(merchant);
        assertEquals(4_500_000, items.get(2).getPrice(), "the sub-10m rare item must be spared");
        assertEquals(1, items.get(0).getPrice());
        assertEquals(1, items.get(1).getPrice());
    }

    @Test
    void onlyTheTopDecileIsProtected() throws Exception {
        // 10 items: exactly one (the most expensive) is protected.
        HiredMerchantArtificial merchant = merchantWith(
                1_000, 2_000, 3_000, 4_000, 5_000, 6_000, 7_000, 8_000, 9_000, 1_000_000);
        ArtificialShopGenerator.applyQuittingSaleDiscount(merchant); // x0.7

        List<PlayerShopItem> items = itemsOf(merchant);
        assertEquals(1_000_000, items.get(9).getPrice(), "top decile must be protected");
        for (int i = 0; i < 9; i++) {
            assertTrue(items.get(i).getPrice() < 1_000_000, "the rest must be discounted");
        }
    }

    @Test
    void singleItemStoreKeepsItsItem() throws Exception {
        HiredMerchantArtificial merchant = merchantWith(4_500_000);
        ArtificialShopGenerator.setOneMesoShop(merchant);

        List<PlayerShopItem> items = itemsOf(merchant);
        assertEquals(4_500_000, items.get(0).getPrice(), "a lone item is always the top decile");
    }

    @Test
    void curatedRareIsSparedEvenWhenItIsNotThePriciest() throws Exception {
        // Two items: a plain 50m item and a curated rare at 3m (below the 5m
        // absolute floor). The top-decile rule spares only the 50m item; without
        // the curated-id signal the 3m iconic rare would be fire-saled.
        HiredMerchantArtificial merchant = merchantOf(new int[][]{
                {PLAIN_ID, 50_000_000},
                {CURATED_RARE_ID, 3_000_000}});
        ArtificialShopGenerator.setOneMesoShop(merchant);

        List<PlayerShopItem> items = itemsOf(merchant);
        assertEquals(50_000_000, items.get(0).getPrice(), "top-decile item must be spared");
        assertEquals(3_000_000, items.get(1).getPrice(), "curated rare must be spared by id");
    }

    @Test
    void absoluteFloorSparesValuableUncuratedStockBeyondTheDecile() throws Exception {
        // Three plain items: the decile spares only the 50m one, but the 8m item
        // is also genuinely rare and must be spared by the absolute floor. The
        // 1m item is ordinary and still gets fire-saled.
        HiredMerchantArtificial merchant = merchantOf(new int[][]{
                {PLAIN_ID, 50_000_000},
                {PLAIN_ID, 8_000_000},
                {PLAIN_ID, 1_000_000}});
        ArtificialShopGenerator.setOneMesoShop(merchant);

        List<PlayerShopItem> items = itemsOf(merchant);
        assertEquals(50_000_000, items.get(0).getPrice(), "top-decile item must be spared");
        assertEquals(8_000_000, items.get(1).getPrice(), "above-floor item must be spared");
        assertEquals(1, items.get(2).getPrice(), "ordinary stock still goes to 1 meso");
    }

    @Test
    void curatedRareIsSparedByWholesaleDiscountToo() throws Exception {
        HiredMerchantArtificial merchant = merchantOf(new int[][]{
                {PLAIN_ID, 50_000_000},
                {CURATED_RARE_ID, 3_000_000}});
        ArtificialShopGenerator.applyQuittingSaleDiscount(merchant); // x0.7

        List<PlayerShopItem> items = itemsOf(merchant);
        assertEquals(50_000_000, items.get(0).getPrice(), "top-decile item must not be discounted");
        assertEquals(3_000_000, items.get(1).getPrice(), "curated rare must not be discounted");
    }
}
