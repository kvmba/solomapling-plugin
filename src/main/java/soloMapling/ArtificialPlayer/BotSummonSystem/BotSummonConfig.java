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
 * <p>The movement knobs describe the official HOVER: a flying summon follows its owner the way a
 * PET does - a stable ring distance BEHIND the owner (the pet system's own values, see
 * {@link BotSummonFollower}), swapped only when the owner walks past it - while the height
 * ({@code offset_y}, negative = above) and the tiny sine bob are this file's own tuning. The
 * summon glides toward that slot at a bounded speed (never warping). See
 * {@link BotSummonFollower}.</p>
 *
 * <p>Read once at startup; there is no hot reload. A missing or broken file falls back to the
 * defaults, because a bad summon config must not stop the rest of the plugin from coming up.</p>
 */
public final class BotSummonConfig {

    /** Relative to the {@code soloMapling/} package root. */
    public static final String RESOURCE_PATH = "ArtificialPlayer/BotSummonSystem/BotSummonConfig.yaml";

    // ---- defaults ----
    private static final boolean DEF_ENABLED = true;
    private static final long DEF_MOVE_TICK_MS = 300L;
    private static final int DEF_HOVER_OFFSET_Y = -65;
    private static final double DEF_BOB_X = 14.0;
    private static final double DEF_BOB_Y = 8.0;
    private static final int DEF_FOLLOW_SPEED_X = 260;
    private static final int DEF_FOLLOW_SPEED_Y = 300;
    private static final double DEF_SNAP_DISTANCE = 900.0;
    private static final double DEF_SPAWN_CHANCE = 0.55;
    private static final int DEF_MIN_LEVEL = 70;
    /**
     * Floor (ms) on the pause a summon takes AFTER its swing animation finishes, before the next
     * strike may begin. The full cooldown is the summon's own {@code attack1} length (600..2280ms,
     * read from Skill.wz) plus this pause, so a strike never overlaps the previous swing and every
     * summon lands at least this much recovery. Without it the cadence was a bare 1100ms for every
     * skill, which had a hawk stinging like a metronome and re-rolling its WZ {@code prop} faster
     * than the bird could animate.
     */
    static final long MIN_ATTACK_RECOVERY_MS = 2200L;
    private static final int DEF_ATTACK_RANGE = 320;

    private final boolean enabled;
    private final long moveTickMs;
    private final int hoverOffsetY;
    private final double bobXAmplitude;
    private final double bobYAmplitude;
    private final int followSpeedX;
    private final int followSpeedY;
    private final double snapDistance;
    private final double spawnChance;
    private final int minLevel;
    private final long attackRecoveryMs;
    private final int attackRange;

    private BotSummonConfig(Builder b) {
        this.enabled = b.enabled;
        this.moveTickMs = b.moveTickMs;
        this.hoverOffsetY = b.hoverOffsetY;
        this.bobXAmplitude = b.bobXAmplitude;
        this.bobYAmplitude = b.bobYAmplitude;
        this.followSpeedX = b.followSpeedX;
        this.followSpeedY = b.followSpeedY;
        this.snapDistance = b.snapDistance;
        this.spawnChance = b.spawnChance;
        this.minLevel = b.minLevel;
        this.attackRecoveryMs = b.attackRecoveryMs;
        this.attackRange = b.attackRange;
    }

    public boolean enabled() { return enabled; }
    public long moveTickMs() { return moveTickMs; }
    /** Hover height (px) above the owner (negative = up, the summon's follow-ring altitude). */
    public int hoverOffsetY() { return hoverOffsetY; }
    public double bobXAmplitude() { return bobXAmplitude; }
    public double bobYAmplitude() { return bobYAmplitude; }
    /** Bounded glide speed (px/s) toward the hover slot, x and y. */
    public int followSpeedX() { return followSpeedX; }
    public int followSpeedY() { return followSpeedY; }
    public double snapDistance() { return snapDistance; }
    public double spawnChance() { return spawnChance; }
    public int minLevel() { return minLevel; }
    /** The pause (ms) after the summon's swing animation, before it may strike again. */
    public long attackRecoveryMs() { return attackRecoveryMs; }
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

        Map<String, Object> move = map(root.get("move"));
        b.moveTickMs = lng(move.get("tick_ms"), DEF_MOVE_TICK_MS);
        b.hoverOffsetY = intOf(move.get("offset_y"), DEF_HOVER_OFFSET_Y);
        b.bobXAmplitude = dbl(move.get("bob_x"), DEF_BOB_X);
        b.bobYAmplitude = dbl(move.get("bob_y"), DEF_BOB_Y);
        b.followSpeedX = intOf(move.get("follow_speed_x"), DEF_FOLLOW_SPEED_X);
        b.followSpeedY = intOf(move.get("follow_speed_y"), DEF_FOLLOW_SPEED_Y);
        b.snapDistance = dbl(move.get("snap_distance"), DEF_SNAP_DISTANCE);

        Map<String, Object> spawn = map(root.get("spawn"));
        b.spawnChance = dbl(spawn.get("chance"), DEF_SPAWN_CHANCE);
        b.minLevel = intOf(spawn.get("min_level"), DEF_MIN_LEVEL);

        Map<String, Object> attack = map(root.get("attack"));
        // Clamped, not just defaulted: the floor is a behavioural rule (a strike never starts on
        // the heels of the previous swing), so a config that asks for 300ms is raised rather than
        // honoured. This is the single place the clamp lives.
        b.attackRecoveryMs = Math.max(MIN_ATTACK_RECOVERY_MS,
                lng(attack.get("recovery_ms"), MIN_ATTACK_RECOVERY_MS));
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
        long moveTickMs = DEF_MOVE_TICK_MS;
        int hoverOffsetY = DEF_HOVER_OFFSET_Y;
        double bobXAmplitude = DEF_BOB_X;
        double bobYAmplitude = DEF_BOB_Y;
        int followSpeedX = DEF_FOLLOW_SPEED_X;
        int followSpeedY = DEF_FOLLOW_SPEED_Y;
        double snapDistance = DEF_SNAP_DISTANCE;
        double spawnChance = DEF_SPAWN_CHANCE;
        int minLevel = DEF_MIN_LEVEL;
        long attackRecoveryMs = MIN_ATTACK_RECOVERY_MS;
        int attackRange = DEF_ATTACK_RANGE;

        BotSummonConfig build() {
            return new BotSummonConfig(this);
        }
    }
}
