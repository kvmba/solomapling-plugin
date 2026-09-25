package soloMapling.ArtificialPlayer.BotSummonSystem;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the summon config parser (spawn odds, movement cadence, attack tuning). */
class BotSummonConfigTest {

    @Test
    void defaultsAreSane() {
        BotSummonConfig c = BotSummonConfig.defaults();
        assertTrue(c.enabled());
        assertTrue(c.spawnChance() > 0 && c.spawnChance() <= 1);
        assertTrue(c.moveTickMs() > 0);
        assertTrue(c.attackTickMs() > 0);
        assertTrue(c.attackRange() > 0);
        assertTrue(c.hoverOffsetY() < 0,
                "the summon rides above its owner (negative y = up)");
        assertTrue(c.followSpeedX() > 0 && c.followSpeedY() > 0, "the glide must have a bounded speed");
        assertTrue(c.snapDistance() > Math.abs(c.hoverOffsetY()),
                "the re-seat threshold must clear the hover altitude");
    }

    @Test
    void parseOverridesAndKeepsDefaultsForMissingKeys() {
        BotSummonConfig c = BotSummonConfig.fromMap(Map.of(
                "enabled", false,
                "spawn", Map.of("chance", 0.2, "min_level", 120),
                "move", Map.of("tick_ms", 500, "offset_y", -90, "bob_x", 20.0),
                "attack", Map.of("tick_ms", 700, "range", 400)));
        assertFalse(c.enabled());
        assertEquals(0.2, c.spawnChance(), 1e-9);
        assertEquals(120, c.minLevel());
        assertEquals(500L, c.moveTickMs());
        assertEquals(-90, c.hoverOffsetY());
        assertEquals(20.0, c.bobXAmplitude(), 1e-9);
        assertEquals(700L, c.attackTickMs());
        assertEquals(400, c.attackRange());
    }

    @Test
    void malformedNumbersFallBackToDefaults() {
        BotSummonConfig c = BotSummonConfig.fromMap(Map.of(
                "spawn", Map.of("chance", "not-a-number", "min_level", "70")));
        assertEquals(BotSummonConfig.defaults().spawnChance(), c.spawnChance(), 1e-9);
        assertEquals(70, c.minLevel(), "a numeric string must still parse");
        assertEquals(BotSummonConfig.defaults().moveTickMs(), c.moveTickMs());
    }
}
