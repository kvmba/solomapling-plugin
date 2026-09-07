package soloMapling.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotChannelRouterTest {

    /**
     * Place n bots one at a time. bots[] drives the proportion, population[] the capacity gate
     * and it starts at bots+real so a channel can be pre-loaded with real players.
     * Returns 1-based bot counts; index 0 is how many were dropped.
     */
    private static int[] place(int channels, int cap, int bots, int[] real) {
        double[] w = BotChannelRouter.weights(channels);
        int[] botCount = new int[channels];
        int[] population = new int[channels];
        for (int i = 0; i < channels; i++) {
            population[i] = real[i];
        }
        int placed = 0;
        for (int k = 0; k < bots; k++) {
            int p = BotChannelRouter.pickChannel(botCount, w, population, cap);
            if (p < 0) break;   // all full -> dropped
            botCount[p]++;
            population[p]++;
            placed++;
        }
        int[] out = new int[channels + 1];
        for (int i = 0; i < channels; i++) out[i + 1] = botCount[i];
        out[0] = bots - placed;
        return out;
    }

    private static int[] none(int n) {
        return new int[n];
    }

    @Test
    void twoChannelsGiveSixtyForty() {
        int[] c = place(2, 1000, 10, none(2));
        assertEquals(6, c[1], "ch1=" + c[1] + " ch2=" + c[2]);
        assertEquals(4, c[2], "ch1=" + c[1] + " ch2=" + c[2]);

        int[] big = place(2, 100000, 2000, none(2));
        assertEquals(0.60, big[1] / 2000.0, 0.01, "ch1=" + big[1] + " ch2=" + big[2]);
        assertEquals(0.40, big[2] / 2000.0, 0.01, "ch1=" + big[1] + " ch2=" + big[2]);
    }

    @Test
    void threeChannelsGiveFiveThreeTwo() {
        int[] c = place(3, 1000, 10, none(3));
        assertEquals(5, c[1], "split=" + c[1] + ":" + c[2] + ":" + c[3]);
        assertEquals(3, c[2], "split=" + c[1] + ":" + c[2] + ":" + c[3]);
        assertEquals(2, c[3], "split=" + c[1] + ":" + c[2] + ":" + c[3]);

        int[] big = place(3, 100000, 2000, none(3));
        assertEquals(0.50, big[1] / 2000.0, 0.01, "split=" + big[1] + ":" + big[2] + ":" + big[3]);
        assertEquals(0.30, big[2] / 2000.0, 0.01, "split=" + big[1] + ":" + big[2] + ":" + big[3]);
        assertEquals(0.20, big[3] / 2000.0, 0.01, "split=" + big[1] + ":" + big[2] + ":" + big[3]);
    }

    @Test
    void realPlayersDoNotInvertTheTaper() {
        // The bug this guards: counting real players in the PROPORTION made a busy ch1 receive
        // the fewest bots. ch1 holds 80 real players, ch3 only 5 - the bot taper must still run
        // 5:3:2, with real players only closing a channel once it hits the cap.
        int[] c = place(3, 1000, 200, new int[]{80, 20, 5});
        assertEquals(0.50, c[1] / 200.0, 0.02, "split=" + c[1] + ":" + c[2] + ":" + c[3]);
        assertEquals(0.30, c[2] / 200.0, 0.02, "split=" + c[1] + ":" + c[2] + ":" + c[3]);
        assertEquals(0.20, c[3] / 200.0, 0.02, "split=" + c[1] + ":" + c[2] + ":" + c[3]);
        assertEquals(0, c[0], "nobody should be dropped with this much room");
    }

    @Test
    void capacityGateCountsRealPlayers() {
        // ch1 already holds 95 real players and the cap is 100: only 5 more may land there,
        // then it must be skipped even though it is still owed bots by the taper.
        int[] c = place(3, 100, 300, new int[]{95, 0, 0});
        assertEquals(5, c[1], "ch1 should take 5 then close, got " + c[1]);
        assertTrue(c[2] > 0 && c[3] > 0, "ch2/ch3 should absorb the rest: " + c[2] + "/" + c[3]);
    }

    @Test
    void botsSpreadFromTheFirstFew() {
        int[] c = place(3, 1000, 6, none(3));
        assertTrue(c[2] > 0, "no bot reached ch2: " + c[1] + ":" + c[2] + ":" + c[3]);
        assertTrue(c[3] > 0, "no bot reached ch3: " + c[1] + ":" + c[2] + ":" + c[3]);
    }

    @Test
    void emptyServerFillsLowestChannelFirst() {
        int first = BotChannelRouter.pickChannel(new int[3], BotChannelRouter.weights(3),
                new int[3], 1000);
        assertEquals(0, first, "first bot should land on ch1, got ch" + (first + 1));

        int[] running = new int[3];
        double[] w = BotChannelRouter.weights(3);
        int[] pop = new int[3];
        for (int k = 0; k < 30; k++) {
            int p = BotChannelRouter.pickChannel(running, w, pop, 1000);
            running[p]++;
            pop[p]++;
            assertTrue(running[0] >= running[1] && running[1] >= running[2],
                    "shape inverted at bot " + (k + 1) + ": "
                            + running[0] + "/" + running[1] + "/" + running[2]);
        }
    }

    @Test
    void stopsAtCapacityAndDropsTheRest() {
        // 3 channels x cap 10 = 30 slots. Asking 40 must place 30, drop 10.
        int[] c = place(3, 10, 40, none(3));
        assertEquals(30, c[1] + c[2] + c[3]);
        assertEquals(10, c[0], "overflow must be dropped, not squeezed in");
    }

    @Test
    void everyChannelFillsToCap() {
        int[] c = place(3, 10, 30, none(3));
        assertEquals(10, c[1]);
        assertEquals(10, c[2]);
        assertEquals(10, c[3]);
    }

    @Test
    void weightsSumToOne() {
        for (int n = 1; n <= 8; n++) {
            double sum = 0;
            for (double w : BotChannelRouter.weights(n)) sum += w;
            assertEquals(1.0, sum, 1e-9, "n=" + n);
        }
    }

    @Test
    void degenerateInputsPickNothing() {
        double[] w2 = BotChannelRouter.weights(2);
        assertEquals(-1, BotChannelRouter.pickChannel(new int[2], w2, new int[2], 0));
        assertEquals(-1, BotChannelRouter.pickChannel(null, w2, new int[2], 10));
        assertEquals(-1, BotChannelRouter.pickChannel(new int[2], null, new int[2], 10));
        assertEquals(-1, BotChannelRouter.pickChannel(new int[2], w2, null, 10));
        assertEquals(-1, BotChannelRouter.pickChannel(new int[3], w2, new int[3], 10));
        assertEquals(0, BotChannelRouter.weights(0).length);
        assertEquals(0, BotChannelRouter.weights(-5).length);
    }
}
