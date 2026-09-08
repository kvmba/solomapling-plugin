package soloMapling.itemPool;

import com.esotericsoftware.yamlbeans.YamlReader;
import org.gms.client.inventory.Equip;
import org.gms.client.inventory.Item;
import org.gms.server.ItemInformationProvider;
import soloMapling.Environment.PluginResources;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static soloMapling.ArtificialPlayer.BotHelpers.isUnusableItem;
import static soloMapling.BotLogger.log;

/**
 * The gacha jackpot pool, built for a joke: every equip in here is a name drop
 * (Zakum Helm, Maple weapons, Skull Bear Shield...) whose stats have been cut
 * down to nothing. A player sees something that looks like a boss drop land on
 * the floor, picks it up, and finds a 1-def hat.
 * <p>
 * The degradation is deliberately brutal rather than a token -1: a Zakum Helm
 * carries 400 points of stats in its wz entry, so shaving one off each stat is
 * invisible. Anything that reaches the floor as a "prize" has to read as junk
 * at a glance, and the gap between what the name promises and what the item
 * delivers is the entire point.
 */
public class GachaPrizePool {

    private static final String PRIZE_POOL_PATH = "itemPool/itemConfig/gachaPrizePool.yaml";

    /** Every stat is divided by this, then capped. 400 -> 8, 188 -> 1 (then capped to 1). */
    private static final int STAT_DIVISOR = 10;
    /** No single stat survives above this, however large it started. */
    private static final int STAT_CAP = 1;

    private static final Random random = new Random();

    private final List<Entry> equips = new ArrayList<>();
    private final List<Entry> items = new ArrayList<>();
    private int equipTotalWeight = 0;
    private int itemTotalWeight = 0;

    public static class Entry {
        public final int itemId;
        public final int weight;

        Entry(int itemId, int weight) {
            this.itemId = itemId;
            this.weight = weight;
        }
    }

    public static GachaPrizePool load() {
        GachaPrizePool pool = new GachaPrizePool();
        try (var reader = PluginResources.openReader(PRIZE_POOL_PATH)) {
            Map<String, Object> root = (Map<String, Object>) new YamlReader(reader).read();
            if (root == null) {
                log("GachaPrizePool: empty pool file");
                return pool;
            }
            pool.equipTotalWeight = loadSection(root, "equips", pool.equips);
            pool.itemTotalWeight = loadSection(root, "items", pool.items);
        } catch (Exception e) {
            log("GachaPrizePool: failed to load " + PRIZE_POOL_PATH + ": " + e.getMessage());
        }
        return pool;
    }

    private static int loadSection(Map<String, Object> root, String key, List<Entry> into) {
        List<Map<String, Object>> rows = (List<Map<String, Object>>) root.get(key);
        if (rows == null) return 0;

        int total = 0;
        for (Map<String, Object> row : rows) {
            int itemId = Integer.parseInt(String.valueOf(row.get("id")));
            int weight = row.get("weight") == null
                    ? 1
                    : Integer.parseInt(String.valueOf(row.get("weight")));
            if (weight <= 0) continue;

            // An id with no localized name is half-finished WZ data. Dropping it
            // would put raw English on the floor, and CustomReactor now refuses
            // those anyway - so a rejected id would silently become no drop at all.
            if (isUnusableItem(itemId)) {
                log("GachaPrizePool: skipping unlocalized " + key + " id " + itemId);
                continue;
            }
            into.add(new Entry(itemId, weight));
            total += weight;
        }
        return total;
    }

    /**
     * Pick one entry from the whole pool. Equips and items are weighted against
     * each other by their section totals, so neither side can starve the other.
     */
    public Entry roll() {
        int total = equipTotalWeight + itemTotalWeight;
        if (total <= 0) return null;

        int roll = random.nextInt(total);
        return roll < equipTotalWeight
                ? pick(equips, equipTotalWeight)
                : pick(items, itemTotalWeight);
    }

    private Entry pick(List<Entry> list, int total) {
        if (list.isEmpty() || total <= 0) return null;
        int roll = random.nextInt(total);
        int cumulative = 0;
        for (Entry e : list) {
            cumulative += e.weight;
            if (roll < cumulative) return e;
        }
        return list.get(list.size() - 1);
    }

    public boolean isEmpty() {
        return equips.isEmpty() && items.isEmpty();
    }

    public int size() {
        return equips.size() + items.size();
    }

    // =========================================================================
    // STAT DEGRADATION
    // =========================================================================

    /**
     * Strip an equip down to a look-alike. Only the {@code inc*} stats are
     * touched - upgrade slots and the level requirement stay exactly as the wz
     * defines them, because changing those alters what a player can do with the
     * item (and lowering reqLevel would make it easier to wear, which is the
     * opposite of junk).
     * <p>
     * The result is floored at 0: these fields are bare shorts with no clamping,
     * and most equips already carry 0 in STR/DEX/INT/LUK, so a plain decrement
     * would ship a -1 to the client.
     */
    public static void degrade(Equip equip) {
        if (equip == null) return;
        equip.setStr(cut(equip.getStr()));
        equip.setDex(cut(equip.getDex()));
        equip.setInt(cut(equip.getInt()));
        equip.setLuk(cut(equip.getLuk()));
        equip.setWatk(cut(equip.getWatk()));
        equip.setWdef(cut(equip.getWdef()));
        equip.setMatk(cut(equip.getMatk()));
        equip.setMdef(cut(equip.getMdef()));
        equip.setAcc(cut(equip.getAcc()));
        equip.setAvoid(cut(equip.getAvoid()));
        equip.setSpeed(cut(equip.getSpeed()));
        equip.setJump(cut(equip.getJump()));
        equip.setHp(cut(equip.getHp()));
        equip.setMp(cut(equip.getMp()));
    }

    /**
     * Divide down, cap, and never go negative. Idempotent: once a stat is at or
     * below the cap it is left exactly as it is, so repeating the pass can never
     * keep eroding an item (1 / 10 would otherwise round back to 0).
     */
    static short cut(short value) {
        if (value <= 0) return 0;
        if (value <= STAT_CAP) return (short) value; // already junk; settle there
        int cut = value / STAT_DIVISOR;
        if (cut > STAT_CAP) cut = STAT_CAP;
        return (short) Math.max(0, cut);
    }

    /**
     * Build the prize item. Equips come out of the wz as a clean white base and
     * are then gutted; anything else is a plain stack.
     */
    public static Item buildPrize(int itemId) {
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        Item base = ii.getEquipById(itemId);
        if (base instanceof Equip equip) {
            degrade(equip);
            return equip;
        }
        return base;
    }
}
