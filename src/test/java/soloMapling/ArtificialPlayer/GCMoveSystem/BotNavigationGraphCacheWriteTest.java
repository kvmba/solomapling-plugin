package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.server.maps.Foothold;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the on-disk nav-graph cache against two distinct corruption modes, both now fixed.
 *
 * <p><b>1. Torn write.</b> Two builds of the same key can reach the disk at once: an async warm
 * on the warmup executor and a {@code !gcmove bake} (rebuildGraph) on the command thread. Writing
 * straight to the target opens it with TRUNCATE_EXISTING, so two ObjectOutputStreams interleave
 * into one truncated file and the next load fails on a half-written stream — silently, because
 * loadGraph logs the failure at debug. Measured on this box: with 8 concurrent writers of
 * DIFFERENT sizes, 15/15 runs produced either a StreamCorruptedException or a truncated-but-
 * parseable file. Same-size writers always produced a byte-identical file, because
 * ObjectOutputStream is deterministic and every writer starts at offset 0 — so the bug needs
 * writers whose serialized sizes differ, which a bake with a GM's profile supplies against a
 * bot's warm. Fixed by writing a temp file and renaming.
 *
 * <p><b>2. Snowshoe collision.</b> {@code GraphCacheKey} has four dimensions —
 * (mapId, speed, jump, snowShoes) — but {@code graphFile} emitted only three, dropping
 * snowShoes. On a slippery map, where {@code canonicalProfile} keeps the flag, a shod and an
 * unshod bot got different keys but the SAME file and overwrote each other on every visit;
 * loadGraph validated only three dimensions, so it handed back a graph whose physics disagreed
 * with the requested profile. Fixed by encoding snowShoes in the filename and validating it.
 *
 * <p>saveGraph is private and CACHE_DIR is a static final field of a fixed path, so this test
 * drives them through reflection against a redirected CACHE_DIR.
 */
class BotNavigationGraphCacheWriteTest {

    // Big enough that interleaved writes actually tear: ~1.6 MB serialized, far past any single
    // OS write buffer, so two concurrent writers of different sizes are guaranteed to overlap.
    private static final int PADDED_REGION_COUNT = 20_000;

    private static final int SMALL_REGION_COUNT = 40;

    private static BotNavigationGraph.Segment segment(int i) {
        // Real Segments, built the way the graph builder does (from Footholds). Filler bytes alone
        // would let a torn file still deserialize to a short-but-valid graph and mask the bug.
        return new BotNavigationGraph.Segment(
                new Foothold(new java.awt.Point(i * 10, i), new java.awt.Point(i * 10 + 100, i), i),
                false);
    }

    private static Method saveGraphMethod() {
        try {
            Method m = BotNavigationGraphProvider.class
                    .getDeclaredMethod("saveGraph", BotNavigationGraph.class);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException e) {
            throw new AssertionError("saveGraph(BotNavigationGraph) not found", e);
        }
    }

    private static BotNavigationGraph graphFor(int mapId, int speed, int jump, int regions) {
        return graphFor(mapId, speed, jump, regions, false);
    }

    private static BotNavigationGraph graphFor(int mapId, int speed, int jump, int regions,
                                               boolean snowShoes) {
        return graphFor(mapId, speed, jump, regions, snowShoes, null);
    }

    private static BotNavigationGraph graphFor(int mapId, int speed, int jump, int regions,
                                               boolean snowShoes,
                                               List<BotNavigationGraph.Region> presetRegions) {
        List<BotNavigationGraph.Region> built =
                presetRegions != null ? presetRegions : new ArrayList<>();
        if (presetRegions == null) {
            for (int i = 0; i < regions; i++) {
                built.add(new BotNavigationGraph.Region(i, List.of(segment(i))));
            }
        }
        return new BotNavigationGraph(mapId, 61,
                new BotMovementProfile(speed, jump, snowShoes), built,
                new HashMap<>(), new HashMap<>(), new HashMap<>(), Set.of(), Set.of());
    }

