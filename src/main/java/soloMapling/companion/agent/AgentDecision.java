package soloMapling.companion.agent;

import java.util.List;
import java.util.Objects;

/** Immutable output of the companion planning boundary. */
public record AgentDecision(
        int schemaVersion,
        String reply,
        String reason,
        List<CompanionAction> actions) {

    public static final int CURRENT_SCHEMA_VERSION = 1;
    public static final int MAX_ACTIONS = 8;
    public static final int MAX_REASON_LENGTH = 500;

    public AgentDecision {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported schemaVersion: " + schemaVersion);
        }
        reply = boundedText(reply, "reply", CompanionAction.MAX_CHAT_LENGTH, true);
        reason = boundedText(reason, "reason", MAX_REASON_LENGTH, false);
        Objects.requireNonNull(actions, "actions must not be null");
        if (actions.size() > MAX_ACTIONS) {
            throw new IllegalArgumentException("actions exceeds maximum of " + MAX_ACTIONS);
        }
        actions = List.copyOf(actions);
        if (actions.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("actions must not contain null");
        }
    }

    private static String boundedText(
            String value, String field, int maxLength, boolean allowBlank) {
        Objects.requireNonNull(value, field + " must not be null");
        String normalized = value.trim();
        if (!allowBlank && normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " exceeds " + maxLength + " characters");
        }
        return normalized;
    }
}
