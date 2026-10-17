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
 * <p>Residency replaces the pixel hunt on those treads: once the bot stands ON the foothold within
 * a step of the target IS the arrival, so both ends of the loop — the driver's target clear and the
 * steer's release — agree through this one predicate. A real {@code MapleMap}/{@code Character}
 * cannot be built in a unit test (same constraint as {@code GroundSwayTest}), so the wiring around
 * the predicate is kept thin and this test pins the rule itself: the bot-to-target step gate (the
 * LPQ door-ledge regression: a target on the SAME 39-60px ledge is not arrival while the bot is
 * still 18-30px away), the X slack, the Y gate that keeps vertically stacked stair treads
 * distinct, and the boundary at {@link BotMovementManager#NARROW_LEDGE_PX}.
 */
class NarrowLedgeResidencyTest {

    private static final int BOT_Y = 1752;
    private static final int STEP = 7;

    private static Foothold fh(int x1, int x2, int y) {
        return new Foothold(new Point(x1, y), new Point(x2, y), 1);
    }

    /** {@code botX} stands where the bot is; the target is {@code targetX/Y}. */
    private static boolean settled(Foothold standing, int botX, int targetX, int targetY) {
        return BotMovementManager.settledNarrowLedge(standing, botX, targetX, targetY, BOT_Y, STEP);
    }

    @Test
    void aStairTreadHoldsItsTarget() {
        // The 221024500 stair treads are 19-20px wide (fh 115/111/113); a step of slack past either
        // edge still counts (the bot cannot be held mid-step).
        Foothold tread = fh(110, 129, BOT_Y);
        assertTrue(settled(tread, 119, 119, BOT_Y), "tread centre");
        assertTrue(settled(tread, 110, 110, BOT_Y), "left edge");
        assertTrue(settled(tread, 129, 129, BOT_Y), "right edge");
        assertTrue(settled(tread, 110, 110 - STEP, BOT_Y), "one step short of the ledge");
        assertTrue(settled(tread, 129, 129 + STEP, BOT_Y), "one step past the ledge");
    }

    @Test
    void aTargetOnTheSameTreadIsNotArrivalWhileTheBotIsStillWalking() {
        // The LPQ stage-4/5 door ledges are 39-60px wide and carry the door portal mid-ledge.
        // 922010400 in02: fh 230 spans [-84,-28], the door sits at x=-54. A bot parked at the
        // ledge's left end must WALK to the door: the old rule reported "arrived" for every pixel
        // of the tread, emitted a zero step, and the portal (|dx| <= 8) was never entered - the
        // reported "chose the hidden door but stands at it" freeze.
        Foothold doorLedge = fh(-84, -28, -1200);
        assertFalse(BotMovementManager.settledNarrowLedge(doorLedge, -84, -54, -1200, -1200, STEP),
                "30px from the door on the same tread is NOT arrival");
        assertFalse(BotMovementManager.settledNarrowLedge(doorLedge, -28, -54, -1200, -1200, STEP),
                "26px from the door on the same tread is NOT arrival");
        assertTrue(BotMovementManager.settledNarrowLedge(doorLedge, -54, -54, -1200, -1200, STEP),
                "standing on the door pixel is arrival");
        assertTrue(BotMovementManager.settledNarrowLedge(doorLedge, -60, -54, -1200, -1200, STEP),
                "one step from the door is arrival");
    }

    @Test
    void aStackedTreadIsNotResidency() {
        // 221024500 stacks its treads 38px apart vertically (y1752 / y1790 / y1833). A precise
        // target on the tread BELOW (y1833) must not be satisfied by standing on the y1752 tread,
        // even though the X spans overlap — the Y gate (MAX_SLOPE_UP = 26) keeps them distinct.
        Foothold standing = fh(110, 129, BOT_Y);
        assertFalse(settled(standing, 119, 119, BOT_Y + 38),
                "target one storey below is not residency");
        assertFalse(settled(standing, 119, 119, BOT_Y - 38),
                "target one storey above is not residency");
        // Just inside the gate counts: the engine itself snaps same-ground within MAX_SLOPE_UP.
        assertTrue(settled(standing, 119, 119, BOT_Y + 26),
                "within the same-ground snap band");
        assertFalse(settled(standing, 119, 119, BOT_Y + 27),
                "just past the snap band");
    }

    @Test
    void aTargetBeyondTheStepSlackIsNotResidency() {
        Foothold tread = fh(110, 129, BOT_Y);
        assertFalse(settled(tread, 110, 110 - 8, BOT_Y), "more than a step short");
        assertFalse(settled(tread, 129, 129 + 8, BOT_Y), "more than a step past");
    }

    @Test
    void aWideFloorNeverCountsAsResidency() {
        // 221024500's two 530px floors keep the ordinary pixel settle (and its tests): residency is
        // only for treads narrower than the hand-off width.
        Foothold floor = fh(-265, 265, 2012);
        assertFalse(BotMovementManager.settledNarrowLedge(floor, 0, 0, 2012, 2012, STEP));
        assertFalse(BotMovementManager.settledNarrowLedge(floor, 0, 0, 2012, 2012, 0));
    }

    @Test
    void theHandOffWidthIsTheBoundary() {
        // Exactly NARROW_LEDGE_PX stays a pixel-hunt ledge; one px narrower is a residency tread.
        assertFalse(BotMovementManager.settledNarrowLedge(
                fh(0, BotMovementManager.NARROW_LEDGE_PX, BOT_Y), 0, 0, BOT_Y, BOT_Y, STEP),
                "at the boundary: still wide");
        assertTrue(BotMovementManager.settledNarrowLedge(
                fh(0, BotMovementManager.NARROW_LEDGE_PX - 1, BOT_Y), 0, 0, BOT_Y, BOT_Y, STEP),
                "one px narrower: residency");
    }

    @Test
    void degenerateInputsSettleNothing() {
        assertFalse(BotMovementManager.settledNarrowLedge(null, 0, 0, BOT_Y, BOT_Y, STEP),
                "no ground under the bot");
    }

    @Test
    void aReversedFootholdIsNormalised() {
        // Foothold orientation is an authoring artifact; the rule reads the span.
        assertTrue(settled(fh(129, 110, BOT_Y), 119, 119, BOT_Y));
        assertFalse(settled(fh(129, 110, BOT_Y), 119, 140, BOT_Y),
                "a target beyond the far edge is a walk, not a settle");
    }
}
