package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Guards the move-packet fh (foothold id) the bot puts on the wire per pose. A real client reads that
 * 16-bit field and, for a land id, SNAPS the entity onto the named foothold's footing and takes that
 * foothold's page as the render layer.
 *
 * <p>The bug this locks down: a bot that jumped off a rope/platform kept sending the GROUND foothold id
 * under it while mid-air, so the client dragged the sprite back onto whatever was below — the reported
 * "jumps down while climbing and the bot suddenly renders on the stairs' last layer". A real client
 * sends fh 0 for the whole airborne arc.
 *
 * <p>Evidence, measured off real player movement captures in this repo
 * ({@code BotMovementSystem/movementDataPackets/**}{@code /*.csv}): jump stances 6/7 carried fh 0 in
 * 4174/4174 frames; walk/stand stances 2/3/4/5 carried a positive id in every frame; ladder/rope
 * stances 14-17 carried the negative rope index. {@link BotMovementManager#resolveWireFh} is the pure
 * encode of that mapping.
 */
class AirborneWireFhTest {

    private static final int ROPE_FH = (-1) & 0xFFFF; // idx 1 -> 0xFFFF
    private static final int GROUND_FH = 309;

    @Test
    void airborneSendsZeroEvenWhenGroundIsBelow() {
        // The regression: a ground id here is exactly the "sprite snaps to the stairs' layer" bug.
        assertEquals(0, BotMovementManager.resolveWireFh(false, true, false, 0, GROUND_FH),
                "mid-air must send fh 0, never the ground id below it");
    }

    @Test
    void groundedSendsTheRealFootholdId() {
        assertEquals(GROUND_FH, BotMovementManager.resolveWireFh(false, false, false, 0, GROUND_FH));
    }

    @Test
    void swimmingSendsZero() {
        assertEquals(0, BotMovementManager.resolveWireFh(false, false, true, 0, GROUND_FH));
    }

    @Test
    void climbingSendsTheNegativeRopeIndex() {
        assertEquals(ROPE_FH, BotMovementManager.resolveWireFh(true, false, false, ROPE_FH, GROUND_FH));
    }

    @Test
    void aRopeWithoutAResolvedIndexSendsZeroNotAGroundId() {
        // climbing is still a non-land pose: if the rope index could not be resolved (0), fall to 0,
        // never to the ground id (which would nail the climbing bot to the ground).
        assertEquals(0, BotMovementManager.resolveWireFh(true, false, false, 0, GROUND_FH));
    }

    @Test
    void groundedWithNoFootholdSendsZero() {
        assertEquals(0, BotMovementManager.resolveWireFh(false, false, false, 0, 0));
    }
}
