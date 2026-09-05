package soloMapling.ArtificialPlayer.BotPartySystem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Return-to-origin handoff: a recruited bot must remember where it was based (and whether it was a
 * grinder) so the ride can end back in its old life instead of adopting whatever far map the leader
 * walked to.
 */
class BotRecruitManagerReturnOriginTest {

    private static final int BOT = 777;

    @AfterEach
    void clear() {
        BotRecruitManager.clearHandoffs(BOT);
    }

    @Test
    void nothingRecordedConsumesAsSentinel() {
        assertEquals(-1, BotRecruitManager.consumeReturnHome(BOT));
        assertFalse(BotRecruitManager.consumeReturnGrinder(BOT));
    }

    @Test
    void grinderOriginRoundTrips() {
        BotRecruitManager.setReturnOrigin(BOT, 100000000, true);

        assertEquals(100000000, BotRecruitManager.consumeReturnHome(BOT));
        assertTrue(BotRecruitManager.consumeReturnGrinder(BOT));
    }

    @Test
    void townBotOriginIsRecordedWithoutGrinderFlag() {
        BotRecruitManager.setReturnOrigin(BOT, 100000000, false);

        assertEquals(100000000, BotRecruitManager.consumeReturnHome(BOT));
        assertFalse(BotRecruitManager.consumeReturnGrinder(BOT));
    }

    // A grinder re-posts its map before converting (convertBotType starts the new bot's tick, and the
    // fresh TrainingBot reads the handoff on its first doInit), but with grinder=false so the second
    // consumption ends the chain instead of looping.
    @Test
    void rePostedOriginKeepsTheMapAndDropsTheGrinderFlag() {
        BotRecruitManager.setReturnOrigin(BOT, 100000000, true);
        int home = BotRecruitManager.consumeReturnHome(BOT);
        assertTrue(BotRecruitManager.consumeReturnGrinder(BOT));

        BotRecruitManager.setReturnOrigin(BOT, home, false);

        assertEquals(100000000, BotRecruitManager.consumeReturnHome(BOT));
        assertFalse(BotRecruitManager.consumeReturnGrinder(BOT),
                "the re-posted handoff must not keep the grinder flag, or the bot re-posts forever");
    }

    // A player ending the ride by hand ("Train here with me!") must drop the pending origin: the
    // bot is deliberately staying put, and a stale entry would be consumed the next time it becomes
    // a follower, dragging it back to a map the player never asked it to return to.
    @Test
    void clearHandoffsDropsAPendingOriginBeforeAStationHereHandoff() {
        BotRecruitManager.setReturnOrigin(BOT, 100000000, true);
        BotRecruitManager.clearHandoffs(BOT);
        BotRecruitManager.markStationHere(BOT);

        assertEquals(-1, BotRecruitManager.consumeReturnHome(BOT),
                "a stale origin must not survive a hand-ended ride");
        assertFalse(BotRecruitManager.consumeReturnGrinder(BOT));
        assertTrue(BotRecruitManager.consumeStationHere(BOT),
                "clearHandoffs must not disturb the station-here handoff set right after it");
    }

    // Each consume is one-shot: a bot must not inherit a stale origin from an earlier ride.
    @Test
    void consumeClearsSoTheNextRideStartsClean() {
        BotRecruitManager.setReturnOrigin(BOT, 100000000, true);
        BotRecruitManager.consumeReturnHome(BOT);
        BotRecruitManager.consumeReturnGrinder(BOT);

        assertEquals(-1, BotRecruitManager.consumeReturnHome(BOT));
        assertFalse(BotRecruitManager.consumeReturnGrinder(BOT));
    }

    @Test
    void clearHandoffsDropsTheOrigin() {
        BotRecruitManager.setReturnOrigin(BOT, 100000000, true);
        BotRecruitManager.clearHandoffs(BOT);

        assertEquals(-1, BotRecruitManager.consumeReturnHome(BOT));
        assertFalse(BotRecruitManager.consumeReturnGrinder(BOT));
    }

    // An unresolved home (<0) must not be stored: TrainingBot reads -1 as "no handoff", so storing
    // it would silently turn into "adopt the current map" - exactly the bug being fixed.
    @Test
    void nonPositiveHomeIsNotStored() {
        BotRecruitManager.setReturnOrigin(BOT, -1, true);

        assertEquals(-1, BotRecruitManager.consumeReturnHome(BOT));
        assertTrue(BotRecruitManager.consumeReturnGrinder(BOT),
                "the grinder flag is independent of the map and must still survive");
    }
}
