package soloMapling.itemPool;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The gacha spray is filler with one jackpot spliced in. Where that jackpot lands is what makes
 * the pop readable: it has to show up in the back half, or the spray peaks on drop #3 and the
 * rest of the items are anticlimax.
 */
class GachaFillerSystemTest {

    private static final int PRIZE_ID = 1082223;
    private static final int FILLER_SIZE = 10;
    private static final int SPRAY_SIZE = FILLER_SIZE + 1;
    private static final int EARLIEST_PRIZE_INDEX = FILLER_SIZE / 2;

    /** The prize must be present exactly once, whatever else the filler rolled. */
    @RepeatedTest(50)
    void prizeAppearsExactlyOnce() {
        List<Integer> spray = GachaFillerSystem.createGachaListWithPrize(PRIZE_ID);

        assertEquals(SPRAY_SIZE, spray.size());
        assertEquals(1, spray.stream().filter(id -> id == PRIZE_ID).count());
    }

    /** Back half only: it used to be reachable at index 2 despite the "halfway" name. */
    @RepeatedTest(200)
    void prizeNeverLandsInTheFrontHalf() {
        List<Integer> spray = GachaFillerSystem.createGachaListWithPrize(PRIZE_ID);

        int prizeIndex = spray.indexOf(PRIZE_ID);
        assertTrue(prizeIndex >= EARLIEST_PRIZE_INDEX,
                "prize landed at index " + prizeIndex + " of " + SPRAY_SIZE
                        + " (drop #" + (prizeIndex + 1) + "), expected the back half (>= index "
                        + EARLIEST_PRIZE_INDEX + ")");
    }

    /** Every legal slot is actually reachable - the range is not silently truncated. */
    @Test
    void everyBackHalfSlotIsReachable() {
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < 3000; i++) {
            seen.add(GachaFillerSystem.createGachaListWithPrize(PRIZE_ID).indexOf(PRIZE_ID));
        }

        assertEquals(Set.of(EARLIEST_PRIZE_INDEX, 6, 7, 8, 9, 10), seen);
    }

    /** Filler slots stay free of the prize id so the two can't be confused. */
    @RepeatedTest(50)
    void fillerNeverCollidesWithPrizeId() {
        List<Integer> filler = GachaFillerSystem.createGachaFillerList();

        assertEquals(FILLER_SIZE, filler.size());
        assertTrue(filler.stream().noneMatch(id -> id == PRIZE_ID));
    }
}
