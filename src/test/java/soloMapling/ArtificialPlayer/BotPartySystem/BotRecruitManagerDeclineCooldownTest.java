package soloMapling.ArtificialPlayer.BotPartySystem;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The refusal cooldown is keyed per (bot, player) pair, and nothing ever comes back to read it once
 * it has run out — so without a sweep the map would hold one entry for every bot every passing
 * player was ever refused by. Player ids keep arriving, so that grows for as long as the server is
 * up. This keeps the sweep honest without waiting months to observe it.
 */
class BotRecruitManagerDeclineCooldownTest {

    @SuppressWarnings("unchecked")
    private static Map<Long, Long> declinedUntil() throws Exception {
        Field f = BotRecruitManager.class.getDeclaredField("DECLINED_UNTIL");
        f.setAccessible(true);
        return (Map<Long, Long>) f.get(null);
    }

    private static void sweep() throws Exception {
        Method m = BotRecruitManager.class.getDeclaredMethod("sweepExpiredDeclines");
        m.setAccessible(true);
        m.invoke(null);
    }

    @SuppressWarnings("unchecked")
    @Test
    void anExpiredCooldownIsSweptAway() throws Exception {
        Map<Long, Long> map = declinedUntil();
        map.clear();
        try {
            map.put(1L, System.currentTimeMillis() - 1);   // already run out
            sweep();
            assertFalse(map.containsKey(1L), "a cooldown that has run out should be dropped");
        } finally {
            map.clear();
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    void aLiveCooldownSurvivesTheSweep() throws Exception {
        Map<Long, Long> map = declinedUntil();
        map.clear();
        try {
            map.put(2L, System.currentTimeMillis() + 60_000);   // still counting down
            sweep();
            assertTrue(map.containsKey(2L),
                    "sweeping must not drop a cooldown the bot is still serving");
        } finally {
            map.clear();
        }
    }
}
