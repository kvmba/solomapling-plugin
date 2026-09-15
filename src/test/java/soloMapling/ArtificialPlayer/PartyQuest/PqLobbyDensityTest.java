package soloMapling.ArtificialPlayer.PartyQuest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks how many bots each quest lobby holds, and how they are spread across its platforms.
 *
 * <p>Two mistakes are easy here and both are silent. Placing exactly one party's worth makes a
 * lobby look like a set of placeholders, which is why the count is a multiple; and rounding each
 * platform's share up to at least one bot - which is what {@code Math.max(1, ...)} did, and what
 * this caught - multiplies the count by the number of platforms whenever a map has more platforms
 * than bots. Neither shows up as an error; the room just fills with the wrong number of bots.
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

    @Test
    void spreadingNeverPlacesMoreThanAsked() {
        // The distribution rule, checked across the shapes that actually occur: fewer bots than
        // platforms, more, and an exact division. Overshooting is the failure mode that the
        // platform count turns into a multiplier.
        int[] counts = {15, 9, 5, 1};
        int[] platforms = {3, 5, 8, 20};
        for (int count : counts) {
            for (int n : platforms) {
                assertEquals(count, distribute(count, n),
                        "placing " + count + " bots over " + n + " platforms");
            }
        }
    }

    @Test
    void spreadingLeavesPlatformsEmptyWhenThereAreMoreOfThem() {
        // Three bots and twenty platforms: the whole share is zero, and only the first three
        // platforms get one each. The earlier Math.max(1, ...) made this twenty.
        assertEquals(0, perPlatformShare(3, 20));
        assertEquals(3, distribute(3, 20));
    }

    @Test
    void aMapWithNoPlatformsPlacesNothing() {
        assertEquals(0, distribute(15, 0));
    }

    /** The spawner's own split: whole share each, then one more to the leading platforms. */
    private static int distribute(int count, int platforms) {
        if (platforms <= 0) {
            return 0;
        }
        int share = perPlatformShare(count, platforms);
        int remainder = count % platforms;
        int total = 0;
        for (int i = 0; i < platforms; i++) {
            int here = share + (i < remainder ? 1 : 0);
            if (here > 0) {
                total += here;
            }
        }
        return total;
    }

    private static int perPlatformShare(int count, int platforms) {
        return platforms <= 0 ? 0 : count / platforms;
    }
}
