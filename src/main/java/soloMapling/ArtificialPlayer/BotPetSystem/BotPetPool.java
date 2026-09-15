package soloMapling.ArtificialPlayer.BotPetSystem;

import com.esotericsoftware.yamlbeans.YamlReader;
import org.gms.constants.inventory.ItemConstants;
import soloMapling.Environment.PluginResources;

import java.io.Reader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The pool of pet item ids a bot may be given. Curated via YAML; validated with
 * the host's own {@code ItemConstants.isPet(itemId)} ({@code 500xxxx}), so a
 * stray non-pet id can never make it into the pool.
 *
 * <p>Loaded lazily on first use. A missing/empty file yields an empty pool and
 * the whole feature degrades to "no pets" rather than throwing at spawn time.</p>
 */
public final class BotPetPool {

    public static final String RESOURCE_PATH = "ArtificialPlayer/BotPetSystem/BotPetPool.yaml";

    private static volatile List<Integer> pets = List.of();

    private BotPetPool() {
    }

    /** True once {@link #load()} has found at least one valid pet id. */
    public static boolean isLoaded() {
        return !pets.isEmpty();
    }

    /** All validated pet ids (immutable). Empty until {@link #load()} succeeds. */
    public static List<Integer> all() {
        return pets;
    }

    public static int size() {
        return pets.size();
    }

    @SuppressWarnings("unchecked")
    public static synchronized void load() {
        if (!pets.isEmpty()) {
            return;
        }
        try {
            if (!PluginResources.exists(RESOURCE_PATH)) {
                System.out.println("[BotPetPool] no " + RESOURCE_PATH + " — bot pets disabled");
                return;
            }
            try (Reader reader = PluginResources.openReader(RESOURCE_PATH)) {
                Object parsed = new YamlReader(reader).read();
                if (!(parsed instanceof Map<?, ?> raw)) {
                    return;
                }
                Object listRaw = ((Map<String, Object>) raw).get("pets");
                if (!(listRaw instanceof List<?> list)) {
                    return;
                }
                List<Integer> loaded = new ArrayList<>();
                Set<Integer> seen = new HashSet<>();
                for (Object entry : list) {
                    Integer id = toId(entry);
                    if (id != null && ItemConstants.isPet(id) && seen.add(id)) {
                        loaded.add(id);
                    }
                }
                pets = List.copyOf(loaded);
                System.out.println("[BotPetPool] loaded " + pets.size() + " pet ids");
            }
        } catch (Exception e) {
            System.err.println("[BotPetPool] failed to load " + RESOURCE_PATH + ": " + e.getMessage());
        }
    }

    /** Force a reload from the YAML (used by the GM reload command). */
    public static synchronized void forceReload() {
        pets = List.of();
        load();
    }

    @SuppressWarnings("unchecked")
    private static Integer toId(Object entry) {
        if (entry instanceof Number n) {
            return n.intValue();
        }
        if (entry instanceof String s) {
            // yamlbeans hands bare scalars back as String.
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        if (entry instanceof Map<?, ?> m) {
            Object id = ((Map<String, Object>) m).get("id");
            if (id instanceof Number n) {
                return n.intValue();
            }
            if (id instanceof String s) {
                try {
                    return Integer.parseInt(s.trim());
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }
}
