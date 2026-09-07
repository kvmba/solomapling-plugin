package soloMapling.companion.intake;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.companion.CompanionRoster;
import soloMapling.companion.lifecycle.CompanionLifecycleAccess;
import soloMapling.companion.lifecycle.CompanionLifecycleCoordinator;
import soloMapling.companion.lifecycle.CompanionLifecycleStatus;
import soloMapling.companion.provisioning.CompanionProvisionResult;
import soloMapling.companion.provisioning.CompanionProvisioningService;
import soloMapling.server.MethodScheduler;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Steadily adds new companions to the world: one every interval, until the
 * population reaches its cap.
 *
 * <p>Companions are otherwise only created by hand, with {@code !companion
 * provision}. This is what makes the world fill up on its own, and what makes
 * the beginner island busy: every newcomer is provisioned at Mushroom Town and
 * has to walk the island like a player would.</p>
 *
 * <p>Each arrival is a real account, a real character with its sub-tables, and a
 * profile — one host transaction, plus a spawn. That is why the interval and the
 * cap exist: a thousand of them released at once would starve the connection
 * pool and make the server unresponsive, which is the whole problem the ambient
 * population's spawn throttle solves for bots that are not even persistent.</p>
 *
 * <p>Failures are logged and counted, never fatal. A name that is already taken
 * costs one retry, not the run: the next interval simply draws another.</p>
 */
public final class CompanionIntakeService {

    private static final Logger log = LoggerFactory.getLogger(CompanionIntakeService.class);

    /** Attempts at a free character name before giving up on this interval. */
    private static final int NAME_ATTEMPTS = 5;

    private final CompanionProvisioningService provisioning;
    private final CompanionLifecycleAccess lifecycleAccess;
    private final NameSource names;
    private final long intervalMs;
    private final int maxTotal;
    private final int worldId;
    private final String timezone;
    private final AtomicBoolean running = new AtomicBoolean();

    private int registered;
    private int failed;
    /**
     * Companions the world already held when intake started, from the roster the
     * plugin loaded at startup. max-total is a ceiling on the world's whole
     * companion population, not on how many this process happens to add, so a
     * restart must not top a world of 20 up to 40.
     */
    private final int baseline;

    public CompanionIntakeService(
            CompanionProvisioningService provisioning,
            CompanionLifecycleAccess lifecycleAccess,
            NameSource names,
            long intervalMs,
            int maxTotal,
            int worldId,
            String timezone
    ) {
        this.provisioning = Objects.requireNonNull(provisioning, "provisioning");
        this.lifecycleAccess = Objects.requireNonNull(lifecycleAccess, "lifecycleAccess");
        this.names = Objects.requireNonNull(names, "names");
        if (intervalMs <= 0) {
            throw new IllegalArgumentException("intervalMs must be positive");
        }
        if (maxTotal <= 0) {
            throw new IllegalArgumentException("maxTotal must be positive");
        }
        this.intervalMs = intervalMs;
        this.maxTotal = maxTotal;
        this.worldId = worldId;
        this.timezone = Objects.requireNonNull(timezone, "timezone");
        this.baseline = CompanionRoster.characterIds().size();
    }

    /** Draws character names. Separate so a test can hand out fixed names. */
    @FunctionalInterface
    public interface NameSource {
        String next();
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        log.info("Companion intake started intervalMs={} maxTotal={} worldId={} timezone={}",
                intervalMs, maxTotal, worldId, timezone);
        scheduleNext();
    }

    /** Stops after the current interval; no new companion is registered. */
    public void stop() {
        if (running.compareAndSet(true, false)) {
            log.info("Companion intake stopped added={} failed={} population={}/{}",
                registered, failed, baseline + registered, maxTotal);
        }
    }

    /** One registration attempt. Visible for tests. */
    void registerOne() {
        if (baseline + registered >= maxTotal) {
            log.info("Companion intake reached its cap maxTotal={} existing={} added={} failed={}",
                    maxTotal, baseline, registered, failed);
            stop();
            return;
        }
        for (int attempt = 1; attempt <= NAME_ATTEMPTS; attempt++) {
            String name = names.next();
            if (name == null || name.isBlank()) {
                continue;
            }
            try {
                CompanionProvisionResult result =
                        provisioning.provision(name, null, worldId, timezone);
                registered++;
                log.info("Companion intake registered cid={} name={} population={}/{}",
                        result.characterId(), result.displayName(),
                        baseline + registered, maxTotal);
                spawnNow(result.characterId());
                return;
            } catch (Exception e) {
                // A name collision is ordinary: the pool is finite and a bot can
                // share a name with a player's character. Anything else is worth
                // seeing but must not stop the next interval either.
                log.warn("Companion intake attempt {} failed for name={}: {}",
                        attempt, name, e.toString());
            }
        }
        failed++;
        log.warn("Companion intake skipped this interval after {} failed attempts; failed={}",
                NAME_ATTEMPTS, failed);
    }

    /**
     * Puts the newcomer into the world straight away.
     *
     * <p>Best-effort by design: provisioning already committed a durable account
     * and character, so a spawn failure must not be read as a failed intake and
     * retried — that would create a second companion for the same purpose. The
     * lifecycle coordinator picks it up on its next reconcile anyway; this only
     * saves it the wait. A companion whose routine says offline right now simply
     * stays offline, which is correct.</p>
     */
    private void spawnNow(int characterId) {
        CompanionLifecycleCoordinator lifecycle = lifecycleAccess.current().orElse(null);
        if (lifecycle == null) {
            return;
        }
        try {
            CompanionLifecycleStatus status = lifecycle.spawnNow(characterId);
            log.info("Companion intake spawned cid={} state={} code={}",
                    characterId, status.state(), status.code());
        } catch (Exception e) {
            log.warn("Companion intake spawn deferred cid={}: {}", characterId, e.toString());
        }
    }

    private void scheduleNext() {
        if (!running.get()) {
            return;
        }
        MethodScheduler.runAfterDelay(() -> {
            if (!running.get()) {
                return;
            }
            try {
                registerOne();
            } catch (Throwable t) {
                // A companion that throws past its own catch-all must still not
                // kill the scheduler: one bad spawn would silently end intake.
                log.error("Companion intake iteration failed", t);
            }
            scheduleNext();
        }, intervalMs);
    }

    /** Companions this service has added since it started. */
    public int registered() {
        return registered;
    }

    /** Intervals that produced no companion. */
    public int failed() {
        return failed;
    }
}
