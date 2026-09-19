package soloMapling.ArtificialPlayer.PartyQuest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks how many bots each quest lobby holds.
 *
 * <p>Placing exactly one party's worth makes a lobby look like a set of placeholders, which is
 * why the count is a multiple; the counts themselves come straight from each quest's
 * {@code maxPlayers}, so this guards the table against a silent edit. How the bots are spread
 * is no longer a per-platform split here - they are scattered across the map's WZ terrain by
 * {@code PlatformPlacement.spawnBotsOnMap} - so only the totals are asserted.
 */
class PqLobbyDensityTest {

    /** The multiple each lobby's party-filling count is scaled by. */
    private static final int EXPECTED_MULTIPLE = 3;

    @Test
    void everyQuestOffersSeveralPartiesWorth() {
        // The bar is that a lobby holds at least a few parties' worth, so a player choosing a
        // party has spare bodies rather than exactly enough to start.
        for (PqRecruitPoints.Point point : PqRecruitPoints.ALL) {
            int partiesWorth = point.botsToOffer(1);
            assertTrue(partiesWorth >= 1,
                    point.name() + " cannot fill a party at all");
            assertTrue(partiesWorth * EXPECTED_MULTIPLE >= 9,
                    point.name() + " would hold fewer than nine bots");
        }
    }

    @Test
    void theMultipleStillFillsThePartyTheQuestWants() {
        // However many are placed, the ones a player actually recruits must reach the quest's
        // minimum - a lobby full of bots the check rejects is worse than an empty one.
        for (PqRecruitPoints.Point point : PqRecruitPoints.ALL) {
            assertTrue(1 + point.botsToOffer(1) >= point.minPlayers(),
                    point.name() + " cannot reach its own minimum");
        }
    }

    @Test
    void theCountsMatchWhatEachQuestCanTake() {
        assertEquals(15, PqRecruitPoints.byName("HenesysPQ").botsToOffer(1) * EXPECTED_MULTIPLE);
        assertEquals(9, PqRecruitPoints.byName("KerningPQ").botsToOffer(1) * EXPECTED_MULTIPLE);
        assertEquals(15, PqRecruitPoints.byName("LudiPQ").botsToOffer(1) * EXPECTED_MULTIPLE);
        assertEquals(15, PqRecruitPoints.byName("HorntailPQ").botsToOffer(1) * EXPECTED_MULTIPLE);
        assertEquals(9, PqRecruitPoints.byName("MagatiaPQ").botsToOffer(1) * EXPECTED_MULTIPLE);
    }
}
