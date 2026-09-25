package soloMapling.ArtificialPlayer.BotSummonSystem;

import org.junit.jupiter.api.Test;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the follower's movement rules and its pure follow-slot seam.
 *
 * <p>The rules that must never regress: a STATIONARY turret is never repositioned (and therefore
 * never receives a move frame) - the {@link BotSummonFollower#shouldReposition} seam pinned here;
 * the movement broadcast is separately gated on the map being observed (one plain
 * {@code if (observed)} at each frame site). The follow maths is pinned so the slot cannot
 * silently stop trailing its owner, flip sides on a mere turn, or drop the bob - and the wire
 * action byte must always carry the OWNER's facing in bit 0 (the client mirrors the sprite from
 * that bit; a bare FLY byte renders the bird facing right forever).</p>
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

    // ---- the wire action byte ---------------------------------------------------------------

    @Test
    void flyActionCarriesTheFacingBit() {
        assertEquals(2, BotSummonFollower.flyAction(false), "facing right: FLY with the bit clear");
        assertEquals(3, BotSummonFollower.flyAction(true), "facing left: FLY with bit 0 set");
        assertEquals(1, BotSummonFollower.standAction(true), "facing left: STAND with bit 0 set");
        assertEquals(0, BotSummonFollower.standAction(false));
    }

    @Test
    void flyActionKeepsItsActionBitsWhenFacingFlips() {
        // Odd/even is the whole difference: flipping the facing must not change the action class.
        assertEquals(BotSummonFollower.flyAction(false) & ~1, BotSummonFollower.flyAction(true) & ~1,
                "both facings encode the same action in the high bits");
    }

    // ---- the follow slot --------------------------------------------------------------------

    @Test
    void followSlotTrailsBehindTheOwner() {
        // Summon left of a right-facing owner: it stays behind (further left), at ring distance.
        assertEquals(40, BotSummonFollower.followTargetX(100, 40, 60),
                "inside the leash the summon holds where it is");
        assertEquals(140, BotSummonFollower.followTargetX(100, 140, 60),
                "inside the leash on the other side too");
        assertEquals(40, BotSummonFollower.followTargetX(100, 20, 60),
                "too far behind: pulled up to its own-side ring, never past the owner");
        assertEquals(160, BotSummonFollower.followTargetX(100, 190, 60),
                "too far ahead on the other side: pulled back to its own-side ring");
    }

    @Test
    void aTurnInPlaceNeverMovesTheSlot() {
        // followTargetX never reads the facing, so an owner turning in place cannot move the
        // summon: the same summon x answers the same slot whichever way the owner looks.
        assertEquals(40, BotSummonFollower.followTargetX(100, 40, 60),
                "owner facing right: the summon behind it holds its spot");
        assertEquals(40, BotSummonFollower.followTargetX(100, 40, 60),
                "owner facing left: still the same spot - a turn in place moves nothing");
    }

    @Test
    void followSlotRidesAboveTheOwner() {
        Point p = BotSummonFollower.followTarget(100, 200, 40, 45, -65, 0.0, 0.0, 0.0, 0.0);
        assertEquals(135, p.y, "the summon rides the configured height above the owner");
        assertTrue(p.y < 200, "negative offsetY must put the summon ABOVE its owner");
    }

    @Test
    void followBobIsBoundedByItsAmplitude() {
        // The offset-only slot (amplitudes 0) is the bob's centre; across a whole period the bobbed
        // slot never strays further than the amplitude from it.
        Point base = BotSummonFollower.followTarget(0, 0, 40, 45, -65, 0.0, 0.0, 0.0, 0.0);
        for (int i = 0; i < 200; i++) {
            double t = i * 0.05;
            Point p = BotSummonFollower.followTarget(0, 0, 40, 45, -65, 1.7, t, 14.0, 8.0);
            assertTrue(Math.abs(p.x - base.x) <= 14, "x bob must stay within +/-14px");
            assertTrue(Math.abs(p.y - base.y) <= 8, "y bob must stay within +/-8px");
        }
    }

    @Test
    void followBobActuallyMoves() {
        // A float that never moves would read as a frozen sprite; the bob must vary over time.
        Point a = BotSummonFollower.followTarget(0, 0, 40, 45, -65, 0.0, 0.0, 14.0, 8.0);
        Point b = BotSummonFollower.followTarget(0, 0, 40, 45, -65, 0.0, 0.7, 14.0, 8.0);
        assertTrue(!a.equals(b), "the follow bob must perturb the slot over time");
    }

    // ---- the follow ring --------------------------------------------------------------------

    @Test
    void followRingMatchesThePetsComfortDistances() {
        assertTrue(BotSummonFollower.FOLLOW_MIN_PX > 0);
        assertTrue(BotSummonFollower.FOLLOW_MAX_PX >= BotSummonFollower.FOLLOW_MIN_PX);
        assertTrue(BotSummonFollower.FOLLOW_DEAD_ZONE_PX > 0
                        && BotSummonFollower.FOLLOW_DEAD_ZONE_PX < BotSummonFollower.FOLLOW_MIN_PX,
                "the dead zone must sit inside the ring so a hold still reads as following");
        for (int i = 0; i < 50; i++) {
            int d = BotSummonFollower.freshFollowDistancePx();
            assertTrue(d >= BotSummonFollower.FOLLOW_MIN_PX && d <= BotSummonFollower.FOLLOW_MAX_PX,
                    "a fresh follow distance must land on the pet's comfort ring");
        }
    }
}
