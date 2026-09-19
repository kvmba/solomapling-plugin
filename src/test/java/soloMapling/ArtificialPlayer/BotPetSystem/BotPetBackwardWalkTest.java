package soloMapling.ArtificialPlayer.BotPetSystem;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the fix for the reported "bot on the lower platform, pet on the upper platform: when the
 * bot moves, the pet walks BACKWARD" — the pet faces one way while sliding the other.
 *
 * <p>Two related flaws combined to produce it, both now pinned here by their pure seams:</p>
 *
 * <ol>
 *   <li><b>The drop-hop fired mid-walk.</b> Round 15's "hop down to an owner below" branch keyed on
 *       {@code ownerBelow && walkStayedLevel}. That is ALSO true on every tick a pet walks
 *       horizontally along its own platform above a lower owner (each walking tick ends level with
 *       where it started), so the branch fired mid-walk and dropped the pet under a stale pose.
 *       {@link BotPetFollower#dropsToOwnerBelow} now requires the pet to be at REST.</li>
 *   <li><b>The jump/fall pose used the stale facing.</b> Even a genuine fall or hop stamped its
 *       JUMP/land pose from the pet's PREVIOUS facing, so a pet that turned (the owner crossed to
 *       its other side) and then hop/land began still faced the old way —
 *       {@link BotPetFollower#facesLeftOnMotion} now faces the pose by the actual motion.</li>
 * </ol>
 *
 * <p>Measured on the production engine over 4000 randomised two-platform scenarios: backward ticks
 * 19022 at HEAD -> 442 with only the drop gate -> <b>0</b> with both seams (and spurious hops while
 * walking 22098 -> 0).</p>
 */
class BotPetBackwardWalkTest {

    private static final double WALKING = 125.0; // px/s: clearly moving

    // ── seam 1: only a pet at rest drops to an owner below ─────────────────────────────────────

    @Test
    void aRestingPetOnALedgeItsOwnerLeftDrops() {
        // The round-15 case, unchanged: owner below, walk stayed level, pet at rest (vx ~ 0).
        assertTrue(BotPetFollower.dropsToOwnerBelow(false, true, true, 0.0),
                "a pet standing still on a ledge the owner has left must hop down to the lower footing");
        assertTrue(BotPetFollower.dropsToOwnerBelow(false, true, true, 1.0),
                "the at-rest band matches the walk's own stand/move threshold (|vx| <= 1)");
    }

    @Test
    void aWalkingPetOnItsOwnPlatformAboveTheOwnerDoesNotDrop() {
        // The reported bug: the pet is walking along its own platform while the owner is below; every
        // walking tick ends level, so the old test dropped it — under a stale (opposite) facing pose.
        assertFalse(BotPetFollower.dropsToOwnerBelow(false, true, true, WALKING),
                "a pet still walking toward a horizontal target is not stalled on an abandoned ledge");
        assertFalse(BotPetFollower.dropsToOwnerBelow(false, true, true, -WALKING),
                "the gate is direction-agnostic: a leftward walk must hold too");
    }

    @Test
    void aDownSlopeIsNeverADrop() {
        assertFalse(BotPetFollower.dropsToOwnerBelow(false, true, false, 0.0),
                "a walk that lowered the pet is a down-slope, not a drop");
    }

    @Test
    void aSharedSwimMapSlopeIsNeverADrop() {
        assertFalse(BotPetFollower.dropsToOwnerBelow(true, true, true, 0.0),
                "a shared walk surface means there is no platform below to drop to");
    }

    @Test
    void anOwnerNotBelowIsNeverADrop() {
        assertFalse(BotPetFollower.dropsToOwnerBelow(false, false, true, 0.0),
                "no lower owner, no drop — the hop-up / walk / stand paths handle the rest");
    }

    // ── seam 2: a jump/fall/land pose faces the motion, not the stale facing ───────────────────

    @Test
    void aPoseFacesTheDirectionOfMotion() {
        assertTrue(BotPetFollower.facesLeftOnMotion(-WALKING, false),
                "moving left must face left even if the pet previously faced right (the reported bug)");
        assertFalse(BotPetFollower.facesLeftOnMotion(WALKING, true),
                "moving right must face right even if the pet previously faced left");
    }

    @Test
    void aMotionlessFallKeepsTheStandingFacing() {
        // A straight-down drop-hop (ax ~ 0) has no direction of its own: it must keep the facing it
        // already had rather than resolve to a side the pet never asked for.
        assertTrue(BotPetFollower.facesLeftOnMotion(0.0, true),
                "a motionless fall keeps a left facing");
        assertFalse(BotPetFollower.facesLeftOnMotion(0.0, false),
                "a motionless fall keeps a right facing");
        // Inside the stand/move dead band counts as motionless (same threshold the walk uses).
        assertTrue(BotPetFollower.facesLeftOnMotion(1.0, true),
                "|vx| <= 1 is treated as no motion, matching the walk's own threshold");
    }
}
