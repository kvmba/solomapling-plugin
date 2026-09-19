package soloMapling.ArtificialPlayer.BotTypes.Amoria;

import org.junit.jupiter.api.Test;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Locks the Amoria rope-puzzle data the bot reads.
 *
 * <p>Stage 2 and stage 3 are both area puzzles, on different maps: stage 2 indexes three areas
 * and stage 3 the nine ropes on {@code 670010400}. The mistakes this guards against are quiet:
 * reading the wrong {@code stageNcombo} leaves the bot standing on a spot the quest never asked
 * for, and indexing a combination against the wrong map's areas sends it to the wrong place.
 */
class AmoriaPqDataTest {

    @Test
    void eachRopeStageReadsItsOwnCombination() {
        assertEquals("stage2combo", AmoriaPqData.comboPropertyFor(2));
        assertEquals("stage3combo", AmoriaPqData.comboPropertyFor(3));
        assertNull(AmoriaPqData.comboPropertyFor(1), "stage 1 is the mirror fight, not a rope puzzle");
        assertNull(AmoriaPqData.comboPropertyFor(4));
    }

    @Test
    void eachRopeStageIndexesItsOwnAreaList() {
        assertEquals(3, AmoriaPqData.spotsFor(2).size(), "stage 2 is the three-area sub-room");
        assertEquals(9, AmoriaPqData.spotsFor(3).size(), "stage 3 is the nine-rope map");
        assertEquals(9, AmoriaPqData.ROPE_SPOTS.size());
        assertEquals(3, AmoriaPqData.STAGE_2_SPOTS.size());
        assertEquals(java.util.List.of(), AmoriaPqData.spotsFor(1));
    }

    @Test
    void stageIsDerivedFromTheMapTheQuestUses() {
        // NPC script: floor((mapId - 670010200) / 100) + 1. The three stage-2 sub-rooms
        // (670010300/301/302) all land on stage 2; the nine-rope map 670010400 is stage 3.
        assertEquals(1, AmoriaPqData.stageOf(670010200));
        assertEquals(2, AmoriaPqData.stageOf(670010300));
        assertEquals(2, AmoriaPqData.stageOf(670010301));
        assertEquals(2, AmoriaPqData.stageOf(670010302));
        assertEquals(3, AmoriaPqData.stageOf(670010400));
        assertEquals(4, AmoriaPqData.stageOf(670010500));
    }

    @Test
    void theCombinationParsesIntoCounts() {
        int[] counts = AmoriaPqData.chosenCounts("1,0,2,0,0,1,0,1,0");
        assertEquals(java.util.List.of(1, 0, 2, 0, 0, 1, 0, 1, 0),
                java.util.Arrays.stream(counts).boxed().toList());
    }

    @Test
    void aBlankOrNullCombinationMeansNobodyHasTalkedToAmosYet() {
        assertNull(AmoriaPqData.chosenCounts(null));
        assertNull(AmoriaPqData.chosenCounts("  "));
    }

    @Test
    void bodiesFillFromTheFarEndSoThePlayerKeepsTheNearSlots() {
        // Three ropes want one body each; the three bodies take the last three slots.
        int[] counts = {1, 1, 1, 0, 0, 0, 0, 0, 0};
        Point first = AmoriaPqData.mySpot(counts, AmoriaPqData.ROPE_SPOTS, 0);
        Point second = AmoriaPqData.mySpot(counts, AmoriaPqData.ROPE_SPOTS, 1);
        Point third = AmoriaPqData.mySpot(counts, AmoriaPqData.ROPE_SPOTS, 2);
        assertEquals(AmoriaPqData.ROPE_SPOTS.get(2), first);
        assertEquals(AmoriaPqData.ROPE_SPOTS.get(1), second);
        assertEquals(AmoriaPqData.ROPE_SPOTS.get(0), third);
        assertNull(AmoriaPqData.mySpot(counts, AmoriaPqData.ROPE_SPOTS, 3), "only three bodies are wanted");
    }

    @Test
    void aStageTwoCombinationFitsTheThreeAreaList() {
        // generateCombo1 outputs nine values but only ever fills slots 0..2 (positions[rndPicked
        // % 3]++); the rest are zero. A body's rank still maps onto the stage's three areas.
        int[] counts = {2, 0, 1, 0, 0, 0, 0, 0, 0};
        assertEquals(AmoriaPqData.STAGE_2_SPOTS.get(2), AmoriaPqData.mySpot(counts, AmoriaPqData.STAGE_2_SPOTS, 0));
        assertEquals(AmoriaPqData.STAGE_2_SPOTS.get(0), AmoriaPqData.mySpot(counts, AmoriaPqData.STAGE_2_SPOTS, 1));
        assertEquals(AmoriaPqData.STAGE_2_SPOTS.get(0), AmoriaPqData.mySpot(counts, AmoriaPqData.STAGE_2_SPOTS, 2));
        assertNull(AmoriaPqData.mySpot(counts, AmoriaPqData.STAGE_2_SPOTS, 3), "only three bodies are wanted");
    }
}
