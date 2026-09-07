package soloMapling.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotChannelRouterTest {

    /** Place n bots one at a time, feeding each placement back as load. Returns 1-based counts. */
    private static int[] place(int channels, int cap, int n) {
        double[] w = BotChannelRouter.weights(channels);
        int[] load = new int[channels];
        int placed = 0;
        for (int k = 0; k < n; k++) {
            int p = BotChannelRouter.pickChannel(load, w, cap);
            if (p < 0) break;   // all full -> dropped
            load[p]++;
            placed++;
        }
        int[] out = new int[channels + 1];
        for (int i = 0; i < channels; i++) out[i + 1] = load[i];
        out[0] = n - placed;    // dropped
        return out;
    }

    @Test
    void twoChannelsGiveSixtyForty() {
        int[] c = place(2, 1000, 10);
        assertEquals(6, c[1], "ch1=" + c[1] + " ch2=" + c[2]);
        assertEquals(4, c[2], "ch1=" + c[1] + " ch2=" + c[2]);

        int[] big = place(2, 100000, 2000);
        assertEquals(0.60, big[1] / 2000.0, 0.01, "ch1=" + big[1] + " ch2=" + big[2]);
        assertEquals(0.40, big[2] / 2000.0, 0.01, "ch1=" + big[1] + " ch2=" + big[2]);
    }

    @Test
    void threeChannelsGiveFiveThreeTwo() {
        int[] c = place(3, 1000, 10);
        assertEquals(5, c[1], "split=" + c[1] + ":" + c[2] + ":" + c[3]);
        assertEquals(3, c[2], "split=" + c[1] + ":" + c[2] + ":" + c[3]);
        assertEquals(2, c[3], "split=" + c[1] + ":" + c[2] + ":" + c[3]);

        int[] big = place(3, 100000, 2000);
        assertEquals(0.50, big[1] / 2000.0, 0.01, "split=" + big[1] + ":" + big[2] + ":" + big[3]);
        assertEquals(0.30, big[2] / 2000.0, 0.01, "split=" + big[1] + ":" + big[2] + ":" + big[3]);
        assertEquals(0.20, big[3] / 2000.0, 0.01, "split=" + big[1] + ":" + big[2] + ":" + big[3]);
    }

    @Test
    void botsSpreadFromTheFirstFew() {
        // The bug being fixed: every bot used to land on channel 1.
        int[] c = place(3, 1000, 6);
        assertTrue(c[2] > 0, "no bot reached ch2: " + c[1] + ":" + c[2] + ":" + c[3]);
        assertTrue(c[3] > 0, "no bot reached ch3: " + c[1] + ":" + c[2] + ":" + c[3]);
    }

    @Test
    void stopsAtCapacityAndDropsTheRest() {
        // 3 channels x cap 10 = 30 slots. Asking 40 must place 30, drop 10.
        int[] c = place(3, 10, 40);
        assertEquals(30, c[1] + c[2] + c[3]);
        assertEquals(10, c[0], "overflow must be dropped, not squeezed in");
    }

    @Test
    void everyChannelFillsToCap() {
        int[] c = place(3, 10, 30);
        assertEquals(10, c[1]);
        assertEquals(10, c[2]);
        assertEquals(10, c[3]);
    }

    @Test
    void existingRealPlayersCountTowardLoad() {
        // ch1 already holds 500 real players: bots must favour the emptier channels.
        int[] load = {500, 0, 0};
        int p = BotChannelRouter.pickChannel(load, BotChannelRouter.weights(3), 1000);
        assertTrue(p > 0, "should avoid the loaded ch1, picked=" + p);
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
        assertEquals(-1, BotChannelRouter.pickChannel(new int[2], BotChannelRouter.weights(2), 0));
        assertEquals(-1, BotChannelRouter.pickChannel(null, BotChannelRouter.weights(2), 10));
        assertEquals(-1, BotChannelRouter.pickChannel(new int[2], null, 10));
        assertEquals(-1, BotChannelRouter.pickChannel(new int[3], BotChannelRouter.weights(2), 10));
        assertEquals(0, BotChannelRouter.weights(0).length);
        assertEquals(0, BotChannelRouter.weights(-5).length);
    }
}
