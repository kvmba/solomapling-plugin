package soloMapling.ArtificialPlayer.BotAttackSystem;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source-level guards for the per-tick memoisation added by the performance pass.
 *
 * <p>{@code BotAttackConfig} and {@code BotBuffConfig} both touch {@code org.gms.client.Job},
 * whose class initialisation needs a Spring context, so a plain unit test cannot call
 * {@code resolve(...)} / {@code buffsForJob(...)}. These checks pin the two properties that make
 * the memoisation safe, and that a future edit must not quietly drop:</p>
 *
 * <ol>
 *   <li>the cached value is produced by a PURE table walk - no per-call input other than
 *       (job, weapon) may leak into it, otherwise a cache would serve the wrong attacks;</li>
 *   <li>the caches are keyed by enum ordinal / job, i.e. finite and bounded, and the buff list is
 *       published through a concurrent structure (readers run on bot ticks).</li>
 * </ol>
 */
class BotAttackMemoisationSourceTest {

    private static final Path ATTACK_CONFIG = Paths.get(
            "src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotAttackConfig.java");
    private static final Path BUFF_CONFIG = Paths.get(
            "src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotBuffConfig.java");

    @Test
    void resolveKeepsItsCacheAndStillFallsBackUncached() throws IOException {
        String code = read(ATTACK_CONFIG);
        assertTrue(code.contains("cacheValue(job,"), "resolve must consult the per-pair cache");
        assertTrue(code.contains("cachePut(job,"), "a miss must publish the resolved pair");
        // The result must depend on the job + weapon only. These are the inputs resolve() reads;
        // none of them may be folded into the cache key or the cached object.
        assertFalse(code.contains("getLevel()"),
                "resolve must stay level-independent, or the memoised rows go stale on level-up");
        assertFalse(code.contains("getMap()"),
                "resolve must stay map-independent, or the memoised rows go stale on map change");
    }

    @Test
    void buffCacheIsBoundedAndConcurrentlyPublished() throws IOException {
        String code = read(BUFF_CONFIG);
        assertTrue(code.contains("AtomicReferenceArray"),
                "buffsForJob is read from bot ticks; publish the per-job list atomically");
        assertTrue(code.contains("Job.values().length"),
                "the cache must be keyed by the finite Job ordinal space");
        assertTrue(code.contains("Collections.unmodifiableList"),
                "callers must not be able to mutate the shared cached list");
    }

    private static String read(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8);
    }
}
