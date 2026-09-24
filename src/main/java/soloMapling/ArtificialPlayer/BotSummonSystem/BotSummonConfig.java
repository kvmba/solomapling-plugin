package soloMapling.ArtificialPlayer.BotSummonSystem;

import com.esotericsoftware.yamlbeans.YamlReader;
import soloMapling.Environment.PluginResources;

import java.io.Reader;
import java.util.Map;

/**
 * Bot-summon tuning, read from the plugin's own resource file (never the host's application.yml -
 * same reasoning as {@link soloMapling.ArtificialPlayer.BotPetSystem.BotPetConfig}). A copy dropped
 * at any {@link PluginResources} resolution root wins over the packaged one, so an operator can
 * retune without a rebuild.
 *
 * <p>Read once at startup; there is no hot reload. A missing or broken file falls back to the
 * defaults, because a bad summon config must not stop the rest of the plugin from coming up.</p>
 *
 * <p>Movement is entirely client-side (the v83 server never drives summon motion), so there are no
 * follow/offset knobs here - only spawn odds and the attack cadence.</p>
 */
public final class BotSummonConfig {

    /** Relative to the {@code soloMapling/} package root. */
    public static final String RESOURCE_PATH = "ArtificialPlayer/BotSummonSystem/BotSummonConfig.yaml";

    // ---- defaults ----
    private static final boolean DEF_ENABLED = true;
    private static final double DEF_SPAWN_CHANCE = 0.55;
    private static final int DEF_MIN_LEVEL = 70;
    private static final long DEF_ATTACK_TICK_MS = 1100L;
    private static final int DEF_ATTACK_RANGE = 320;

    private final boolean enabled;
    private final double spawnChance;
    private final int minLevel;
    private final long attackTickMs;
    private final int attackRange;

    private BotSummonConfig(Builder b) {
        this.enabled = b.enabled;
        this.spawnChance = b.spawnChance;
        this.minLevel = b.minLevel;
        this.attackTickMs = b.attackTickMs;
        this.attackRange = b.attackRange;
    }

    public boolean enabled() { return enabled; }
    public double spawnChance() { return spawnChance; }
    public int minLevel() { return minLevel; }
    public long attackTickMs() { return attackTickMs; }
    public int attackRange() { return attackRange; }

    /** All defaults, no file. */
    public static BotSummonConfig defaults() {
        return new Builder().build();
    }

    @SuppressWarnings("unchecked")
    public static BotSummonConfig load() {
        try (Reader reader = openReader()) {
            if (reader == null) {
                return defaults();
            }
            Object parsed = new YamlReader(reader).read();
            if (!(parsed instanceof Map<?, ?> raw)) {
                return defaults();
            }
            return parse((Map<String, Object>) raw);
        } catch (Exception e) {
            System.err.println("[BotSummonConfig] failed to load " + RESOURCE_PATH + ": "
                    + e.getMessage() + " — using defaults");
            return defaults();
        }
    }

    private static Reader openReader() throws Exception {
        if (PluginResources.exists(RESOURCE_PATH)) {
            return PluginResources.openReader(RESOURCE_PATH);
        }
        return null;
    }

    /** Test seam: build a config from an in-memory map (missing keys -> defaults). */
    static BotSummonConfig fromMap(Map<String, Object> root) {
        return parse(root);
    }

    @SuppressWarnings("unchecked")
    private static BotSummonConfig parse(Map<String, Object> root) {
        Builder b = new Builder();
        b.enabled = bool(root.get("enabled"), DEF_ENABLED);

        Map<String, Object> spawn = map(root.get("spawn"));
        b.spawnChance = dbl(spawn.get("chance"), DEF_SPAWN_CHANCE);
        b.minLevel = intOf(spawn.get("min_level"), DEF_MIN_LEVEL);

        Map<String, Object> attack = map(root.get("attack"));
        b.attackTickMs = lng(attack.get("tick_ms"), DEF_ATTACK_TICK_MS);
        b.attackRange = intOf(attack.get("range"), DEF_ATTACK_RANGE);
        return b.build();
    }

    private static boolean bool(Object v, boolean dflt) {
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return Boolean.parseBoolean(s.trim());
        return dflt;
    }

    private static int intOf(Object v, int dflt) {
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try { return Integer.parseInt(s.trim()); } catch (NumberFormatException ignored) { }
        }
        return dflt;
    }

    private static long lng(Object v, long dflt) {
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s) {
            try { return Long.parseLong(s.trim()); } catch (NumberFormatException ignored) { }
        }
        return dflt;
    }

    private static double dbl(Object v, double dflt) {
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) {
            try { return Double.parseDouble(s.trim()); } catch (NumberFormatException ignored) { }
        }
        return dflt;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object v) {
        if (v instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return Map.of();
    }

    private static final class Builder {
        boolean enabled = DEF_ENABLED;
        double spawnChance = DEF_SPAWN_CHANCE;
        int minLevel = DEF_MIN_LEVEL;
        long attackTickMs = DEF_ATTACK_TICK_MS;
        int attackRange = DEF_ATTACK_RANGE;

        BotSummonConfig build() {
            return new BotSummonConfig(this);
        }
    }
}
