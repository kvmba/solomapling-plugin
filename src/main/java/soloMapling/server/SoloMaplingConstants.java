package soloMapling.server;

import org.gms.net.server.Server;
import org.gms.net.server.channel.Channel;

public class SoloMaplingConstants {

    /**
     * Channel 1, resolved on demand.
     *
     * <p>This used to be a {@code static final} field, which was evaluated once at class-load
     * time: if the channels weren't up yet it captured {@code null} and kept it forever.
     *
     * <p>It is just "channel 1", not "the bot's channel" — bots live on every open channel now,
     * so anything looking for a particular bot should use
     * {@link BotChannelRouter#findCharacter(int)}, which searches the whole world.
     */
    public static Channel mainChannel() {
        return Server.getInstance().getChannel(GameConstants.WORLD_SCANIA, GameConstants.CHANNEL_1);
    }

    public static class GameConstants {
        public static final int WORLD_SCANIA = 0;
        public static final int CHANNEL_1 = 1;
        public static final int BOT_BASE_ID = 20000;
    }

}
