package soloMapling.ArtificialPlayer.PartyQuest;

import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the LPQ stage-2 descent decision against the tower's real geometry, read off the map
 * img (922010200): the spawn floor sits at y=-2521 and the first box below it at y=-2186 -
 * a 335px drop the bot must make by walking off ledges / down-jumping / grabbing a rope
 * downwards. The bug this guards: {@code approachUnder}'s arrival box (90x70) contained the
 * bot on the spawn floor while the box it had to break hung a floor below, so the bot read
 * IN_POSITION and stood still one room short of the fight.
 */
class PqActionsDescendTest {

    private static final int LPQ_STAGE2_SPAWN_Y = -2521;
    private static final int LPQ_STAGE2_FIRST_BOX_BELOW_Y = -2186;

    /** The vertical gap the stage-2 descent must cover. */
    private static final int STAGE2_FIRST_DESCENT_PX =
            LPQ_STAGE2_FIRST_BOX_BELOW_Y - LPQ_STAGE2_SPAWN_Y - 70; // 70 = APPROACH_Y

    @Test
    void approachBoxIsNarrowerThanTheFirstStage2Descent() {
        // The regression only existed because the approach box could NOT span the descent:
        // pin the numbers so a future change to either side is a conscious one.
        int descentPx = LPQ_STAGE2_FIRST_BOX_BELOW_Y - LPQ_STAGE2_SPAWN_Y;
        assertTrue(Math.abs(descentPx) > 2 * 70,
                "the 335px stage-2 descent now fits a 70px-tall approach box - the descend "
                        + "step would be dead code again");
    }

    @Test
    void descendRequiresAFloorUnderTheTarget() throws Exception {
        Method m = PqActions.class.getDeclaredMethod("descendNeedsFloor", Point.class, Point.class);
        m.setAccessible(true);
        Point bot = new Point(-177, LPQ_STAGE2_SPAWN_Y);
        // A target whose floor is level with the bot: no descent needed.
        assertTrue(!(Boolean) m.invoke(null, bot, new Point(-170, LPQ_STAGE2_SPAWN_Y)),
                "a level floor must read as no descent");
        // A target clearly below: descent.
        assertTrue((Boolean) m.invoke(null, bot, new Point(-170, LPQ_STAGE2_FIRST_BOX_BELOW_Y)),
                "a floor 335px below must read as a descent");
        // No floor under the target (over a hole): never a descent.
        assertTrue(!(Boolean) m.invoke(null, bot, null),
                "no floor under the target must not read as a descent");
    }

    @Test
    void theStage2FirstBoxIsOutJumpReachOfTheSpawnFloor() {
        // Why the plain walk cannot fix this: the box's floor is far below the spawn floor,
        // and the ledges between are forbidFallDown one-ways - only the nav graph's descent
        // edges (walk-off / down-jump / rope descend) reach it.
        assertTrue(Math.abs(LPQ_STAGE2_FIRST_BOX_BELOW_Y - LPQ_STAGE2_SPAWN_Y) > 200,
                "the first box below is within down-jump reach of the spawn floor - "
                        + "the tower would not need the descend step");
    }
}
