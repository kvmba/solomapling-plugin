package soloMapling.Environment;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Read-only SoloMapling packaged resources.
 *
 * <p>Resolution order for a path relative to the {@code soloMapling/} package root
 * (e.g. {@code FreeMarket/FMNameDesc/randomRealMaplestoryIGNs.txt}):
 * <ol>
 *   <li>{@code data/solomapling/override/&lt;rel&gt;} — optional hot-edit overlay</li>
 *   <li>{@code src/main/java/soloMapling/&lt;rel&gt;} — legacy Cosmic-era FS layout</li>
 *   <li>classpath {@code soloMapling/&lt;rel&gt;} — plugin jar (normal production path)</li>
 * </ol>
 */
public final class PluginResources {

    private PluginResources() {
    }

    public static final String CP_ROOT = "soloMapling/";
    public static final String LEGACY_FS_ROOT = "src/main/java/soloMapling/";
    public static final String OVERRIDE_FS_ROOT = "data/solomapling/override/";

    /** Strip legacy / classpath prefixes so callers can pass either form. */
    public static String normalize(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("resource path is blank");
        }
        String p = path.replace('\\', '/');
        while (p.startsWith("./")) {
            p = p.substring(2);
        }
        if (p.startsWith(LEGACY_FS_ROOT)) {
            p = p.substring(LEGACY_FS_ROOT.length());
        } else if (p.startsWith("src/main/java/" + CP_ROOT)) {
            p = p.substring(("src/main/java/" + CP_ROOT).length());
        } else if (p.startsWith(OVERRIDE_FS_ROOT)) {
            p = p.substring(OVERRIDE_FS_ROOT.length());
        } else if (p.startsWith(CP_ROOT)) {
            p = p.substring(CP_ROOT.length());
        }
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        return p;
    }

    public static boolean exists(String relativeOrLegacy) {
        String rel = normalize(relativeOrLegacy);
        if (Files.isRegularFile(Path.of(OVERRIDE_FS_ROOT + rel))) {
            return true;
        }
        if (Files.isRegularFile(Path.of(LEGACY_FS_ROOT + rel))) {
            return true;
        }
        return classLoader().getResource(CP_ROOT + rel) != null;
    }

    public static InputStream openStream(String relativeOrLegacy) throws IOException {
        String rel = normalize(relativeOrLegacy);
        Path override = Path.of(OVERRIDE_FS_ROOT + rel);
        if (Files.isRegularFile(override)) {
            return Files.newInputStream(override);
        }
        Path legacy = Path.of(LEGACY_FS_ROOT + rel);
        if (Files.isRegularFile(legacy)) {
            return Files.newInputStream(legacy);
        }
        InputStream in = classLoader().getResourceAsStream(CP_ROOT + rel);
        if (in == null) {
            throw new IOException("SoloMapling resource not found: " + rel
                    + " (checked override, legacy FS, classpath)");
        }
        return in;
    }

    public static Reader openReader(String relativeOrLegacy) throws IOException {
        return new InputStreamReader(openStream(relativeOrLegacy), StandardCharsets.UTF_8);
    }

    /**
     * Lists basenames (no directory prefix) under a resource directory.
     * Example: {@code listBasenames("ArtificialPlayer/.../map910000000", ".csv")}
     * returns {@code ["m1", "m2", ...]} when {@code stripExtension} is true,
     * or full file names when false.
     */
    public static List<String> listFileNames(String relativeDir, String endsWith) {
        return listFileNames(relativeDir, endsWith, false);
    }

    public static List<String> listBasenames(String relativeDir, String endsWith) {
        return listFileNames(relativeDir, endsWith, true);
    }

    public static List<String> listFileNames(String relativeDir, String endsWith, boolean stripExtension) {
        String dir = normalize(relativeDir);
        if (!dir.isEmpty() && !dir.endsWith("/")) {
            dir = dir + "/";
        }
        Set<String> names = new LinkedHashSet<>();

        collectFromFsDir(Path.of(OVERRIDE_FS_ROOT + dir), endsWith, stripExtension, names);
        collectFromFsDir(Path.of(LEGACY_FS_ROOT + dir), endsWith, stripExtension, names);
        collectFromClasspathDir(dir, endsWith, stripExtension, names);

        List<String> out = new ArrayList<>(names);
        Collections.sort(out);
        return out;
    }

    public static boolean directoryExists(String relativeDir) {
        String dir = normalize(relativeDir);
        if (Files.isDirectory(Path.of(OVERRIDE_FS_ROOT + dir))) {
            return true;
        }
        if (Files.isDirectory(Path.of(LEGACY_FS_ROOT + dir))) {
            return true;
        }
        // Classpath "dirs" exist if any resource has that prefix.
        return !listFileNames(dir, null, false).isEmpty();
    }

    private static void collectFromFsDir(Path dir, String endsWith, boolean stripExtension, Set<String> out) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile).forEach(p -> {
                String name = p.getFileName().toString();
                if (endsWith != null && !name.endsWith(endsWith)) {
                    return;
                }
                out.add(stripExtension ? stripExt(name) : name);
            });
        } catch (IOException ignored) {
            // skip unreadable overlay
        }
    }

    private static void collectFromClasspathDir(String dirWithSlash, String endsWith,
                                                boolean stripExtension, Set<String> out) {
        String prefix = CP_ROOT + dirWithSlash;
        try {
            URL probe = classLoader().getResource(prefix.isEmpty() ? CP_ROOT : prefix);
            if (probe == null) {
                // Still try jar scan via a known sibling resource root
                probe = classLoader().getResource(CP_ROOT + "Environment/EnvironmentPopulation.yaml");
                if (probe == null) {
                    probe = classLoader().getResource(CP_ROOT + "FreeMarket/FMNameDesc/randomRealMaplestoryIGNs.txt");
                }
            }
            if (probe == null) {
                return;
            }
            if ("jar".equals(probe.getProtocol())) {
                collectFromJar(probe, prefix, endsWith, stripExtension, out);
            } else if ("file".equals(probe.getProtocol())) {
                // Exploded classpath: resolve the directory under the soloMapling root
                Path soloRoot = findSoloMaplingRoot(probe);
                if (soloRoot != null) {
                    collectFromFsDir(soloRoot.resolve(dirWithSlash), endsWith, stripExtension, out);
                }
            }
        } catch (Exception ignored) {
            // best-effort listing
        }
    }

    private static void collectFromJar(URL anyJarResource, String prefix, String endsWith,
                                       boolean stripExtension, Set<String> out) throws IOException {
        String full = anyJarResource.toString();
        int sep = full.indexOf("!/");
        if (sep < 0) {
            return;
        }
        String jarUrl = full.substring(0, sep);
        if (jarUrl.startsWith("jar:")) {
            jarUrl = jarUrl.substring(4);
        }
        Path jarPath = Path.of(URI.create(jarUrl));
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                if (!name.startsWith(prefix)) {
                    continue;
                }
                String rest = name.substring(prefix.length());
                if (rest.isEmpty() || rest.contains("/")) {
                    continue; // only immediate children
                }
                if (endsWith != null && !rest.endsWith(endsWith)) {
                    continue;
                }
                out.add(stripExtension ? stripExt(rest) : rest);
            }
        }
    }

    private static Path findSoloMaplingRoot(URL probe) {
        try {
            Path p = Path.of(probe.toURI());
            // Walk up until directory named soloMapling
            for (Path cur = p; cur != null; cur = cur.getParent()) {
                if (cur.getFileName() != null && "soloMapling".equals(cur.getFileName().toString())) {
                    return cur;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static ClassLoader classLoader() {
        ClassLoader cl = PluginResources.class.getClassLoader();
        return cl != null ? cl : ClassLoader.getSystemClassLoader();
    }
}
