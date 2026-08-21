package soloMapling.companion.memory;

import java.time.Instant;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Immutable memory value object. Actor and map keys are optional and represented
 * by {@code null}; tags are always a canonical, immutable set.
 */
public record MemoryRecord(
        String id,
        MemoryType type,
        String content,
        double salience,
        double strength,
        Instant occurredAt,
        Instant lastRecalledAt,
        String actorKey,
        String mapKey,
        Set<String> tags,
        boolean archived) {

    public MemoryRecord {
        id = requireText(id, "id");
        type = Objects.requireNonNull(type, "type must not be null");
        content = requireText(content, "content");
        salience = requireUnitInterval(salience, "salience");
        strength = requireUnitInterval(strength, "strength");
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        if (lastRecalledAt != null && lastRecalledAt.isBefore(occurredAt)) {
            throw new IllegalArgumentException("lastRecalledAt must not precede occurredAt");
        }
        actorKey = optionalText(actorKey, "actorKey");
        mapKey = optionalText(mapKey, "mapKey");
        tags = canonicalTags(tags);
    }

    /** The latest instant from which recency decay should be measured. */
    public Instant recencyAnchor() {
        return lastRecalledAt == null ? occurredAt : lastRecalledAt;
    }

    private static Set<String> canonicalTags(Set<String> values) {
        Objects.requireNonNull(values, "tags must not be null");
        TreeSet<String> canonical = new TreeSet<>();
        for (String value : values) {
            canonical.add(requireText(value, "tag"));
        }
        return Collections.unmodifiableSet(canonical);
    }

    private static double requireUnitInterval(double value, String field) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(field + " must be finite and within [0, 1]");
        }
        return value;
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
