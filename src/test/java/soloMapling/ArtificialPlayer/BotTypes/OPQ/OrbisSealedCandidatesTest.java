package soloMapling.ArtificialPlayer.BotTypes.OPQ;

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
 * Pins Orbis stage 4's (the sealed room) candidate space.
 *
 * <p>The script accepts only a total of exactly three bodies on the three platforms, so the
 * accepted shapes are the ten spreads of three bodies over three platforms. Reading the published
 * {@code stage4_0..2} numbers would be a lookup; walking these ten is playing the puzzle.
 */
class OrbisSealedCandidatesTest {

    @Test
    void theRoomIsEverySpreadOfThreeBodiesOverThreePlatforms() {
        List<PositionCombination.Candidate> candidates = OrbisStages.sealedCandidates();
        // The script accepts any layout totalling three bodies, so this is the ten compositions
        // of 3 into 3 parts - including (3,0,0). A permutation-only space would hide those.
        assertEquals(10, candidates.size(), "C(3+3-1, 3-1) = 10");
        Set<Long> seen = new HashSet<>();
        for (PositionCombination.Candidate candidate : candidates) {
            assertEquals(OrbisPqData.SEALED_PLAYERS, candidate.spots().size(),
                    "every layout places all three bodies");
            assertEquals(PqActions.bodiesOf(candidate.pattern()), candidate.spots().size());
            for (Point spot : candidate.spots()) {
                assertTrue(java.util.Arrays.asList(OrbisPqData.SEALED_AREAS).contains(spot));
            }
            assertTrue(seen.add(candidate.pattern()), "duplicate layout");
        }
    }

    @Test
    void theSealedAreasMatchTheMapsOwnRectangles() {
        // The script uses getNumPlayersInArea(0..2), i.e. the map's area list, so the transcribed
        // rectangles must contain the spots the bots stand on - otherwise the count reads zero.
        assertEquals(OrbisPqData.SEALED_PLAYERS, OrbisPqData.SEALED_AREA_RECTS.size());
        for (int i = 0; i < OrbisPqData.SEALED_PLAYERS; i++) {
            assertTrue(OrbisPqData.SEALED_AREA_RECTS.get(i)
                            .contains(OrbisPqData.SEALED_AREAS[i]),
                    "platform " + i + " centre is outside its own rectangle");
        }
    }
}
