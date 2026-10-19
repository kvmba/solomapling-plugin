package soloMapling.ArtificialPlayer.BotAttackSystem;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The swing lock is the one thing that makes a bot's tempo decision atomic across the two thread
 * families that reach {@link BotAttackDriver#botAttack} for the same bot through different
 * monitors (the PQ combat beat and the stage macro tick). These pin the properties the fix relies
 * on, without needing a live map: one lock per bot, non-reentrant (so a second swing cannot nest
 * inside the first), and surviving {@code clearBot} so a swing in flight is never orphaned by a
 * despawn that reuses the character id.
 */
class BotAttackDriverSwingSerializationTest {

    @SuppressWarnings("unchecked")
    private static Map<Integer, ReentrantLock> swingLocks() throws Exception {
        Field f = BotAttackDriver.class.getDeclaredField("SWING_LOCK");
        f.setAccessible(true);
        return (Map<Integer, ReentrantLock>) f.get(null);
    }

    @Test
    void oneLockPerBotAndStableAcrossCalls() throws Exception {
        Map<Integer, ReentrantLock> locks = swingLocks();
        locks.clear();
        try {
            ReentrantLock a = swingLocks().computeIfAbsent(500, k -> new ReentrantLock());
            ReentrantLock b = swingLocks().computeIfAbsent(500, k -> new ReentrantLock());
            assertTrue(a == b, "the same bot must always get the same lock, or two threads could both swing");

            ReentrantLock other = swingLocks().computeIfAbsent(501, k -> new ReentrantLock());
            assertFalse(a == other, "different bots must not share one lock, or a swing would serialise the world");
        } finally {
            locks.clear();
        }
    }

    /**
     * The property that actually fixes the double swing: two THREADS cannot both be inside the
     * swing. Reentrancy within one thread is irrelevant here - nothing in attack() calls back into
     * botAttack, so the same thread never re-acquires - and a reentrant lock would be harmless if it
     * did. What matters is that a second thread's tryLock fails while the first is swinging.
     */
    @Test
    void aSecondThreadCannotSwingWhileTheFirstIsSwinging() throws Exception {
        Map<Integer, ReentrantLock> locks = swingLocks();
        locks.clear();
        try {
            ReentrantLock lock = swingLocks().computeIfAbsent(600, k -> new ReentrantLock());
            assertTrue(lock.tryLock(), "an idle bot's lock is free");
            java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
            boolean[] acquired = new boolean[1];
            Thread other = new Thread(() -> {
                acquired[0] = lock.tryLock();
                if (acquired[0]) {
                    lock.unlock();
                }
                done.countDown();
            }, "second-swing");
            other.start();
            assertTrue(done.await(2, java.util.concurrent.TimeUnit.SECONDS), "the second thread must not block");
            assertFalse(acquired[0],
                    "a second thread must be refused while a swing is in flight - that is the double-swing fix");
            lock.unlock();
        } finally {
            locks.clear();
        }
    }

    @Test
    void clearBotKeepsTheLockSoAnInFlightSwingIsNotOrphaned() throws Exception {
        Map<Integer, ReentrantLock> locks = swingLocks();
        locks.clear();
        try {
            ReentrantLock held = swingLocks().computeIfAbsent(700, k -> new ReentrantLock());
            assertTrue(held.tryLock(), "the sweep thread holds this mid-swing");
            BotAttackDriver.clearBot(700);
            assertNotNull(swingLocks().get(700),
                    "evicting the lock would let a reused character id acquire a different one and swing beside the live one");
            held.unlock();
        } finally {
            locks.clear();
        }
    }
}
