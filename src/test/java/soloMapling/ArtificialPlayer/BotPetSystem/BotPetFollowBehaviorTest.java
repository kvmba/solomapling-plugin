package soloMapling.ArtificialPlayer.BotPetSystem;

import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.ArtificialPlayer.GCMoveSystem.MapleMovement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the two behaviour changes of the independent-follow / jump-parity fix:
 *
 * <ol>
 *   <li><b>Independent follow distance.</b> A pet's follow offset is drawn from a fixed band, so
 *       removing the chained formation must not let a pet send itself an arbitrary distance (it
 *       still holds a "reasonable" gap behind the owner).</li>
 *   <li><b>Hop height parity with the owner.</b> The pet's hop rise comes from the owner's own jump
 *       profile ({@link GCMovement#jumpFeel}), not a fixed base-stat value — the bug being that a
 *       pet launched at the base-stat 555 px/s and therefore could not reach a platform a
 *       higher-jump bot could. A null owner falls back to the base profile, whose rise must equal
 *       the profile-consistent apex (v^2 / 2g).</li>
 * </ol>
 */
class BotPetFollowBehaviorTest {

    @Test
    void followOffsetStaysInTheConfiguredBand() {
        int[] range = BotPetFollower.followOffsetRange();
        int min = range[0];
        int max = range[1];
        assertTrue(min > 0 && max > min, "band must be a positive, ordered range: " + min + ".." + max);
        for (int i = 0; i < 10_000; i++) {
            int offset = BotPetFollower.randomFollowOffset();
            assertTrue(offset >= min && offset <= max,
                    "follow offset " + offset + " outside [" + min + ", " + max + "]");
        }
    }

    @Test
    void baseOwnerHopRiseMatchesThePhysics() {
        // Null owner => base movement profile (jump stat 100 => base jump speed, x1 multiplier).
        GCMovement.JumpFeel feel = GCMovement.jumpFeel(null);
        assertEquals((float) MapleMovement.JUMP_SPEED_PXS, feel.jumpSpeedPxs(), 1e-3,
                "base hop must launch at the base jump speed");
        int expectedRise = (int) (MapleMovement.JUMP_SPEED_PXS * MapleMovement.JUMP_SPEED_PXS
                / (2.0 * MapleMovement.GRAVITY_PXS2));
        assertEquals(expectedRise, feel.risePx(),
                "base hop rise must be the profile-consistent apex v^2/2g");
    }
}
