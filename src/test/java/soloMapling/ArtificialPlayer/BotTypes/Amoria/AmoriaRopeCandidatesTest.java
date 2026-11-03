package soloMapling.ArtificialPlayer.BotTypes.Amoria;

import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.PartyQuest.PositionCombination;
import soloMapling.ArtificialPlayer.PartyQuest.PqActions;

import java.awt.Point;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the two Amoria rope stages' candidate spaces.
 *
 * <p>Both scripts store their answer in an instance property, and reading it would be a lookup a
 * player cannot make. That makes the search space itself load-bearing: a missing or duplicated
 * pattern means a correct answer can never be presented, and the stage deadlocks.
 */
class AmoriaRopeCandidatesTest {

    @Test
    void stage3IsEveryFiveOfNineRopesExactlyOnce() {
        List<PositionCombination.Candidate> candidates = AmoriaPqData.stage3Candidates();
        assertEquals(126, candidates.size(), "C(9,5)");
        Set<Long> seen = new HashSet<>();
        for (PositionCombination.Candidate candidate : candidates) {
            assertEquals(AmoriaPqData.BODIES_ON_ROPES, candidate.spots().size());
            assertEquals(candidate.spots().size(), new HashSet<>(candidate.spots()).size(),
                    "a candidate repeats a rope");
            for (Point spot : candidate.spots()) {
                assertTrue(AmoriaPqData.ROPE_SPOTS.contains(spot));
            }
            assertTrue(seen.add(candidate.pattern()), "duplicate candidate " + candidate.pattern());
        }
    }

    @Test
    void stage2IsEveryWayToSpreadFiveBodiesOverThreeAreas() {
        // generateCombo1 draws a per-area COUNT, so the space is the compositions of 5 into 3
        // parts: C(5+3-1, 3-1) = 21, not the 3^5 assignments.
        List<PositionCombination.Candidate> candidates = AmoriaPqData.stage2Candidates();
        assertEquals(21, candidates.size());
        Set<Long> seen = new HashSet<>();
        for (PositionCombination.Candidate candidate : candidates) {
            assertEquals(AmoriaPqData.BODIES_ON_ROPES, candidate.spots().size(),
                    "every candidate places exactly five bodies");
            assertEquals(PqActions.bodiesOf(candidate.pattern()), candidate.spots().size(),
                    "the pattern and the spot list must describe the same placement");
            for (Point spot : candidate.spots()) {
                assertTrue(AmoriaPqData.STAGE_2_SPOTS.contains(spot));
            }
            assertTrue(seen.add(candidate.pattern()), "duplicate candidate " + candidate.pattern());
        }
    }

    @Test
    void theRopeAreasAreTheMapsOwnRectangles() {
        // Amos counts with map.getArea(...), so a drifted transcript would put every bot just
        // outside the rectangle it is counted in and no candidate could ever match.
        for (int stage : new int[]{2, 3}) {
            List<Point> spots = AmoriaPqData.spotsFor(stage);
            List<java.awt.Rectangle> areas = AmoriaPqData.areasFor(stage);
            assertEquals(spots.size(), areas.size(), "stage " + stage);
            for (int i = 0; i < spots.size(); i++) {
                assertTrue(areas.get(i).contains(spots.get(i)),
                        "stage " + stage + " spot " + i + " is outside its own rectangle");
            }
        }
    }

    @Test
    void onlyRopeStagesHaveAreas() {
        assertTrue(AmoriaPqData.areasFor(1).isEmpty());
        assertTrue(AmoriaPqData.areasFor(4).isEmpty());
        assertFalse(AmoriaPqData.areasFor(2).isEmpty());
        assertFalse(AmoriaPqData.areasFor(3).isEmpty());
    }

    @Test
    void theStagePropertyNamesAreTheOnesTheScriptsWrite() {
        // Only the PRESENCE of these is ever checked (it marks the intro talk); no bot reads
        // their value. Keeping the names here pins them against the event scripts.
        assertEquals("stage2combo", AmoriaPqData.comboPropertyFor(2));
        assertEquals("stage3combo", AmoriaPqData.comboPropertyFor(3));
        assertNull(AmoriaPqData.comboPropertyFor(1), "stage 1 is the mirror fight");
        assertNull(AmoriaPqData.comboPropertyFor(4));
    }
}
