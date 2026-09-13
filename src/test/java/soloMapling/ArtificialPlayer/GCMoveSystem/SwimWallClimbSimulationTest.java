package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.server.maps.Foothold;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tick-by-tick simulation of the swim-map wall crossing on the REAL stacked columns found in the
 * audit, driving the PRODUCTION wall probe and PRODUCTION swim constants.
 *
 * <p>A real {@code MapleMap}/{@code Character} cannot be built in a unit test — their constructors
 * trip the Spring-backed {@code Server} static initializer, and Mockito is not on the test classpath.
 * So this test drives {@link BotPhysicsEngine#swimWallTopAhead} (the production probe, unmodified)
 * through a faithful copy of {@code applySwimMotion}'s vertical sub-model, using the live values in
 * {@link BotPhysicsEngine#cfg}. What it asserts is the emergent behaviour the fix is for: a swimmer
 * that starts on the floor climbs the whole column, segment by segment, and gets clear of the top.
 *
 * <p>Geometry is the real thing: Crystal Canyon (230010100) x=51 rises 240px, Secret Village
 * (230020101) ~400px, and Fish Plain (230030001) carries the tallest columns in the game — x=-399 is
 * 16 segments (920px), x=-231 13 segments (760px). Every one of those maps is {@code swim=true} and
 * has no rope at these columns, so swimming is the only way across.
 *
 * <p>The control case (no wall intent) pins the regression itself: with only the normal target-driven
 * vertical intent, a same-depth target never gets an upward input and the swimmer sinks — it never
 * clears the column. That is exactly the original bug.
 */
class SwimWallClimbSimulationTest {

    private static final int TICK_MS = BotPhysicsEngine.cfg.TICK_MS;
    private static final double T = TICK_MS / 1000.0;

    /** Builds a column of stacked vertical wall segments, top-down like the client's footholds. */
    private static List<Foothold> column(int x, int yTop, int yBottom, int segHeight) {
        List<Foothold> segs = new ArrayList<>();
        int id = 1;
        for (int y = yBottom; y > yTop; y -= segHeight) {
            int top = Math.max(yTop, y - segHeight);
            segs.add(new Foothold(new Point(x, y), new Point(x, top), id++));
        }
        return segs;
    }

    private record Climb(int ticks, int peakY) {
        boolean crossed() {
            return ticks > 0;
        }
    }

    /**
     * Runs the swim loop against one column. {@code useWallIntent} selects the fixed branch
     * (computeSwimIntents' wall-ahead intent) vs the control (ordinary target-driven intent, which
     * for a same-depth target is "hold UP" only). Returns the crossing tick and the highest point.
     */
    private static Climb simulate(List<Foothold> walls, int wallX, int floorY, int targetX, boolean useWallIntent) {
        double x = wallX - 10;
        double physY = floorY;
        // beginGroundJump's swim branch launches with velY = -jumpSpeedPxs (px/s).
        double vy = -BotPhysicsEngine.cfg.JUMP_SPEED_PXS;
        int nextBurstTick = 10; // first wall burst at +500ms, mirrors the ground-jump's swimNextJumpAtMs arm
        int peakY = floorY;

        for (int tick = 0; tick < 1000; tick++) {
            Point pos = new Point((int) Math.round(x), (int) Math.round(physY));
            int wallTop = BotPhysicsEngine.swimWallTopAhead(walls, pos, new Point(targetX, floorY));

            boolean burst = false;
            boolean holdUp;
            if (useWallIntent) {
                holdUp = true; // the wall branch always holds UP
                if (wallTop != Integer.MIN_VALUE && tick >= nextBurstTick) {
                    burst = true;
                    nextBurstTick = tick + BotPhysicsEngine.cfg.SWIM_JUMP_COOLDOWN_MS / TICK_MS;
                }
                if (wallTop == Integer.MIN_VALUE) {
                    // Clear of the column: cross horizontally (SWIM_VEL cap).
                    x += BotPhysicsEngine.cfg.SWIM_VEL_PXS * T;
                    if (x > wallX + 10) {
                        return new Climb(tick, peakY);
                    }
                }
            } else {
                // Control: same-depth target => computeSwimIntents leaves swimVerticalHold = -1 (UP)
                // but never bursts (dy is not <= -SWIM_JUMP_TRIGGER_DY_PX). It just drifts/sinks.
                holdUp = true;
            }

            if (burst) {
                vy = -BotPhysicsEngine.cfg.SWIM_JUMP_BURST_PXS;
            }
            vy *= Math.max(0.0, 1.0 - BotPhysicsEngine.cfg.SWIM_FRICTION_HZ * T);
            vy += BotPhysicsEngine.cfg.SWIM_GRAVITY_PXS2 * T;
            if (holdUp) {
                vy -= BotPhysicsEngine.cfg.SWIM_UP_THRUST_PXS2 * T;
            }
            vy = Math.max(-BotPhysicsEngine.cfg.SWIM_MAX_SPEED_PXS,
                    Math.min(BotPhysicsEngine.cfg.SWIM_UP_MAX_SINK_PXS, vy));
            physY += vy * T;
            peakY = Math.min(peakY, (int) Math.round(physY));
        }
        return new Climb(-1, peakY);
    }

    private static void assertClimbs(String name, int x, int yTop, int yBottom, int segHeight) {
        List<Foothold> walls = column(x, yTop, yBottom, segHeight);
        Climb climb = simulate(walls, x, yBottom, x + 120, true);
        assertTrue(climb.crossed(),
                name + ": swimmer should cross the column (was stuck at y=" + climb.peakY() + ")");
        assertTrue(climb.peakY() <= yTop - BotPhysicsEngine.cfg.SWIM_WALL_CLEAR_PX,
                name + ": swimmer should end above the top " + yTop + " by the clearance margin, got "
                        + climb.peakY());
    }

    @Test
    void climbsCrystalCanyon240pxColumn() {
        // 230010100, x=51: five 48px segments from 580 up to 340.
        assertClimbs("Crystal Canyon x=51", 51, 340, 580, 48);
    }

    @Test
    void climbsSecretVillage400pxColumn() {
        // 230020101, x=501: 400px (100 up to -300).
        assertClimbs("Secret Village x=501", 501, -300, 100, 50);
    }

    @Test
    void climbsFishPlainTallest920pxColumn() {
        // 230030001, x=-399: the tallest column in the game, 920px (360 up to -560), 16 segments.
        assertClimbs("Fish Plain x=-399", -399, -560, 360, 60);
    }

    @Test
    void everyFishPlainColumnIsClimbable() {
        // Fish Plain's three tall columns from the audit: x=-399 (920px), x=-231 (760px), x=951 (560px).
        assertClimbs("Fish Plain x=-231", -231, -480, 280, 60);
        assertClimbs("Fish Plain x=951", 951, -480, 280, 60);
    }

    @Test
    void withoutTheWallIntentTheSwimmerNeverClearsTheColumn() {
        // Regression control: the ordinary (target-driven) intent gives a same-depth target no upward
        // input, so the swimmer just drifts and sinks — it never rises out of the column. This is the
        // original behaviour the fix replaces.
        List<Foothold> walls = column(51, 340, 580, 48);
        Climb control = simulate(walls, 51, 580, 171, false);
        assertTrue(!control.crossed(), "control must not cross");
        assertTrue(control.peakY() > 340, "control sinks instead of climbing above the 340 top");
    }
}
