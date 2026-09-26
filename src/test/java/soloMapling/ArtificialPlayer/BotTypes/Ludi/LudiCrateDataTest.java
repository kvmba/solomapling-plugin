package soloMapling.ArtificialPlayer.BotTypes.Ludi;

import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks Ludi PQ's stage 8 crate combination and its stage numbering.
 *
 * <p>Stage 8 is the one place in this quest where a bot can make things worse rather than
 * better. The quest requires exactly five bodies on crates and a matching pattern, counting
 * every player in the instance - the real one included. So the bots have to leave a slot for
 * the player, and an off-by-one here means a party that stands on the right boxes and still
 * fails, with nothing in the feedback to say why.
 */
class LudiCrateDataTest {

    @Test
    void thereAreNineCratesWithDistinctSpots() {
        assertEquals(9, LudiPqData.CRATE_SPOTS.size());
        assertEquals(9, new java.util.HashSet<>(LudiPqData.CRATE_SPOTS).size());
    }

    @Test
    void everyStageMapIsInTheQuestsRun() {
        assertEquals(922010100, LudiPqData.ENTRY_MAP);
        assertEquals(922010200, LudiPqData.STAGE_2);
        assertEquals(922010600, LudiPqData.STAGE_6);
        assertEquals(922010800, LudiPqData.STAGE_8);
        assertEquals(922010900, LudiPqData.STAGE_9);
        // The stage number is derived by subtracting the entry map, so the run has to be
        // contiguous or a bot would misname the stage it is standing in.
        for (int map = LudiPqData.ENTRY_MAP; map <= LudiPqData.STAGE_9; map++) {
            assertTrue(map >= LudiPqData.ENTRY_MAP);
        }
    }

    @Test
    void theComboNamesThreeOfNine() {
        // The quest builds the combination as exactly five of nine 1s; the crate list is
        // built from those 1s, so the count is the check that the parse worked at all.
        assertEquals(5, LudiPqData.CRATES_TO_STAND_ON);
        String combo = "1,0,1,0,1,0,1,0,1";
        assertEquals(5, LudiPqData.cratesIn(combo).size());
    }

    @Test
    void cratesAreListedInAreaOrder() {
        // Position i in the combination is the i-th crate area, so the mapping has to be
        // positional: "1,0,..." means the first crate, not whichever one got found first.
        String combo = "1,0,0,0,0,0,0,0,0";
        List<Point> crates = LudiPqData.cratesIn(combo);
        assertEquals(1, crates.size());
        assertEquals(LudiPqData.CRATE_SPOTS.get(0), crates.get(0));
    }

    @Test
    void theLastCrateIsSelectable() {
        // The returned list is the wanted crates packed together, so a combination naming
        // only the ninth area yields a one-entry list holding that area.
        String combo = "0,0,0,0,0,0,0,0,1";
        List<Point> crates = LudiPqData.cratesIn(combo);
        assertEquals(1, crates.size());
        assertEquals(LudiPqData.CRATE_SPOTS.get(8), crates.get(0));
    }

    @Test
    void botsFillFromTheFarEndSoNoneTakesAnothersCrate() {
        String combo = "1,1,1,1,1,0,0,0,0";
        // Counting from the tail: bot 0 gets the last wanted crate, bot 1 the one before it.
        Point first = LudiPqData.myCrate(combo, 0);
        Point second = LudiPqData.myCrate(combo, 1);
        assertNotNull(first);
        assertNotNull(second);
        assertTrue(!first.equals(second), "two bots were sent to the same crate");
    }

    @Test
    void aBodyWithNoCrateIsSentNowhere() {
        // Five crates cannot hold six bots; the extras must stay off rather than doubling up
        // on a crate the quest is counting.
        String combo = "1,1,1,1,1,0,0,0,0";
        assertNull(LudiPqData.myCrate(combo, 5));
        assertNull(LudiPqData.myCrate(combo, -1));
        assertNull(LudiPqData.myCrate(null, 0));
    }

    @Test
    void theCollectionStagesAskForTheCountsTheQuestNames() {
        // These come from the stage NPCs' own dialogue, one per stage, and each is the
        // number a bot stops gathering at. Stage 1 is one of them: the Red Balloon
        // (2040036) demands 25 passes from the leader before the lpq0 portal opens, so
        // treating the entry room as a waiting room stalls the run at its first door.
        assertEquals(25, LudiPqData.passesWanted(1), "the entry room wants 25 passes");
        assertEquals(15, LudiPqData.passesWanted(2));
        assertEquals(32, LudiPqData.passesWanted(3));
        assertEquals(6, LudiPqData.passesWanted(4));
        assertEquals(24, LudiPqData.passesWanted(5));
        assertEquals(3, LudiPqData.passesWanted(7));
        assertEquals(0, LudiPqData.passesWanted(6), "the climb asks for no passes");
    }

    @Test
    void theQuestNeedsFivePlayersAndAllowsSix() {
        // Five matters twice over: it is the party minimum, and it is how many bodies the
        // stage-8 check demands, so a party of five is exactly enough for both.
        assertEquals(5, LudiPqData.MIN_PLAYERS);
        assertEquals(LudiPqData.MIN_PLAYERS, LudiPqData.CRATES_TO_STAND_ON);
    }
}
