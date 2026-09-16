package soloMapling.ArtificialPlayer.BotGrindSystem;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertTrue;

/*
 * Guards the turn-beat deadzone invariant that fixes the flat-ground left-right sway.
 *
 * doTurnBeat steps up to TURN_STEP_MAX_PX toward the mob to sell the "turn while walking" beat.
 * needsTurnBeat only fires when the mob is farther than TURN_DEADZONE_PX to the side. If the
 * deadzone is smaller than the max step, a turn-beat from that band can step PAST the mob; the mob
 * then sits on the other side, needsTurnBeat fires again next beat, and the bot paces back and forth
 * across it (observed on flat ground as a slow left-right sway). A pet trailing by the owner's
 * facing mirrors that flip, so it paces too.
 *
 * The invariant: TURN_DEADZONE_PX >= TURN_STEP_MAX_PX, so a turn-beat only ever starts from a
 * distance the step cannot cross.
 */
class EngageBeatTurnDeadzoneTest {

    private static int constant(String name) throws Exception {
        Field f = EngageBeat.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.getInt(null);
    }

    @Test
    void turnDeadzoneIsAtLeastTheMaxStepSoATurnCannotOvershootTheMob() throws Exception {
        int deadzone = constant("TURN_DEADZONE_PX");
        int maxStep = constant("TURN_STEP_MAX_PX");
        assertTrue(deadzone >= maxStep,
                "TURN_DEADZONE_PX (" + deadzone + ") must be >= TURN_STEP_MAX_PX (" + maxStep
                        + "), else a turn-beat can step past the mob and pace the bot back and forth");
    }

    @Test
    void turnStepBoundsAreOrdered() throws Exception {
        int minStep = constant("TURN_STEP_MIN_PX");
        int maxStep = constant("TURN_STEP_MAX_PX");
        assertTrue(minStep <= maxStep, "TURN_STEP_MIN_PX must not exceed TURN_STEP_MAX_PX");
        assertTrue(minStep > 0, "TURN_STEP_MIN_PX must be a real step");
    }
}
