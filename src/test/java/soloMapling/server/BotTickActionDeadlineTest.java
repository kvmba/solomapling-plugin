package soloMapling.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BotTickActionDeadlineTest {
    @Test
    void anEarlierActionDeadlineSurvivesReschedulingAndFiresOnlyOnce() {
        var entry = new BotTickService.Entry(() -> {}, 40_000, 40_000);
        entry.requestActionAt(600);
        entry.requestActionAt(900);
        BotTickService.requestFireAt(entry, 41_000);

        assertEquals(Long.MAX_VALUE, entry.tryStart(599));
        assertEquals(600, entry.tryStart(600));
        entry.requestActionAt(700);
        assertEquals(Long.MAX_VALUE, entry.tryStart(700), "a bot must not overlap its running tick");
        entry.finish(720);
        assertEquals(700, entry.tryStart(720), "a request made during a tick survives its completion");
        entry.finish(721);
        assertEquals(Long.MAX_VALUE, entry.tryStart(900), "the action deadline is consumed, not a new period");
    }
}
