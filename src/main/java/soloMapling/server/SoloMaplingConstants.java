package soloMapling.server;

import org.gms.net.server.Server;
import org.gms.net.server.channel.Channel;

public class SoloMaplingConstants {

    /**
     * Channel 1, resolved on demand.
     *
     * <p>This used to be a {@code static final} field, which was evaluated once at class-load
     * time: if the channels weren't up yet it captured {@code null} and kept it forever. It is
     * only "channel 1", not "the bot's channel" — bots now live on every open channel, so
     * anything resolving a specific bot should go through
     * {@link BotChannelRouter#findCharacter(int)} and read the channel off the character.
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
