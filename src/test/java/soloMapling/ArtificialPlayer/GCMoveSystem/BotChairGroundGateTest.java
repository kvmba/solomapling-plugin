package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/*
 * Regression for the "bot sitting in mid-air" report: a chair (a ground pose) must never be pinned on a
 * GC-driven bot that is not actually standing on a foothold — most visibly a bot cruising an underwater
 * (map.isSwim) town, which is airborne/swimming by design and has no foothold under it. The movement
 * driver skips every tick for a seated bot, so pinning one mid-swim froze it in the SIT pose at whatever
 * water column it was in.
 *
 * The fix is a single shared predicate, GCMovement.isGrounded(state) / .isGrounded(character), used both
 * by botSitChair's ground gate and the driver's chair hold. These tests lock that predicate's truth table.
 */
class BotChairGroundGateTest {

    private static BotMovementState state(boolean inAir, boolean swimming, boolean climbing) {
        BotMovementState st = new BotMovementState(null, null);
        st.inAir = inAir;
        st.swimming = swimming;
        st.climbing = climbing;
        return st;
    }

    @Test
    void aBotInNoGcStateIsNotGrounded() {
        assertFalse(GCMovement.isGrounded((BotMovementState) null));
    }

    @Test
    void aSettledBotIsGrounded() {
        assertTrue(GCMovement.isGrounded(state(false, false, false)));
    }

    @Test
    void aSwimmingBotIsNotGrounded() {
        // The underwater-town case: awake, moving through water, no foothold beneath it.
        assertFalse(GCMovement.isGrounded(state(true, true, false)));
    }

    @Test
    void anAirborneBotIsNotGrounded() {
        assertFalse(GCMovement.isGrounded(state(true, false, false)));
    }

    @Test
    void aClimbingBotIsNotGrounded() {
        assertFalse(GCMovement.isGrounded(state(false, false, true)));
    }

    @Test
    void anyNonGroundPoseDefeatsTheGate() {
        assertFalse(GCMovement.isGrounded(state(false, true, false)));
        assertFalse(GCMovement.isGrounded(state(false, true, true)));
        assertFalse(GCMovement.isGrounded(state(true, false, true)));
    }
}
