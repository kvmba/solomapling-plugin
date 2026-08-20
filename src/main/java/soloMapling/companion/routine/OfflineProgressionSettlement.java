package soloMapling.companion.routine;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Auditable ordinary-currency settlement. It intentionally has no item or boss fields.
 */
public record OfflineProgressionSettlement(
        long experience,
        long mesos,
        Duration elapsed,
        Duration creditedElapsed,
        Instant settledThrough,
        String reason) {

    public OfflineProgressionSettlement {
        if (experience < 0 || mesos < 0) {
            throw new IllegalArgumentException("rewards must not be negative");
        }
        Objects.requireNonNull(elapsed, "elapsed must not be null");
        Objects.requireNonNull(creditedElapsed, "creditedElapsed must not be null");
        Objects.requireNonNull(settledThrough, "settledThrough must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        if (elapsed.isNegative() || creditedElapsed.isNegative()
                || creditedElapsed.compareTo(elapsed) > 0) {
            throw new IllegalArgumentException("credited elapsed must be within elapsed");
        }
        if (reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
    }

    public boolean wasElapsedCapped() {
        return creditedElapsed.compareTo(elapsed) < 0;
    }
}
