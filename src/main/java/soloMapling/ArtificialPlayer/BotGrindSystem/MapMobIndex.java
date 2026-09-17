package soloMapling.ArtificialPlayer.BotGrindSystem;

import org.gms.provider.Data;
import org.gms.provider.DataProvider;
import org.gms.provider.DataProviderFactory;
import org.gms.provider.DataTool;
import org.gms.provider.wz.WZFiles;
import org.gms.server.life.LifeFactory;
import org.gms.server.life.Monster;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// Cached map -> representative (median) mob level + mob count, read straight from WZ: Map.wz `life`
// entries of type "m" give the mob ids (following info/link like MapFactory), and each mob's level
// comes from its MonsterStats. Lazy per map, cached forever after. Deterministic - no recordings, no
// config. Maps with no mobs (towns) report level -1 and are naturally skipped by callers.
//
// Two counts are kept per map, and the difference matters:
//   - mobCount / medianLevel cover EVERY mob spawn, including non-combat "exhibit" mobs (boss-flagged
//     caged/display animals with no meaningful exp — e.g. the zoo on 230000003). Town-presence and
//     death/field consumers want "does this map have mobs at all".
//   - huntableCount / huntableMedianLevel cover only mobs a bot may actually grind (not boss-flagged,
//     positive exp). A training bot must key off THESE, or it will pick a caged-exhibit map as a
//     hunting ground and swing at animals that grant nothing.
//
// Our own creation. Lets a TrainingBot discover level-appropriate nearby maps without a hand-authored
// table.
public final class MapMobIndex {

    private MapMobIndex() {
    }

    // A static spawn point: raw WZ position (x, cy) plus the ledge key of the foothold it sits on —
    // derived from the WZ foothold tree's layer/group structure (-1 when the fh id is unknown). Not the
    // nav graph's region id, but the same physical grouping, so the capacity estimator can split spots
    // per ledge the way the live profile does without loading the map.
    public record SpawnPos(int x, int y, int fhGroup) {
    }

    // medianLevel/mobCount span EVERY mob spawn (incl. exhibit/boss "display" mobs) — the "has mobs at
    // all" view. huntableMedianLevel/huntableCount span only grindable mobs (not boss-flagged, exp>0),
    // which is the count a TRAINING bot must select by. Both are -1 / 0 when the respective set is empty.
    public record MapMobInfo(int medianLevel, int mobCount, int huntableMedianLevel, int huntableCount,
                             List<Integer> mobIds, List<SpawnPos> spawnPoints) {
        static final MapMobInfo NONE = new MapMobInfo(-1, 0, -1, 0, List.of(), List.of());
    }

    // Shared, lazily-created WZ handle. Deliberately NOT a ThreadLocal: bot ticks are dispatched
    // onto virtual threads (a fresh one per tick), so a ThreadLocal built an entire DataProvider
    // per tick — and each construction walks the whole Map.wz tree (~65ms, 5.7k files).
    //
    // Thread safety: the returned provider is a plain XMLWZFile (Map.wz is not localized), whose
    // state is two final fields populated in the constructor and never mutated afterwards, and
    // whose getData is synchronized. Sharing one instance is therefore safe, and matches how the
    // host itself holds its providers (MapFactory, LifeFactory). Verified with 12k concurrent
    // reads across 8 threads: no cross-thread corruption.
    //
    // Every lookup is memoized in CACHE below, so the read path runs at most once per map for the
    // lifetime of the process.
    private static volatile DataProvider MAP_SOURCE;

    private static DataProvider mapSource() {
        DataProvider source = MAP_SOURCE;
        if (source == null) {
            synchronized (MapMobIndex.class) {
                source = MAP_SOURCE;
                if (source == null) {
                    source = DataProviderFactory.getDataProvider(WZFiles.MAP);
                    MAP_SOURCE = source;
                }
            }
        }
        return source;
    }

    private static final Map<Integer, MapMobInfo> CACHE = new ConcurrentHashMap<>();
    private static final Map<Integer, Integer> MOB_LEVEL = new ConcurrentHashMap<>();
    private static final Map<Integer, Boolean> HUNTABLE = new ConcurrentHashMap<>();

    // Representative (median) mob level of a map, or -1 if it has no mobs (towns, etc.).
    public static int level(int mapId) {
        return info(mapId).medianLevel();
    }

    // Number of mob spawn entries on a map.
    public static int mobCount(int mapId) {
        return info(mapId).mobCount();
    }

    // Mob ids the map is defined to spawn (from WZ life data), or empty for mobless maps (towns).
    public static List<Integer> mobIds(int mapId) {
        return info(mapId).mobIds();
    }

    // Raw WZ spawn points (x, cy, foothold-group ledge key). Static data straight off the map img — no
    // live map load, no nav bake — so DECIDE can estimate a map's claimable-spot count before any bot
    // ever grinds it.
    public static List<SpawnPos> spawnPoints(int mapId) {
        return info(mapId).spawnPoints();
    }

    public static MapMobInfo info(int mapId) {
        return CACHE.computeIfAbsent(mapId, MapMobIndex::compute);
    }

