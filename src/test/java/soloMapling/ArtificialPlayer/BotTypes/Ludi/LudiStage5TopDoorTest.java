package soloMapling.ArtificialPlayer.BotTypes.Ludi;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the stage-5 TOP door gate: the door (in01 of 922010500) hangs on a ledge a full 100px above
 * the row beneath it, with no WZ foothold / rope / portal bridging the step. So a bot may only be
 * sent up there when its own jump physics clears 100px - a property of the BODY, not of the job
 * label.
 *
 * <p>Why this is a rule test and not only an execution test: the room claim used to admit every
 * thief/mage lineage ("mobility class"), and a 35-50 bot's profile is 110 jump = 93px rise. The
 * bot then walked into a door it could never enter and stood there for the rest of the stage - the
 * reported "chooses the hidden door but does nothing". The numbers below come from the real WZ
 * constants (Physics.img gravity 2000, jumpSpeed 555) through the production jump profile.
 */
class LudiStage5TopDoorTest {

    /** The production rise for a jump stat, exactly as GCMovement.jumpProfile computes it. */
    private static int riseForJumpStat(int jumpStat) {
        double v = 555.0 * (jumpStat / 100.0);
        return (int) (v * v / (2.0 * 2000.0));
    }

    @Test
    void theTopDoorStepIsOneHundredPixels() {
        // WZ 922010500: fh420 (the door ledge) y=-1291, fh415/416 y=-1276, the climbable row below
        // y=-1176. The step no walk/rope/portal bridges is the 100px one.
        assertEquals(100, LudiStages.STAGE5_TOP_DOOR_STEP_PX);

        // The physics boundary the gate is built on: 113 jump is still short, 114 clears the step
        // (100.08px of rise). A 5-point bucket never lands on 113, so the boundary is a safety
        // margin rather than a value any real profile hits.
        assertTrue(riseForJumpStat(113) < LudiStages.STAGE5_TOP_DOOR_STEP_PX);
        assertTrue(riseForJumpStat(114) >= LudiStages.STAGE5_TOP_DOOR_STEP_PX);
    }

    @Test
    void theLpqLevelBandFallsShortOfTheTopDoor() {
        // levelJumpStat: level <= 50 -> 110 jump -> 93px rise. LPQ is 35-50, so a plain party
        // member cannot be posted to the top room at all.
        assertEquals(110, jumpBaseline(50));
        assertTrue(riseForJumpStat(110) < LudiStages.STAGE5_TOP_DOOR_STEP_PX,
                "the whole LPQ level band sits below the top door's step");
    }

    @Test
    void aHastedThiefClearsIt() {
        // A thief past the Haste self-cap runs 123 jump -> 116px rise: the top room IS theirs.
        assertTrue(riseForJumpStat(123) >= LudiStages.STAGE5_TOP_DOOR_STEP_PX);
    }

    @Test
    void theGateAnswersFromTheProfileNotFromNull() {
        // The predicate must answer, not throw, for a bot with no readable profile yet: refusing a
        // room is recoverable, crashing the stage tick is not.
        assertFalse(LudiStages.jumpClearsTheTopDoor(null));
    }

    private static int jumpBaseline(int level) {
        try {
            java.lang.reflect.Method m = Class.forName(
                            "soloMapling.ArtificialPlayer.GCMoveSystem.BotMovementProfile")
                    .getDeclaredMethod("levelJumpStat", int.class);
            m.setAccessible(true);
            return (int) m.invoke(null, level);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void aBodyWithNoJobIsRefusedRatherThanThrowing() {
        // A bot mid-construction can have a null job; the room claim runs inside the stage tick, so
        // an NPE there would kill the whole tick. The two predicates the gate reads for a jobless
        // body are the seam that has to answer instead of throwing.
        assertFalse(soloMapling.ArtificialPlayer.BotGrindSystem.MovementStylePolicy.hasHasteSkill(null),
                "a null job carries no Haste");
        assertFalse(LudiStages.jumpClearsTheTopDoor(null),
                "a body with no readable profile is refused the top room, not crashed on");
    }

}