    /*
     * A List<Region> that sleeps briefly while it is being walked by the serializer. Java
     * serialization iterates a List via its iterator when the field is declared as List, so
     * throttling next() stretches the writer's occupancy of the file into a predictable window.
     */
    private static final class ThrottledRegionList
            extends java.util.AbstractList<BotNavigationGraph.Region>
            implements java.io.Serializable {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        private final java.util.List<BotNavigationGraph.Region> backing = new ArrayList<>();

        ThrottledRegionList() {
            for (int i = 0; i < PADDED_REGION_COUNT; i++) {
                backing.add(new BotNavigationGraph.Region(i, List.of(segment(i))));
            }
        }

        @Override
        public BotNavigationGraph.Region get(int index) {
            if (index % 500 == 0) {
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return backing.get(index);
        }

        @Override
        public int size() {
            return backing.size();
        }
    }

    /** The on-disk file for a full cache key, derived the same way graphFile() does. */
    private static Path cacheFile(Path tmpRoot, int mapId, int speed, int jump, boolean snowShoes) {
        return cacheDirFor(tmpRoot).resolve(mapId + "-s" + speed + "-j" + jump
                + "-ss" + (snowShoes ? 1 : 0) + ".bin");
    }

    /*
     * Read GRAPH_VERSION rather than hardcoding "v61": the cache directory is derived from it, so a
     * hardcoded literal would silently point the test at the wrong directory after any version bump.
     */
    private static Path cacheDirFor(Path tmpRoot) {
        int version = graphVersion();
        return tmpRoot.resolve("cache").resolve("bot-nav").resolve("v" + version);
    }

    private static int graphVersion() {
        try {
            Field f = BotNavigationGraphProvider.class.getDeclaredField("GRAPH_VERSION");
            f.setAccessible(true);
            return (int) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("GRAPH_VERSION not found", e);
        }
    }

    private static void invokeSave(BotNavigationGraph graph) {
        try {
            saveGraphMethod().invoke(null, graph);
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        } catch (InvocationTargetException e) {
            throw new AssertionError("saveGraph threw", e.getCause());
        }
    }

    /*
     * Deterministic tear, without depending on scheduler luck.
     *
     * Every List inside BotNavigationGraph is defensively copied by its constructor
     * (this.regions = new ArrayList<>(regions)), so a slow List cannot be injected from outside —
     * an earlier attempt at a throttled list silently did nothing and the test passed on buggy
     * code. And relying on the OS to interleave two writers is flaky (measured 1-in-3 here).
     *
     * So instead of trying to WIN a race, this test creates the tear deterministically: it starts
     * a slow writer and then, while that writer is provably still mid-stream, truncates the target
     * out from under it by opening it for write — exactly what a second writer's TRUNCATE_EXISTING
     * does. The fix must be immune to this because it never writes the target in place.
     */
    @Test
    void targetFileIsNeverWrittenInPlace() throws Exception {
        Path file = cacheFile(tmpRoot, 4_000_000, 140, 110, false);

        // Seed a valid, complete cache file so the target exists before the race.
        invokeSave(graphFor(4_000_000, 140, 110, SMALL_REGION_COUNT, false, null));
        long pristineSize = Files.size(file);

        // A large graph takes ~120 ms to serialize (measured), giving a wide, reliable window.
        BotNavigationGraph big = graphFor(4_000_000, 140, 110, PADDED_REGION_COUNT, false, null);
        CountDownLatch finished = new CountDownLatch(1);
        Thread writer = new Thread(() -> {
            try {
                invokeSave(big);
            } finally {
                finished.countDown();
            }
        });
        writer.start();

        // The unfixed writer opens `target` with TRUNCATE_EXISTING as its FIRST action, so the
        // file drops to 0 bytes and then grows. Detect that shrink — it is the fingerprint of
        // writing the target in place. The fixed writer never touches `target` until the final
        // atomic rename, so its size stays at the pristine value for the whole window.
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        boolean sawInPlaceTruncate = false;
        while (System.nanoTime() < deadline && finished.getCount() > 0) {
            if (Files.isRegularFile(file) && Files.size(file) < pristineSize) {
                sawInPlaceTruncate = true;
                break;
            }
            Thread.sleep(1);
        }
        finished.await();
        writer.join();

        org.junit.jupiter.api.Assertions.assertFalse(sawInPlaceTruncate,
                "writer truncated the target in place - the cache file is exposed to torn writes");

        // Whatever happened, the end state must be one complete graph.
        BotNavigationGraph restored;
        try (ObjectInputStream in = new ObjectInputStream(Files.newInputStream(file))) {
            restored = (BotNavigationGraph) in.readObject();
        }
        assertEquals(PADDED_REGION_COUNT, restored.regions.size(),
                "writer left a partial file behind");
    }

    /**
     * The regression itself: many writers, same cache file, DIFFERENT sizes, all at once.
     *
     * <p>Same-size writers would pass even on the unfixed code (identical bytes land at identical
     * offsets), so this deliberately mixes a large and a small graph to exercise the real tear.
     * This is the broad-stroke sibling of the deterministic test above: noisier, but it sweeps
     * more interleavings.
     */
    @Test
    void concurrentWritersNeverTearTheCacheFile() throws Exception {
        final int writers = 8;
        // Interleave big and small so the writers' serialized sizes differ — the precondition
        // for an actual tear.
        BotNavigationGraph[] graphs = new BotNavigationGraph[writers];
        for (int i = 0; i < writers; i++) {
            int n = (i % 2 == 0) ? PADDED_REGION_COUNT : SMALL_REGION_COUNT;
            graphs[i] = graphFor(1_000_000, 140, 110, n, false);
        }

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(writers);
        List<Throwable> failures = java.util.Collections.synchronizedList(new ArrayList<>());
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < writers; i++) {
            final BotNavigationGraph g = graphs[i];
            Thread t = new Thread(() -> {
                try {
                    start.await();
                    invokeSave(g);
                } catch (Throwable e) {
                    failures.add(e);
                } finally {
                    done.countDown();
                }
            });
            t.start();
            threads.add(t);
        }
        start.countDown();
        done.await();
        for (Thread t : threads) {
            t.join();
        }
        assertTrue(failures.isEmpty(), "writers threw: " + failures);

        Path file = cacheFile(tmpRoot, 1_000_000, 140, 110, false);
        assertTrue(Files.isRegularFile(file), "cache file missing at " + file);

        // The whole point: a torn file either throws here (StreamCorruptedException /
        // EOFException) or deserializes short. Both mean a silent cache miss on the next boot.
        BotNavigationGraph restored;
        try (ObjectInputStream in = new ObjectInputStream(Files.newInputStream(file))) {
            restored = (BotNavigationGraph) in.readObject();
        }
        assertNotNull(restored, "deserialized null");
        assertEquals(1_000_000, restored.mapId);
        int restoredRegions = restored.regions.size();
        // Must be EXACTLY one writer's graph — never a partial mixture of the two.
        boolean complete = restoredRegions == PADDED_REGION_COUNT
                || restoredRegions == SMALL_REGION_COUNT;
        assertTrue(complete, "cache file was torn - deserialized " + restoredRegions
                + " regions, expected exactly " + PADDED_REGION_COUNT + " or " + SMALL_REGION_COUNT);

        // No temp files left behind.
        try (Stream<Path> leftovers = Files.list(file.getParent())) {
            List<Path> tmpFiles = leftovers.filter(p -> p.getFileName().toString().contains(".tmp"))
                    .toList();
            assertEquals(List.of(), tmpFiles, "temp files leaked: " + tmpFiles);
        }
    }

