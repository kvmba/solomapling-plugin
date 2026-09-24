package soloMapling.ArtificialPlayer.BotSummonSystem;

import org.junit.jupiter.api.Test;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the follower's movement rules and its two pure position seams.
 *
 * <p>The rule that must never regress: a STATIONARY turret is never repositioned (and therefore
 * never receives a move frame). The movement broadcast is separately gated on the map being
 * observed - that LOD gate is one plain {@code if (observed)} at each frame site, while the turret
 * rule is the {@link BotSummonFollower#shouldReposition} seam pinned here. The position maths is
 * pinned so the follow slot and the orbit ring cannot silently flip sides.</p>
 */
class BotSummonMoveRuleTest {

    // ---- the turret rule --------------------------------------------------------------------

    @Test
    void stationarySummonIsNeverRepositioned() {
        assertFalse(BotSummonFollower.shouldReposition(true),
                "a turret is placed, not moved - the follower must never reposition it");
        assertTrue(BotSummonFollower.shouldReposition(false),
                "a follow/orbit summon is repositioned by the follower");
    }

    // ---- FOLLOW slot ------------------------------------------------------------------------

    @Test
    void followSlotSitsInFrontOfTheOwnersFacing() {
        assertEquals(new Point(155, 188),
                BotSummonFollower.followTarget(100, 200, false, 55, -12),
                "facing right: the summon hovers to the owner's right");
        assertEquals(new Point(45, 188),
                BotSummonFollower.followTarget(100, 200, true, 55, -12),
                "facing left: mirrored");
    }

    // ---- CIRCLE ring ------------------------------------------------------------------------

    @Test
    void circleTargetRidesTheRingAroundTheOwner() {
        assertEquals(new Point(170, 200), BotSummonFollower.circleTarget(100, 200, 0, 70),
                "0 degrees sits on the owner's right at the ring radius");
        assertEquals(new Point(100, 270), BotSummonFollower.circleTarget(100, 200, 90, 70),
                "90 degrees sits below (screen coords)");
        assertEquals(new Point(30, 200), BotSummonFollower.circleTarget(100, 200, 180, 70));
    }

    @Test
    void circleAngleAdvancesEachTick() {
        Point a = BotSummonFollower.circleTarget(0, 0, 0, 70);
        Point b = BotSummonFollower.circleTarget(0, 0, 6, 70);
        assertNotEquals(a, b, "an orbit that never advances would read as a frozen ring");
    }
}
