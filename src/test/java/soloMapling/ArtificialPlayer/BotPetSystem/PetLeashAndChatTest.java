package soloMapling.ArtificialPlayer.BotPetSystem;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the two behaviour fixes of the pet leash + chat change:
 *
 * <ol>
 *   <li><b>One-sided leash follow.</b> A pet holds its own comfort distance and only ever CLOSES a
 *       gap — it must not run around behind the owner when the owner turns in place, and must not
 *       flee an owner walking toward it. The old facing-relative target did both.</li>
 *   <li><b>Spoken lines from the pet's own dialogue.</b> The command packet carries no text, so the
 *       line is fetched separately; the key selection must honour the command index and the
 *       obeyed/refused branch.</li>
 * </ol>
 */
class PetLeashAndChatTest {

    // ── one-sided leash ───────────────────────────────────────────────────────

    @Test
    void turningInPlaceDoesNotMoveThePet() {
        // Owner at x=100, pet resting to the LEFT at x=40 (60px away, inside its comfort 80).
        // The old facing model sent the target to the owner's back, flipping the pet across when
        // the owner turned; the leash target is the pet's OWN x, so nothing pulls it.
        assertEquals(40, BotPetFollower.followTargetX(100, 40, 80, false),
                "pet inside its leash stands where it is, whatever the owner faces");
        // Same owner, pet on the RIGHT at x=160: still held in place.
        assertEquals(160, BotPetFollower.followTargetX(100, 160, 80, false),
                "a pet inside the leash is unaffected on the other side too");
    }

    @Test
    void ownerWalkingTowardThePetDoesNotPushIt() {
        // Pet at x=40, owner advances from 100 to 60: |40-60|=20 <= 80, so the pet still stands.
        assertEquals(40, BotPetFollower.followTargetX(60, 40, 80, false),
                "an approaching owner must not push the pet (no fleeing)");
        // And even while the owner is MOVING right through the pet's pixel, it holds (ownerMoving).
        assertEquals(50, BotPetFollower.followTargetX(50, 50, 80, true),
                "an owner walking through the pet must not push it");
    }

    @Test
    void petClosesOnlyWhenDrawnBeyondItsLeashStayingOnItsOwnSide() {
        // Pet at x=0, owner walked to x=120 (gap 120 > comfort 80): the leash restrains on the
        // PET's side (x=40), never across the owner to the far side.
        assertEquals(40, BotPetFollower.followTargetX(120, 0, 80, false),
                "a drawn-out pet walks to the comfort ring on its OWN side");
        // Mirror: pet at x=200, owner at x=80 (gap 120): restraint at 160.
        assertEquals(160, BotPetFollower.followTargetX(80, 200, 80, false),
                "symmetrical on the other side (never sent past the owner)");
    }

    @Test
    void standingOwnerNeverLetsAPetRestInsideIt() {
        // The host re-places every pet on the owner's pixel at map entry; a standing owner must walk
        // the pet back out (else it stays stacked inside the bot forever).
        int comfort = 80;
        int out = BotPetFollower.followTargetX(500, 500, comfort, false);
        assertTrue(Math.abs(out - 500) > 30,
                "a pet collapsed onto a STANDING owner steps out past the walk dead zone, got " + out);
        assertTrue(Math.abs(out - 500) <= comfort, "the step-out stays within the comfort band");
        // But an owner WALKING through the pet's pixel still leaves it put.
        assertEquals(500, BotPetFollower.followTargetX(500, 500, comfort, true),
                "no step-out while the owner is moving (would read as fleeing)");
    }

    @Test
    void nearestStableDistanceIsHeldOverTime() {
        // The comfort distance is drawn once and held; the band must stay positive and ordered.
        int[] range = BotPetFollower.followDistanceRange();
        assertTrue(range[0] > 0 && range[1] > range[0], "band must be a positive ordered range");
        for (int i = 0; i < 10_000; i++) {
            int d = BotPetFollower.randomFollowDistance();
            assertTrue(d >= range[0] && d <= range[1], "comfort distance " + d + " outside the band");
        }
    }

    // ── spoken lines ──────────────────────────────────────────────────────────

    @Test
    void picksTheLineForTheCommandAndBranch() {
        Map<String, String> lines = Map.of(
                "c1_s1", "meow~", "c1_s2", "meeeooww~",
                "c1_f1", "meow?", "c1_f2", "meoow?",
                "c2_s1", "sit, meow");
        Random rng = new Random(1);
        for (int i = 0; i < 200; i++) {
            String obey = PetDialogTable.pickLine(lines, 0, true, rng);
            assertTrue("meow~".equals(obey) || "meeeooww~".equals(obey), "obey => c1_s* corpus");
            String refuse = PetDialogTable.pickLine(lines, 0, false, rng);
            assertTrue("meow?".equals(refuse) || "meoow?".equals(refuse), "refuse => c1_f* corpus");
        }
        assertEquals("sit, meow", PetDialogTable.pickLine(lines, 1, true, rng),
                "command index 1 maps to the c2_ keys");
    }

    @Test
    void commandPrefixDoesNotBleedAcrossIndices() {
        // c11_s1 must NOT be picked as an obey line for command 0 (c1_*). A naive startsWith would.
        Map<String, String> lines = Map.of(
                "c1_s1", "one", "c11_s1", "eleven");
        for (int i = 0; i < 500; i++) {
            assertEquals("one", PetDialogTable.pickLine(lines, 0, true, new Random(i)));
        }
    }

    @Test
    void missingLineYieldsNullNotBlank() {
        assertNull(PetDialogTable.pickLine(null, 0, true, new Random(1)));
        assertNull(PetDialogTable.pickLine(Map.of(), 0, true, new Random(1)));
        // Only a failure corpus present => an obeyed command has nothing to say.
        assertNull(PetDialogTable.pickLine(Map.of("c1_f1", "nope"), 0, true, new Random(1)));
        // Blank values are not spoken.
        assertNull(PetDialogTable.pickLine(Map.of("c1_s1", "   "), 0, true, new Random(1)));
    }
}
