package soloMapling.ArtificialPlayer.BotMovementSystem;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard for the "bot frozen in the air in the jump pose" report on the recorded-path engine
 * (the Free Market merchants' platform shuffle, {@code PlatformPlacement} -> {@code pathFinderBeta} ->
 * {@code executeMovement}).
 *
 * <p>A recorded path is routinely a SLICE of a longer recording —
 * {@code getPathBetweenTwoPointsInMainArea} trims by timestamp — and 244 of the shipped main-area
 * packets end on a jump stance, so a trimmed slice can end there too. The replay then leaves the bot
 * mid-air carrying JUMP(6/7). The pre-existing replay epilogue does not cover that:
 * {@code injectArtificialStopPacket} fires only for a WALKING stance, because a walk has to be stopped
 * while a jump was assumed self-resolving. A headless bot has no client gravity sim to resolve it, so
 * the last broadcast pose is what observers keep rendering.
 *
 * <p>{@code executeMovement} now ends every airborne replay on real ground in a standing pose. This pins
 * the decision half of it: which end stances count as airborne.
 */
class RecordedReplaySettleTest {

    private static final byte WALK_RIGHT = 2;
    private static final byte WALK_LEFT = 3;
    private static final byte STAND_RIGHT = 4;
    private static final byte STAND_LEFT = 5;
    private static final byte JUMP_RIGHT = 6;
    private static final byte JUMP_LEFT = 7;

    @Test
    void aJumpEndIsAirborne() {
        // The reported case: the trimmed slice stopped on a jump frame.
        assertTrue(MovementCommands.endsAirborne(JUMP_RIGHT));
        assertTrue(MovementCommands.endsAirborne(JUMP_LEFT));
    }

    @Test
    void aWalkEndIsNotAirborne() {
        // Covered by the pre-existing stop-packet path; settling it here would double-send.
        assertFalse(MovementCommands.endsAirborne(WALK_RIGHT));
        assertFalse(MovementCommands.endsAirborne(WALK_LEFT));
    }

    @Test
    void aStandEndIsNotAirborne() {
        // Already on the floor: nothing to settle.
        assertFalse(MovementCommands.endsAirborne(STAND_RIGHT));
        assertFalse(MovementCommands.endsAirborne(STAND_LEFT));
    }

    @Test
    void otherPosesAreNotTreatedAsAirborne() {
        // Rope/swim/dead/sit ends must not be yanked to the floor by this rule: a rope or an underwater
        // pose is a legitimate place to be, and there is no floor to snap to.
        for (byte stance : new byte[]{10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21}) {
            assertFalse(MovementCommands.endsAirborne(stance), "stance " + stance + " must not settle");
        }
    }
}
