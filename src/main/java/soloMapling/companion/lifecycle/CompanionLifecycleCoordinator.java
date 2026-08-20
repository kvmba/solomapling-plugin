package soloMapling.companion.lifecycle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.companion.persistence.CompanionProfile;
import soloMapling.companion.persistence.CompanionProfileRepository;
import soloMapling.companion.routine.OfflineProgressionPolicy;
import soloMapling.companion.routine.OfflineProgressionSettlement;
import soloMapling.companion.routine.PersonaProgressionProfile;
import soloMapling.companion.routine.RoutineActivity;
import soloMapling.companion.routine.RoutineProfileCodec;
import soloMapling.companion.routine.RoutineProfileCodec.RoutineProfileParseException;
import soloMapling.companion.routine.RoutineSchedule;
import soloMapling.server.MethodScheduler;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns the persistent companion online/offline lifecycle.
 */
public final class CompanionLifecycleCoordinator {
    public static final Duration DEFAULT_RECONCILE_INTERVAL = Duration.ofSeconds(60);

    private static final Logger log =
            LoggerFactory.getLogger(CompanionLifecycleCoordinator.class);

    private final CompanionProfileRepository profiles;
    private final CompanionRuntimeAdapter runtime;
    private final OfflineProgressionPolicy progressionPolicy;
    private final Clock clock;
    private final Duration reconcileInterval;
    private final DelayedScheduler scheduler;
    private final ConcurrentMap<Integer, CompanionRuntimeAdapter.LoadedCompanion> online =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<Integer, CompanionRuntimeAdapter.LoadedCompanion> residualLoaded =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<Integer, CompanionProfile> knownProfiles =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<Integer, CompanionLifecycleStatus> statuses =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<Integer, Object> profileLocks = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean reconciling = new AtomicBoolean();

    public CompanionLifecycleCoordinator(
            CompanionProfileRepository profiles,
            CompanionRuntimeAdapter runtime,
            OfflineProgressionPolicy progressionPolicy,
            Clock clock) {
        this(profiles, runtime, progressionPolicy, clock, DEFAULT_RECONCILE_INTERVAL,
                MethodScheduler::runAfterDelay);
    }

    public CompanionLifecycleCoordinator(
            CompanionProfileRepository profiles,
            CompanionRuntimeAdapter runtime,
            OfflineProgressionPolicy progressionPolicy,
            Clock clock,
            Duration reconcileInterval,
            DelayedScheduler scheduler) {
        this.profiles = java.util.Objects.requireNonNull(profiles, "profiles");
        this.runtime = java.util.Objects.requireNonNull(runtime, "runtime");
        this.progressionPolicy = java.util.Objects.requireNonNull(
                progressionPolicy, "progressionPolicy");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.reconcileInterval = java.util.Objects.requireNonNull(
                reconcileInterval, "reconcileInterval");
        this.scheduler = java.util.Objects.requireNonNull(scheduler, "scheduler");
        if (reconcileInterval.isNegative() || reconcileInterval.isZero()) {
            throw new IllegalArgumentException("reconcileInterval must be positive");
        }
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        reconcileNow();
        scheduleNext();
    }

    public void reconcileNow() {
        if (!running.get() || !reconciling.compareAndSet(false, true)) {
            return;
        }
        try {
            reconcileEnabledProfiles();
        } catch (Throwable exception) {
            log.error("Persistent companion reconcile failed before profile isolation", exception);
        } finally {
            reconciling.set(false);
        }
    }

