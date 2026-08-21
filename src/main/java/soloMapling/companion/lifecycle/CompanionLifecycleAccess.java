package soloMapling.companion.lifecycle;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Instance-scoped, thread-safe handoff from extension startup to command handling.
 */
public final class CompanionLifecycleAccess {
    private final AtomicReference<CompanionLifecycleCoordinator> coordinator =
            new AtomicReference<>();

    public void register(CompanionLifecycleCoordinator value) {
        Objects.requireNonNull(value, "value");
        if (!coordinator.compareAndSet(null, value)) {
            throw new IllegalStateException("Companion lifecycle is already registered");
        }
    }

    public Optional<CompanionLifecycleCoordinator> current() {
        return Optional.ofNullable(coordinator.get());
    }

    public void clear(CompanionLifecycleCoordinator expected) {
        if (expected != null) {
            coordinator.compareAndSet(expected, null);
        }
    }

    public void clear() {
        coordinator.set(null);
    }
}
