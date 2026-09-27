package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.server.maps.Foothold;
import org.gms.server.maps.FootholdTree;
import org.gms.server.maps.MapleMap;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression guard for the tower maps "bot jumps past the widest foothold, falls outside the map
 * forever, never lands" report.
 *
 * <p>The real client bounds a character's motion to the STANDABLE foothold AABB — a player can only
 * ever be where a foothold exists (the host's own drop cache {@code
 * MapleMap.generateMapDropRangeCache} encodes the same rule for items). Many tower maps leave a
 * full-height margin between their last platform and the VR wall: Eos/玩具塔 stage 1 (920010100) is
 * VR right edge 359, standable extent 220, and a continuous WALL column at x=320 (47 segments,
 * y=-2460..360, no gap) — a 139px column band with no foothold at any height. The old
 * {@code effectiveRightBoundaryX} took {@code max(VR, treeBounds)} = 359, which placed the
 * side-collision wall INSIDE that void: a jump from the top platform's edge (x=219, walkStep 9 →
 * 100px of arc) entered the void column, missed every foothold, and free-fell forever (the live
 * plummet no watchdog catches: position keeps changing, the frozen-air watchdog needs 30 still
 * ticks, and the fall-off-map recovery only keys on the VR rect — which the void sits inside).
 *
 * <p>The fix clamps the boundary to the standable extent (walls excluded — clamping to the tree's
 * raw bounds would strand the bot ON the wall column, which no foothold can catch either). The
 * tests drive the production {@code simulateJumpLanding} against the REAL 920010100 edge geometry,
 * on a Mockito map (a real {@code MapleMap} constructor stands up the Spring context — see
 * {@code SwimWallClimbSimulationTest} for the suite-order trap that makes that unusable here).
 */
class VoidColumnFlightBoundTest {

    // ── 920010100's real edge geometry, transcribed from Map9/920010100.img.xml ──

    /** The top platform: fh#209, x=[-310,220], y=-1905. The report's takeoff ledge. */
    private static final int TOP_Y = -1905;

    /**
     * The full-height wall columns: x=320 (right) and x=-410 (left), y from -2460 to 360,
     * continuous (the map's real side boundaries). The standable extent on the top row is
     * x ∈ [-349, 250] (fh#4char ramp tips at -349/233), but on the TOP PLATFORM the rightmost
     * standable pixel is 220 — the wall column and everything right of it is void.
     */
    private static List<Foothold> eosTowerEdge() {
        List<Foothold> fhs = new ArrayList<>();
        int id = 1;
        // Top platform (walkable) — x2=220 is the real standable extent on this side.
        fhs.add(new Foothold(new Point(-310, TOP_Y), new Point(220, TOP_Y), id++));
        // The wall columns, bottom-up exactly as the WZ lays them (isWall() only needs x1 == x2).
        int y = 360;
        while (y > -2460) {
            int next = y - 60;
            fhs.add(new Foothold(new Point(320, y), new Point(320, Math.max(next, -2460)), id++));
            fhs.add(new Foothold(new Point(-410, y), new Point(-410, Math.max(next, -2460)), id++));
            y = next;
        }
        return fhs;
    }

    private static MapleMap eosMap() {
        MapleMap map = mock(MapleMap.class);
        when(map.getMapArea()).thenReturn(new java.awt.Rectangle(-436, -3619, 800, 3669)); // the real VR
        when(map.getFootholdSpeed()).thenReturn(1.0f);
        FootholdTree tree = new FootholdTree(new Point(-410, -2460), new Point(320, 360));
        for (Foothold fh : eosTowerEdge()) {
            tree.insert(fh);
        }
        doReturn(tree).when(map).getFootholds();
        return map;
    }

    @Test
    void jumpFromTheTopPlatformEdgeCannotEnterTheVoidColumn() {
        // Max-input launch from the platform's last standable pixel, as resolveAirVelocityX fires
        // it: airVelX = ±walkStep px/tick, through the production landing simulator. walkStep 9 is
        // a fast profile's step (the arc spans stepX * 11.1 flight ticks ≈ 100px — far past 220).
        BotPhysicsEngine.JumpLanding landing = BotPhysicsEngine.simulateJumpLanding(eosMap(), new Point(219, TOP_Y), 9);
        // With the boundary clamped to the standable extent the arc hits the side wall at x=220,
        // drops straight down, and lands back on the top platform — a legal, client-faithful
        // bounce. Before the fix the same arc sailed to x≈327 and free-fell forever.
        assertTrue(landing != null, "the arc must land, not fall forever");
        assertTrue(landing.point().x <= 220,
                "no airborne sample may rest beyond the standable extent (got x=" + landing.point().x + ")");
    }

    @Test
    void jumpFromJustInsideTheEdgeStillLandsOnThePlatform() {
        // A takeoff from the platform's interior (x=210) must still land on the platform — the
        // clamp must not swallow legitimate near-edge hops.
        BotPhysicsEngine.JumpLanding landing = BotPhysicsEngine.simulateJumpLanding(eosMap(), new Point(210, TOP_Y), 9);
        assertTrue(landing != null, "an interior jump must still land");
        assertTrue(landing.point().x <= 220 && landing.point().y >= TOP_Y,
                "lands on the platform (x=" + landing.point().x + ", y=" + landing.point().y + ")");
    }

    @Test
    void anExtremeLeftwardArcIsBoundedByTheLeftWallColumn() {
        // Same rule on the left edge: the arc stops at the standable extent (-310 here, the top
        // platform's x1) instead of entering the -410 wall column / [-409,-350] void band.
        BotPhysicsEngine.JumpLanding landing = BotPhysicsEngine.simulateJumpLanding(eosMap(), new Point(-309, TOP_Y), -30);
        assertTrue(landing != null, "the leftward arc must land, not fall forever");
        assertTrue(landing.point().x >= -310,
                "no airborne sample may rest beyond the left standable extent (got x=" + landing.point().x + ")");
    }
}
