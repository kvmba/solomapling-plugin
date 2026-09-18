package soloMapling.itemPool;

import com.esotericsoftware.yamlbeans.YamlReader;
import soloMapling.Environment.PluginResources;

import java.util.*;

/**
 * Curated rare / best-in-slot gear registry. Two independent pieces of data:
 *
 * <ul>
 *   <li><b>Whitelist</b> ({@link #isDesirable}) — items that bypass the price
 *       floor and level-band filters in {@code generateEquipListIIPU} because
 *       they are desirable despite being low-level / low-WZ-price.</li>
 *   <li><b>Override prices</b> ({@link #getRarePrice}) — market prices for
 *       genuinely rare gear, keyed to 国服 (ChinaMS) v83 conventions. WZ price
 *       is a shop-resale number, not a market value, so the most sought-after
 *       items (Zakum Helm, Brown Work Gloves, …) otherwise listed for less than
 *       junk. Anything not listed prices exactly as before.</li>
 * </ul>
 *
 * Both files are optional and fail open: a missing/broken file just leaves the
 * respective map empty, so shop generation degrades to the old behavior rather
 * than throwing.
 */
public class DesirableEquipList {

    private static final String YAML_PATH = "itemPool/itemConfig/desirableEquips.yaml";
    private static final String PRICES_PATH = "itemPool/itemConfig/rareItemPrices.yaml";

    private static final Set<Integer> desirableIds = new HashSet<>();
    private static final Map<Integer, Integer> rarePrices = new HashMap<>();
    private static boolean loaded = false;

    public static synchronized void load() {
        if (loaded) return;
        long start = System.currentTimeMillis();
        loadWhitelist();
        loadRarePrices();
        loaded = true;
        System.out.println("[DesirableEquipList] Loaded " + desirableIds.size() + " whitelisted ids and "
                + rarePrices.size() + " rare prices in " + (System.currentTimeMillis() - start) + "ms");
    }

    @SuppressWarnings("unchecked")
    private static void loadWhitelist() {
        try {
            YamlReader reader = new YamlReader(PluginResources.openReader(YAML_PATH));
            Map<String, List<String>> categories = (Map<String, List<String>>) reader.read();
            if (categories == null) {
                return;
            }
            for (Map.Entry<String, List<String>> entry : categories.entrySet()) {
                List<String> ids = entry.getValue();
                if (ids == null) continue;
                for (String raw : ids) {
                    try {
                        desirableIds.add(Integer.parseInt(raw.toString().trim()));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[DesirableEquipList] Failed to load whitelist: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static void loadRarePrices() {
        try {
            YamlReader reader = new YamlReader(PluginResources.openReader(PRICES_PATH));
            Map<Object, Object> entries = (Map<Object, Object>) reader.read();
            if (entries == null) {
                return;
            }
            for (Map.Entry<Object, Object> entry : entries.entrySet()) {
                try {
                    int id = Integer.parseInt(entry.getKey().toString().trim());
                    int price = (int) Long.parseLong(entry.getValue().toString().trim());
                    if (price > 0) {
                        rarePrices.put(id, price);
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (Exception e) {
            System.err.println("[DesirableEquipList] Failed to load rare prices: " + e.getMessage());
        }
    }

    public static boolean isDesirable(int itemId) {
        return desirableIds.contains(itemId);
    }

    public static Set<Integer> getAll() {
        return Collections.unmodifiableSet(desirableIds);
    }

    /** Overridden market price for genuinely rare gear, or {@code null} if the
     * item prices normally. See {@code rareItemPrices.yaml} for the table. */
    public static Integer getRarePrice(int itemId) {
        return rarePrices.get(itemId);
    }
}
