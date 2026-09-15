package soloMapling.ArtificialPlayer.BotPetSystem;

import com.esotericsoftware.yamlbeans.YamlReader;
import soloMapling.Environment.PluginResources;

import java.io.Reader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Random pet nicknames. Chinese (like a mainland player's pet), kept short (<=7
 * chars — the client truncates longer pet names) and deduplicated at load time.
 * Loaded once and cached; drawing a name is a single indexed read.
 */
public final class BotPetNames {

    public static final String RESOURCE_PATH = "ArtificialPlayer/BotPetSystem/BotPetNames.yaml";

    /** Max chars a pet name may carry; anything longer is dropped at load. */
    private static final int MAX_NAME_LEN = 7;

    private static volatile List<String> names = List.of();

    private BotPetNames() {
    }

    public static synchronized void load() {
        if (!names.isEmpty()) {
            return;
        }
        try {
            if (!PluginResources.exists(RESOURCE_PATH)) {
                return;
            }
            try (Reader reader = PluginResources.openReader(RESOURCE_PATH)) {
                Object parsed = new YamlReader(reader).read();
                if (!(parsed instanceof Map<?, ?> raw)) {
                    return;
                }
                Object listRaw = ((Map<?, ?>) raw).get("names");
                if (!(listRaw instanceof List<?> list)) {
                    return;
                }
                List<String> loaded = new ArrayList<>();
                Set<String> seen = new HashSet<>();
                for (Object entry : list) {
                    if (entry == null) {
                        continue;
                    }
                    String name = String.valueOf(entry).trim();
                    if (!name.isEmpty() && name.length() <= MAX_NAME_LEN && seen.add(name)) {
                        loaded.add(name);
                    }
                }
                names = List.copyOf(loaded);
                System.out.println("[BotPetNames] loaded " + names.size() + " pet names");
            }
        } catch (Exception e) {
            System.err.println("[BotPetNames] failed to load " + RESOURCE_PATH + ": " + e.getMessage());
        }
    }

    /** Force a reload from the YAML (used by the GM reload command). */
    public static synchronized void forceReload() {
        names = List.of();
        load();
    }

    /** A random name, or {@code null} when the pool is empty (caller keeps the default). */
    public static String random() {
        if (names.isEmpty()) {
            return null;
        }
        return names.get(ThreadLocalRandom.current().nextInt(names.size()));
    }
}
