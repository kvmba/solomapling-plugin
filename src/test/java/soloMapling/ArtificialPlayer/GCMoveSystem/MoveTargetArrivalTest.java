package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.junit.jupiter.api.Test;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard for the "bot sticks in place, flickering climb/stand" report in
 * 地球防御本部·机库 (map 221000200) — a tall shaft that stacks many short ladders.
 *
 * <p>{@code GCMovementDriver.clearReachedMoveTarget} box-tested the bot's position against the move
 * target with no regard for movement phase. Both a CLIMB (X pinned to the rope column, Y still rising)
 * and a JUMP (sweeping the whole vertical band of a same-column target) cross within {@code STOP_DIST}
 * (30px) of the goal before actually reaching it, so the target was cleared mid-move and the bot froze
 * a body height short of it — it was not stuck, it believed it had already arrived.
 *
 * <p>Real numbers: measured on 250 random routes on this map, 18 ended with a frozen bot (400–2900
 * ticks); an instrumented run showed 107 of the clears were mid-jump and 18 mid-climb. Ladder x=194
 * (span y=[-4564,-4249], ledge at y=-4566) is the canonical one: the bot was declared "arrived" at
 * y=-4539 (27px short) and sat there for 2835 consecutive ticks.
 *
 * <p>Swimming is the one phase that must NOT gate: a swim-mode bot runs with {@code inAir} set for the
 * whole session, so blocking airborne arrivals outright would strand every swim bot. The rule is pure
 * (no map/Character), the same shape as {@link GCMovementDriver#nextDeadline} /
 * {@link GCMovement#settledPoint}, so it is pinned directly.
 */
class MoveTargetArrivalTest {

    private static final int STOP_DIST = BotMovementManager.cfg.STOP_DIST; // 30 in production

    @Test
    void aClimberWithinStopDistOfTheGoalHasNotArrived() {
        // The reported case: on ladder x=194 at y=-4539, goal = the ledge at (194,-4566), dy = 27.
        assertFalse(GCMovementDriver.reachedMoveTarget(true, false, false, new Point(194, -4539), new Point(194, -4566), STOP_DIST),
                "a bot still on the rope must not be declared arrived");
    }

    @Test
    void aMidAirBotWithinStopDistHasNotArrived() {
        // A jump crosses the goal's 2D band mid-arc (measured dist=(0,27), (26,25), ...) without landing.
        assertFalse(GCMovementDriver.reachedMoveTarget(false, true, false, new Point(168, -3342), new Point(138, -3342), STOP_DIST),
                "a bot still in the air must not be declared arrived");
    }

    @Test
    void aSwimBotIsNotGatedByAirborne() {
        // Swim mode keeps inAir set for the whole session; arrival must still resolve while swimming.
        assertTrue(GCMovementDriver.reachedMoveTarget(false, true, true, new Point(100, 200), new Point(100, 210), STOP_DIST),
                "a swim bot inAir+swimming must still be able to arrive");
    }

    @Test
    void aGroundedBotWithinStopDistHasArrived() {
        assertTrue(GCMovementDriver.reachedMoveTarget(false, false, false, new Point(100, 200), new Point(120, 210), STOP_DIST));
    }

    @Test
    void aGroundedBotOutsideStopDistHasNotArrived() {
        assertFalse(GCMovementDriver.reachedMoveTarget(false, false, false, new Point(100, 200), new Point(100, 240), STOP_DIST));
        assertFalse(GCMovementDriver.reachedMoveTarget(false, false, false, new Point(100, 200), new Point(140, 200), STOP_DIST));
    }
}
