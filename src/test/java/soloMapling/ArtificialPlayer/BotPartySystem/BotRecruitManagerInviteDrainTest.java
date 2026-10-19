package soloMapling.ArtificialPlayer.BotPartySystem;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The invite drain must honour the (bot, player) decline cooldown it is handed.
 *
 * <p>The drain used to reject unconditionally, so the cooldown {@code rollPartyAsk} records on a
 * decline was written and never read on the invite path: the player re-invited inside the window
 * and got an instant "declined" notice instead of the bot simply not answering yet.
 * {@link BotRecruitManager#declinedByThisBot} is the shared question both drains ask.
 */
class BotRecruitManagerInviteDrainTest {

    @SuppressWarnings("unchecked")
    private static Map<Long, Long> declinedUntil() throws Exception {
        Field f = BotRecruitManager.class.getDeclaredField("DECLINED_UNTIL");
        f.setAccessible(true);
        return (Map<Long, Long>) f.get(null);
    }

    private static long pairKey(int botId, int playerId) throws Exception {
        Method m = BotRecruitManager.class.getDeclaredMethod("pairKey", int.class, int.class);
        m.setAccessible(true);
        return (Long) m.invoke(null, botId, playerId);
    }

    @Test
    void aLiveCooldownAnswersNoToAnotherInvite() throws Exception {
        Map<Long, Long> map = declinedUntil();
        map.clear();
        try {
            map.put(pairKey(10, 20), System.currentTimeMillis() + 60_000);
            assertTrue(BotRecruitManager.declinedByThisBot(10, 20),
                    "a cooldown still counting down must stop the drain from re-answering");
        } finally {
            map.clear();
        }
    }

    @Test
    void anExpiredCooldownLetsTheDrainAnswerAgain() throws Exception {
        Map<Long, Long> map = declinedUntil();
        map.clear();
        try {
            map.put(pairKey(11, 21), System.currentTimeMillis() - 1);
            assertFalse(BotRecruitManager.declinedByThisBot(11, 21),
                    "once the window has run out the bot answers the next invite in its own right");
        } finally {
            map.clear();
        }
    }

    @Test
    void theCooldownIsPerPairNotPerBot() throws Exception {
        Map<Long, Long> map = declinedUntil();
        map.clear();
        try {
            map.put(pairKey(12, 22), System.currentTimeMillis() + 60_000);
            assertFalse(BotRecruitManager.declinedByThisBot(12, 99),
                    "refusing one player says nothing about a different one");
        } finally {
            map.clear();
        }
    }

    @Test
    void noRecordAtAllIsNotACooldown() throws Exception {
        Map<Long, Long> map = declinedUntil();
        map.clear();
        try {
            assertFalse(BotRecruitManager.declinedByThisBot(13, 23),
                    "a pair the bot never refused is not on cooldown");
        } finally {
            map.clear();
        }
    }
}
