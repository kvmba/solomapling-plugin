package soloMapling.companion.intake;

import com.esotericsoftware.yamlbeans.YamlReader;
import soloMapling.Environment.PluginResources;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * The plugin's own intake settings.
 *
 * <p>SoloMapling is a guest in someone else's server: it registers capabilities
 * with the host and otherwise keeps to itself. Asking every deployment to add
 * companion keys to the host's {@code application.yml} would put plugin settings
 * in a file the plugin does not own — invisible to anyone reading the plugin,
 * easy to lose in a host upgrade, and one more thing to remember when moving to
 * another host. So these live in the plugin's own resource file instead.</p>
 *
 * <p>Read once, at server ready. There is no hot reload: the values are decided
 * before the first companion is provisioned and never revisited. An operator who
 * needs different numbers edits the override copy and restarts.</p>
 */
public final class CompanionIntakeConfig {

    /** Relative to the {@code soloMapling/} package root — see {@link PluginResources}. */
    static final String RESOURCE_PATH = "Environment/CompanionIntake.yaml";

    /** Off until somebody turns it on: a world should not fill itself unbidden. */
    private static final int DEFAULT_INTERVAL_SECONDS = 0;
    private static final int DEFAULT_MAX_TOTAL = 2000;
    private static final int DEFAULT_WORLD_ID = 0;
    private static final String DEFAULT_TIMEZONE = "Asia/Shanghai";

    private final int intervalSeconds;
    private final int maxTotal;
    private final int worldId;
    private final String timezone;
    private final String source;

    public CompanionIntakeConfig(
            int intervalSeconds, int maxTotal, int worldId, String timezone, String source) {
        this.intervalSeconds = intervalSeconds;
        this.maxTotal = maxTotal;
        this.worldId = worldId;
        this.timezone = Objects.requireNonNull(timezone, "timezone");
        this.source = source;
    }

    /** Above zero, intake provisions a companion this often. Zero or less is off. */
    public int intervalSeconds() {
        return intervalSeconds;
    }

    public int maxTotal() {
        return maxTotal;
    }

    public int worldId() {
        return worldId;
    }

    public String timezone() {
        return timezone;
    }

    /** Where the values came from, for the startup log line. */
    public String source() {
        return source;
    }

    /** True when the configured interval asks for companions at all. */
    public boolean enabled() {
        return intervalSeconds > 0;
    }

    /** All defaults: no companion is provisioned. */
    public static CompanionIntakeConfig disabled(String source) {
        return new CompanionIntakeConfig(
                DEFAULT_INTERVAL_SECONDS, DEFAULT_MAX_TOTAL, DEFAULT_WORLD_ID,
                DEFAULT_TIMEZONE, source);
    }

    /**
     * Loads the settings, falling back to all defaults when the file is missing
     * or unreadable. A broken config file must not stop the server: intake is a
     * convenience, and the rest of the plugin has to come up regardless.
     */
    public static CompanionIntakeConfig load() {
        final String label;
        try (Reader reader = openReader()) {
            if (reader == null) {
                return disabled("(no " + RESOURCE_PATH + ")");
            }
            YamlReader yaml = new YamlReader(reader);
            Object parsed = yaml.read();
            label = sourceLabel;
            if (!(parsed instanceof Map<?, ?> raw)) {
                return disabled(label + " (empty)");
            }
            return parse(raw, label);
        } catch (Exception e) {
            System.out.println("[CompanionIntakeConfig] failed to load " + RESOURCE_PATH + ": "
                    + e.getMessage() + " — intake disabled");
            return disabled("(fallback after error)");
        }
    }

    @SuppressWarnings("unchecked")
    private static CompanionIntakeConfig parse(Map<?, ?> raw, String label) {
        Map<String, Object> root = (Map<String, Object>) raw;
        return new CompanionIntakeConfig(
                toInt(root.get("interval-seconds"), DEFAULT_INTERVAL_SECONDS),
                toInt(root.get("max-total"), DEFAULT_MAX_TOTAL),
                toInt(root.get("world-id"), DEFAULT_WORLD_ID),
                toText(root.get("timezone"), DEFAULT_TIMEZONE),
                label);
    }

    private static volatile String sourceLabel = "(unknown)";

    private static Reader openReader() throws Exception {
        // Mirror of PluginResources' own resolution order, done here only so the
        // startup line can say which of them won — otherwise an operator who
        // edited the override copy would see it reported as the packaged file
        // and not know their edit was the one in force.
        Path override = Path.of(PluginResources.OVERRIDE_FS_ROOT + RESOURCE_PATH);
        if (Files.isRegularFile(override)) {
            sourceLabel = override.toAbsolutePath().toString();
            return Files.newBufferedReader(override, StandardCharsets.UTF_8);
        }
        Path legacy = Path.of(PluginResources.LEGACY_FS_ROOT + RESOURCE_PATH);
        if (Files.isRegularFile(legacy)) {
            sourceLabel = legacy.toAbsolutePath().toString();
            return Files.newBufferedReader(legacy, StandardCharsets.UTF_8);
        }
        if (PluginResources.exists(RESOURCE_PATH)) {
            sourceLabel = "plugin:" + RESOURCE_PATH;
            return PluginResources.openReader(RESOURCE_PATH);
        }
        sourceLabel = "(missing)";
        return null;
    }

    private static int toInt(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static String toText(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? fallback : text;
    }
}
