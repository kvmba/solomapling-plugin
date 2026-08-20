package soloMapling.companion.memory;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryScorerTest {

    private static final Instant OCCURRED = Instant.parse("2026-01-01T00:00:00Z");
    private static final MemoryScorer.Parameters PARAMETERS = new MemoryScorer.Parameters(
            0.6,
            0.4,
            Duration.ofDays(10),
            Duration.ofDays(100),
            0.5,
            0.3,
            0.2);

    @Test
    void regularMemoryFadesWhileCommitmentEndures() {
        Instant afterOneHundredDays = OCCURRED.plus(Duration.ofDays(100));
        MemoryScorer.Context context =
                new MemoryScorer.Context(afterOneHundredDays, null, null, Set.of());
        MemoryRecord episode = memory("episode", MemoryType.EPISODIC, null);
        MemoryRecord commitment = memory("promise", MemoryType.COMMITMENT, null);

        double episodeScore = MemoryScorer.score(episode, context, PARAMETERS);
        double commitmentScore = MemoryScorer.score(commitment, context, PARAMETERS);

        assertEquals(0.8 / 1024.0, episodeScore, 1.0e-12);
        assertEquals(0.4, commitmentScore, 1.0e-12);
        assertTrue(commitmentScore > episodeScore * 500);
    }

    @Test
    void recentRecallRefreshesTheDecayAnchor() {
        Instant recalled = OCCURRED.plus(Duration.ofDays(99));
        MemoryRecord recalledEpisode = memory("recalled", MemoryType.EPISODIC, recalled);
        MemoryScorer.Context context = new MemoryScorer.Context(
                OCCURRED.plus(Duration.ofDays(100)), null, null, Set.of());

        assertEquals(
                0.8 * Math.pow(0.5, 0.1),
                MemoryScorer.score(recalledEpisode, context, PARAMETERS),
                1.0e-12);
    }

    @Test
    void actorMapAndTagMatchesEachRaiseTheScore() {
        MemoryRecord memory = new MemoryRecord(
                "matched",
                MemoryType.SEMANTIC,
                "The actor likes this map.",
                0.8,
                0.8,
                OCCURRED,
                null,
                "actor-7",
                "henesys",
                Set.of("friendship", "town"),
                false);
        double baseline = MemoryScorer.score(
                memory,
                new MemoryScorer.Context(OCCURRED, null, null, Set.of()),
                PARAMETERS);

        double actor = MemoryScorer.score(
                memory,
                new MemoryScorer.Context(OCCURRED, "actor-7", null, Set.of()),
                PARAMETERS);
        double map = MemoryScorer.score(
                memory,
                new MemoryScorer.Context(OCCURRED, null, "henesys", Set.of()),
                PARAMETERS);
        double tag = MemoryScorer.score(
                memory,
                new MemoryScorer.Context(OCCURRED, null, null, Set.of("friendship")),
                PARAMETERS);

        assertEquals(baseline * 1.5, actor, 1.0e-12);
        assertEquals(baseline * 1.3, map, 1.0e-12);
        assertEquals(baseline * 1.2, tag, 1.0e-12);
    }

    @Test
    void memoryValueObjectValidatesUnitIntervalsAndTimeOrder() {
        assertThrows(IllegalArgumentException.class,
                () -> new MemoryRecord(
                        "bad",
                        MemoryType.EPISODIC,
                        "content",
                        1.1,
                        0.5,
                        OCCURRED,
                        null,
                        null,
                        null,
                        Set.of(),
                        false));
        assertThrows(IllegalArgumentException.class,
                () -> new MemoryRecord(
                        "bad-time",
                        MemoryType.EPISODIC,
                        "content",
                        0.5,
                        0.5,
                        OCCURRED,
                        OCCURRED.minusSeconds(1),
                        null,
                        null,
                        Set.of(),
                        false));
    }

    private static MemoryRecord memory(
            String id, MemoryType type, Instant lastRecalledAt) {
        return new MemoryRecord(
                id,
                type,
                "Remember this.",
                0.8,
                0.8,
                OCCURRED,
                lastRecalledAt,
                null,
                null,
                Set.of(),
                false);
    }
}
