package soloMapling.server;

import org.gms.client.Character;
import org.gms.config.GameConfig;
import org.gms.net.server.Server;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.World;

import java.util.Collection;

/**
 * Picks the channel a newly created bot lives on.
 *
 * <p>Before this, every bot was pinned to channel 1: the shared headless client was built with
 * {@code world=0, channel=1} and every bot character was loaded through it, so the whole
 * population stacked on one channel no matter how many the server opened.
 *
 * <p>Rules (per product decision):
 * <ul>
 *   <li>Lower channel id = more bots, so bots taper off by id (ch1 heaviest): 6:4 on two
 *       channels, 5:3:2 on three.</li>
 *   <li>The taper is a rule about BOTS. Real players must not skew it, or a busy ch1 would end
 *       up with the fewest bots - the exact inversion of the rule above.</li>
 *   <li>Capacity is a rule about HEADCOUNT and does include real players: a channel at
 *       {@code channel_capacity} takes no more.</li>
 *   <li>Bot counts are read live, so nothing has to be tracked here and no counter can drift as
 *       bots log off for a rest.</li>
 *   <li>When every channel is at the cap the bot is dropped, not squeezed in.</li>
 *   <li>A bot never switches channels, so it stays where it was created.</li>
 * </ul>
 *
 * <p>Load is read live from each channel's player storage (O(1)), so nothing has to be tracked
 * here and no counter can drift as bots log off for a rest. Allocation picks the channel with the
 * lowest {@code load / weight} ratio that still has room, which lands on 6:4 (two channels) and
 * 5:3:2 (three) at steady state. Ties go to the lowest id, so the "lower id = fuller" shape holds
 * while the population is still coming up and not only once it settles.
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
            int[] bots = new int[n];
            int[] population = new int[n];
            for (int i = 0; i < n; i++) {
                Channel ch = world.getChannel(i + 1); // channel ids are 1-based
                if (ch == null) {
                    continue;
                }
                Collection<Character> chars = ch.getPlayerStorage().getAllCharacters();
                population[i] = chars.size();
                for (Character c : chars) {
                    if (isArtificial(c)) {
                        bots[i]++;
                    }
                }
            }
            int pick = pickChannel(bots, weights(n), population, cap);
            return pick < 0 ? NONE : pick + 1;
        } catch (RuntimeException e) {
            return DEFAULT_CHANNEL; // never let routing break a spawn
        }
    }

    /**
     * Whether a character is one of ours. Goes through the host registry rather than a plugin
     * class so this package stays independent of the bot packages.
     *
     * <p>The plugin registers its classifier in {@code onLoad}, before any bot exists, so this
     * is reliable by the time routing runs. If it were ever called before registration it would
     * report nobody as a bot, and the taper would flatten onto channel 1 — degraded, but no
     * worse than the old fixed-channel behaviour.
     */
    private static boolean isArtificial(Character chr) {
        return chr != null && org.gms.extension.api.ArtificialCharacters.isArtificial(chr.getId());
    }

    /**
     * Index of the channel that should take the next bot, or -1 when they are all full.
     * Pure, so the split is testable without a server.
     *
     * <p>The two inputs answer different questions and must not be conflated:
     * <ul>
     *   <li>{@code bots} drives the PROPORTION. Only bots are counted, because the taper
     *       (6:4, 5:3:2) is a rule about where bots go. Feeding real players into it would
     *       invert the shape: a busy ch1 would then receive the FEWEST bots.</li>
     *   <li>{@code population} drives the CAPACITY GATE. It includes real players, because
     *       what must not be exceeded is the channel's total headcount.</li>
     * </ul>
     *
     * <p>Scanned low id -&gt; high with a strict {@code <}, so on an equal ratio the LOWEST id
     * wins. That keeps "lower id = more bots" true at every point in time, not just at
     * steady state: with the opposite tie-break the empty server fills tail-first (3,2,1,...),
     * which inverts the intended shape while the population is still coming up. The steady
     * split is the same either way — 6:4 / 5:3:2 — only the fill order differs.
     */
    static int pickChannel(int[] bots, double[] w, int[] population, int cap) {
        if (bots == null || w == null || population == null
                || bots.length != w.length || population.length != w.length) {
            return -1;
        }
        int pick = -1;
        double best = Double.MAX_VALUE;
        for (int i = 0; i < w.length; i++) {
            if (population[i] >= cap) {
                continue; // channel is full - real players included
            }
            double ratio = bots[i] / w[i];
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
