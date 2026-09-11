package soloMapling.ArtificialPlayer.BotGrindSystem;

import org.gms.provider.Data;
import org.gms.provider.DataProvider;
import org.gms.provider.DataProviderFactory;
import org.gms.provider.DataTool;
import org.gms.provider.wz.WZFiles;

import java.util.HashMap;
import java.util.Map;

/**
 * Human-readable map names for the migration log, read once from String.wz.
 *
 * <p>A migration line is only useful if you can tell Ellinia from Orbis without
 * looking up an id, and the maps a line names are exactly the ones that are NOT
 * loaded: the destination continent is a whole crossing away and the pier being
 * boarded is usually on another continent entirely, so asking the live map for
 * its name returns nothing. The names come from WZ instead, which has them for
 * every map whether or not anybody is standing there.</p>
 *
 * <p>Lazy and best-effort on purpose. It is a diagnostic aid, so it must never
 * be the reason a bot fails to move: if WZ cannot be read the name falls back
 * to the id, which is what the rest of the logging already prints.</p>
 */
public final class BotPlaceNames {

    private BotPlaceNames() {
    }

    private static volatile Map<Integer, String> names;
    private static volatile boolean attempted;

    /**
     * "street - map" for mapId, or the id itself when WZ has no name for it.
     *
     * <p>Never throws and never returns null, so a log line is always printable.</p>
     */
    public static String name(int mapId) {
        Map<Integer, String> table = table();
        String name = table.get(mapId);
        return name != null ? name : String.valueOf(mapId);
    }

    private static Map<Integer, String> table() {
        Map<Integer, String> cached = names;
        if (cached != null) {
            return cached;
        }
        if (!attempted) {
            synchronized (BotPlaceNames.class) {
                if (!attempted) {
                    attempted = true;
                    names = build();
                    return names;
                }
            }
        }
        return Map.of(); // WZ unreadable — callers fall back to the id
    }

    private static Map<Integer, String> build() {
        Map<Integer, String> out = new HashMap<>();
        try {
            DataProvider provider = DataProviderFactory.getDataProvider(WZFiles.STRING);
            Data root = provider.getData("Map.img");
            if (root == null) {
                return out;
            }
            // Map.img is two levels deep: an area ("victoria", "maple", ...) whose children are
            // the maps themselves. Reading only the top level finds no ids at all and leaves the
            // table empty, which would silently print every name as a number.
            for (Data area : root.getChildren()) {
                for (Data map : area.getChildren()) {
                    int mapId;
                    try {
                        mapId = Integer.parseInt(map.getName());
                    } catch (NumberFormatException ignored) {
                        continue; // not a map entry
                    }
                    String name = DataTool.getString("mapName", map, null);
                    if (name == null) {
                        continue;
                    }
                    String street = DataTool.getString("streetName", map, null);
                    out.put(mapId, street == null || street.isBlank() ? name : street + " - " + name);
                }
            }
        } catch (Exception e) {
            System.err.println("[BotPlaceNames] Failed to load map names from String.wz: "
                    + e.getMessage());
        }
        return out;
    }
}
