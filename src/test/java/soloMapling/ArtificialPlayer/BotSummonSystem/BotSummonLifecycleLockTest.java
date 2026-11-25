package soloMapling.ArtificialPlayer.BotSummonSystem;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotSummonLifecycleLockTest {
    @Test
    void summonsDoNotStarveTheVirtualThreadScheduler(@TempDir Path temp) throws Exception {
        // The virtual-thread scheduler is JVM-wide, so run the one-carrier regression in a fork.
        Path args = temp.resolve("java.args");
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Files.writeString(args, """
                -Djdk.virtualThreadScheduler.parallelism=1
                -Djdk.virtualThreadScheduler.maxPoolSize=1
                -cp
                "%s"
                %s
                """.formatted(classpath.replace("\\", "\\\\").replace("\"", "\\\""), getClass().getName()));
        Path output = temp.resolve("probe.log");
        Process probe = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "@" + args).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(probe.waitFor(25, SECONDS), "single-carrier probe did not exit");
            assertEquals(0, probe.exitValue(), Files.readString(output));
        } finally {
            if (probe.isAlive()) {
                probe.destroyForcibly().waitFor(5, SECONDS);
            }
        }
    }

    /** Runs in the fork; the fair map lock stands in for MapleMap's character lock. */
    public static void main(String[] args) throws Exception {
        var mapLock = new ReentrantReadWriteLock(true);
        var writerHasLock = new CountDownLatch(1);
        var releaseWriter = new CountDownLatch(1);
        var writer = new FutureTask<Void>(() -> {
            mapLock.writeLock().lock();
            try {
                writerHasLock.countDown();
                releaseWriter.await();
            } finally {
                mapLock.writeLock().unlock();
            }
            return null;
        });
        Thread.startVirtualThread(writer);
        var spawn = new FutureTask<>(() -> BotSummonFollower.spawnIfAbsent(1, () -> {
            mapLock.readLock().lock();
            mapLock.readLock().unlock();
        }));
        var waiterStarted = new CountDownLatch(1);
        var sameBot = new FutureTask<>(() -> {
            waiterStarted.countDown();
            return BotSummonFollower.spawnIfAbsent(1, () -> {});
        });
        try {
            assertTrue(writerHasLock.await(5, SECONDS), "map writer did not start");
            Thread spawner = Thread.startVirtualThread(spawn);
            long deadline = System.nanoTime() + SECONDS.toNanos(5);
            while (!mapLock.hasQueuedThread(spawner) && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }
            assertTrue(mapLock.hasQueuedThread(spawner), "the spawn must be waiting for the map writer");
            Thread.startVirtualThread(sameBot);
            assertTrue(waiterStarted.await(5, SECONDS), "the spawn waiting on the map pinned the only carrier");

            // The same-bot waiter must park without pinning; an unrelated bot must not share its lock.
            var otherBot = new FutureTask<>(() -> BotSummonFollower.spawnIfAbsent(2, () -> {}));
            Thread.startVirtualThread(otherBot);
            otherBot.get(5, SECONDS);
            assertFalse(sameBot.isDone(), "the same bot must still wait for the first spawn");
        } finally {
            releaseWriter.countDown();
        }
        writer.get(5, SECONDS);
        spawn.get(5, SECONDS);
        sameBot.get(5, SECONDS);
    }
}
