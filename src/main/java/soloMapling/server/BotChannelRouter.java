package soloMapling.server;

import org.gms.client.Character;
import org.gms.config.GameConfig;
import org.gms.net.server.Server;
import org.gms.net.server.world.World;


/**
 * Picks the channel a newly created bot lives on.
 *
 * <p>Two ways in, because there are two different questions:
 * <ul>
 *   <li>{@link #nextChannel(int)} — "put this bot on my preferred channel, usually ch1". The
 *       ambient world (shops, merchants, town crowds) lives on channel 1, so every ambient bot
 *       asks for it by name.</li>
 *   <li>{@link #resolveChannel(int)} — "put this bot on that exact channel". The training
 *       cohorts use it: a cohort's {@code count} is a per-channel quota, so the spawn loop
 *       fills channel 1, then channel 2, and so on, and every channel ends up with its own
 *       full set of grinders.</li>
 * </ul>
 *
 * <p>Capacity is the only shared rule: a channel at {@code channel_capacity} takes no more.
 * A bot that cannot be placed is dropped rather than squeezed in past the cap, and a bot never
 * switches channels once created.
 *
 * <p>IMPORTANT - what {@code channel_capacity} counts: BOTS ONLY, not real players. It used to
 * be total headcount, but that required reading {@code PlayerStorage.getSize()} on every spawn,
 * and that read is what deadlocked startup (see {@link #BOTS_ON_CHANNEL}). Counting bots with an
 * atomic counter removes the storage read entirely. So a channel holding many real players still
 * accepts bots up to the cap, and the total on a channel can exceed {@code channel_capacity}.
 * That is the intended trade: the population here is overwhelmingly bots, and a gate that cannot
 * deadlock the server is worth more than one that is exact.
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

    /** Channel a character lives on, read off its own client. */
    public static int channelOf(Character chr) {
        if (chr == null) {
            return DEFAULT_CHANNEL;
        }
        org.gms.client.Client c = chr.getClient();
        if (c == null) {
            return DEFAULT_CHANNEL;
        }
        int ch = c.getChannel();
        return ch > 0 ? ch : DEFAULT_CHANNEL;
    }

    /**
     * Live count of bots placed on each channel, maintained by the spawn/teardown paths.
     *
     * <p>This is the capacity gate's ONLY input, and it is deliberately NOT
     * {@code PlayerStorage.getSize()}. The storage read is what deadlocked startup: every spawn
     * read the headcount through the channel's READ lock and then registered the bot through
     * the same storage's WRITE lock, thousands of times per wave, so the two directions met on
     * PlayerStorage and the wave parked for good with the CPU and IO idle. Counting bots here
     * instead means the routing path never touches a storage lock at all.
     *
     * <p>The trade, which is intended: real players on a channel are no longer counted, so
     * {@code channel_capacity} becomes a cap on BOTS per channel rather than a cap on total
     * headcount. That is the right trade here - the population is overwhelmingly bots, and a
     * gate that cannot deadlock the server is worth more than one that is exact.
     */
    private static final java.util.Map<Integer, java.util.concurrent.atomic.AtomicInteger> BOTS_ON_CHANNEL =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Record a bot arriving on a channel. Paired with {@link #noteBotRemoved}.
     * Must be called from whichever channel the bot was actually placed on.
     */
    public static void noteBotAdded(int channel) {
        if (channel > 0) {
            BOTS_ON_CHANNEL.computeIfAbsent(channel, k -> new java.util.concurrent.atomic.AtomicInteger())
                    .incrementAndGet();
        }
    }

    /**
     * Undo {@link #noteBotAdded} for a bot that is leaving. Must be passed the same channel the
     * bot was added on, or the count drifts upward and a channel eventually refuses new bots.
     */
    public static void noteBotRemoved(int channel) {
        if (channel > 0) {
            java.util.concurrent.atomic.AtomicInteger c = BOTS_ON_CHANNEL.get(channel);
            if (c != null) {
                c.decrementAndGet();
            }
        }
    }

    /** Bots currently placed on {@code channel}. Negative counts read as 0. */
    private static int botsOnChannel(int channel) {
        java.util.concurrent.atomic.AtomicInteger c = BOTS_ON_CHANNEL.get(channel);
        return c == null ? 0 : Math.max(0, c.get());
    }

    /**
     * The channel a caller explicitly asked for, or {@link #NONE} when it doesn't exist or is
     * already at {@code channel_capacity}.
     *
     * <p>Unlike {@link #nextChannel(int)} this never falls back to another channel: the caller
     * wants this specific one (the training cohorts spawn a full set per channel), so a channel
     * that cannot take it must report failure rather than quietly putting the bot somewhere else
     * and leaving this one short of its quota.
     *
     * @param channel 1-based channel id.
     */
    public static int resolveChannel(int channel) {
        try {
            World world = Server.getInstance().getWorld(WORLD);
            if (world == null || channel <= 0) {
                return NONE;
            }
            int n = world.getChannelsSize();
            if (n <= 0 || channel > n) {
                return NONE;
            }
            int cap = GameConfig.getServerInt("channel_capacity");
            if (cap > 0 && !hasRoom(channel, n, cap)) {
                return NONE; // at capacity - the caller skips this spawn
            }
            return channel;
        } catch (RuntimeException e) {
            return NONE; // never let routing break a spawn
        }
    }

    /**
     * The channel for the next created bot: the caller's preferred one when it has room,
     * otherwise any channel that does.
     *
     * <p>Preference is not a "heaviest on ch1" split - it is just "the ambient world lives on
     * ch1, so send ambient bots there". When that channel is at {@code channel_capacity} the
     * bot goes to the first channel with headroom instead of being dropped, which is all the
     * spreading this needs: training cohorts are placed per-channel by
     * {@link #resolveChannel(int)}.
     *
     * @param preferredChannel 1-based channel id, or {@code <= 0} for no preference.
     * @return a 1-based channel id, or {@link #NONE} when every channel is at the cap.
     */
    public static int nextChannel(int preferredChannel) {
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
            if (preferredChannel > 0 && preferredChannel <= n
                    && hasRoom(preferredChannel, n, cap)) {
                return preferredChannel;
            }
            for (int channelId = 1; channelId <= n; channelId++) {
                if (hasRoom(channelId, n, cap)) {
                    return channelId;
                }
            }
            return NONE;
        } catch (RuntimeException e) {
            return DEFAULT_CHANNEL; // never let routing break a spawn
        }
    }

    /**
     * True when the channel is up and below {@code channel_capacity}.
     *
     * <p>Touches no lock on the routing path. It reads the bot counter instead of
     * {@code PlayerStorage} (see {@link #BOTS_ON_CHANNEL}), and it takes the channel COUNT the
     * caller already fetched instead of calling {@code world.getChannel()} again - getChannel()
     * takes World's own read lock, so using it per spawn would just move the contention from one
     * lock to another. A channel id past the end is treated as missing, which means full.
     */
    private static boolean hasRoom(int channelId, int channelCount, int cap) {
        return channelId > 0 && channelId <= channelCount && botsOnChannel(channelId) < cap;
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
}
