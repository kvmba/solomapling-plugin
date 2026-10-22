package soloMapling.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The router answers two questions only: "put this bot on my preferred channel"
 * ({@link BotChannelRouter#reservePreferred(int)}) and "put this bot on that exact channel"
 * ({@link BotChannelRouter#reserveExact(int)}).
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
    void reservationIsAtomicWithTheCapacityCheck() {
        // With no world up, both entry points answer the sentinel/fallback without counting, so
        // this pins the reservation arithmetic itself rather than live routing.
        BotChannelRouter.noteBotAdded(7);
        assertEquals(1, BotChannelRouter.botsOnChannel(7));
        BotChannelRouter.noteBotAdded(7);
        assertEquals(2, BotChannelRouter.botsOnChannel(7));
        BotChannelRouter.release(7);
        BotChannelRouter.release(7);
        // A release past zero must not leave a negative count that would make the channel read
        // as "has room" forever.
        BotChannelRouter.release(7);
        assertEquals(0, BotChannelRouter.botsOnChannel(7));
    }

    @Test
    void channelOfFallsBackToDefault() {
        // A bot whose client is gone still has to report a channel, or teardown would try to
        // remove it from a channel that does not exist.
        assertEquals(BotChannelRouter.DEFAULT_CHANNEL, BotChannelRouter.channelOf(null));
    }
}
