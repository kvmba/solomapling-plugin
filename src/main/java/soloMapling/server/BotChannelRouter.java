package soloMapling.server;

import org.gms.client.Character;
import org.gms.config.GameConfig;
import org.gms.net.server.Server;
import org.gms.net.server.world.World;


/**
 * Picks the channel a newly created bot lives on, and reserves that channel's slot.
 *
 * <p>Two ways in, because there are two different questions:
 * <ul>
 *   <li>{@link #reservePreferred(int)} — "put this bot on my preferred channel, usually ch1". The
 *       ambient world (shops, merchants, town crowds) lives on channel 1, so every ambient bot
 *       asks for it by name.</li>
 *   <li>{@link #reserveExact(int)} — "put this bot on that exact channel". The training
 *       cohorts use it: a cohort's {@code count} is a per-channel quota, so the spawn loop
 *       fills channel 1, then channel 2, and so on, and every channel ends up with its own
 *       full set of grinders.</li>
 * </ul>
 *
 * <p>Capacity is the only shared rule: a channel at {@code channel_capacity} takes no more.
 * A bot that cannot be placed is dropped rather than squeezed in past the cap, and a bot never
 * switches channels once created. Both entry points are reservations, not lookups: the slot is
 * claimed atomically with the decision, because a check followed by a later registration lets
 * a whole parallel spawn wave pass the same check before any of it lands (see {@link #tryReserve}).
 * A caller that fails after reserving must {@link #release(int)} the slot.
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
     * Record a bot arriving on a channel. Paired with {@link #release(int)}.
     * Must be called from whichever channel the bot was actually placed on.
     *
     * <p>Spawn paths that came through {@link #reservePreferred(int)} /
     * {@link #reserveExact(int)} already hold their slot and must NOT call this again - the
     * reservation is the count.
     */
    public static void noteBotAdded(int channel) {
        if (channel > 0) {
            BOTS_ON_CHANNEL.computeIfAbsent(channel, k -> new java.util.concurrent.atomic.AtomicInteger())
                    .incrementAndGet();
        }
    }

    /**
     * Undo {@link #noteBotAdded} or a {@link #reservePreferred}/{@link #reserveExact}
     * reservation. Must be passed the same channel the bot was added on, or the count drifts
     * upward and a channel eventually refuses new bots.
     */
    public static void release(int channel) {
        if (channel > 0) {
            java.util.concurrent.atomic.AtomicInteger c = BOTS_ON_CHANNEL.get(channel);
            if (c != null) {
                // Clamp at zero: an unmatched release must not push the counter negative, or
                // the channel would then accept that many extra bots before it reads as full.
                c.updateAndGet(current -> current > 0 ? current - 1 : 0);
            }
        }
    }

    /** Bots currently placed on {@code channel}, including in-flight reservations. */
    static int botsOnChannel(int channel) {
        java.util.concurrent.atomic.AtomicInteger c = BOTS_ON_CHANNEL.get(channel);
        return c == null ? 0 : Math.max(0, c.get());
    }

    /**
     * Claim a slot on {@code channelId} for a spawn that is about to happen.
     *
     * <p>The claim is the count: there is no separate "commit" step. That is what closes the
     * check-then-act hole this router used to have - selection and the headcount bump happen in
     * one atomic operation, so N spawn threads racing for the last slot cannot all observe
     * "below cap" and then all register. A caller that fails after reserving must hand the slot
     * back with {@link #release(int)}.
     *
     * <p>{@code cap <= 0} means the operator disabled the cap; the bot is still counted, so the
     * number stays honest if the cap is re-enabled at runtime.
     */
    private static boolean tryReserve(int channelId, int channelCount, int cap) {
        if (channelId <= 0 || channelId > channelCount) {
            return false;
        }
        java.util.concurrent.atomic.AtomicInteger counter = BOTS_ON_CHANNEL
                .computeIfAbsent(channelId, k -> new java.util.concurrent.atomic.AtomicInteger());
        int used = counter.incrementAndGet();
        if (cap > 0 && used > cap) {
            counter.decrementAndGet(); // lost the race for the last slot
            return false;
        }
        return true;
    }

    /**
     * The channel a caller explicitly asked for, or {@link #NONE} when it doesn't exist or is
     * already at {@code channel_capacity}. Reserves the slot on success.
     *
     * <p>Unlike {@link #reservePreferred(int)} this never falls back to another channel: the caller
     * wants this specific one (the training cohorts spawn a full set per channel), so a channel
     * that cannot take it must report failure rather than quietly putting the bot somewhere else
     * and leaving this one short of its quota.
     *
     * @param channel 1-based channel id.
     */
    public static int reserveExact(int channel) {
        try {
            World world = Server.getInstance().getWorld(WORLD);
            if (world == null || channel <= 0) {
                return NONE;
            }
            int n = world.getChannelsSize();
            if (n <= 0 || channel > n) {
                return NONE;
            }
            return tryReserve(channel, n, GameConfig.getServerInt("channel_capacity"))
                    ? channel : NONE;
        } catch (RuntimeException e) {
            return NONE; // never let routing break a spawn
        }
    }

    /**
     * The channel for the next created bot: the caller's preferred one when it has room,
     * otherwise any channel that does. Reserves the slot on success.
     *
     * <p>Preference is not a "heaviest on ch1" split - it is just "the ambient world lives on
     * ch1, so send ambient bots there". When that channel is at {@code channel_capacity} the
     * bot goes to the first channel with headroom instead of being dropped, which is all the
     * spreading this needs: training cohorts are placed per-channel by
     * {@link #reserveExact(int)}.
     *
     * @param preferredChannel 1-based channel id, or {@code <= 0} for no preference.
     * @return a 1-based channel id, or {@link #NONE} when every channel is at the cap.
     */
    public static int reservePreferred(int preferredChannel) {
        try {
            World world = Server.getInstance().getWorld(WORLD);
            if (world == null) {
                return reserveUncapped(DEFAULT_CHANNEL);
            }
            int n = world.getChannelsSize();
            int cap = GameConfig.getServerInt("channel_capacity");
            if (n <= 0 || cap <= 0) {
                return reserveUncapped(DEFAULT_CHANNEL);
            }
            if (tryReserve(preferredChannel, n, cap)) {
                return preferredChannel;
            }
            for (int channelId = 1; channelId <= n; channelId++) {
                if (tryReserve(channelId, n, cap)) {
                    return channelId;
                }
            }
            return NONE;
        } catch (RuntimeException e) {
            return reserveUncapped(DEFAULT_CHANNEL); // never let routing break a spawn
        }
    }

    /**
     * Fallback used when the topology or the cap cannot be read: still count the bot on the
     * default channel, so the caller's "the returned channel is already reserved" contract holds
     * and a later teardown's {@link #release(int)} stays balanced.
     */
    private static int reserveUncapped(int channel) {
        noteBotAdded(channel);
        return channel;
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