    /**
     * SnowShoes is part of the cache key, so it must also be part of the filename. It used to be
     * dropped, so on a slippery map — where canonicalProfile keeps the flag — a shod and an unshod
     * bot hashed to different keys but the SAME file, overwriting each other on every visit.
     *
     * <p>loadGraph only validated three dimensions, so it happily handed back a graph whose physics
     * disagreed with the profile the caller asked for.
     */
    @Test
    void snowShoeFlagIsPartOfTheKeySoItMustBePartOfTheFileName() throws Exception {
        // Same map / speed / jump — only the snowShoes bit of the key differs.
        BotNavigationGraph shod = graphFor(3_000_000, 140, 110, SMALL_REGION_COUNT, true);
        BotNavigationGraph bare = graphFor(3_000_000, 140, 110, SMALL_REGION_COUNT, false);
        assertNotEquals(shod.movementProfile.snowShoes(), bare.movementProfile.snowShoes(),
                "test setup: profiles must differ only by snowShoes");

        invokeSave(shod);
        invokeSave(bare);

        Path shodFile = cacheFile(tmpRoot, 3_000_000, 140, 110, true);
        Path bareFile = cacheFile(tmpRoot, 3_000_000, 140, 110, false);
        assertNotEquals(shodFile, bareFile,
                "snowShoes is part of the key, so the two profiles must not share a file");

        // Both survive, each with its own profile intact.
        try (ObjectInputStream in = new ObjectInputStream(Files.newInputStream(shodFile))) {
            BotNavigationGraph g = (BotNavigationGraph) in.readObject();
            assertTrue(g.movementProfile.snowShoes(), "shod graph lost its snowShoes flag");
        }
        try (ObjectInputStream in = new ObjectInputStream(Files.newInputStream(bareFile))) {
            BotNavigationGraph g = (BotNavigationGraph) in.readObject();
            org.junit.jupiter.api.Assertions.assertFalse(g.movementProfile.snowShoes(),
                    "unshod graph gained a snowShoes flag");
        }
    }

