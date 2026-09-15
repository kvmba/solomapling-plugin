package soloMapling.ArtificialPlayer.BotTypes.Kerning;

import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks Kerning PQ's three area puzzles - the tables the quest compares against, and the
 * reading of the row it picked.
 *
 * <p>These are the stages a bot wins by reading rather than guessing, so the transcription
 * has to be exact: a table with a wrong row, or a property parsed one off, sends the whole
 * party to a placement the quest rejects. The rows are also load-bearing between bots - the
 * quest counts every player in the instance, so a bot that claims a spot the player also
 * walks to makes the check fail for everyone.
 */
class KerningCombinationDataTest {

    @Test
    void eachStageOffersTheTablesTheQuestDeclares() {
        assertEquals(4, KerningPqData.combinationsFor(2).length);
        assertEquals(10, KerningPqData.combinationsFor(3).length);
        assertEquals(20, KerningPqData.combinationsFor(4).length);
    }

    @Test
    void everyRowHasExactlyOneEntryPerSpot() {
        for (int stage = 2; stage <= 4; stage++) {
            int[][] table = KerningPqData.combinationsFor(stage);
            int spots = KerningPqData.spotsFor(stage).size();
            for (int i = 0; i < table.length; i++) {
                assertEquals(spots, table[i].length,
                        "stage " + stage + " row " + i + " has the wrong width");
            }
        }
    }

    @Test
    void everyRowWantsExactlyThreeBodies() {
        // The quest's dialogue is explicit - three of the spots are connected - and the
        // stage-4 table is built from every 3-of-6 combination, so this holds for all rows.
        for (int stage = 2; stage <= 4; stage++) {
            for (int[] row : KerningPqData.combinationsFor(stage)) {
                int bodies = 0;
                for (int v : row) {
                    bodies += v;
                }
                assertEquals(3, bodies, "stage " + stage + " has a row wanting " + bodies);
            }
        }
    }

    @Test
    void stageTwoRowsAreTheOnesTheQuestLists() {
        int[][] expected = {
                {0, 1, 1, 1}, {1, 0, 1, 1}, {1, 1, 0, 1}, {1, 1, 1, 0},
        };
        for (int i = 0; i < expected.length; i++) {
            org.junit.jupiter.api.Assertions.assertArrayEquals(expected[i],
                    KerningPqData.combinationsFor(2)[i], "row " + i);
        }
    }

    @Test
    void stageFourCoversEveryThreeOfSix() {
        // The quest's table is all 20 ways to choose 3 from 6, so no arrangement is missing.
        java.util.Set<String> seen = new HashSet<>();
        for (int[] row : KerningPqData.combinationsFor(4)) {
            StringBuilder key = new StringBuilder();
            for (int v : row) {
                key.append(v);
            }
            assertTrue(seen.add(key.toString()), "duplicate row " + key);
        }
        assertEquals(20, seen.size());
    }

    @Test
    void aPublishedIndexSelectsThatRow() {
        assertArrayEqualsRow(new int[]{1, 0, 1, 1}, KerningPqData.chosenCombination(2, "1"));
        assertArrayEqualsRow(new int[]{1, 1, 1, 0}, KerningPqData.chosenCombination(2, "3"));
        // The quest stores the index as a string with possible whitespace.
        assertArrayEqualsRow(new int[]{1, 1, 1, 0}, KerningPqData.chosenCombination(2, " 3 "));
    }

    @Test
    void anUnpublishedOrBadAnswerIsNotGuessed() {
        // Null means "nobody has talked to Cloto yet", which is exactly when the quest picks.
        // Returning a row here would send the bot somewhere the quest never chose.
        assertNull(KerningPqData.chosenCombination(2, null));
        assertNull(KerningPqData.chosenCombination(2, ""));
        assertNull(KerningPqData.chosenCombination(2, "not a number"));
        assertNull(KerningPqData.chosenCombination(2, "-1"));
        assertNull(KerningPqData.chosenCombination(2, "99999"));
    }

    @Test
    void wantedSpotsListsOnePerBodyInRowOrder() {
        List<Point> wanted = KerningPqData.wantedSpots(
                new int[]{0, 1, 1, 0}, KerningPqData.STAGE_2_SPOTS);
        assertEquals(2, wanted.size());
        assertEquals(KerningPqData.STAGE_2_SPOTS.get(1), wanted.get(0));
        assertEquals(KerningPqData.STAGE_2_SPOTS.get(2), wanted.get(1));
    }

    @Test
    void theTwoBotsOnAStageTakeDifferentSpots() {
        // Counting from the tail keeps the head free for the player, and two bots on the same
        // row must not collide - a doubled spot is a failed check for the whole party.
        int[] row = {1, 1, 1, 0};
        Point first = KerningPqData.mySpotFor(row, KerningPqData.STAGE_2_SPOTS, 0);
        Point second = KerningPqData.mySpotFor(row, KerningPqData.STAGE_2_SPOTS, 1);
        assertNotNull(first);
        assertNotNull(second);
        assertTrue(!first.equals(second), "two bots were sent to the same spot");
    }

    @Test
    void aBotWithNoSpotIsSentNowhere() {
        // A row asking for three bodies cannot keep four bots busy; the extra one must stay
        // off the spots rather than doubling up on one.
        int[] row = {0, 1, 1, 1};
        assertNull(KerningPqData.mySpotFor(row, KerningPqData.STAGE_2_SPOTS, 3));
        assertNull(KerningPqData.mySpotFor(null, KerningPqData.STAGE_2_SPOTS, 0));
    }

    @Test
    void answerPropertiesAreTheOnesTheQuestWrites() {
        assertEquals("stg2Property", KerningPqData.answerPropertyFor(2));
        assertEquals("stg3Property", KerningPqData.answerPropertyFor(3));
        assertEquals("stg4Property", KerningPqData.answerPropertyFor(4));
        assertNull(KerningPqData.answerPropertyFor(1));
    }

    @Test
    void theStagesAndTheirSpotsLineUp() {
        assertEquals(103000800, KerningPqData.STAGE_1);
        assertEquals(103000804, KerningPqData.STAGE_5);
        assertEquals(4, KerningPqData.STAGE_2_SPOTS.size());
        assertEquals(5, KerningPqData.STAGE_3_SPOTS.size());
        assertEquals(6, KerningPqData.STAGE_4_SPOTS.size());
    }

    private static void assertArrayEqualsRow(int[] expected, int[] actual) {
        assertNotNull(actual, "no row was selected");
        org.junit.jupiter.api.Assertions.assertArrayEquals(expected, actual);
    }
}
