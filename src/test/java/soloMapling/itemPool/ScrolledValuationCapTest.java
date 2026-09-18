package soloMapling.itemPool;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.SortedMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the scrolled-equip valuation DP against the geometric blow-up that used
 * to pin every top stat band at the 2,147,483,647 int cap (so distinct rolls all
 * listed for the same, absurd price).
 *
 * <p>The per-scroll marginal cost is {@code price / successRate}: a failed scroll
 * consumes a slot but does not undo earlier successes, so cost accumulates
 * additively. The old recurrence {@code (cost + price) / rate} re-inflated the
 * whole accumulated cost each step (compounding) and hit the cap by ~+19.
 */
class ScrolledValuationCapTest {

    // Glove ATK scrolls: 10% +3, 60% +2, 30% +3, 70% +2 (mesos).
    private static ArrayList<UpgradeSimulator.Scroll> gloveScrolls() {
        ArrayList<UpgradeSimulator.Scroll> s = new ArrayList<>();
        s.add(new UpgradeSimulator.Scroll(1_200_000, 10, 3));
        s.add(new UpgradeSimulator.Scroll(3_000_000, 60, 2));
        s.add(new UpgradeSimulator.Scroll(1_600_000, 30, 3));
        s.add(new UpgradeSimulator.Scroll(2_000_000, 70, 2));
        return s;
    }

    private static SortedMap<Integer, Long> valuation(long base, int slots) {
        ArrayList<UpgradeSimulator.Scroll> scrolls = gloveScrolls();
        int maxIncrement = UpgradeSimulator.getHighestStatBonus(scrolls);
        int maxBonus = maxIncrement * slots;
        double[][] cost = UpgradeSimulator.minimumCostToCreateEquipCalculator(
                scrolls, base, slots, maxBonus, maxIncrement);
        return UpgradeSimulator.cleanUpMinCostData(cost, slots, maxBonus).getStatBonusList();
    }

    @Test
    void fullStatBandsStrictlyIncreaseInsteadOfCollapsingAtTheCap() {
        SortedMap<Integer, Long> prices = valuation(1_000, 7);
        int cap = 2_147_483_647;

        long prev = Long.MIN_VALUE;
        for (Long price : prices.values()) {
            assertTrue(price < cap, "no band may sit at the int cap, was " + price);
            assertTrue(price >= prev, "bands must not decrease as the stat rolls up");
            prev = price;
        }
        // The top band is the most valuable and must be clearly above the clean one.
        long top = prices.get(prices.lastKey());
        long clean = prices.get(0);
        assertTrue(top > clean * 10, "top band should dominate the clean price");
    }

    @Test
    void marginalCostIsAdditiveNotCompounded() {
        // One 10% scroll of +3: marginal expected cost = price/0.10 = 10x price.
        ArrayList<UpgradeSimulator.Scroll> one = new ArrayList<>();
        one.add(new UpgradeSimulator.Scroll(1_000_000, 10, 3));
        double[][] cost = UpgradeSimulator.minimumCostToCreateEquipCalculator(one, 0, 1, 3, 3);
        assertEquals(10_000_000.0, cost[1][3], 1.0,
                "one 10% scroll must cost price/rate, not (price)/rate re-inflated");
    }

    @Test
    void twoScrollsAccumulateAdditively() {
        // Two identical 60% +2 scrolls: additive target = base + 2*(price/0.6).
        ArrayList<UpgradeSimulator.Scroll> s = new ArrayList<>();
        s.add(new UpgradeSimulator.Scroll(1_200_000, 60, 2));
        double[][] cost = UpgradeSimulator.minimumCostToCreateEquipCalculator(s, 0, 2, 4, 2);
        double expected = 2 * (1_200_000 / 0.6); // 4,000,000
        assertEquals(expected, cost[2][4], 1.0);
    }
}
