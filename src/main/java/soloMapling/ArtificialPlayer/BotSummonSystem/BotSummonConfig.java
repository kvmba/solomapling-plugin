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
 */
public final class BotSummonConfig {

    /** Relative to the {@code soloMapling/} package root. */
    public static final String RESOURCE_PATH = "ArtificialPlayer/BotSummonSystem/BotSummonConfig.yaml";

    // ---- defaults ----
    private static final boolean DEF_ENABLED = true;
    private static final long DEF_FOLLOW_TICK_MS = 300L;
    private static final int DEF_FOLLOW_OFFSET_X = 55;
    private static final int DEF_FOLLOW_OFFSET_Y = -12;
    private static final int DEF_CIRCLE_RADIUS = 70;
    private static final double DEF_CIRCLE_STEP_DEG = 6.0;
    private static final double DEF_WARP_DISTANCE = 900.0;

    private static final long DEF_ATTACK_TICK_MS = 1100L;
    private static final int DEF_ATTACK_RANGE = 320;
    private static final double DEF_SPAWN_CHANCE = 0.55;
    private static final int DEF_MIN_LEVEL = 70;

    private final boolean enabled;
    private final long followTickMs;
    private final int followOffsetX;
    private final int followOffsetY;
    private final int circleRadius;
    private final double circleStepDeg;
    private final double warpDistance;
    private final long attackTickMs;
    private final int attackRange;
    private final double spawnChance;
    private final int minLevel;

    private BotSummonConfig(Builder b) {
        this.enabled = b.enabled;
        this.followTickMs = b.followTickMs;
        this.followOffsetX = b.followOffsetX;
        this.followOffsetY = b.followOffsetY;
        this.circleRadius = b.circleRadius;
        this.circleStepDeg = b.circleStepDeg;
        this.warpDistance = b.warpDistance;
        this.attackTickMs = b.attackTickMs;
        this.attackRange = b.attackRange;
        this.spawnChance = b.spawnChance;
        this.minLevel = b.minLevel;
    }

    public boolean enabled() { return enabled; }
    public long followTickMs() { return followTickMs; }
    public int followOffsetX() { return followOffsetX; }
    public int followOffsetY() { return followOffsetY; }
    public int circleRadius() { return circleRadius; }
    public double circleStepDeg() { return circleStepDeg; }
    public double warpDistance() { return warpDistance; }
    public long attackTickMs() { return attackTickMs; }
    public int attackRange() { return attackRange; }
    public double spawnChance() { return spawnChance; }
    public int minLevel() { return minLevel; }

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

        Map<String, Object> follow = map(root.get("follow"));
        b.followTickMs = lng(follow.get("tick_ms"), DEF_FOLLOW_TICK_MS);
        b.followOffsetX = intOf(follow.get("offset_x"), DEF_FOLLOW_OFFSET_X);
        b.followOffsetY = intOf(follow.get("offset_y"), DEF_FOLLOW_OFFSET_Y);
        b.circleRadius = intOf(follow.get("circle_radius"), DEF_CIRCLE_RADIUS);
        b.circleStepDeg = dbl(follow.get("circle_step_deg"), DEF_CIRCLE_STEP_DEG);
        b.warpDistance = dbl(follow.get("warp_distance"), DEF_WARP_DISTANCE);

        Map<String, Object> attack = map(root.get("attack"));
        b.attackTickMs = lng(attack.get("tick_ms"), DEF_ATTACK_TICK_MS);
        b.attackRange = intOf(attack.get("range"), DEF_ATTACK_RANGE);

        Map<String, Object> spawn = map(root.get("spawn"));
        b.spawnChance = dbl(spawn.get("chance"), DEF_SPAWN_CHANCE);
        b.minLevel = intOf(spawn.get("min_level"), DEF_MIN_LEVEL);
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
        long followTickMs = DEF_FOLLOW_TICK_MS;
        int followOffsetX = DEF_FOLLOW_OFFSET_X;
        int followOffsetY = DEF_FOLLOW_OFFSET_Y;
        int circleRadius = DEF_CIRCLE_RADIUS;
        double circleStepDeg = DEF_CIRCLE_STEP_DEG;
        double warpDistance = DEF_WARP_DISTANCE;
        long attackTickMs = DEF_ATTACK_TICK_MS;
        int attackRange = DEF_ATTACK_RANGE;
        double spawnChance = DEF_SPAWN_CHANCE;
        int minLevel = DEF_MIN_LEVEL;

        BotSummonConfig build() {
            return new BotSummonConfig(this);
        }
    }
}
