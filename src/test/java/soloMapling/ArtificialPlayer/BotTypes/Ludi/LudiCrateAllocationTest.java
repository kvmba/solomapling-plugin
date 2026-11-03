package soloMapling.ArtificialPlayer.BotTypes.Ludi;

import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.PartyQuest.PositionCombination;
import soloMapling.ArtificialPlayer.PartyQuest.PqActions;

import java.awt.Point;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the stage-8 search space: the bots must find the five crates by submitting patterns and
 * discarding the ones the quest judged wrong, never by reading the stored answer.
 */
class LudiCrateAllocationTest {

    @Test
    void everyPatternCanBeRuledOutExactlyOnce() {
        List<PositionCombination.Candidate> candidates = LudiPqData.crateCandidates();
        assertEquals(LudiPqData.COMBO_COUNT, candidates.size());
        Set<Long> tried = new HashSet<>();
        int index = 0;
        for (int attempt = 0; attempt < LudiPqData.COMBO_COUNT; attempt++) {
            index = PositionCombination.nextUntried(tried, candidates, index);
            assertTrue(index >= 0, "ran out of candidates at attempt " + attempt);
            assertTrue(tried.add(candidates.get(index).pattern()));
        }
        // All 126 retired: nothing left to present, which the caller treats as a broken ledger
        // and restarts (never as "the answer is elsewhere").
        assertEquals(-1, PositionCombination.nextUntried(tried, candidates, 0));
        assertEquals(LudiPqData.COMBO_COUNT, tried.size());
    }

    @Test
    void aFiveBotPartyCoversAllFiveCratesWithoutOverlap() {
        for (int attempt = 0; attempt < LudiPqData.COMBO_COUNT; attempt++) {
            List<Point> candidate = LudiPqData.candidateAt(attempt);
            Set<Point> taken = new HashSet<>();
            for (int bot = 0; bot < LudiPqData.CRATES_TO_STAND_ON; bot++) {
                taken.add(PositionCombination.mine(candidate, bot));
            }
            assertEquals(5, taken.size(), "two bots took the same crate on attempt " + attempt);
        }
    }

    @Test
    void theCrateCandidatesCarryTheirOwnSpotLists() {
        List<PositionCombination.Candidate> candidates = LudiPqData.crateCandidates();
        for (int i = 0; i < candidates.size(); i++) {
            PositionCombination.Candidate candidate = candidates.get(i);
            assertEquals(5, PqActions.bodiesOf(candidate.pattern()));
            assertEquals(5, candidate.spots().size());
            assertEquals(LudiPqData.candidateAt(i), candidate.spots(),
                    "candidate " + i + " disagrees with the index-based lookup");
            assertEquals(PositionCombination.maskToPattern(LudiPqData.maskAt(i)),
                    candidate.pattern());
        }
    }

    @Test
    void theRoomAreasAreTheNineCratesInQuestOrder() {
        // The script counts areas in map order; the crate table is in that same order, so with
        // the real map data present the two must line up one for one.
        List<java.awt.Rectangle> areas = LudiPqData.crateAreas(null);
        assertEquals(LudiPqData.CRATE_SPOTS.size(), areas.size());
        for (int i = 0; i < areas.size(); i++) {
            assertTrue(areas.get(i).contains(LudiPqData.CRATE_SPOTS.get(i)),
                    "crate " + i + " is outside its own fallback rectangle");
        }
    }
}
