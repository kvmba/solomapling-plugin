package soloMapling.companion.execution;

import java.util.Objects;

/**
 * The immediate outcome of attempting one already-validated companion action.
 */
public record ActionExecutionResult(Status status, String reasonCode, String reason) {

    public enum Status {
        SUCCESS,
        REJECTED,
        DEFERRED,
        FAILED
    }

    public ActionExecutionResult {
        Objects.requireNonNull(status, "status must not be null");
        reasonCode = requireText(reasonCode, "reasonCode");
        reason = requireText(reason, "reason");
    }

    public static ActionExecutionResult success(String reasonCode, String reason) {
        return new ActionExecutionResult(Status.SUCCESS, reasonCode, reason);
    }

    public static ActionExecutionResult rejected(String reasonCode, String reason) {
        return new ActionExecutionResult(Status.REJECTED, reasonCode, reason);
    }

    public static ActionExecutionResult deferred(String reasonCode, String reason) {
        return new ActionExecutionResult(Status.DEFERRED, reasonCode, reason);
    }

    public static ActionExecutionResult failed(String reasonCode, String reason) {
        return new ActionExecutionResult(Status.FAILED, reasonCode, reason);
    }

    public boolean successful() {
        return status == Status.SUCCESS;
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
