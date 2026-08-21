package soloMapling.companion.lifecycle;

import java.time.Instant;
import java.util.Objects;

public record CompanionLifecycleStatus(
        int characterId,
        State state,
        boolean desiredOnline,
        boolean loaded,
        String code,
        String detail,
        Instant observedAt
) {
    public CompanionLifecycleStatus {
        if (characterId <= 0) {
            throw new IllegalArgumentException("characterId must be positive");
        }
        state = Objects.requireNonNull(state, "state");
        code = Objects.requireNonNullElse(code, "");
        detail = Objects.requireNonNullElse(detail, "");
        observedAt = Objects.requireNonNull(observedAt, "observedAt");
    }

    public enum State {
        ONLINE,
        OFFLINE,
        INVALID_ROUTINE,
        FAILED,
        STOPPED
    }
}