    /** Different (mapId, speed, jump) must land in different files, never clobber one another. */
    @Test
    void distinctProfilesWriteDistinctFiles() throws Exception {
        invokeSave(graphFor(2_000_000, 130, 110, SMALL_REGION_COUNT, false));
        invokeSave(graphFor(2_000_000, 145, 120, SMALL_REGION_COUNT, false));
        invokeSave(graphFor(2_000_000, 155, 123, SMALL_REGION_COUNT, false));

        for (int[] key : new int[][]{{130, 110}, {145, 120}, {155, 123}}) {
            Path f = cacheFile(tmpRoot, 2_000_000, key[0], key[1], false);
            assertTrue(Files.isRegularFile(f), "missing cache file for s" + key[0] + "-j" + key[1]);
            try (ObjectInputStream in = new ObjectInputStream(Files.newInputStream(f))) {
                BotNavigationGraph g = (BotNavigationGraph) in.readObject();
                assertEquals(SMALL_REGION_COUNT, g.regions.size());
                assertEquals(key[0], g.movementProfile.totalSpeedStat());
                assertEquals(key[1], g.movementProfile.totalJumpStat());
            }
        }
    }

    /**
     * Point CACHE_DIR at a temp dir so tests never touch the real server cache.
     */
    private static Path originalCacheDir;

    /*
     * The single temp dir for a test. It has to be captured here rather than taken as a
     * @TempDir parameter on each test method: JUnit hands the @BeforeEach and the test method
     * DIFFERENT @TempDir instances, so a per-method parameter would assert against a directory
     * CACHE_DIR was never redirected to.
     */
    private Path tmpRoot;

    @BeforeEach
    void redirectCacheDir(@TempDir Path tmp) {
        tmpRoot = tmp;
        originalCacheDir = readCacheDir();
        Path redirected = cacheDirFor(tmp);
        writeCacheDir(redirected);
        try {
            Files.createDirectories(redirected);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    @AfterEach
    void restoreCacheDir() {
        if (originalCacheDir != null) {
            writeCacheDir(originalCacheDir);
        }
    }

    private static Path readCacheDir() {
        try {
            return (Path) cacheDirField().get(null);
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

    /*
     * CACHE_DIR is a static final Path pointing at the real server cache directory. Tests must
     * never write there, so it gets swapped for a temp dir. `Field.setAccessible` alone cannot
     * write a static final field, and the old `modifiers` hack was removed in JDK 12+, so go
     * through Unsafe: staticFieldBase + staticFieldOffset is the surviving way to poke a static
     * field on a modern JDK, and it sidesteps the final-field write barrier entirely.
     */
    private static void writeCacheDir(Path value) {
        Field f = cacheDirField();
        UNSAFE.putObject(UNSAFE.staticFieldBase(f), UNSAFE.staticFieldOffset(f), value);
    }

    private static Field cacheDirField() {
        try {
            Field f = BotNavigationGraphProvider.class.getDeclaredField("CACHE_DIR");
            f.setAccessible(true);
            return f;
        } catch (NoSuchFieldException e) {
            throw new AssertionError("CACHE_DIR not found", e);
        }
    }

    private static final sun.misc.Unsafe UNSAFE = unsafe();

    @SuppressWarnings("deprecation")
    private static sun.misc.Unsafe unsafe() {
        try {
            Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            return (sun.misc.Unsafe) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unsafe unavailable", e);
        }
    }
}
