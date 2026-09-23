package soloMapling.ArtificialPlayer.BotSummonSystem;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the summon config parser and the STATIONARY-vs-observed movement gate. */
class BotSummonConfigTest {

    @Test
    void defaultsAreSane() {
        BotSummonConfig c = BotSummonConfig.defaults();
        assertTrue(c.enabled());
        assertTrue(c.spawnChance() > 0 && c.spawnChance() <= 1);
        assertTrue(c.followTickMs() > 0);
        assertTrue(c.attackTickMs() > 0);
        assertTrue(c.attackRange() > 0);
        assertTrue(c.warpDistance() > c.circleRadius(), "the warp threshold must exceed the orbit radius");
    }

    @Test
    void parseOverridesAndKeepsDefaultsForMissingKeys() {
        BotSummonConfig c = BotSummonConfig.fromMap(Map.of(
                "enabled", false,
                "spawn", Map.of("chance", 0.2, "min_level", 120),
                "follow", Map.of("tick_ms", 500, "circle_radius", 100)));
        assertFalse(c.enabled());
        assertEquals(0.2, c.spawnChance(), 1e-9);
        assertEquals(120, c.minLevel());
        assertEquals(500L, c.followTickMs());
        assertEquals(100, c.circleRadius());
        // untouched keys keep their defaults
        assertEquals(BotSummonConfig.defaults().attackTickMs(), c.attackTickMs());
    }

    @Test
    void malformedNumbersFallBackToDefaults() {
        BotSummonConfig c = BotSummonConfig.fromMap(Map.of(
                "spawn", Map.of("chance", "not-a-number", "min_level", "70")));
        assertEquals(BotSummonConfig.defaults().spawnChance(), c.spawnChance(), 1e-9);
        assertEquals(70, c.minLevel(), "a numeric string must still parse");
    }

    // ---- the movement gate (the STATIONARY rule, at the tick level) ----

    @Test
    void stationarySummonIsNeverMovedEvenWhenObserved() {
        assertFalse(BotSummonFollower.shouldTickMovement(true, true),
                "a turret must never be repositioned, even on an observed map");
        assertFalse(BotSummonFollower.shouldTickMovement(true, false));
    }

    @Test
    void movingSummonMovesOnlyWhenObserved() {
        assertTrue(BotSummonFollower.shouldTickMovement(false, true),
                "a follow summon glides while a real player watches");
        assertFalse(BotSummonFollower.shouldTickMovement(false, false),
                "no player watching: skip the movement packet entirely");
    }
}
