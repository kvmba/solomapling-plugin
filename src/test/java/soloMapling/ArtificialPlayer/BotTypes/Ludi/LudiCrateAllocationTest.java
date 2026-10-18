package soloMapling.ArtificialPlayer.BotTypes.Ludi;

import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins stage 8's crate allocation.
 *
 * <p>{@code 2040043.js} generates {@code stage8combo} only inside its
 * {@code playersOnCombo == 5} branch - i.e. only after five bodies already stand on crate
 * areas - so a bot that WAITED for the property deadlocked the stage ("stage 8 bots do nothing
 * at all"). The set used before the property exists must therefore be a full five DIFFERENT
 * crates: the bots have to produce that precondition themselves, then re-seat on the answer the
 * script keeps.
 *
 * <p>The allocation also has to leave the head slots free (the tail-first rule the real
 * combination already used), because the quest counts every body in the instance and rejects
 * anything but exactly five.
 */
class LudiCrateAllocationTest {

    @Test
    void thePreCombinationSetIsAFullFiveDifferentCrates() {
        List<Point> guess = LudiPqData.guessCrates();
        assertEquals(LudiPqData.CRATES_TO_STAND_ON, guess.size());
        assertEquals(guess.size(), new HashSet<>(guess).size(),
                "the pre-combination crate set repeats an area, so two bots would stack");
        for (Point crate : guess) {
            assertTrue(LudiPqData.CRATE_SPOTS.contains(crate),
                    "the pre-combination set names a spot the map does not have: " + crate);
        }
    }

    @Test
    void thePreCombinationSetUsesTheTailSlots() {
        // Same tail-first allocation as myCrate, so the head slots stay free for players.
        List<Point> tail = LudiPqData.CRATE_SPOTS.subList(
                LudiPqData.CRATE_SPOTS.size() - LudiPqData.CRATES_TO_STAND_ON,
                LudiPqData.CRATE_SPOTS.size());
        assertEquals(tail, LudiPqData.guessCrates());
        // A real combination routes through the same rule: bot 0 takes the last wanted crate.
        assertEquals(new Point(-83, -183), LudiPqData.myCrate("1,1,1,1,1,0,0,0,0", 0));
    }

    @Test
    void aPartyOfFiveBotsCoversAllFiveCratesWithoutOverlap() {
        String combo = "1,1,1,1,1,0,0,0,0";
        Set<Point> taken = new HashSet<>();
        for (int bot = 0; bot < LudiPqData.CRATES_TO_STAND_ON; bot++) {
            taken.add(LudiPqData.myCrate(combo, bot));
        }
        assertEquals(5, taken.size(), "two bots took the same crate");
    }

    @Test
    void extraBodiesAreNotSentToCrates() {
        // Six bodies would break the script's `playersOnCombo == 5` check; a sixth must stay off.
        String combo = "1,1,1,1,1,0,0,0,0";
        assertNull(LudiPqData.myCrate(combo, 5));
        assertNull(LudiPqData.myCrate(combo, -1));
        assertNull(LudiPqData.myCrate((String) null, 0));
    }

    @Test
    void theCombinationStillMapsPositionally() {
        String combo = "0,0,0,0,0,0,0,0,1";
        List<Point> crates = LudiPqData.cratesIn(combo);
        assertEquals(1, crates.size());
        assertEquals(LudiPqData.CRATE_SPOTS.get(8), crates.get(0));
    }
}
