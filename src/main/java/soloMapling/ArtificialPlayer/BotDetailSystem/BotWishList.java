package soloMapling.ArtificialPlayer.BotDetailSystem;

import org.gms.client.Character;
import org.gms.constants.id.ItemId;
import org.gms.dao.entity.ModifiedCashItemDO;
import org.gms.server.CashShop;
import org.gms.server.CashShop.CashItemFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Presets the cash-shop wishlist (心愿单 / "想要购买的道具") a bot shows in its character-info window.
 *
 * <p>The window writes the bot's wishlist serial numbers (up to 10 - the client's wishlist
 * capacity). Bots inherited the {@code fmbot} template's (shared, empty) wishlist.
 *
 * <p>The catalog is the host's live cash-shop item map, filtered to on-sale, non-package items.
 * That map is replaced wholesale by a GM reload, so the filtered pool is cached against the map's
 * identity and rebuilt only when the shop actually changes - a spawn never re-scans 8k items.
 */
public final class BotWishList {

    /** The client wishlist holds at most 10 entries (see CashOperationHandler). */
    static final int MAX_WISHLIST = 10;

    private static final int SALT = 0x4D42_0003;
    private static final int LEVEL_FLOOR = 10;
    private static final int LEVEL_CEIL = 80;

    /** The filtered pool plus the catalog map it was derived from, published as one reference. */
    private record CachedPool(Map<Integer, ModifiedCashItemDO> source, List<Integer> pool) {
    }

    private static volatile CachedPool cached;

    private BotWishList() {
    }

    /** Write a deterministic wishlist onto the bot. No-op when the shop is empty. */
    public static void apply(Character bot) {
        if (bot == null) {
            return;
        }
        List<Integer> pool = pool();
        if (pool.isEmpty()) {
            return;
        }
        var cashShop = bot.getCashShop();
        cashShop.clearWishList();
        for (int sn : select(bot.getId(), bot.getLevel(), pool)) {
            cashShop.addToWishList(sn);
        }
    }

    /** Empty the bot's wishlist (for a re-roll). */
    public static void clear(Character bot) {
        if (bot != null) {
            bot.getCashShop().clearWishList();
        }
    }

    /** On-sale, non-package SNs from the host catalog, cached against the catalog's identity. */
    static List<Integer> pool() {
        Map<Integer, ModifiedCashItemDO> source = CashItemFactory.getItems();
        CachedPool current = cached;
        if (current != null && current.source() == source) {
            return current.pool();
        }
        List<Integer> pool = new ArrayList<>();
        for (Map.Entry<Integer, ModifiedCashItemDO> e : source.entrySet()) {
            ModifiedCashItemDO item = e.getValue();
            if (item != null && item.isSelling() && !ItemId.isCashPackage(item.getItemId())) {
                pool.add(e.getKey());
            }
        }
        CachedPool built = new CachedPool(source, Collections.unmodifiableList(pool));
        cached = built;
        return built.pool();
    }

    /**
     * Deterministically choose SNs: a level-scaled count (0 below the level floor, up to
     * {@link #MAX_WISHLIST}), sampled per cid. Pure, so it is unit-testable without the host.
     */
    static List<Integer> select(int cid, int level, List<Integer> pool) {
        if (level < LEVEL_FLOOR || pool.isEmpty()) {
            return List.of();
        }
        double t = Math.min(1.0, (double) (level - LEVEL_FLOOR) / (LEVEL_CEIL - LEVEL_FLOOR));
        int k = (int) Math.round(t * MAX_WISHLIST);
        List<Integer> picked = BotDetailRoll.sample(cid, SALT, pool, k);
        Collections.sort(picked);
        return picked;
    }
}