    public CompanionLifecycleStatus spawnNow(int characterId) throws SQLException {
        CompanionProfile profile = profiles.findByCharacterId(characterId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Companion profile not found: " + characterId));
        if (!profile.enabled() || !"active".equals(profile.status())) {
            throw new IllegalStateException("Companion profile is not enabled and active");
        }
        knownProfiles.put(characterId, profile);
        RoutineSchedule schedule = RoutineProfileCodec.parse(
                profile.routineTimezone(), profile.routineProfile());
        return withProfileLock(characterId, () -> spawnInternal(
                profile, schedule.activityAt(clock.instant()), clock.instant()));
    }

    public CompanionLifecycleStatus despawnNow(int characterId) {
        Instant now = clock.instant();
        return withProfileLock(characterId, () -> despawnInternal(
                characterId, knownProfiles.get(characterId), now, "MANUAL_DESPAWN"));
    }

    public Optional<CompanionLifecycleStatus> status(int characterId) {
        return Optional.ofNullable(statuses.get(characterId));
    }

    public Map<Integer, CompanionLifecycleStatus> status() {
        return Map.copyOf(statuses);
    }

    public Optional<String> buildDiagnostics(int characterId) throws SQLException {
        CompanionRuntimeAdapter.LoadedCompanion loaded = online.get(characterId);
        if (loaded == null) {
            return Optional.empty();
        }
        CompanionProfile profile = profiles.findByCharacterId(characterId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Companion profile not found: " + characterId));
        return Optional.of(runtime.buildDiagnostics(loaded, profile));
    }

    public RoutineSchedule schedule(int characterId) throws SQLException {
        CompanionProfile profile = profiles.findByCharacterId(characterId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Companion profile not found: " + characterId));
        return RoutineProfileCodec.parse(profile.routineTimezone(), profile.routineProfile());
    }

    /**
     * Persists one currently online companion without changing desired state or reconciling.
     */
    public void checkpointNow(int characterId) {
        withProfileLock(characterId, () -> {
            CompanionRuntimeAdapter.LoadedCompanion loaded = online.get(characterId);
            if (loaded == null) {
                throw new IllegalStateException("Companion is not online: " + characterId);
            }
            runtime.saveCheckpoint(loaded);
            return null;
        });
    }

    /**
     * Stops future reconciliation and synchronously best-effort saves every loaded companion.
     */
    public void stop() {
        running.set(false);
        Instant now = clock.instant();
        Set<Integer> shutdownIds = new HashSet<>(knownProfiles.keySet());
        shutdownIds.addAll(online.keySet());
        shutdownIds.addAll(residualLoaded.keySet());
        for (Integer characterId : shutdownIds) {
            try {
                withProfileLock(characterId, () -> despawnInternal(
                        characterId, knownProfiles.get(characterId), now, "SHUTDOWN"));
            } catch (Throwable exception) {
                log.error("Failed saving companion {} during shutdown", characterId, exception);
                putStatus(characterId, CompanionLifecycleStatus.State.FAILED,
                        false, isLoaded(characterId), "SHUTDOWN_SAVE_FAILED",
                        exception.toString(), now);
            }
        }
    }

    private void reconcileEnabledProfiles() throws SQLException {
        Instant now = clock.instant();
        List<CompanionProfile> enabledProfiles = profiles.findEnabled();
        Set<Integer> enabledIds = new HashSet<>();
        for (CompanionProfile profile : enabledProfiles) {
            enabledIds.add(profile.characterId());
            knownProfiles.put(profile.characterId(), profile);
            try {
                reconcileProfile(profile, now);
            } catch (Throwable exception) {
                log.error("Companion {} reconcile failed; continuing with remaining profiles",
                        profile.characterId(), exception);
                putStatus(profile.characterId(), CompanionLifecycleStatus.State.FAILED,
                        exception instanceof SpawnFailureException,
                        isLoaded(profile.characterId()), "RECONCILE_FAILED",
                        exception.toString(), now);
            }
        }

        Set<Integer> loadedIds = new HashSet<>(online.keySet());
        loadedIds.addAll(residualLoaded.keySet());
        for (Integer characterId : loadedIds) {
            if (!enabledIds.contains(characterId)) {
                try {
                    withProfileLock(characterId, () -> despawnInternal(
                            characterId, knownProfiles.get(characterId), now,
                            "PROFILE_NOT_ACTIVE"));
                } catch (Throwable exception) {
                    log.error("Failed removing inactive companion {}", characterId, exception);
                }
            }
        }
    }

    private void reconcileProfile(CompanionProfile profile, Instant now) {
        final RoutineSchedule schedule;
        try {
            schedule = RoutineProfileCodec.parse(
                    profile.routineTimezone(), profile.routineProfile());
        } catch (RoutineProfileParseException exception) {
            withProfileLock(profile.characterId(), () -> {
                String detail = exception.getMessage();
                if (isLoaded(profile.characterId())) {
                    try {
                        despawnInternal(profile.characterId(), profile, now, "INVALID_ROUTINE");
                    } catch (Throwable isolationFailure) {
                        detail += "; isolation failure=" + isolationFailure;
                        log.error("Failed taking invalid-routine companion {} offline",
                                profile.characterId(), isolationFailure);
                    }
                }
                return putStatus(profile.characterId(),
                        CompanionLifecycleStatus.State.INVALID_ROUTINE,
                        false, isLoaded(profile.characterId()),
                        exception.code(), detail, now);
            });
            log.warn("Companion {} routine isolated: {} {}",
                    profile.characterId(), exception.code(), exception.getMessage());
            return;
        }

        RoutineActivity activity = schedule.activityAt(now);
        boolean shouldBeOnline = shouldBeOnline(activity);
        withProfileLock(profile.characterId(), () -> {
            if (!running.get()) {
                return statusOrStopped(profile.characterId(), now);
            }
            cleanupResidual(profile.characterId());
            if (shouldBeOnline) {
                CompanionRuntimeAdapter.LoadedCompanion loaded = online.get(profile.characterId());
                if (loaded == null) {
                    return spawnInternal(profile, activity, now);
                }
                CompanionRuntimeAdapter.CareerReconciliation career =
                        runtime.reconcileCareer(loaded, profile);
                if (career.changed()) {
                    runtime.saveCheckpoint(loaded);
                }
                return putStatus(profile.characterId(),
                        CompanionLifecycleStatus.State.ONLINE,
                        true, true, "ALREADY_ONLINE",
                        activity.name() + ";careerAdvancements=" + career.advancements()
                                + ";apSpent=" + career.apSpent()
                                + ";spSpent=" + career.spSpent()
                                + ";" + career.detail(), now);
            }
            return online.containsKey(profile.characterId())
                    ? despawnInternal(profile.characterId(), profile, now,
                            "ROUTINE_" + activity.name())
                    : putStatus(profile.characterId(),
                            CompanionLifecycleStatus.State.OFFLINE,
                            false, false, "ROUTINE_" + activity.name(), "", now);
        });
    }

    private CompanionLifecycleStatus spawnInternal(
            CompanionProfile profile,
            RoutineActivity activity,
            Instant now) {
        CompanionRuntimeAdapter.LoadedCompanion existing = online.get(profile.characterId());
        if (existing != null) {
            return putStatus(profile.characterId(), CompanionLifecycleStatus.State.ONLINE,
                    true, true, "ALREADY_ONLINE", "", now);
        }

        CompanionRuntimeAdapter.LoadedCompanion loaded = null;
        OfflineProgressionSettlement settlement = null;
        boolean checkpointSaved = false;
        try {
            int persistedLevel = runtime.persistedLevel(profile);
            settlement = settle(profile, persistedLevel, now);
            loaded = runtime.load(profile);
            CompanionRuntimeAdapter.CareerReconciliation career =
                    runtime.reconcileCareer(loaded, profile);
            runtime.applyProgression(loaded, settlement);
            career = career.plus(runtime.reconcileCareer(loaded, profile));
            // Character and profile persistence are separate transactions. Saving the
            // reward first leaves a bounded duplicate-on-crash window, never a lost reward.
            runtime.saveCheckpoint(loaded);
            checkpointSaved = true;
            runtime.attachAndStart(loaded);
            online.put(profile.characterId(), loaded);
            profiles.updateRuntimeState(
                    profile.characterId(), "online", now, settlement.settledThrough());
            return putStatus(profile.characterId(), CompanionLifecycleStatus.State.ONLINE,
                    true, true, "SPAWNED",
                    "activity=" + activity + ";settlementActivity=OFFLINE"
                            + ";exp=" + settlement.experience()
                            + ";mesos=" + settlement.mesos()
                            + ";careerAdvancements=" + career.advancements()
                            + ";apSpent=" + career.apSpent()
                            + ";spSpent=" + career.spSpent()
                            + ";" + career.detail(), now);
        } catch (Throwable exception) {
            online.remove(profile.characterId());
            if (loaded != null) {
                try {
                    runtime.stopSaveAndRemove(loaded);
                    residualLoaded.remove(profile.characterId());
                } catch (Throwable cleanupFailure) {
                    exception.addSuppressed(cleanupFailure);
                    residualLoaded.put(profile.characterId(), loaded);
                }
            }
            try {
                profiles.updateRuntimeState(
                        profile.characterId(), "offline", null,
                        checkpointSaved && settlement != null ? settlement.settledThrough() : null);
            } catch (Throwable profileRollbackFailure) {
                exception.addSuppressed(profileRollbackFailure);
            }
            putStatus(profile.characterId(), CompanionLifecycleStatus.State.FAILED,
                    true, isLoaded(profile.characterId()), "SPAWN_FAILED",
                    exception.toString(), now);
            throw new SpawnFailureException(exception);
        }
    }

    private OfflineProgressionSettlement settle(
            CompanionProfile profile,
            int level,
            Instant now) {
        Instant since = profile.lastSettledAt();
        if (since == null || since.isAfter(now)) {
            return new OfflineProgressionSettlement(
                    0, 0, Duration.ZERO, Duration.ZERO, now,
                    since == null ? "first lifecycle settlement" : "future settlement timestamp reset");
        }
        return progressionPolicy.settle(
                since, now, Math.max(1, level), PersonaProgressionProfile.NEUTRAL,
                RoutineActivity.OFFLINE);
    }

    private CompanionLifecycleStatus despawnInternal(
            int characterId,
            CompanionProfile profile,
            Instant now,
            String code) {
        CompanionRuntimeAdapter.LoadedCompanion loaded = online.get(characterId);
        if (loaded == null) {
            loaded = residualLoaded.get(characterId);
        }
        if (loaded == null) {
            return putStatus(characterId, CompanionLifecycleStatus.State.OFFLINE,
                    false, false, "ALREADY_OFFLINE", "", now);
        }

        runtime.stopSaveAndRemove(loaded);
        online.remove(characterId, loaded);
        residualLoaded.remove(characterId, loaded);
        if (profile != null) {
            try {
                profiles.updateRuntimeState(characterId, "offline", null, now);
            } catch (SQLException exception) {
                throw new IllegalStateException(
                        "Character saved but profile timestamp update failed", exception);
            }
        }
        return putStatus(characterId, CompanionLifecycleStatus.State.OFFLINE,
                false, false, code, "", now);
    }

    private static boolean shouldBeOnline(RoutineActivity activity) {
        return activity != RoutineActivity.OFFLINE && activity != RoutineActivity.SLEEP;
    }

    private void cleanupResidual(int characterId) {
        CompanionRuntimeAdapter.LoadedCompanion residual = residualLoaded.get(characterId);
        if (residual == null) {
            return;
        }
        runtime.stopSaveAndRemove(residual);
        residualLoaded.remove(characterId, residual);
    }

    private boolean isLoaded(int characterId) {
        return online.containsKey(characterId) || residualLoaded.containsKey(characterId);
    }

    private void scheduleNext() {
        if (!running.get()) {
            return;
        }
        scheduler.runAfterDelay(() -> {
            if (!running.get()) {
                return;
            }
            reconcileNow();
            scheduleNext();
        }, reconcileInterval.toMillis());
    }

    private CompanionLifecycleStatus statusOrStopped(int characterId, Instant now) {
        return putStatus(characterId, CompanionLifecycleStatus.State.STOPPED,
                false, isLoaded(characterId), "COORDINATOR_STOPPED", "", now);
    }

    private CompanionLifecycleStatus putStatus(
            int characterId,
            CompanionLifecycleStatus.State state,
            boolean desiredOnline,
            boolean loaded,
            String code,
            String detail,
            Instant observedAt) {
        CompanionLifecycleStatus status = new CompanionLifecycleStatus(
                characterId, state, desiredOnline, loaded, code, detail, observedAt);
        statuses.put(characterId, status);
        return status;
    }

    private <T> T withProfileLock(int characterId, LockedOperation<T> operation) {
        Object lock = profileLocks.computeIfAbsent(characterId, ignored -> new Object());
        synchronized (lock) {
            return operation.run();
        }
    }

    private static final class SpawnFailureException extends RuntimeException {
        private SpawnFailureException(Throwable cause) {
            super("Companion spawn failed", cause);
        }
    }

    @FunctionalInterface
    public interface DelayedScheduler {
        void runAfterDelay(Runnable task, long delayMilliseconds);
    }

    @FunctionalInterface
    private interface LockedOperation<T> {
        T run();
    }
}
