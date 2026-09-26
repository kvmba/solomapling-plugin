package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the truth table of the humanlike down-jump cadence gate
 * ({@code BotPhysicsEngine.downJumpOnCadenceCooldown}) that gates the three straight down-jump
 * entry points (graph DROP executor, warmup-fallback drop, CROUCH ground action).
 *
 * <p>Background: without a pause the engine re-fired a DROP within a couple of ticks of landing,
 * which on stacked-shaft maps (玩具塔) read as one continuous floor-to-floor dive — visibly faster
 * than a player pressing Down+Alt, who needs about a second between two down-jumps.
 *
 * @see BotPhysicsEngine.Config#DOWN_JUMP_CADENCE_MS
 * @see BotMovementState#downJumpCadenceUntilMs
 */
class DownJumpCadenceTest {

    private static BotMovementState state() {
        return new BotMovementState(null, null);
    }

    @Test
    void aBotInsideTheCadenceBeatIsGated() {
        BotMovementState st = state();
        st.downJumpCadenceUntilMs = System.currentTimeMillis() + 5000;
        assertTrue(BotPhysicsEngine.downJumpOnCadenceCooldown(st),
                "a bot within the humanlike beat must not launch the next down-jump yet");
    }

    @Test
    void aBotWithNoArmedBeatIsNeverGated() {
        // Default state (never down-jumped): the gate must be a no-op so first drops are instant.
        assertFalse(BotPhysicsEngine.downJumpOnCadenceCooldown(state()),
                "a bot that never down-jumped must not be gated");
    }

    @Test
    void anElapsedBeatIsNeverGated() {
        BotMovementState st = state();
        st.downJumpCadenceUntilMs = System.currentTimeMillis() - 1;
        assertFalse(BotPhysicsEngine.downJumpOnCadenceCooldown(st),
                "once the beat has passed the next down-jump must be free to launch");
    }
}
