package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.junit.jupiter.api.Test;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression guard for the "bot frozen in mid-air" report on the Free Market arrivals.
 *
 * <p>An arrival can end a movement session while the bot is still at the map-entry float point:
 * {@code GCMovementDriver.onMapChange} lifts the bot {@code PORTAL_FLOAT_HEIGHT_PX} (60px) above the
 * floor and arms the drop, and a hand-over that completes before the release leaves those suspended
 * coordinates behind. The session-final settle used to only set the standing POSE
 * ({@code idleOnGround}) and keep the coordinates, so the bot was left standing 60px in the air with
 * the driver stopped and its state removed — nothing was left to drop it.
 *
 * <p>{@code settleGroundedOnDisable} now re-resolves the floor under the bot before settling, using
 * the pure rule in {@link GCMovement#settledPoint}. A real {@code MapleMap}/{@code Character} cannot
 * be built in a unit test (their constructors trip the Spring-backed {@code Server} static
 * initializer; the same constraint as {@code GroundSwayTest} / {@code JumpLaunchGateTest}), so these
 * tests pin the rule itself: snap to real ground, and never invent a position when there is none.
 */
class SettledPointTest {

    @Test
    void snapsToTheGroundWhenThereIsAny() {
        // The reported case: hovering at the map-entry float point, floor 60px below.
        Point floatPoint = new Point(790, -380);
        Point floor = new Point(790, -320);
        assertEquals(floor, GCMovement.settledPoint(floatPoint, floor),
                "a session must end on the floor below, not at the float point");
    }

    @Test
    void keepsThePositionWhenThereIsNoGround() {
        // No foothold below (a mid-shaft cut, an unplaceable spot): never invent a position — leaving
        // the bot where it is keeps the honest, visible state rather than teleporting it somewhere.
        Point suspended = new Point(120, 400);
        assertEquals(suspended, GCMovement.settledPoint(suspended, null));
    }

    @Test
    void keepsAnAlreadyGroundedPositionUnchanged() {
        Point grounded = new Point(42, 77);
        assertEquals(grounded, GCMovement.settledPoint(grounded, new Point(42, 77)),
                "a bot already on its floor must not be moved by the settle");
    }
}
