package soloMapling.itemPool;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import com.esotericsoftware.yamlbeans.YamlReader;
import soloMapling.Environment.PluginResources;

import java.io.Reader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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

    /**
     * A round that rolls no prize must not grow the spray. 0 is the meso
     * sentinel in the filler, so splicing it in would hand out an extra meso
     * pile on exactly the rounds that missed the jackpot.
     */
    @RepeatedTest(20)
    void noPrizeLeavesTheSprayAtFillerSize() {
        List<Integer> spray = GachaFillerSystem.createGachaListWithPrize(0);

        assertEquals(FILLER_SIZE, spray.size());
        assertTrue(spray.stream().noneMatch(id -> id == PRIZE_ID));
        // Whatever the filler rolled, nothing extra was inserted for the prize.
        assertEquals(FILLER_SIZE, spray.stream().filter(id -> id != PRIZE_ID).count());
    }

    /** Filler slots stay free of the prize id so the two can't be confused. */
    @RepeatedTest(50)
    void fillerNeverCollidesWithPrizeId() {
        List<Integer> filler = GachaFillerSystem.createGachaFillerList();

        assertEquals(FILLER_SIZE, filler.size());
        assertTrue(filler.stream().noneMatch(id -> id == PRIZE_ID));
    }

    /**
     * The filler and the prize pool overlap in id space - both carry 400xxx
     * entries - so nothing but the actual ids keeps them apart. If a filler roll
     * ever repeats a prize id, one round would spray that id twice and the
     * jackpot would show up as a pair instead of the single item the round
     * rolled.
     */
    @Test
    void fillerSharesNoIdWithThePrizePool() {
        List<Integer> fillerIds = loadFillerIds();
        for (int prizeId : loadPrizePoolIds()) {
            assertTrue(!fillerIds.contains(prizeId),
                    "prize id " + prizeId + " is also reachable as filler; one round could "
                            + "spray it twice");
        }
    }

    /** Every id the filler can roll, read straight from its yaml. */
    private static List<Integer> loadFillerIds() {
        List<Integer> ids = new ArrayList<>();
        try (Reader r = PluginResources.openReader(
                "itemPool/itemConfig/gachaFiller.yaml")) {
            Map<String, Object> root = (Map<String, Object>) new YamlReader(r).read();
            for (Object typeNode : root.values()) {
                Map<String, Object> tiers = (Map<String, Object>) typeNode;
                for (Object tierNode : tiers.values()) {
                    for (Object raw : (List<?>) tierNode) {
                        // YamlBeans hands these back as String; same tolerance the
                        // production loader uses.
                        ids.add(Integer.parseInt(String.valueOf(raw)));
                    }
                }
            }
        } catch (Exception e) {
            throw new AssertionError("could not read the filler pool", e);
        }
        return ids;
    }

    private static List<Integer> loadPrizePoolIds() {
        List<Integer> ids = new ArrayList<>();
        try (Reader r = PluginResources.openReader(
                "itemPool/itemConfig/gachaPrizePool.yaml")) {
            Map<String, Object> root = (Map<String, Object>) new YamlReader(r).read();
            for (String key : List.of("equips", "items")) {
                List<Map<String, Object>> rows =
                        (List<Map<String, Object>>) root.get(key);
                if (rows == null) continue;
                for (Map<String, Object> row : rows) {
                    ids.add(Integer.parseInt(String.valueOf(row.get("id"))));
                }
            }
        } catch (Exception e) {
            throw new AssertionError("could not read the prize pool", e);
        }
        return ids;
    }
}
