package soloMapling.ArtificialPlayer.BotTownSystem;

import soloMapling.Environment.RuntimeData;

import java.awt.Point;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Machine-owned sidecar for pinned town spots appended by the "mark this spot" command. Kept SEPARATE
// from the hand-authored TownPresence.yaml so appending a pin never has to rewrite (and clobber the
// comments of) that file. Plain append-friendly text - one pin per line: "<mapId>: <x>,<y>". Merged
// into each map's pins at config load. Safe to hand-edit or delete lines.
public final class TownPinsStore {

    private TownPinsStore() {
    }

    // Parsed pins are cached. This file used to be re-read from disk on EVERY
    // TownPresenceConfig.overridesFor(mapId) miss, and that method is called per
    // bot relocation tick - at a few hundred ambient bots that was thousands of
    // serialized file reads per minute under a global lock. The sidecar only
    // changes when this class appends to it, so the cache is invalidated on write.
    private static volatile Map<Integer, List<Point>> cache;

    // Append one pin. Writes a header the first time the file is created.
    public static synchronized void addPin(int mapId, int x, int y) {
        try {
            Path path = RuntimeData.ensureParent(RuntimeData.townPins());
            boolean writeHeader = !Files.isRegularFile(path);
            StringBuilder sb = new StringBuilder();
            if (writeHeader) {
                sb.append("# Machine-owned pinned town spots (appended by !env townpresence mark).\n");
                sb.append("# One per line:  <mapId>: <x>,<y>   - merged into TownPresence.yaml pins at load.\n");
                sb.append("# Safe to hand-edit or delete lines.\n");
            }
            sb.append(mapId).append(": ").append(x).append(',').append(y).append('\n');
            Files.writeString(path, sb.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            cache = null; // this write is the only in-process mutation; drop the cache
        } catch (IOException e) {
            System.out.println("[TownPinsStore] failed to append pin: " + e.getMessage());
        }
    }

    // mapId -> its pinned points. Empty if the file doesn't exist yet. Cached;
    // callers get an unmodifiable snapshot so a concurrent addPin can't mutate a
    // list they are iterating.
    public static Map<Integer, List<Point>> load() {
        Map<Integer, List<Point>> local = cache;
        if (local == null) {
            // Double-checked against the same lock addPin uses, so a burst of
            // relocating bots hitting a cold cache reads the file once, not once
            // per thread, and can't race an in-flight addPin.
            synchronized (TownPinsStore.class) {
                local = cache;
                if (local == null) {
                    local = readFromDisk();
                    cache = local;
                }
            }
        }
        return local;
    }

    public static List<Point> forMap(int mapId) {
        return load().getOrDefault(mapId, List.of());
    }

    /**
     * Drop the cached pins so the next {@link #load()} re-reads the sidecar.
     * Call this after a hand-edit of the file - it is documented as safe to edit
     * by hand, so the cache must be droppable without a restart.
     */
    public static void invalidate() {
        synchronized (TownPinsStore.class) {
            cache = null;
        }
    }

    private static Map<Integer, List<Point>> readFromDisk() {
        Map<Integer, List<Point>> out = new HashMap<>();
        Path path = RuntimeData.townPins();
        if (!Files.isRegularFile(path)) {
            return out;
        }
        try (BufferedReader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                String s = line.trim();
                if (s.isEmpty() || s.startsWith("#")) {
                    continue;
                }
                int colon = s.indexOf(':');
                int comma = s.indexOf(',');
                if (colon < 0 || comma < 0 || comma < colon) {
                    continue;
                }
                try {
                    int mapId = Integer.parseInt(s.substring(0, colon).trim());
                    int x = Integer.parseInt(s.substring(colon + 1, comma).trim());
                    int y = Integer.parseInt(s.substring(comma + 1).trim());
                    out.computeIfAbsent(mapId, k -> new ArrayList<>()).add(new Point(x, y));
                } catch (NumberFormatException ignored) {
                    // skip a malformed line rather than fail the whole load
                }
            }
        } catch (IOException e) {
            System.out.println("[TownPinsStore] failed to read pins: " + e.getMessage());
        }
        out.replaceAll((k, v) -> List.copyOf(v));
        return Map.copyOf(out);
    }
}
