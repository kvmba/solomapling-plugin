package soloMapling.itemPool;

import org.gms.constants.inventory.EquipType;
import org.gms.util.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Pool of equips that regular NPC shops actually sell (host tables
 * {@code shops} / {@code shopitems}), for dressing spawned bots in gear a real
 * player of that level could have simply bought.
 *
 * <p><b>One DB read, ever.</b> The shop table is read once into memory, deduped
 * by item id (the same equip is stocked by many NPCs) and kept only for equip
 * id ranges; entries are joined against the in-memory {@link
 * EquipMetadataCache} for reqLevel / reqJob / gender / cash flags. Only
 * <b>regular</b> shops count: the row must join to the {@code shops} table, its
 * NPC must not be in the 900xxxxx GM band, and the price must be meso-positive
 * — admin/event shops that hand out endgame gear for 1 meso are exactly the
 * "precious items on every bot" leak this blocks. Spawning never touches the
 * DB — a draw is a single indexed list read. A GM reload re-reads the table.</p>
 *
 * <p>Draws are probabilistic ({@link #DRAW_CHANCE}): when the shop pool offers
 * something wearable the caller still rolls, so most bots wear shop-bought
 * looks while the WZ-wide pool keeps boss drops / quest gear in rotation and
 * bots from dressing alike.</p>
 *
 * <p>Fails closed but never naked: with no loaded pool (or an empty shop table)
 * draws return null and callers fall back to the WZ-wide pools, exactly as
 * before this pool existed. There is deliberately no lazy load on the spawn
 * path — a missing pool must never turn into a DB query per spawn.</p>
 */
public final class ShopEquipPool {

    /**
     * Chance a slot draw comes from the shop pool when it offers something
     * wearable. Below 1 on purpose: a small pool drawn at 100% makes every bot
     * dress alike, so the remainder rolls into the WZ-wide pool (drops, quest
     * rewards, crafted gear) for variety. Single source of truth - the
     * ItemInformationProviderUtilities hook reads this, QuickEquip estimates it.
     */
    public static final double DRAW_CHANCE = 0.40;

    private static volatile Map<EquipType, List<EquipMetadataCache.EquipEntry>> byType = Map.of();
    private static volatile int totalEntries;

    private ShopEquipPool() {
    }

    public static boolean isLoaded() {
        return totalEntries > 0;
    }

    public static int size() {
        return totalEntries;
    }

    /**
     * Read the shop table once. No-op when already loaded; the GM reload path
     * calls {@link #forceReload()} directly.
     */
    public static synchronized void load() {
        if (isLoaded()) {
            return;
        }
        forceReload();
    }

    /**
     * Re-read the shop table (GM reload / init retry). Replaces the published
     * map atomically; concurrent draws keep serving the old map until then.
     */
    public static synchronized void forceReload() {
        long start = System.currentTimeMillis();
        Set<Integer> shopEquipIds = readShopEquipIds();
        List<EquipMetadataCache.EquipEntry> metadata = EquipMetadataCache.isInitialized()
                ? EquipMetadataCache.get().all()
                : List.of();
        Map<EquipType, List<EquipMetadataCache.EquipEntry>> pool = buildPool(shopEquipIds, metadata);
        byType = pool;
        int total = 0;
        for (List<EquipMetadataCache.EquipEntry> list : pool.values()) {
            total += list.size();
        }
        totalEntries = total;
        System.out.println("[ShopEquipPool] loaded " + total + " equips from "
                + shopEquipIds.size() + " distinct shop item ids in "
                + (System.currentTimeMillis() - start) + "ms");
    }

    /**
     * Draw a random shop-sold equip of the given type a bot of this level/
     * gender/job could wear, or null when nothing fits (or the pool isn't
     * loaded). {@code reqJob} is the style bitmask (1=warrior .. 16=pirate);
     * reqJob-0 (common) shop gear always passes.
     */
    public static Integer getRandomEquip(EquipType type, int maxLevel, int reqJob, int gender) {
        return draw(byType.get(type), maxLevel, reqJob, gender);
    }

    /**
     * Join the shop's distinct equip ids against WZ metadata, dropping cash
     * items and ids outside the tracked equip types. Package-visible pure
     * function: unit-tested without a DB.
     */
    static Map<EquipType, List<EquipMetadataCache.EquipEntry>> buildPool(
            Collection<Integer> shopEquipIds, List<EquipMetadataCache.EquipEntry> metadata) {
        Map<EquipType, List<EquipMetadataCache.EquipEntry>> next = new EnumMap<>(EquipType.class);
        for (EquipType type : EquipType.values()) {
            next.put(type, new ArrayList<>());
        }
        if (!shopEquipIds.isEmpty() && !metadata.isEmpty()) {
            for (EquipMetadataCache.EquipEntry e : metadata) {
                if (!shopEquipIds.contains(e.id) || e.cash) {
                    continue;
                }
                next.get(e.equipType).add(e);
            }
        }
        for (Map.Entry<EquipType, List<EquipMetadataCache.EquipEntry>> entry : next.entrySet()) {
            next.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return Map.copyOf(next);
    }

    /**
     * Filter a type list down to what this bot could wear and pick one at
     * random. Package-visible pure function: unit-tested without a DB.
     *
     * <p>Same level window as the WZ-wide pool: maxLevel - max(25% of
     * maxLevel, 10) &lt;= reqLevel &lt;= maxLevel — a bot wears shop gear close
     * to its level or the caller falls back to the wider pool, never a lv100
     * bot in a lv10 shop hat.</p>
     */
    static Integer draw(List<EquipMetadataCache.EquipEntry> list, int maxLevel, int reqJob, int gender) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        int minLevel = maxLevel - Math.max(maxLevel / 4, 10);
        List<Integer> fits = new ArrayList<>();
        for (EquipMetadataCache.EquipEntry e : list) {
            if (e.reqLevel < minLevel || e.reqLevel > maxLevel) {
                continue;
            }
            if (e.gender != 2 && e.gender != gender) {
                continue;
            }
            if (e.reqJob != 0 && (e.reqJob & reqJob) == 0) {
                continue;
            }
            if (EquipOmitList.isOmitted(e.id)) {
                continue;
            }
            fits.add(e.id);
        }
        if (fits.isEmpty()) {
            return null;
        }
        return fits.get(ThreadLocalRandom.current().nextInt(fits.size()));
    }

    /**
     * The distinct equip-range item ids currently stocked by any regular NPC
     * shop. Regular = joined against the {@code shops} table (orphan rows that
     * no NPC opens are skipped), not a GM/admin shop (NPC id band 900xxxxx,
     * e.g. the admin Fredrick selling endgame gear for 1 meso), and actually
     * bought with mesos (price &gt; 0). Events/giveaway shops hand out items for
     * 0-1 meso, so the price floor also blocks unintended free equip stock.
     */
    private static Set<Integer> readShopEquipIds() {
        Set<Integer> ids = new LinkedHashSet<>();
        String sql = """
                SELECT DISTINCT si.itemid
                  FROM shopitems si
                  JOIN shops sh ON sh.shopid = si.shopid
                 WHERE si.price > 0
                   AND sh.npcid NOT BETWEEN 9000000 AND 9009999
                """;
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                int id = rs.getInt("itemid");
                if (isEquipId(id)) {
                    ids.add(id);
                }
            }
        } catch (SQLException e) {
            System.err.println("[ShopEquipPool] failed to read shopitems: " + e.getMessage());
        }
        return ids;
    }

    /** Classic v83 equip id ranges (caps, body armours, shields, capes, weapons). */
    static boolean isEquipId(int id) {
        return (id >= 1000000 && id < 1010000)
                || (id >= 1030000 && id < 1040000)
                || (id >= 1040000 && id < 1050000)
                || (id >= 1050000 && id < 1060000)
                || (id >= 1060000 && id < 1070000)
                || (id >= 1070000 && id < 1080000)
                || (id >= 1080000 && id < 1090000)
                || (id >= 1092000 && id < 1099000)
                || (id >= 1102000 && id < 1110000)
                || (id >= 1122000 && id < 1130000)
                || (id >= 1300000 && id < 1500000);
    }
}
