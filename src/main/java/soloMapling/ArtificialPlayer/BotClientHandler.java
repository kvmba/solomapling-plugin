package soloMapling.ArtificialPlayer;

import org.gms.client.BotClient;
import org.gms.client.Client;
import soloMapling.server.SoloMaplingConstants.GameConstants;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Source of truth for the shared headless bot {@link Client}s.
 *
 * <p>One client per channel, since a bot's client is what ties it to a channel: the channel it
 * reports decides its {@code ChannelServer}, map factory and player storage. Every bot on the
 * same channel shares that channel's client.
 */
public class BotClientHandler {

    private static final Map<Integer, Client> BOT_CLIENTS = new ConcurrentHashMap<>();

    /**
     * Constructs the shared headless bot client for one channel. Idempotent — safe to call
     * more than once per channel (only the first call builds the instance). Must run after
     * the channels exist, since the client reports {@code WORLD_SCANIA} / the channel id
     * for routing.
     */
    public static void initHeadlessBotClient() {
        clientFor(GameConstants.CHANNEL_1);
    }

    /**
     * The headless client for a channel, creating it on first use. Every bot on that channel
     * shares it. Falls back to channel 1's client (creating it if needed) when the requested
     * channel isn't up, so a spawn is never blocked by a missing channel.
     */
    public static Client clientFor(int channel) {
        int ch = channel > 0 ? channel : GameConstants.CHANNEL_1;
        // computeIfAbsent is atomic, so concurrent callers for the same channel can never build
        // two clients - every bot on a channel must share exactly one.
        return BOT_CLIENTS.computeIfAbsent(ch,
                c -> new BotClient(GameConstants.WORLD_SCANIA, c));
    }

    /**
     * The channel-1 client: the default for anything that doesn't care which channel it is on.
     * Built on first use (idempotent) — never null, so a spawn is never blocked by nobody having
     * called {@link #initHeadlessBotClient()} yet.
     */
    public static Client getBotClient() {
        return clientFor(GameConstants.CHANNEL_1);
    }
}
