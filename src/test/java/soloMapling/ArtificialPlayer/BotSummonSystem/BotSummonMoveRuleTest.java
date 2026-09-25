package soloMapling.ArtificialPlayer.BotSummonSystem;

import org.junit.jupiter.api.Test;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the follower's movement rules and its pure hover-slot seam.
 *
 * <p>The rule that must never regress: a STATIONARY turret is never repositioned (and therefore
 * never receives a move frame). The movement broadcast is separately gated on the map being
 * observed - that LOD gate is one plain {@code if (observed)} at each frame site, while the turret
 * rule is the {@link BotSummonFollower#shouldReposition} seam pinned here. The hover maths is
 * pinned so the slot cannot silently flip sides or drop the bob.</p>
 */
class BotSummonMoveRuleTest {

    // ---- the turret rule --------------------------------------------------------------------

    @Test
    void stationarySummonIsNeverRepositioned() {
        assertFalse(BotSummonFollower.shouldReposition(true),
                "a turret is placed, not moved - the follower must never reposition it");
        assertTrue(BotSummonFollower.shouldReposition(false),
                "a hover summon is repositioned by the follower");
    }

    // ---- hover slot -------------------------------------------------------------------------

    @Test
    void hoverSlotSitsInFrontOfAndAboveTheOwnersFacing() {
        // No bob (amplitudes 0) pins the base offset: beside the facing side, above (offsetY < 0).
        assertEquals(new Point(160, 135),
                BotSummonFollower.hoverTarget(100, 200, false, 60, -65, 0.0, 0.0, 0.0, 0.0),
                "facing right: the summon floats to the owner's right and above");
        assertEquals(new Point(40, 135),
                BotSummonFollower.hoverTarget(100, 200, true, 60, -65, 0.0, 0.0, 0.0, 0.0),
                "facing left: mirrored");
    }

    @Test
    void hoverBobIsBoundedByItsAmplitude() {
        // The offset-only slot (amplitudes 0) is the bob's centre; across a whole period the bobbed
        // slot never strays further than the amplitude from it.
        Point base = BotSummonFollower.hoverTarget(0, 0, false, 60, -65, 0.0, 0.0, 0.0, 0.0);
        for (int i = 0; i < 200; i++) {
            double t = i * 0.05;
            Point p = BotSummonFollower.hoverTarget(0, 0, false, 60, -65, 1.7, t, 14.0, 8.0);
            assertTrue(Math.abs(p.x - base.x) <= 14, "x bob must stay within +/-14px");
            assertTrue(Math.abs(p.y - base.y) <= 8, "y bob must stay within +/-8px");
        }
    }

    @Test
    void hoverBobActuallyMoves() {
        // A float that never moves would read as a frozen sprite; the bob must vary over time.
        Point a = BotSummonFollower.hoverTarget(0, 0, false, 60, 65, 0.0, 0.0, 14.0, 8.0);
        Point b = BotSummonFollower.hoverTarget(0, 0, false, 60, 65, 0.0, 0.7, 14.0, 8.0);
        assertTrue(!a.equals(b), "the hover bob must perturb the slot over time");
    }
}
