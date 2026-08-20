package soloMapling.companion.memory;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Deterministic, side-effect-free relevance scoring for companion memories. */
public final class MemoryScorer {

    private static final double LN_2 = Math.log(2.0);

    private MemoryScorer() {
    }

    /**
     * Scores a memory as:
     * {@code weighted quality * half-life decay * (1 + matching boosts)}.
     */
    public static double score(MemoryRecord memory, Context context, Parameters parameters) {
        Objects.requireNonNull(memory, "memory must not be null");
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(parameters, "parameters must not be null");

        double quality = parameters.salienceWeight() * memory.salience()
                + parameters.strengthWeight() * memory.strength();
        double decay = decay(memory, context.referenceTime(), parameters);

        double boost = 1.0;
        if (memory.actorKey() != null && memory.actorKey().equals(context.actorKey())) {
            boost += parameters.actorMatchBoost();
        }
        if (memory.mapKey() != null && memory.mapKey().equals(context.mapKey())) {
            boost += parameters.mapMatchBoost();
        }
        if (!Collections.disjoint(memory.tags(), context.tags())) {
            boost += parameters.tagMatchBoost();
        }
        return quality * decay * boost;
    }

    private static double decay(
            MemoryRecord memory, Instant referenceTime, Parameters parameters) {
        Duration age = Duration.between(memory.recencyAnchor(), referenceTime);
        double ageSeconds = Math.max(0.0, age.getSeconds() + age.getNano() / 1_000_000_000.0);
        Duration halfLife = memory.type() == MemoryType.COMMITMENT
                ? parameters.commitmentHalfLife()
                : parameters.regularHalfLife();
        double halfLifeSeconds =
                halfLife.getSeconds() + halfLife.getNano() / 1_000_000_000.0;
        return Math.exp(-LN_2 * ageSeconds / halfLifeSeconds);
    }

    public record Context(
            Instant referenceTime,
            String actorKey,
            String mapKey,
            Set<String> tags) {

        public Context {
            referenceTime = Objects.requireNonNull(
                    referenceTime, "referenceTime must not be null");
            actorKey = optionalText(actorKey, "actorKey");
            mapKey = optionalText(mapKey, "mapKey");
            Objects.requireNonNull(tags, "tags must not be null");
            TreeSet<String> canonicalTags = new TreeSet<>();
            for (String tag : tags) {
                canonicalTags.add(requireText(tag, "tag"));
            }
            tags = Collections.unmodifiableSet(canonicalTags);
        }
    }

    public record Parameters(
            double salienceWeight,
            double strengthWeight,
            Duration regularHalfLife,
            Duration commitmentHalfLife,
            double actorMatchBoost,
            double mapMatchBoost,
            double tagMatchBoost) {

        public Parameters {
            requireNonNegativeFinite(salienceWeight, "salienceWeight");
            requireNonNegativeFinite(strengthWeight, "strengthWeight");
            if (salienceWeight + strengthWeight == 0.0) {
                throw new IllegalArgumentException(
                        "at least one quality weight must be positive");
            }
            regularHalfLife = requirePositive(regularHalfLife, "regularHalfLife");
            commitmentHalfLife = requirePositive(
                    commitmentHalfLife, "commitmentHalfLife");
            if (commitmentHalfLife.compareTo(regularHalfLife) < 0) {
                throw new IllegalArgumentException(
                        "commitmentHalfLife must be at least regularHalfLife");
            }
            requireNonNegativeFinite(actorMatchBoost, "actorMatchBoost");
            requireNonNegativeFinite(mapMatchBoost, "mapMatchBoost");
            requireNonNegativeFinite(tagMatchBoost, "tagMatchBoost");
        }

        public static Parameters defaults() {
            return new Parameters(
                    0.6,
                    0.4,
                    Duration.ofDays(14),
                    Duration.ofDays(90),
                    0.35,
                    0.25,
                    0.20);
        }
    }

    private static Duration requirePositive(Duration value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }

    private static void requireNonNegativeFinite(double value, String field) {
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException(field + " must be finite and non-negative");
        }
    }

    private static String optionalText(String value, String field) {
        return value == null ? null : requireText(value, field);
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
