package soloMapling.companion.persistence;

import java.time.Instant;
import java.util.Objects;

public record CompanionRelationship(
        long id,
        int companionCharacterId,
        int relatedCharacterId,
        String relationshipType,
        int familiarity,
        int trust,
        int affinity,
        long interactionCount,
        String summary,
        Instant lastInteractionAt,
        Instant createdAt,
        Instant updatedAt
) {
    public CompanionRelationship {
        if (id <= 0) {
            throw new IllegalArgumentException("id must be positive");
        }
        requirePositive(companionCharacterId, "companionCharacterId");
        requirePositive(relatedCharacterId, "relatedCharacterId");
        relationshipType = requireText(relationshipType, "relationshipType");
        if (interactionCount < 0) {
            throw new IllegalArgumentException("interactionCount must not be negative");
        }
        summary = Objects.requireNonNullElse(summary, "");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    private static void requirePositive(int value, String field) {
        if (value <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
