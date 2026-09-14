package soloMapling.ArtificialPlayer.BotPetSystem;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure-logic tests for the pet-behaviour picker (no WZ, no host types). */
class PetCommandInterpreterTest {

    private static PetInteractionTable.Interact it(int index, int prob, int l0, int l1) {
        return new PetInteractionTable.Interact(index, prob, l0, l1);
    }

    @Test
    void picksOnlyWithinLevelBand() {
        // Only the low band is allowed at level 3.
        List<PetInteractionTable.Interact> table = List.of(
                it(0, 100, 1, 9),
                it(10, 100, 10, 19));
        Random rng = new Random(1);
        for (int i = 0; i < 500; i++) {
            PetInteractionTable.Interact pick = PetCommandInterpreter.pick(table, 3, rng);
            assertEquals(0, pick.index(), "only the 1..9 band applies at level 3");
        }
    }

    @Test
    void higherLevelsUnlockMoreCommands() {
        List<PetInteractionTable.Interact> table = List.of(
                it(0, 1, 1, 9),
                it(10, 1, 10, 19));
        Random rng = new Random(2);
        boolean sawHigh = false;
        for (int i = 0; i < 2000; i++) {
            PetInteractionTable.Interact pick = PetCommandInterpreter.pick(table, 15, rng);
            if (pick.index() == 10) {
                sawHigh = true;
            }
        }
        assertTrue(sawHigh, "a level-15 pet can reach the 10..19 command");
    }

    @Test
    void emptyOrZeroProbYieldsNull() {
        Random rng = new Random(3);
        assertNull(PetCommandInterpreter.pick(List.of(), 5, rng));
        assertNull(PetCommandInterpreter.pick(null, 5, rng));
        assertNull(PetCommandInterpreter.pick(List.of(it(0, 0, 1, 200)), 5, rng));
        // Out of band only.
        assertNull(PetCommandInterpreter.pick(List.of(it(0, 50, 20, 29)), 5, rng));
    }

    @Test
    void weightingFavoursHigherProb() {
        List<PetInteractionTable.Interact> table = List.of(
                it(0, 90, 1, 200),
                it(1, 10, 1, 200));
        Random rng = new Random(4);
        int zero = 0;
        int trials = 20_000;
        for (int i = 0; i < trials; i++) {
            if (PetCommandInterpreter.pick(table, 5, rng).index() == 0) {
                zero++;
            }
        }
        double rate = (double) zero / trials;
        assertTrue(rate > 0.80 && rate < 0.98, "90:10 weighting => ~0.90, was " + rate);
    }
}
