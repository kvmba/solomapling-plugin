package soloMapling.companion.memory;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Selects a stable, relevance-ordered memory window from arbitrary candidates. */
public final class MemorySelector {

    private MemorySelector() {
    }

    /**
     * Returns at most {@code limit} active memories whose strength is at least
     * {@code minimumStrength}. Ties are resolved by recency, then memory ID.
     */
    public static List<MemoryRecord> select(
            Collection<MemoryRecord> candidates,
            int limit,
            double minimumStrength,
            MemoryScorer.Context context,
            MemoryScorer.Parameters parameters) {
        Objects.requireNonNull(candidates, "candidates must not be null");
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(parameters, "parameters must not be null");
        if (limit < 0) {
            throw new IllegalArgumentException("limit must not be negative");
        }
        if (!Double.isFinite(minimumStrength)
                || minimumStrength < 0.0
                || minimumStrength > 1.0) {
            throw new IllegalArgumentException(
                    "minimumStrength must be finite and within [0, 1]");
        }

        Comparator<MemoryRecord> ranking = Comparator
                .comparingDouble((MemoryRecord memory) ->
                        MemoryScorer.score(memory, context, parameters))
                .reversed()
                .thenComparing(MemoryRecord::recencyAnchor, Comparator.reverseOrder())
                .thenComparing(MemoryRecord::id);

        return candidates.stream()
                .map(memory -> Objects.requireNonNull(
                        memory, "candidates must not contain null"))
                .filter(memory -> !memory.archived())
                .filter(memory -> memory.strength() >= minimumStrength)
                .sorted(ranking)
                .limit(limit)
                .toList();
    }
}
