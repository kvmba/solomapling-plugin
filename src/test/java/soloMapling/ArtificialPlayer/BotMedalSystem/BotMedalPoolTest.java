package soloMapling.ArtificialPlayer.BotMedalSystem;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic checks for the medal pool's admission rules and value scoring — no WZ, no
 * game server. Locks the "XX王" rejection (which must NOT catch boss names like
 * 暗黑龙王杀手) and the inc*-derived value score.
 */
class BotMedalPoolTest {

    /** Builds the 20-slot stats array; only the slots a test cares about are set. */
    private static int[] stats(int str, int dex, int intel, int luk, int mhp, int mmp,
                               int pad, int mad, int acc, int eva, int spd, int jmp) {
        return new int[]{0, 0, 0, 0, 0, 0, 0, str, dex, intel, luk, mhp, mmp, pad, mad, acc, eva, spd, jmp, 0};
    }

    @Test
    void gaudyKingTitlesAreRejected() {
        assertTrue(BotMedalPool.isGaudyKingTitle("狙击王勋章"));
        assertTrue(BotMedalPool.isGaudyKingTitle("海盗王勋章"));
        assertTrue(BotMedalPool.isGaudyKingTitle("连击王勋章"));
        assertTrue(BotMedalPool.isGaudyKingTitle("钓鱼王勋章"));
        assertTrue(BotMedalPool.isGaudyKingTitle("2010冬季王者勋章"));
        assertTrue(BotMedalPool.isGaudyKingTitle("2010冬季女王勋章"));
    }

    @Test
    void bossSlayerNamesKeepTheirKing() {
        // "王" lives inside the boss name (暗黑龙王) and the title ends in 杀手 → not gaudy.
        assertFalse(BotMedalPool.isGaudyKingTitle("暗黑龙王杀手勋章"));
        assertFalse(BotMedalPool.isGaudyKingTitle("品克缤杀手勋章"));
        assertFalse(BotMedalPool.isGaudyKingTitle("战武神勋章"));
    }

    @Test
    void valueScoreWeightsDisplayStats() {
        // Plain +1/+1/+1/+1 all-stat, no HP/atk/move.
        assertEquals(4, BotMedalPool.valueScore(stats(1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0)));
        // 10/10/10/10 = 40, the fanciest low-id medal in the pool.
        assertEquals(40, BotMedalPool.valueScore(stats(10, 10, 10, 10, 0, 0, 0, 0, 0, 0, 0, 0)));
        // HP counts 1 per 20; speed/jump 1 per 3.
        assertEquals(0 + 5 + 0 + 0 + 0, BotMedalPool.valueScore(stats(0, 0, 0, 0, 100, 0, 0, 0, 0, 0, 0, 0)));
        assertEquals(3, BotMedalPool.valueScore(stats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 9, 0)));
        // Attack weighs 2 each.
        assertEquals(4, BotMedalPool.valueScore(stats(0, 0, 0, 0, 0, 0, 1, 1, 0, 0, 0, 0)));
    }

    @Test
    void tierCutPointsAreStable() {
        assertEquals(10, BotMedalPool.VALUE_LOW_MAX);
        assertEquals(20, BotMedalPool.VALUE_MID_MAX);
    }
}
