package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.server.maps.Foothold;
import org.junit.jupiter.api.Test;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the ground steer's stop rule — the "bot walks, stops, then sways left-right on the spot"
 * report (observed on a train of bots in the Free Market hall, but produced by every grounded
 * mover, pets included).
 *
 * <p>{@code calcStepX} clamps the INTENT step to the remaining distance, but the tick then
 * integrates real momentum (the client's 8ms ground steps). On a precise target — stopDist 0 on a
 * JUMP / straight-DROP launch-waypoint approach, 1 on CLIMB/PORTAL, 4 on a WALK edge — that clamp
 * is far smaller than the travel the current speed carries (~50px of glide-out at walk speed on
 * firm ground): the bot sails through the target, finds it behind it on the next tick, walks back,
 * overshoots again, forever. {@link BotMovementManager#updateStepX} releases the key when either
 * letting go would already land inside the band, or holding one more tick would land past it.
 *
 * <p>A real {@code MapleMap}/{@code Character} cannot be built in a unit test — their constructors
 * trip the Spring-backed {@code Server} static initializer and Mockito is not on the test classpath
 * (the same constraint documented in {@code SwimWallClimbSimulationTest}). Following that test's
 * convention, this drives the PRODUCTION steer rule ({@code updateStepX} on a real
 * {@link BotMovementState}) together with the PRODUCTION physics core
 * ({@link MapleMovement#groundStep} under the live {@link BotPhysicsEngine#cfg} constants,
 * sub-stepped at {@code TICK_MS / 8ms} with the same carry the engine threads). What is asserted is
 * the emergent behaviour in the three directions the report cares about: the bot settles instead of
 * hunting, it still walks INTO a tight launch window, and a long walk is never throttled by it.
 *
 * <p>Before the fix the same sweep reached 24-31px of settled travel (a full left-right sway) in 30
 * of 77 speed/stopDist cells; after it, 0 of 77.
 */
class GroundSwayTest {

    private static final int TICK_MS = BotPhysicsEngine.cfg.TICK_MS;
    private static final double CLIENT_STEP_S = MapleMovement.CLIENT_STEP_MS / 1000.0;
    private static final Foothold FLOOR = new Foothold(new Point(-100_000, 0), new Point(100_000, 0), 1);

    /** The bot's own pace for its profile, as the engine derives it: px per 8ms client step. */
    private static double walkCapStep(BotMovementProfile profile) {
        return profile.hForcePxs() * CLIENT_STEP_S
                * BotPhysicsEngine.cfg.GROUNDSLIP / (BotPhysicsEngine.cfg.FRICTION + BotPhysicsEngine.cfg.SLOPEFACTOR);
    }

    private static int walkStep(BotMovementProfile profile) {
        return Math.max(1, (int) Math.round(walkCapStep(profile) * TICK_MS / MapleMovement.CLIENT_STEP_MS));
    }

    /**
     * One bot walking one flat foothold: the production steer rule picks the intent, and the
     * production ground-step integrator (firm ground: no slip scale, nothing to collide with)
     * carries it out for the tick.
     */
    private static final class Walker {
        final BotMovementProfile profile;
        final BotMovementState state = new BotMovementState(null, null);
        double physX;
        double carryMs = 0.0;

        Walker(BotMovementProfile profile, int startX) {
            this.profile = profile;
            this.physX = startX;
            state.movementProfile = profile;
        }

        int x() {
            return (int) Math.round(physX);
        }

        /** Run one tick toward targetX; returns the intent sign the steer rule chose. */
        int tick(int targetX, int stopDist) {
            // Production steer rule. The map is null: only its slip scale is consulted, so this is
            // exactly the fs=1 firm-ground case under test.
            int stepX = BotMovementManager.updateStepX(state, null, x(), targetX, stopDist, stopDist);
            int dir = Integer.signum(stepX);

            int steps = (int) ((carryMs + TICK_MS) / MapleMovement.CLIENT_STEP_MS);
            carryMs = carryMs + TICK_MS - steps * MapleMovement.CLIENT_STEP_MS;
            double hspeed = state.hspeed;
            for (int i = 0; i < steps; i++) {
                hspeed = MapleMovement.groundStep(hspeed, FLOOR, dir,
                        profile.hForcePxs() * CLIENT_STEP_S, walkCapStep(profile), 1.0);
                physX += hspeed;
            }
            state.hspeed = hspeed;
            // previewGroundStep snaps the foot point to a whole pixel on firm ground.
            physX = Math.round(physX);
            return dir;
        }
    }

    /** Outcome of a settle: the settled travel span, its direction reversals, and the final pixel. */
    private record Settle(int span, int reversals, int finalX) {
    }

    private static Settle settle(int speedStat, int startX, int targetX, int stopDist, int warm) {
        Walker w = new Walker(new BotMovementProfile(speedStat, 100), startX);
        int ticks = warm + 1000;
        int[] tail = new int[1000];
        int[] dirs = new int[1000];
        for (int t = 0; t < ticks; t++) {
            int dir = w.tick(targetX, stopDist);
            if (t >= warm) {
                tail[t - warm] = w.x();
                dirs[t - warm] = dir;
            }
        }
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        int reversals = 0;
        int prev = 0;
        for (int v : tail) {
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        for (int d : dirs) {
            if (d != 0) {
                if (prev != 0 && d != prev) {
                    reversals++;
                }
                prev = d;
            }
        }
        return new Settle(max - min, reversals, w.x());
    }

    @Test
    void aStoppedBotNeverSwaysAtAnyPreciseStopDist() {
        // 0 = JUMP / straight-DROP approach, 1 = CLIMB/PORTAL anchor, 4 = WALK edge and move();
        // the looser bands (8/16/30) are the non-precise hold radii. Every combination used to
        // leave the bot pacing around its target; the settled window must be perfectly still.
        for (int stopDist : new int[]{0, 1, 2, 4, 8, 16, 30}) {
            for (int speed : new int[]{95, 100, 105, 110, 120, 125, 140, 160, 180, 200, 250}) {
                for (int offset : new int[]{1, 2, 3, 5, 8, 12, 16, 20, 30, 40, 60, 90, 150, 300, 800}) {
                    Settle s = settle(speed, 1000 - offset, 1000, stopDist, 500);
                    assertEquals(0, s.reversals(),
                            "speed=" + speed + " stopDist=" + stopDist + " offset=" + offset
                                    + ": still reversing direction after settling (" + s.reversals() + "x)");
                    assertTrue(s.span() <= 1,
                            "speed=" + speed + " stopDist=" + stopDist + " offset=" + offset
                                    + ": still travelling " + s.span() + "px after settling");
                }
            }
        }
    }

    @Test
    void theBotStopsInsideItsOwnBand() {
        // Settling must not become "stop wherever": the residual distance to the target has to stay
        // inside the caller's band (or one tick of travel, whichever is wider — whole pixels mean a
        // 0px band cannot be hit exactly).
        for (int stopDist : new int[]{0, 1, 4, 8, 30}) {
            for (int speed : new int[]{100, 105, 125, 200, 250}) {
                int allowed = Math.max(stopDist, walkStep(new BotMovementProfile(speed, 100)));
                for (int offset : new int[]{5, 40, 200, 800}) {
                    Settle s = settle(speed, 1000 - offset, 1000, stopDist, 500);
                    assertTrue(Math.abs(s.finalX() - 1000) <= allowed,
                            "speed=" + speed + " stopDist=" + stopDist + " offset=" + offset
                                    + ": settled " + Math.abs(s.finalX() - 1000) + "px off the target"
                                    + " (allowed " + allowed + ")");
                }
            }
        }
    }

    @Test
    void aTightLaunchWindowIsStillEntered() {
        // The point of stopDist=0 is to walk INTO a launch window; 2px is the tightest in the game
        // (El Nath, fs=0.2). The contract the nav actually uses is its own exec gate —
        // |botX - launchX| <= walkStep — so a release rule that parks the bot outside that gate
        // would make the committed edge impossible to fire. That is the regression this pins.
        for (int speed : new int[]{95, 100, 105, 110, 120, 125, 140, 160, 180, 200, 250}) {
            int gate = walkStep(new BotMovementProfile(speed, 100));
            for (int offset : new int[]{6, 20, 60, 200, 600, 1200}) {
                Settle s = settle(speed, 1000 - offset, 1000, 0, 600);
                assertTrue(Math.abs(s.finalX() - 1000) <= gate,
                        "speed=" + speed + " offset=" + offset + ": settled at " + s.finalX()
                                + ", outside the launch gate (|dx| <= " + gate + ")");
            }
        }
    }

    @Test
    void aLongWalkIsNeverInterruptedOrThrottled() {
        // The release rule runs on EVERY tick, not just the arrival ones: a bot far from its target
        // must still close the distance at its full pace.
        for (int speed : new int[]{100, 105, 125, 200}) {
            Settle s = settle(speed, 200, 1000, 4, 2400);
            assertTrue(Math.abs(s.finalX() - 1000) <= 8,
                    "speed=" + speed + ": an 800px walk stalled at " + s.finalX());
            // and it must not be slowed down on the way: 800px at >=125px/s is ~6.4s = 128 ticks
            Walker w = new Walker(new BotMovementProfile(speed, 100), 200);
            int ticks = 0;
            while (w.x() < 992 && ticks < 5000) {
                w.tick(1000, 4);
                ticks++;
            }
            int unobstructed = (int) Math.ceil(800.0 / (125.0 * speed / 100.0) * 20);
            assertTrue(ticks <= unobstructed + 8,
                    "speed=" + speed + ": 800px took " + ticks + " ticks, unobstructed ~" + unobstructed);
        }
    }

    @Test
    void theStopOutCollapsesAtRestAndGrowsWithMomentum() {
        // The band is derived from the CURRENT speed: it must vanish at rest (or the bot could
        // never take its first step) and scale with the profile (a Haste thief slides farther).
        BotMovementProfile slow = new BotMovementProfile(100, 100);
        BotMovementProfile fast = new BotMovementProfile(200, 100);

        assertEquals(0, BotPhysicsEngine.groundStopOutPx(0.0, slow, null), "no momentum = no band");
        assertTrue(BotPhysicsEngine.groundStopOutPx(walkCapStep(slow), slow, null) > 0);

        assertTrue(BotPhysicsEngine.groundStopOutPx(walkCapStep(fast), fast, null)
                        > BotPhysicsEngine.groundStopOutPx(walkCapStep(slow), slow, null),
                "a faster profile carries farther after the key is released");
    }
}