    private static MapMobInfo compute(int mapId) {
        try {
            Data mapData = loadMapData(mapId);
            if (mapData == null) {
                return MapMobInfo.NONE;
            }
            Data life = mapData.getChildByPath("life");
            if (life == null) {
                return MapMobInfo.NONE;
            }
            Map<Integer, Integer> fhGroups = footholdGroups(mapData);
            List<Integer> levels = new ArrayList<>();
            List<Integer> huntableLevels = new ArrayList<>();
            List<Integer> mobIds = new ArrayList<>();
            List<SpawnPos> positions = new ArrayList<>();
            for (Data entry : life) {
                if (!"m".equals(DataTool.getString("type", entry, ""))) {
                    continue;
                }
                String idStr = DataTool.getString("id", entry, "");
                if (idStr.isEmpty()) {
                    continue;
                }
                int mobId;
                try {
                    mobId = Integer.parseInt(idStr);
                } catch (NumberFormatException e) {
                    continue;
                }
                int lvl = mobLevel(mobId);
                if (lvl > 0) {
                    levels.add(lvl);
                    if (isHuntable(mobId)) {
                        huntableLevels.add(lvl);
                    }
                    mobIds.add(mobId);
                    // x + cy is what MapFactory feeds calcPointBelow for the live spawn point; close
                    // enough for cluster counting without loading the map.
                    int x = DataTool.getInt("x", entry, 0);
                    int cy = DataTool.getInt("cy", entry, DataTool.getInt("y", entry, 0));
                    int fh = DataTool.getInt("fh", entry, -1);
                    positions.add(new SpawnPos(x, cy, fhGroups.getOrDefault(fh, -1)));
                }
            }
            if (levels.isEmpty()) {
                return MapMobInfo.NONE;
            }
            Collections.sort(levels);
            Collections.sort(huntableLevels);
            int huntableMedian = huntableLevels.isEmpty()
                    ? -1 : huntableLevels.get(huntableLevels.size() / 2);
            return new MapMobInfo(levels.get(levels.size() / 2), levels.size(),
                    huntableMedian, huntableLevels.size(), mobIds, positions);
        } catch (RuntimeException e) {
            return MapMobInfo.NONE;
        }
    }

    // fh id -> a per-map ledge key from the WZ foothold tree (one key per layer/group node). Footholds in
    // the same group form one connected walkable run, so this mirrors the nav graph's ledge notion closely
    // enough for the estimator's per-ledge spot split.
    private static Map<Integer, Integer> footholdGroups(Data mapData) {
        Map<Integer, Integer> out = new HashMap<>();
        Data root = mapData.getChildByPath("foothold");
        if (root == null) {
            return out;
        }
        int key = 0;
        for (Data layer : root) {
            for (Data group : layer) {
                key++;
                for (Data f : group) {
                    try {
                        out.put(Integer.parseInt(f.getName()), key);
                    } catch (NumberFormatException ignored) {
                        // non-numeric foothold node — skip
                    }
                }
            }
        }
        return out;
    }

    private static Data loadMapData(int mapId) {
        DataProvider src = mapSource();
        Data mapData = src.getData(mapImgPath(mapId));
        if (mapData == null) {
            return null;
        }
        Data info = mapData.getChildByPath("info");
        String link = info != null ? DataTool.getString("link", info, "") : "";
        if (!link.isEmpty()) {
            try {
                Data linked = src.getData(mapImgPath(Integer.parseInt(link)));
                if (linked != null) {
                    return linked;
                }
            } catch (NumberFormatException ignored) {
                // malformed link — use the map as-is
            }
        }
        return mapData;
    }

    private static int mobLevel(int mobId) {
        return MOB_LEVEL.computeIfAbsent(mobId, id -> {
            try {
                Monster m = LifeFactory.getMonster(id);
                return (m == null || m.getStats() == null) ? -1 : m.getStats().getLevel();
            } catch (RuntimeException e) {
                return -1;
            }
        });
    }

    // Whether a bot may make a grind target of this mob: not a boss, and grants exp. Boss-flagged mobs
    // are summons, gate-keepers and CAGED DISPLAY ANIMALS (the Aquarium zoo: 9500200-9500204, boss=1,
    // exp 0-10) — content a training bot must never treat as a hunting ground, however many of them a
    // map lists. exp>0 additionally drops exp-less script props (e.g. 6130201 小鬼怪, exp 0) that sit on
    // an otherwise-mobless map and would otherwise read as grindable.
    //
    // Memoized like MOB_LEVEL: LifeFactory.getMonster is the expensive part and is already cached by the
    // level lookup, so this reads cheap after the first touch per mob.
    private static boolean isHuntable(int mobId) {
        return HUNTABLE.computeIfAbsent(mobId, id -> {
            try {
                Monster m = LifeFactory.getMonster(id);
                if (m == null || m.getStats() == null) {
                    return false;
                }
                return !m.isBoss() && m.getExp() > 0;
            } catch (RuntimeException e) {
                return false;
            }
        });
    }

    private static String mapImgPath(int mapId) {
        return "Map/Map" + (mapId / 100000000) + "/" + String.format("%09d", mapId) + ".img";
    }
}
