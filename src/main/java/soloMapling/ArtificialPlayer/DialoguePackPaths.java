package soloMapling.ArtificialPlayer;

import soloMapling.Environment.LocalizedResources;
import soloMapling.Environment.PluginResources;
import soloMapling.Environment.SoloMaplingLanguageConfig;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves bot dialogue YAML paths with language fallback, matching BeiDou's {@code scripts} /
 * {@code scripts-zh-CN} pattern: localized pack first, then English default.
 */
public final class DialoguePackPaths {

    private DialoguePackPaths() {
    }

    public static final String PACK_ROOT = "ArtificialPlayer/";
    public static final String DEFAULT_PACK = "BotDialoguePack";

    /** Opens a dialogue YAML with PluginResources resolution and English fallback. */
    public static Reader openDialogueReader(String fileName) throws IOException {
        String rel = resolveRelative(fileName);
        if (rel == null) {
            throw new IOException("Dialogue file not found: " + fileName
                    + " (language=" + SoloMaplingLanguageConfig.languageTag() + ")");
        }
        return PluginResources.openReader(rel);
    }

    /** Best FS path for logging / diagnostics; null if only on classpath. */
    public static String resolveFsPath(String fileName) {
        String rel = resolveRelative(fileName);
        if (rel == null) {
            return null;
        }
        Path override = Path.of(PluginResources.OVERRIDE_FS_ROOT + rel);
        if (Files.isRegularFile(override)) {
            return override.toString();
        }
        Path legacy = Path.of(PluginResources.LEGACY_FS_ROOT + rel);
        if (Files.isRegularFile(legacy)) {
            return legacy.toString();
        }
        return null;
    }

    private static String resolveRelative(String fileName) {
        return LocalizedResources.resolve(PACK_ROOT, DEFAULT_PACK, fileName);
    }
}
