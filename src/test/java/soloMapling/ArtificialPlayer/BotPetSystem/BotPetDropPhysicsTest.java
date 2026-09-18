package soloMapling.ArtificialPlayer.BotPetSystem;

import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.GCMoveSystem.MapleMovement;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the pet's drop-hop — the fix for "the pet is YANKED down after the bot instead of jumping
 * down". Before it, whenever the owner dropped to a footing below, the pet fell from rest and the
 * whole descent was broadcast with a vertical speed of 0, so an observer rendered a physics-free
 * slide, not a fall.
 *
 * <p>The fix launches the pet UPWARD a little so its own descent is a real arc — under its OWN JUMP
 * pose — and (the other half) publishes the real vertical speed while airborne, so the client renders
 * the arc instead of a slide. The hop stays on the pet's own column: it is NOT aimed at the owner's
 * landing, which would both read as being reeled in and risk overshooting a narrow lower ledge.
 *
 * <p>A real {@code MapleMap}/{@code Character} cannot be built in a unit test (Spring static
 * initializer; Mockito is not on the classpath), so — like {@code GroundSwayTest} — this pins the
 * PRODUCTION launch constant against the PRODUCTION gravity. {@code DROP_HOP_UP_PXS} is
 * package-private precisely so it can be pinned here.
 */
class BotPetDropPhysicsTest {

    @Test
    void theDropHopLaunchesUpwardSoTheDescentIsARealArcNotASlide() {
        assertTrue(BotPetFollower.DROP_HOP_UP_PXS < 0, "the drop hop must launch upward");
        // The apex of a v0-up launch, exactly as simulateAirStep integrates it under the client's
        // gravity. It must clear the launch point, or the descent is still a rest drop; but stay
        // small — it is a step-off hop, not a leap.
        double apex = BotPetFollower.DROP_HOP_UP_PXS * BotPetFollower.DROP_HOP_UP_PXS
                / (2.0 * MapleMovement.GRAVITY_PXS2);
        assertTrue(apex >= 2.0,
                "the launch must rise clear of the ground (apex=" + apex + "px), or it still reads as"
                        + " a stumble off a ledge rather than a jump");
        assertTrue(apex < 40.0, "but it is a short step-off hop, not a leap (apex=" + apex + "px)");
    }

    @Test
    void theDropHopReachesTheGroundWithinOneFollowTick() {
        // A drop hop is a short arc: by the end of the hop its downward speed must be substantial,
        // so the pet falls the remaining distance to its lower ledge promptly (rather than floating).
        double endVy = BotPetFollower.DROP_HOP_UP_PXS + MapleMovement.GRAVITY_PXS2 * 0.2; // one 200ms tick
        assertTrue(endVy > MapleMovement.MAX_FALL_PXS / 3,
                "after one tick the pet must already be falling fast (vy=" + endVy + "px/s)");
    }
}
