package soloMapling.server;

import org.gms.client.Character;
import org.gms.config.GameConfig;
import org.gms.net.server.Server;
import org.gms.net.server.channel.Channel;
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
 * <p>Capacity is the only shared rule: a channel at {@code channel_capacity} takes no more,
 * counting real players as well as bots, because what must not be exceeded is the channel's
 * total headcount. A bot that cannot be placed is dropped rather than squeezed in past the cap,
 * and a bot never switches channels once created.
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
            if (cap > 0 && !hasRoom(world, channel, cap)) {
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
                    && hasRoom(world, preferredChannel, cap)) {
                return preferredChannel;
            }
            for (int channelId = 1; channelId <= n; channelId++) {
                if (hasRoom(world, channelId, cap)) {
                    return channelId;
                }
            }
            return NONE;
        } catch (RuntimeException e) {
            return DEFAULT_CHANNEL; // never let routing break a spawn
        }
    }

    /** True when the channel is up and below {@code channel_capacity}. */
    private static boolean hasRoom(World world, int channelId, int cap) {
        Channel ch = world.getChannel(channelId);
        // A missing channel counts as full, not empty: treating it as room would route bots
        // onto a channel that isn't there.
        return ch != null && ch.getPlayerStorage().getSize() < cap;
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
