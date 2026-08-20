package soloMapling.companion.persistence;

import java.time.Instant;
import java.util.Objects;

/** An immutable row from the host {@code bot_knowledge} table. */
public record CompanionKnowledge(
        long id,
        int companionCharacterId,
        String knowledgeKey,
        String category,
        String content,
        String source,
        int priority,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt
) {

    public CompanionKnowledge {
        if (id <= 0) {
            throw new IllegalArgumentException("id must be positive");
        }
        if (companionCharacterId <= 0) {
            throw new IllegalArgumentException("companionCharacterId must be positive");
        }
        knowledgeKey = requireText(knowledgeKey, "knowledgeKey", 191);
        category = requireText(category, "category", 64);
        content = requireText(content, "content", Integer.MAX_VALUE);
        source = optionalText(source, "source", 255);
        requireSmallInt(priority, "priority");
        createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not precede createdAt");
        }
    }

    static String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(
                    field + " must not exceed " + maxLength + " characters");
        }
        return normalized;
    }

    static String optionalText(String value, String field, int maxLength) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(
                    field + " must not exceed " + maxLength + " characters");
        }
        return normalized.isEmpty() ? null : normalized;
    }

    static void requireSmallInt(int value, String field) {
        if (value < Short.MIN_VALUE || value > Short.MAX_VALUE) {
            throw new IllegalArgumentException(field + " must fit a SMALLINT");
        }
    }
}
