package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.server.maps.Foothold;
import org.junit.jupiter.api.Test;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the shared narrow-ledge residency rule ({@link BotMovementManager#settledNarrowLedge}) —
 * the fix for the toy-tower (221024500) stair sway: fifteen LudiPQ lobby bots strolling on a map
 * whose stair treads are 8-62px wide kept reversing around a precise pixel target, because a WALK
 * step (~7px) plus its glide-out cannot hold a pixel on a 19px tread.
 *
 * <p>Residency replaces the pixel hunt on those treads: standing on the foothold inside the
 * target's band IS the arrival, so both ends of the loop — the driver's target clear and the
 * steer's release — agree through this one predicate. A real {@code MapleMap}/{@code Character}
 * cannot be built in a unit test (same constraint as {@code GroundSwayTest}), so the wiring around
 * the predicate is kept thin and this test pins the rule itself: the X slack, the Y gate that
 * keeps vertically stacked stair treads distinct, and the boundary at
 * {@link BotMovementManager#NARROW_LEDGE_PX} where the two callers hand off.
 */
class NarrowLedgeResidencyTest {

    private static final int BOT_Y = 1752;

    private static Foothold fh(int x1, int x2, int y) {
        return new Foothold(new Point(x1, y), new Point(x2, y), 1);
    }

    @Test
    void aStairTreadHoldsItsTarget() {
        // The 221024500 stair treads are 19-20px wide (fh 115/111/113); a step of slack past either
        // edge still counts (the bot cannot be held mid-step).
        Foothold tread = fh(110, 129, BOT_Y);
        assertTrue(BotMovementManager.settledNarrowLedge(tread, 119, BOT_Y, BOT_Y, 7), "tread centre");
        assertTrue(BotMovementManager.settledNarrowLedge(tread, 110, BOT_Y, BOT_Y, 7), "left edge");
        assertTrue(BotMovementManager.settledNarrowLedge(tread, 129, BOT_Y, BOT_Y, 7), "right edge");
        assertTrue(BotMovementManager.settledNarrowLedge(tread, 110 - 7, BOT_Y, BOT_Y, 7), "one step short");
        assertTrue(BotMovementManager.settledNarrowLedge(tread, 129 + 7, BOT_Y, BOT_Y, 7), "one step past");
    }

    @Test
    void aStackedTreadIsNotResidency() {
        // 221024500 stacks its treads 38px apart vertically (y1752 / y1790 / y1833). A precise
        // target on the tread BELOW (y1833) must not be satisfied by standing on the y1752 tread,
        // even though the X spans overlap — the Y gate (MAX_SLOPE_UP = 26) keeps them distinct.
        Foothold standing = fh(110, 129, BOT_Y);
        assertFalse(BotMovementManager.settledNarrowLedge(standing, 119, BOT_Y + 38, BOT_Y, 7),
                "target one storey below is not residency");
        assertFalse(BotMovementManager.settledNarrowLedge(standing, 119, BOT_Y - 38, BOT_Y, 7),
                "target one storey above is not residency");
        // Just inside the gate counts: the engine itself snaps same-ground within MAX_SLOPE_UP.
        assertTrue(BotMovementManager.settledNarrowLedge(standing, 119, BOT_Y + 26, BOT_Y, 7),
                "within the same-ground snap band");
        assertFalse(BotMovementManager.settledNarrowLedge(standing, 119, BOT_Y + 27, BOT_Y, 7),
                "just past the snap band");
    }

    @Test
    void aTargetBeyondTheStepSlackIsNotResidency() {
        Foothold tread = fh(110, 129, BOT_Y);
        assertFalse(BotMovementManager.settledNarrowLedge(tread, 110 - 8, BOT_Y, BOT_Y, 7),
                "more than a step short");
        assertFalse(BotMovementManager.settledNarrowLedge(tread, 129 + 8, BOT_Y, BOT_Y, 7),
                "more than a step past");
    }

    @Test
    void aWideFloorNeverCountsAsResidency() {
        // 221024500's two 530px floors keep the ordinary pixel settle (and its tests): residency is
        // only for treads narrower than the hand-off width.
        Foothold floor = fh(-265, 265, 2012);
        assertFalse(BotMovementManager.settledNarrowLedge(floor, 0, 2012, 2012, 7));
        assertFalse(BotMovementManager.settledNarrowLedge(floor, 0, 2012, 2012, 0));
    }

    @Test
    void theHandOffWidthIsTheBoundary() {
        // Exactly NARROW_LEDGE_PX stays a pixel-hunt ledge; one px narrower is a residency tread.
        assertFalse(BotMovementManager.settledNarrowLedge(
                fh(0, BotMovementManager.NARROW_LEDGE_PX, BOT_Y), 0, BOT_Y, BOT_Y, 7),
                "at the boundary: still wide");
        assertTrue(BotMovementManager.settledNarrowLedge(
                fh(0, BotMovementManager.NARROW_LEDGE_PX - 1, BOT_Y), 0, BOT_Y, BOT_Y, 7),
                "one px narrower: residency");
    }

    @Test
    void degenerateInputsSettleNothing() {
        assertFalse(BotMovementManager.settledNarrowLedge(null, 0, BOT_Y, BOT_Y, 7),
                "no ground under the bot");
    }

    @Test
    void aReversedFootholdIsNormalised() {
        // Foothold orientation is an authoring artifact; the rule reads the span.
        assertTrue(BotMovementManager.settledNarrowLedge(fh(129, 110, BOT_Y), 119, BOT_Y, BOT_Y, 7));
        assertFalse(BotMovementManager.settledNarrowLedge(fh(129, 110, BOT_Y), 140, BOT_Y, BOT_Y, 7));
    }
}
