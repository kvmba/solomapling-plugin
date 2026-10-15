package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The warmup-notice stamp table must not grow one entry per map a player ever followed a bot
 * across: a stamp only throttles inside its own 10s window, so the single writer drops the
 * owner's expired stamps on the way in. Pinned through the package seams
 * {@code markWarmupNotified} / {@code warmupNotifiedStamps} (the table is internal otherwise).
 */
class BotNavigationManagerWarmupNoticeTest {

    @Test
    void aStaleStampIsPrunedWithTheNextNotice() {
        int owner = 990_001;
        BotNavigationManager.markWarmupNotified(owner, 100_000_000, 1_000L);
        assertTrue(BotNavigationManager.warmupNotifiedStamps(owner).containsKey(100_000_000));

        // 19s later, on another map: the first stamp is past its window and can never throttle again.
        BotNavigationManager.markWarmupNotified(owner, 100_000_100, 20_000L);

        Map<Integer, Long> stamps = BotNavigationManager.warmupNotifiedStamps(owner);
        assertEquals(1, stamps.size(), "stale stamps must not accumulate per (owner, map)");
        assertTrue(stamps.containsKey(100_000_100));
        assertFalse(stamps.containsKey(100_000_000));
    }

    @Test
    void aStampStillInsideItsWindowSurvives() {
        int owner = 990_002;
        BotNavigationManager.markWarmupNotified(owner, 100_000_000, 1_000L);
        BotNavigationManager.markWarmupNotified(owner, 100_000_100, 5_000L); // both live, 4s apart
        assertEquals(2, BotNavigationManager.warmupNotifiedStamps(owner).size(),
                "a stamp still inside its throttle window is live state and must survive");
    }
}
