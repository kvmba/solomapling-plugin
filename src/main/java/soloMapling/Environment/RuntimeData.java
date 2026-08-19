package soloMapling.Environment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writable SoloMapling runtime files (logs, pins, caches, recording output).
 * Kept out of {@code src/} so the host tree stays clean.
 *
 * <pre>
 * gms-server/
 *   logs/BotLog.txt
 *   logs/solomapling-graph.dot
 *   data/solomapling/TownPins.txt
 *   data/solomapling/recordings/map&lt;id&gt;/...
 *   cache/bot-nav/...          (existing nav cache)
 *   data/solomapling/override/ (optional static hot-edits; see PluginResources)
 * </pre>
 */
public final class RuntimeData {

    private RuntimeData() {
    }

    public static final Path LOGS_DIR = Path.of("logs");
    public static final Path DATA_DIR = Path.of("data", "solomapling");
    public static final Path OVERRIDE_DIR = DATA_DIR.resolve("override");
    public static final Path RECORDINGS_DIR = DATA_DIR.resolve("recordings");

    public static Path botLog() {
        return LOGS_DIR.resolve("BotLog.txt");
    }

    public static Path townPins() {
        return DATA_DIR.resolve("TownPins.txt");
    }

    public static Path graphDot() {
        return LOGS_DIR.resolve("solomapling-graph.dot");
    }

    public static Path recordingFile(int mapId, String nameWithExt) {
        return RECORDINGS_DIR.resolve("map" + mapId).resolve(nameWithExt);
    }

    public static Path ensureParent(Path file) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        return file;
    }
}
