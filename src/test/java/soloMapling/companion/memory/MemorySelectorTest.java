package soloMapling.companion.memory;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MemorySelectorTest {

    private static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");
    private static final MemoryScorer.Context CONTEXT =
            new MemoryScorer.Context(NOW, null, null, Set.of());

    @Test
    void topKIsStableAcrossCandidateIterationOrder() {
        MemoryRecord alpha = memory("alpha", 0.8, false);
        MemoryRecord beta = memory("beta", 0.8, false);
        MemoryRecord gamma = memory("gamma", 0.8, false);

        List<String> first = selectedIds(List.of(gamma, alpha, beta), 2);
        List<String> second = selectedIds(List.of(beta, gamma, alpha), 2);

        assertEquals(List.of("alpha", "beta"), first);
        assertEquals(first, second);
    }

    @Test
    void filtersArchivedAndWeakMemoriesBeforeRanking() {
        MemoryRecord archived = memory("archived", 1.0, true);
        MemoryRecord weak = memory("weak", 0.19, false);
        MemoryRecord eligible = memory("eligible", 0.2, false);

        assertEquals(
                List.of("eligible"),
                selectedIds(List.of(archived, weak, eligible), 10));
    }

    @Test
    void rejectsInvalidSelectionArguments() {
        assertThrows(IllegalArgumentException.class,
                () -> MemorySelector.select(
                        List.of(), -1, 0.2, CONTEXT, MemoryScorer.Parameters.defaults()));
        assertThrows(IllegalArgumentException.class,
                () -> MemorySelector.select(
                        List.of(), 1, 1.01, CONTEXT, MemoryScorer.Parameters.defaults()));
    }

    private static List<String> selectedIds(List<MemoryRecord> memories, int limit) {
        return MemorySelector.select(
                        memories,
                        limit,
                        0.2,
                        CONTEXT,
                        MemoryScorer.Parameters.defaults())
                .stream()
                .map(MemoryRecord::id)
                .toList();
    }

    private static MemoryRecord memory(String id, double strength, boolean archived) {
        return new MemoryRecord(
                id,
                MemoryType.SEMANTIC,
                "Memory " + id,
                0.8,
                strength,
                NOW,
                null,
                null,
                null,
                Set.of(),
                archived);
    }
}
