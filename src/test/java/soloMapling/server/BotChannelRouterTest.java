package soloMapling.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The router answers two questions only: "put this bot on my preferred channel"
 * ({@link BotChannelRouter#nextChannel(int)}) and "put this bot on that exact channel"
 * ({@link BotChannelRouter#resolveChannel(int)}).
 *
 * <p>Both read live server state, and {@code Server.getInstance()} cannot stand up in a unit
 * test, so what is pinned down here is the contract the callers rely on: the sentinel values
 * and {@link BotChannelRouter#channelOf}, which is pure. The routing itself is exercised by
 * running the server.
 */
class BotChannelRouterTest {

    @Test
    void sentinelsAreWhatCallersCheckFor() {
        assertEquals(1, BotChannelRouter.DEFAULT_CHANNEL);
        assertEquals(-1, BotChannelRouter.NONE);
        // createBotOnChannel treats NONE as "skip this spawn", so it must not collide with a
        // real channel id.
        assertTrue(BotChannelRouter.NONE < 1);
    }

    @Test
    void channelOfFallsBackToDefault() {
        // A bot whose client is gone still has to report a channel, or teardown would try to
        // remove it from a channel that does not exist.
        assertEquals(BotChannelRouter.DEFAULT_CHANNEL, BotChannelRouter.channelOf(null));
    }
}
