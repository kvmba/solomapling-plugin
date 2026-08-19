package soloMapling.ArtificialPlayer;

import soloMapling.Environment.SoloMaplingLanguageConfig;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves bot dialogue YAML paths with language fallback, matching BeiDou's {@code scripts} /
 * {@code scripts-zh-CN} pattern: localized pack first, then English default.
 */
public final class DialoguePackPaths {

    private DialoguePackPaths() {
    }

    public static final String FS_BASE = "src/main/java/soloMapling/ArtificialPlayer/";
    public static final String CP_BASE = "soloMapling/ArtificialPlayer/";
    public static final String DEFAULT_PACK = "BotDialoguePack";

    /** Opens a dialogue YAML with FS → classpath resolution and English fallback. */
    public static Reader openDialogueReader(String fileName) throws IOException {
        ResolvedPath resolved = resolve(fileName);
        if (resolved == null) {
            throw new IOException("Dialogue file not found: " + fileName
                    + " (language=" + SoloMaplingLanguageConfig.languageTag() + ")");
        }
        if (resolved.fromClasspath()) {
            InputStream in = DialoguePackPaths.class.getClassLoader().getResourceAsStream(resolved.path());
            if (in == null) {
                throw new IOException("Classpath dialogue missing: " + resolved.path());
            }
            return new InputStreamReader(in, StandardCharsets.UTF_8);
        }
        return Files.newBufferedReader(Path.of(resolved.path()), StandardCharsets.UTF_8);
    }

    /** Best FS path for logging / diagnostics; null if only on classpath. */
    public static String resolveFsPath(String fileName) {
        ResolvedPath resolved = resolve(fileName);
        return resolved != null && !resolved.fromClasspath() ? resolved.path() : null;
    }

    private static ResolvedPath resolve(String fileName) {
        String localizedPack = SoloMaplingLanguageConfig.dialoguePackDirectoryName();

        // 1) FS localized
        String fsLocalized = FS_BASE + localizedPack + "/" + fileName;
        if (new File(fsLocalized).isFile()) {
            return new ResolvedPath(fsLocalized, false);
        }

        // 2) FS English default (when localized pack differs)
        if (!DEFAULT_PACK.equals(localizedPack)) {
            String fsDefault = FS_BASE + DEFAULT_PACK + "/" + fileName;
            if (new File(fsDefault).isFile()) {
                return new ResolvedPath(fsDefault, false);
            }
        }

        // 3) Classpath localized
        String cpLocalized = CP_BASE + localizedPack + "/" + fileName;
        if (classpathResourceExists(cpLocalized)) {
            return new ResolvedPath(cpLocalized, true);
        }

        // 4) Classpath English default
        if (!DEFAULT_PACK.equals(localizedPack)) {
            String cpDefault = CP_BASE + DEFAULT_PACK + "/" + fileName;
            if (classpathResourceExists(cpDefault)) {
                return new ResolvedPath(cpDefault, true);
            }
        }

        // 5) FS default pack when language is English
        if (DEFAULT_PACK.equals(localizedPack)) {
            String fsDefault = FS_BASE + DEFAULT_PACK + "/" + fileName;
            if (new File(fsDefault).isFile()) {
                return new ResolvedPath(fsDefault, false);
            }
            String cpDefault = CP_BASE + DEFAULT_PACK + "/" + fileName;
            if (classpathResourceExists(cpDefault)) {
                return new ResolvedPath(cpDefault, true);
            }
        }

        return null;
    }

    private static boolean classpathResourceExists(String resourcePath) {
        return DialoguePackPaths.class.getClassLoader().getResource(resourcePath) != null;
    }

    private record ResolvedPath(String path, boolean fromClasspath) {
    }
}
