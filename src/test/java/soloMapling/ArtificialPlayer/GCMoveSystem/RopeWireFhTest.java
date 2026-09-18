package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.constants.game.CharacterStance;
import org.gms.server.maps.Rope;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Guards {@link GCMovement#ropeFh(int, int, int, List)} — the rope/ladder fh a climbing bot puts on
 * the wire (now shared with the recorded-path engine via {@code MovementCommands.findFootHoldId}).
 *
 * <p>A real client tests {@code fh & 0x8000} to tell "on a rope" from "on ground" and binds the
 * character to that rope. The wire value must therefore be the two's-complement NEGATIVE 1-based
 * index ({@code (-idx) & 0xFFFF}), NOT {@code 0x8000 | idx}, which decodes to 32767 and silently
 * means "no rope". Sending a plain positive ground id while climbing is what lets the client snap
 * the character onto that foothold — the observed "climb send a positive id => nailed to the ground"
 * bug.
 */
class RopeWireFhTest {

    // Rope x must line up with the climbing character's x within ROPE_GRAB_X (8).
    private static final List<Rope> ROPES = List.of(
            new Rope(100, 200, 400, false), // 1-based index 1 (rope)
            new Rope(300, 100, 500, true)); // 1-based index 2 (ladder)

    private static final int ROPE_STANCE = CharacterStance.ROPE_RIGHT_STANCE; // 16

    @Test
    void firstRopeIsNegativeOne() {
        // idx 1 -> (-1) & 0xFFFF = 0xFFFF, which the client negates back to 1.
        assertEquals(0xFFFF, GCMovement.ropeFh(ROPE_STANCE, 100, 300, ROPES));
    }

    @Test
    void secondRopeIsNegativeTwo() {
        // idx 2 -> (-2) & 0xFFFF = 0xFFFE.
        assertEquals(0xFFFE, GCMovement.ropeFh(CharacterStance.LADDER_RIGHT_STANCE, 300, 300, ROPES));
    }

    @Test
    void notClimbingIsNotARopeFh() {
        // Standing/walking on the rope column is NOT a rope fh — the caller falls back to the ground id.
        assertEquals(0, GCMovement.ropeFh(CharacterStance.STAND_RIGHT_STANCE, 100, 300, ROPES));
        assertEquals(0, GCMovement.ropeFh(CharacterStance.WALK_RIGHT_STANCE, 100, 300, ROPES));
    }

    @Test
    void offAxisOrOutOfSpanIsNotARopeFh() {
        assertEquals(0, GCMovement.ropeFh(ROPE_STANCE, 140, 300, ROPES), "31px off axis > ROPE_GRAB_X");
        assertEquals(0, GCMovement.ropeFh(ROPE_STANCE, 100, 199, ROPES), "above the rope top");
        assertEquals(0, GCMovement.ropeFh(ROPE_STANCE, 100, 401, ROPES), "below the rope bottom");
    }

    @Test
    void noRopesOrNullIsZero() {
        assertEquals(0, GCMovement.ropeFh(ROPE_STANCE, 100, 300, List.of()));
        assertEquals(0, GCMovement.ropeFh(ROPE_STANCE, 100, 300, null));
    }

    @Test
    void picksTheNearestColumnWhenTwoOverlap() {
        // Two ropes that both span y and sit within grab range: the first match wins (index 1).
        List<Rope> overlapping = List.of(
                new Rope(100, 200, 400, false),
                new Rope(104, 200, 400, false));
        assertEquals(0xFFFF, GCMovement.ropeFh(ROPE_STANCE, 102, 300, overlapping));
    }
}
