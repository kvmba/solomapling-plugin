package soloMapling.server;

import org.gms.config.GameConfig;
import org.gms.net.server.Server;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.World;

/**
 * Picks the channel a newly created bot lives on.
 *
 * <p>Before this, every bot was pinned to channel 1: the shared headless client was built with
 * {@code world=0, channel=1} and every bot character was loaded through it, so the whole
 * population stacked on one channel no matter how many the server opened.
 *
 * <p>Rules (per product decision):
 * <ul>
 *   <li>Lower channel id = more population, so bots taper off by id (ch1 heaviest).</li>
 *   <li>Load is a channel's TOTAL population - real players and bots together.</li>
 *   <li>A channel never takes more than {@code channel_capacity}.</li>
 *   <li>When every channel is full the bot is dropped, not squeezed in.</li>
 *   <li>A bot never switches channels, so it stays where it was created.</li>
 * </ul>
 *
 * <p>Load is read live from each channel's player storage (O(1)), so nothing has to be tracked
 * here and no counter can drift as bots log off for a rest. Allocation picks the channel with the
 * lowest {@code load / weight} ratio that still has room; ties go to the highest id (the most
 * starved), which is what makes the taper come out as 6:4 / 5:3:2 rather than starving the tail.
 */
public final class BotChannelRouter {

    private BotChannelRouter() {
    }

    /** Bots live in world 0; it always exists. */
    private static final int WORLD = SoloMaplingConstants.GameConstants.WORLD_SCANIA;

    /** Used when the topology can't be read - preserves the old single-channel behaviour. */
    public static final int DEFAULT_CHANNEL = 1;

    /** -1 when every channel is full: the caller must skip the spawn. */
    public static final int NONE = -1;

    /**
     * The channel for the next created bot, or {@link #NONE} when all channels are at the cap.
     * Falls back to {@link #DEFAULT_CHANNEL} when the channel topology is unavailable.
     */
    public static int nextChannel() {
        try {
            World world = Server.getInstance().getWorld(WORLD);
            if (world == null) {
                return DEFAULT_CHANNEL;
            }
            int n = world.getChannelsSize();
            int cap = GameConfig.getServerInt("channel_capacity");
            if (n <= 0 || cap <= 0) {
                return DEFAULT_CHANNEL;
            }
            int[] load = new int[n];
            for (int i = 0; i < n; i++) {
                Channel ch = world.getChannel(i + 1); // channel ids are 1-based
                load[i] = (ch == null) ? 0 : ch.getPlayerStorage().getSize();
            }
            int pick = pickChannel(load, weights(n), cap);
            return pick < 0 ? NONE : pick + 1;
        } catch (RuntimeException e) {
            return DEFAULT_CHANNEL; // never let routing break a spawn
        }
    }

    /**
     * Index of the channel that should take the next bot, or -1 when they are all full.
     * Pure, so the split is testable without a server.
     */
    static int pickChannel(int[] load, double[] w, int cap) {
        if (load == null || w == null || load.length != w.length) {
            return -1;
        }
        int pick = -1;
        double best = Double.MAX_VALUE;
        for (int i = w.length - 1; i >= 0; i--) {
            if (load[i] >= cap) {
                continue; // channel full
            }
            double ratio = load[i] / w[i];
            if (ratio < best) {
                best = ratio;
                pick = i;
            }
        }
        return pick;
    }

    /**
     * Look a character up across the WHOLE world in O(1).
     *
     * <p>The world keeps its own cross-channel player storage, and {@code addBotToServer} puts
     * every bot in it, so nothing has to sweep the channels one by one to find a bot that may
     * live on any of them.
     */
    public static org.gms.client.Character findCharacter(int id) {
        try {
            World world = Server.getInstance().getWorld(WORLD);
            return (world == null) ? null : world.getPlayerStorage().getCharacterById(id);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Name-based counterpart of {@link #findCharacter(int)}. */
    public static org.gms.client.Character findCharacter(String name) {
        try {
            World world = Server.getInstance().getWorld(WORLD);
            return (world == null) ? null : world.getPlayerStorage().getCharacterByName(name);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Share of bots per channel, index = channel count.
     *
     * <p>These are the agreed splits, not a derived curve: an arithmetic taper
     * {@code (N-i+1)} converges to 2:1 on two channels and 3:2:1 on three, a steeper fall-off
     * than wanted (measured 1333:667 and 999:667:334 at 2000 bots). The tail channels need to
     * stay fuller, so 2 and 3 channels are pinned to the requested 6:4 and 5:3:2.
     */
    private static final double[][] SPLIT = {
            {},                     // unused (0 channels)
            {1.00},                 // 1
            {0.60, 0.40},           // 2
            {0.50, 0.30, 0.20},     // 3
    };

    /** Per-channel share for {@code channelCount} channels. Package-private for tests. */
    static double[] weights(int channelCount) {
        if (channelCount <= 0) {
            return new double[0];
        }
        if (channelCount < SPLIT.length) {
            return SPLIT[channelCount].clone();
        }
        // Beyond the agreed cases: harmonic fall-off, normalised.
        double[] w = new double[channelCount];
        double sum = 0;
        for (int i = 0; i < channelCount; i++) {
            w[i] = 1.0 / (i + 1.0);
            sum += w[i];
        }
        for (int i = 0; i < channelCount; i++) {
            w[i] /= sum;
        }
        return w;
    }
}
