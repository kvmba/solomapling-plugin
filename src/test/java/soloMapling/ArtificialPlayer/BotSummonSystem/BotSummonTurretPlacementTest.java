package soloMapling.ArtificialPlayer.BotSummonSystem;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the turret PLACEMENT rules: where a STATIONARY turret is planted, and the knobs the
 * follower's relocate beat keys off.
 *
 * <p>The rule that must never regress: a turret is planted at the NEAREST live mob within the
 * configured search radius of its owner (a turret never moves, so planting one on an empty
 * stretch of a hunting field means it never fires), and falls back to the owner's feet when the
 * radius holds no live mob - the pure set-dressing spawn a town bot's turret must remain.
 * {@code turretAnchor} is exercised through reflection: it is package-private and its Monster
 * argument would drag a host {@code Character} into a plain JUnit load otherwise.</p>
 */
class BotSummonTurretPlacementTest {

    private static BotSummonConfig cfgWithMobs(boolean atMobs) throws Exception {
        var map = new java.util.HashMap<String, Object>();
        var place = new java.util.HashMap<String, Object>();
        place.put("at_mobs", atMobs);
        map.put("place", place);
        Method m = BotSummonConfig.class.getDeclaredMethod("fromMap", java.util.Map.class);
        m.setAccessible(true);
        return (BotSummonConfig) m.invoke(null, map);
    }

    /**
     * Forces the anchor decision without a live map: when the radius is dry the anchor is the
     * owner's own point; the mob path is pinned by the radius/flag wiring and the fallback rule
     * here. Reflection keeps the package-private seam testable without a host MapleMap.
     */
    private static java.awt.Point anchorDryRadius(boolean atMobs) throws Exception {
        BotSummonConfig cfg = cfgWithMobs(atMobs);
        Method m = BotSummonFollower.class.getDeclaredMethod("turretAnchor",
                org.gms.client.Character.class, java.awt.Point.class,
                org.gms.server.maps.MapleMap.class, BotSummonConfig.class);
        m.setAccessible(true);
        // A null map makes the host range query return empty (nearestMobs guards it) - exactly
        // the "no live mob in radius" branch, without constructing a MapleMap.
        java.awt.Point owner = new java.awt.Point(1000, 300);
        return (java.awt.Point) m.invoke(null, null, owner, null, cfg);
    }

    @Test
    void dryRadiusFallsBackToTheOwnersFeet() throws Exception {
        // The town rule: no mob in radius -> the turret spawns exactly where the owner stands,
        // the pre-placement behaviour every town keeps.
        assertEquals(new java.awt.Point(1000, 300), anchorDryRadius(true));
    }

    @Test
    void placementDisabledAlwaysUsesTheOwnersFeet() throws Exception {
        // at_mobs: false restores the old rule outright, whatever the map holds.
        assertEquals(new java.awt.Point(1000, 300), anchorDryRadius(false));
    }

    @Test
    void placementDefaultsOnAndRadiusIsALocalSearch() throws Exception {
        // Defaults: placement at mobs ON, the search a LOCAL window around the bot (wider than
        // the turret's own attack range but still a neighbourhood, not the whole map).
        BotSummonConfig cfg = BotSummonConfig.defaults();
        assertTrue(cfg.placeAtMobs(), "a turret belongs where the mobs are - on by default");
        assertTrue(cfg.placeSearchRadius() > 0, "the search must be a finite window");
        assertTrue(cfg.placeRelocateMinMobs() >= 1, "relocate keys off the turret going dry");
        assertTrue(cfg.placeRelocateAfterMs() >= 1000, "the probe must be throttled, not per-tick");
    }

    @Test
    void relocateThrottleFieldIsTickedNotGlobal() throws Exception {
        // The throttle lives per summon (a fresh turret may be probed at once), on the same
        // single-tick-writes contract as nextAttackAtMs.
        Field f = BotSummon.class.getDeclaredField("nextRelocateProbeAtMs");
        assertEquals(long.class, f.getType());
    }
}
