package soloMapling.ArtificialPlayer.BotTypes.Ludi;

import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.PartyQuest.PositionCombination;
import soloMapling.ArtificialPlayer.PartyQuest.PqActions;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void theCandidateSpaceIsEveryFiveOfNineExactlyOnce() {
        // The whole "no cheating" story rests on this: 126 patterns, no repeats, no gaps, each
        // exactly five distinct crates. A duplicate would waste a talk; a gap could hide the
        // answer forever.
        assertEquals(126, LudiPqData.COMBO_COUNT);
        assertEquals(LudiPqData.COMBO_COUNT,
                LudiPqData.combinations(LudiPqData.CRATE_SPOTS.size(), LudiPqData.CRATES_TO_STAND_ON));
        Set<Integer> masks = new HashSet<>();
        for (int i = 0; i < LudiPqData.COMBO_COUNT; i++) {
            List<Point> candidate = LudiPqData.candidateAt(i);
            assertEquals(LudiPqData.CRATES_TO_STAND_ON, candidate.size(), "attempt " + i);
            assertEquals(candidate.size(), new HashSet<>(candidate).size(),
                    "attempt " + i + " repeats a crate");
            for (Point crate : candidate) {
                assertTrue(LudiPqData.CRATE_SPOTS.contains(crate),
                        "attempt " + i + " names a spot the map does not have: " + crate);
            }
            int mask = LudiPqData.maskAt(i);
            assertEquals(LudiPqData.CRATES_TO_STAND_ON, Integer.bitCount(mask));
            assertTrue(masks.add(mask), "attempt " + i + " repeats a pattern: " + mask);
        }
        assertEquals(LudiPqData.COMBO_COUNT, masks.size());
    }

    @Test
    void theWalkStartsAtTheFirstCombinationAndWrapsAround() {
        assertEquals(List.of(LudiPqData.CRATE_SPOTS.get(0), LudiPqData.CRATE_SPOTS.get(1),
                        LudiPqData.CRATE_SPOTS.get(2), LudiPqData.CRATE_SPOTS.get(3),
                        LudiPqData.CRATE_SPOTS.get(4)),
                LudiPqData.candidateAt(0));
        // A 127th attempt is the 1st again, not an exception: the caller wraps the index.
        assertEquals(LudiPqData.candidateAt(0), LudiPqData.candidateAt(126));
        assertEquals(LudiPqData.candidateAt(125), LudiPqData.candidateAt(-1));
    }

    @Test
    void botsFillFromTheFarEndSoNoneTakesAnothersCrate() {
        List<Point> candidate = LudiPqData.candidateAt(0);
        Point first = PositionCombination.mine(candidate, 0);
        Point second = PositionCombination.mine(candidate, 1);
        assertNotNull(first);
        assertNotNull(second);
        assertFalse(first.equals(second), "two bots were sent to the same crate");
        assertEquals(candidate.get(candidate.size() - 1), first, "the tail is seated first");
    }

    @Test
    void aBodyWithNoCrateIsSentNowhere() {
        // Five crates cannot hold six bots; the extras must stay off rather than doubling up
        // on a crate the quest is counting.
        List<Point> candidate = LudiPqData.candidateAt(0);
        assertNull(PositionCombination.mine(candidate, 5));
        assertNull(PositionCombination.mine(candidate, -1));
        assertNull(PositionCombination.mine(List.of(), 0));
    }

    @Test
    void theObservedPatternMirrorsTheScriptsObjset() {
        // 2040043.js walks the map's areas in order and stops at the first match, counting every
        // instance player - including one standing in no area at all. The ledger reads the
        // verdict off exactly this, so the rule has to be reproduced, not approximated.
        List<java.awt.Rectangle> areas = new ArrayList<>();
        for (Point crate : LudiPqData.CRATE_SPOTS) {
            areas.add(new java.awt.Rectangle(crate.x - 10, crate.y - 10, 20, 20));
        }
        List<Point> bodies = new ArrayList<>();
        bodies.add(LudiPqData.CRATE_SPOTS.get(2));
        bodies.add(LudiPqData.CRATE_SPOTS.get(5));
        bodies.add(new Point(0, 0)); // off the crates: counted by nobody
        PqActions.Pattern pattern = PqActions.observedPattern(bodies, areas);
        assertEquals(2, pattern.bodies(), "only the two bodies inside an area count");
        assertEquals(PositionCombination.maskToPattern((1 << 2) | (1 << 5)), pattern.pattern());
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

    @Test
    void stage7BoxWorkIsAnchoredToTheRealMapReactors() throws Exception {
        // Stage 7's three passes (4001022) are not loot anywhere on 922010700: the only source
        // is the Rombard (9300010) that a box reactor (2201002) summons when hit, and the
        // room's three pre-placed mobs drop 4001156 instead. If the box id ever drifts from the
        // map's real reactors, the bot's box work silently does nothing and the stage deadlocks
        // on a bot-only party. Pin the id against the map's own WZ.
        java.nio.file.Path mapXml = java.nio.file.Path.of(
                "wz/Map.wz/Map/Map" + (922010700 / 100000000) + "/922010700.img.xml");
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isRegularFile(mapXml),
                "Map.wz not resolvable from this working directory");
        String xml = java.nio.file.Files.readString(mapXml);
        int boxes = xml.split("<string name=\"id\" value=\"" + LudiPqData.BOX_STAGE7 + "\"/>", -1).length - 1;
        assertEquals(3, boxes, "922010700 holds exactly three stage-7 box reactors");
    }
}
