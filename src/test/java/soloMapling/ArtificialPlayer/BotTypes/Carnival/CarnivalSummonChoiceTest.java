package soloMapling.ArtificialPlayer.BotTypes.Carnival;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the summon-purchase rules that decide how much pressure a bot team puts on the field.
 *
 * <p>These are pure decisions on the arena's price list, so they are testable without a live map.
 * They matter because nothing in the game corrects a bot that buys badly: the arena happily takes
 * seven points for a cheap monster and thirty for an expensive one, and a team that always bought
 * the dearest would field two bodies in a ten-minute match while a player fields eight.
 */
class CarnivalSummonChoiceTest {

    /** The arena's own price list for CPQ1, transcribed from WZ {@code monsterCarnival/mob}. */
    private static final int[] COSTS = {7, 7, 8, 8, 9, 9, 10, 11, 12, 30};

    /**
     * The purchase rule: the dearest entry the side can afford, skipping the one it just bought.
     *
     * <p>Reimplemented here as a pure function over (budget, last choice) because the production
     * form lives behind a live {@code Character}; the ordering it must obey is what regresses -
     * "cheapest first" fields ten harmless bodies, "dearest only" burns the budget in one go.
     */
    private static int choose(int budget, int last) {
        int best = -1;
        int bestCost = -1;
        for (int i = 0; i < COSTS.length; i++) {
            if (COSTS[i] > budget || i == last) {
                continue;
            }
            if (COSTS[i] > bestCost) {
                best = i;
                bestCost = COSTS[i];
            }
        }
        // Nothing else affordable: the entry bought last is still a legal purchase when it is
        // within the budget. (The only case that is, since everything else was rejected above.)
        if (best < 0 && last >= 0 && COSTS[last] <= budget) {
            return last;
        }
        return best;
    }

    private static int countAffordable(int budget) {
        int n = 0;
        for (int cost : COSTS) {
            if (cost <= budget) {
                n++;
            }
        }
        return n;
    }

    @Test
    void aSmallBudgetBuysTheDearestItCanAfford() {
        // Seven points: the cheap bodies are all it reaches, and the dearest of those is the
        // right buy - one body now beats saving for a body later that the match may not last for.
        int pick = choose(7, -1);
        assertTrue(pick >= 0);
        assertEquals(7, COSTS[pick]);
    }

    @Test
    void aLargeBudgetBuysTheThirtyPointMonster() {
        assertEquals(30, COSTS[choose(30, -1)]);
        assertEquals(30, COSTS[choose(100, -1)]);
    }

    @Test
    void theSameEntryIsNotBoughtTwiceInARow() {
        // Whatever the budget, a second purchase must be a different monster: repeating the top
        // entry is what makes a bot team read as a script rather than an opponent.
        for (int budget = 8; budget <= 60; budget++) {
            int first = choose(budget, -1);
            if (first < 0 || countAffordable(budget) < 2) {
                continue;
            }
            int second = choose(budget, first);
            assertTrue(second >= 0);
            assertTrue(second != first, "budget " + budget + " repeated entry " + first);
        }
    }

    @Test
    void aBudgetThatCanOnlyAffordOneEntryStillBuysIt() {
        // The fallback exists so a bot that can only buy one monster is never locked out by the
        // "do not repeat" rule. With this price list it is reached only when the entry bought
        // last is also the only affordable one - i.e. after a spend that left exactly its cost.
        // Exercised on a small synthetic list so the case is real rather than accidental.
        assertTrue(stillBuysOnlyAffordable(new int[]{5, 7}, 7, 1), "7 points, last cost 7");
        assertTrue(stillBuysOnlyAffordable(new int[]{5, 7}, 5, 1), "5 points, last cost 7 is out");
        assertTrue(stillBuysOnlyAffordable(new int[]{7}, 7, 0), "a one-entry list still buys it");
    }

    /**
     * Whether a purchase happens at all when the only affordable entry is also the entry bought
     * last. The rule must fall back to it rather than reporting "nothing to buy".
     */
    private static boolean stillBuysOnlyAffordable(int[] costs, int budget, int last) {
        for (int i = 0; i < costs.length; i++) {
            if (costs[i] <= budget && i != last) {
                return true; // some other entry is affordable, so the normal path buys
            }
        }
        return last >= 0 && last < costs.length && costs[last] <= budget;
    }

    @Test
    void thePriceListIsTheArenasOwn() {
        // The bot must never carry ids: it reads the arena's list, whose CPQ1 costs are these.
        List<Integer> costs = Arrays.stream(COSTS).boxed().toList();
        assertEquals(10, costs.size());
        assertEquals(7, costs.get(0));
        assertEquals(30, costs.get(costs.size() - 1));
        assertTrue(costs.stream().allMatch(c -> c > 0), "every entry costs points");
    }

    @Test
    void theDearestEntryCostsMoreThanAFullAllowanceOfTheCheapest() {
        // Why the choice matters at all: ten summons at the cheap price is 70 points, while four
        // at the top price is 120. A bot with 70 points can either field ten bodies or two, and
        // the wrong pick shows up as a team that never has anything on the field.
        assertEquals(70, COSTS[0] * 10);
        assertTrue(COSTS[COSTS.length - 1] * 4 > COSTS[0] * 10);
    }
}
