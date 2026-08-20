package soloMapling.companion.lifecycle;

import org.junit.jupiter.api.Test;
import soloMapling.companion.persistence.CompanionProfile;
import soloMapling.companion.persistence.CompanionProfileRepository;
import soloMapling.companion.routine.OfflineProgressionPolicy;
import soloMapling.companion.routine.OfflineProgressionSettlement;
import soloMapling.companion.routine.RoutineActivity;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionLifecycleCoordinatorTest {
    private static final Instant NOW = Instant.parse("2026-08-20T12:00:00Z");
    private static final String ONLINE = "v1|00:00-23:59=TRAIN";

    @Test
    void startsProfileWhoseScheduleIsOnline() {
        FakeRepository repository = new FakeRepository(profile(11, ONLINE, NOW.minusSeconds(60)));
        FakeRuntime runtime = new FakeRuntime();
        CompanionLifecycleCoordinator coordinator = coordinator(repository, runtime);

        coordinator.start();

        assertEquals(1, runtime.loads.getOrDefault(11, 0));
        assertEquals(1, runtime.starts.getOrDefault(11, 0));
        assertEquals(CompanionLifecycleStatus.State.ONLINE,
                coordinator.status(11).orElseThrow().state());
    }

    @Test
    void capsOfflineSettlementAndConsumesWholeObservedInterval() {
        FakeRepository repository =
                new FakeRepository(profile(12, ONLINE, NOW.minus(Duration.ofDays(30))));
        FakeRuntime runtime = new FakeRuntime();
        CompanionLifecycleCoordinator coordinator = new CompanionLifecycleCoordinator(
                repository, runtime, tinyCapPolicy(), fixedClock(),
                Duration.ofSeconds(60), (task, delay) -> { });

        coordinator.start();

        OfflineProgressionSettlement settlement = runtime.settlements.get(12);
        assertNotNull(settlement);
        assertEquals(Duration.ofHours(1), settlement.creditedElapsed());
        assertEquals(Duration.ofDays(30), settlement.elapsed());
        assertEquals(1, settlement.experience());
        assertEquals(2, settlement.mesos());
        assertEquals(NOW, settlement.settledThrough());
        assertEquals(NOW, repository.lastSettledUpdates.get(12));
    }

    @Test
    void attachFailureCleansLoadedCharacterAndLeavesProfileOffline() {
        FakeRepository repository =
                new FakeRepository(profile(20, ONLINE, NOW.minusSeconds(60)));
        FakeRuntime runtime = new FakeRuntime(repository.events);
        runtime.failAttaches.add(20);
        CompanionLifecycleCoordinator coordinator = coordinator(repository, runtime);

        coordinator.start();

        CompanionLifecycleStatus status = coordinator.status(20).orElseThrow();
        assertEquals(CompanionLifecycleStatus.State.FAILED, status.state());
        assertFalse(status.loaded());
        assertEquals(List.of("offline"), repository.modeUpdates.get(20));
        assertEquals(1, runtime.stops.getOrDefault(20, 0));
    }

    @Test
    void savesCharacterCheckpointBeforeAdvancingProfileSettlementCursor() {
        FakeRepository repository =
                new FakeRepository(profile(21, ONLINE, NOW.minusSeconds(60)));
        FakeRuntime runtime = new FakeRuntime(repository.events);
        CompanionLifecycleCoordinator coordinator = coordinator(repository, runtime);

        coordinator.start();

        assertTrue(repository.events.indexOf("checkpoint:21")
                < repository.events.indexOf("profile:online:21"));
    }

    @Test
    void trainWindowStillUsesConservativeOfflineSettlementMultiplier() {
        FakeRepository repository =
                new FakeRepository(profile(22, ONLINE, NOW.minus(Duration.ofHours(1))));
        FakeRuntime runtime = new FakeRuntime();
        CompanionLifecycleCoordinator coordinator = new CompanionLifecycleCoordinator(
                repository, runtime, activitySensitivePolicy(), fixedClock(),
                Duration.ofSeconds(60), (task, delay) -> { });

        coordinator.start();

        OfflineProgressionSettlement settlement = runtime.settlements.get(22);
        assertEquals(20, settlement.experience());
        assertEquals(51, settlement.mesos());
        assertTrue(settlement.reason().contains("activity=OFFLINE"));
        assertTrue(coordinator.status(22).orElseThrow().detail()
                .contains("activity=TRAIN;settlementActivity=OFFLINE"));
    }

    @Test
    void repeatedReconcileDoesNotSpawnOrDespawnTwice() {
        FakeRepository repository = new FakeRepository(profile(13, ONLINE, NOW.minusSeconds(60)));
        FakeRuntime runtime = new FakeRuntime();
        CompanionLifecycleCoordinator coordinator = coordinator(repository, runtime);

        coordinator.start();
        coordinator.reconcileNow();
        coordinator.reconcileNow();

        assertEquals(1, runtime.loads.getOrDefault(13, 0));
        assertEquals(1, runtime.starts.getOrDefault(13, 0));
        assertEquals(0, runtime.stops.getOrDefault(13, 0));
    }

    @Test
    void invalidProfileIsTypedAndDoesNotBlockOtherProfiles() {
        FakeRepository repository = new FakeRepository(
                profile(14, "not-versioned", NOW.minusSeconds(60)),
                profile(15, ONLINE, NOW.minusSeconds(60)));
        FakeRuntime runtime = new FakeRuntime();
        CompanionLifecycleCoordinator coordinator = coordinator(repository, runtime);

        coordinator.start();

        CompanionLifecycleStatus invalid = coordinator.status(14).orElseThrow();
        assertEquals(CompanionLifecycleStatus.State.INVALID_ROUTINE, invalid.state());
        assertEquals("UNSUPPORTED_VERSION", invalid.code());
        assertFalse(runtime.loads.containsKey(14));
        assertEquals(CompanionLifecycleStatus.State.ONLINE,
                coordinator.status(15).orElseThrow().state());
    }

    @Test
    void runtimeFailureForOneProfileDoesNotBlockAnother() {
        FakeRepository repository = new FakeRepository(
                profile(16, ONLINE, NOW.minusSeconds(60)),
                profile(17, ONLINE, NOW.minusSeconds(60)));
        FakeRuntime runtime = new FakeRuntime();
        runtime.failLoads.add(16);
        CompanionLifecycleCoordinator coordinator = coordinator(repository, runtime);

        coordinator.start();

        assertEquals(CompanionLifecycleStatus.State.FAILED,
                coordinator.status(16).orElseThrow().state());
        assertEquals(CompanionLifecycleStatus.State.ONLINE,
                coordinator.status(17).orElseThrow().state());
    }

    @Test
    void shutdownStopsAndSavesEveryOnlineCompanion() {
        FakeRepository repository = new FakeRepository(
                profile(18, ONLINE, NOW.minusSeconds(60)),
                profile(19, ONLINE, NOW.minusSeconds(60)));
        FakeRuntime runtime = new FakeRuntime();
        CompanionLifecycleCoordinator coordinator = coordinator(repository, runtime);
        coordinator.start();

        coordinator.stop();

        assertEquals(1, runtime.stops.getOrDefault(18, 0));
        assertEquals(1, runtime.stops.getOrDefault(19, 0));
        assertTrue(coordinator.status().values().stream()
                .allMatch(status -> !status.loaded()));
    }

    @Test
    void manualCheckpointSavesOnlineCharacterWithoutReconciling() {
        FakeRepository repository = new FakeRepository(profile(23, ONLINE, NOW.minusSeconds(60)));
        FakeRuntime runtime = new FakeRuntime();
        CompanionLifecycleCoordinator coordinator = coordinator(repository, runtime);
        coordinator.start();
        int checkpointsAfterSpawn = java.util.Collections.frequency(
                runtime.events, "checkpoint:23");

        coordinator.checkpointNow(23);

        assertEquals(checkpointsAfterSpawn + 1,
                java.util.Collections.frequency(runtime.events, "checkpoint:23"));
        assertEquals(1, runtime.loads.getOrDefault(23, 0));
        assertEquals(0, runtime.stops.getOrDefault(23, 0));
        assertEquals(CompanionLifecycleStatus.State.ONLINE,
                coordinator.status(23).orElseThrow().state());
    }

    @Test
    void manualCheckpointRejectsOfflineCharacter() {
        FakeRepository repository = new FakeRepository(
                profile(24, "v1|00:00-23:59=SLEEP", NOW.minusSeconds(60)));
        FakeRuntime runtime = new FakeRuntime();
        CompanionLifecycleCoordinator coordinator = coordinator(repository, runtime);
        coordinator.start();

        assertThrows(IllegalStateException.class, () -> coordinator.checkpointNow(24));
    }

    private static CompanionLifecycleCoordinator coordinator(
            FakeRepository repository,
            FakeRuntime runtime) {
        return new CompanionLifecycleCoordinator(
                repository, runtime, OfflineProgressionPolicy.conservativeDefaults(),
                fixedClock(), Duration.ofSeconds(60), (task, delay) -> { });
    }

    private static Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    private static OfflineProgressionPolicy tinyCapPolicy() {
        EnumMap<RoutineActivity, Integer> multipliers = new EnumMap<>(RoutineActivity.class);
        for (RoutineActivity activity : RoutineActivity.values()) {
            multipliers.put(activity, 10_000);
        }
        return new OfflineProgressionPolicy(Duration.ofHours(1), 1, 2, multipliers);
    }

    private static OfflineProgressionPolicy activitySensitivePolicy() {
        EnumMap<RoutineActivity, Integer> multipliers = new EnumMap<>(RoutineActivity.class);
        for (RoutineActivity activity : RoutineActivity.values()) {
            multipliers.put(activity, 10_000);
        }
        multipliers.put(RoutineActivity.OFFLINE, 500);
        return new OfflineProgressionPolicy(
                Duration.ofHours(24), 100_000, 100_000, multipliers);
    }

    private static CompanionProfile profile(
            int characterId,
            String routineProfile,
            Instant lastSettledAt) {
        return new CompanionProfile(
                characterId, characterId + 100, "Companion" + characterId,
                "active", true, characterId, "", "", "",
                "UTC", routineProfile, "novice", "offline",
                null, lastSettledAt, NOW.minus(Duration.ofDays(60)), NOW);
    }

    private static final class FakeRepository implements CompanionProfileRepository {
        private final Map<Integer, CompanionProfile> profiles = new HashMap<>();
        private final Map<Integer, Instant> lastSettledUpdates = new HashMap<>();
        private final Map<Integer, List<String>> modeUpdates = new HashMap<>();
        private final List<String> events = new java.util.ArrayList<>();

        private FakeRepository(CompanionProfile... profiles) {
            for (CompanionProfile profile : profiles) {
                this.profiles.put(profile.characterId(), profile);
            }
        }

        @Override
        public Optional<CompanionProfile> findByCharacterId(int characterId) {
            return Optional.ofNullable(profiles.get(characterId));
        }

        @Override
        public List<CompanionProfile> findEnabled() {
            return profiles.values().stream()
                    .filter(CompanionProfile::enabled)
                    .filter(profile -> "active".equals(profile.status()))
                    .sorted(java.util.Comparator.comparingInt(CompanionProfile::characterId))
                    .toList();
        }

        @Override
        public void updateRuntimeState(
                int characterId,
                String currentMode,
                Instant lastOnlineAt,
                Instant lastSettledAt) throws SQLException {
            if (!profiles.containsKey(characterId)) {
                throw new SQLException("missing profile");
            }
            modeUpdates.computeIfAbsent(characterId, ignored -> new java.util.ArrayList<>())
                    .add(currentMode);
            events.add("profile:" + currentMode + ":" + characterId);
            if (lastSettledAt != null) {
                lastSettledUpdates.put(characterId, lastSettledAt);
            }
        }
    }

    private static final class FakeRuntime implements CompanionRuntimeAdapter {
        private final Map<Integer, Integer> loads = new HashMap<>();
        private final Map<Integer, Integer> starts = new HashMap<>();
        private final Map<Integer, Integer> stops = new HashMap<>();
        private final Map<Integer, OfflineProgressionSettlement> settlements = new HashMap<>();
        private final Set<Integer> failLoads = new HashSet<>();
        private final Set<Integer> failAttaches = new HashSet<>();
        private final List<String> events;

        private FakeRuntime() {
            this(new java.util.ArrayList<>());
        }

        private FakeRuntime(List<String> events) {
            this.events = events;
        }

        @Override
        public int persistedLevel(CompanionProfile profile) {
            return 200;
        }

        @Override
        public LoadedCompanion load(CompanionProfile profile) {
            loads.merge(profile.characterId(), 1, Integer::sum);
            if (failLoads.contains(profile.characterId())) {
                throw new IllegalStateException("synthetic load failure");
            }
            return new FakeLoaded(profile.characterId(), 200);
        }

        @Override
        public void applyProgression(
                LoadedCompanion companion,
                OfflineProgressionSettlement settlement) {
            settlements.put(companion.characterId(), settlement);
        }

        @Override
        public void saveCheckpoint(LoadedCompanion companion) {
            events.add("checkpoint:" + companion.characterId());
        }

        @Override
        public void attachAndStart(LoadedCompanion companion) {
            starts.merge(companion.characterId(), 1, Integer::sum);
            if (failAttaches.contains(companion.characterId())) {
                throw new IllegalStateException("synthetic attach failure");
            }
        }

        @Override
        public void stopSaveAndRemove(LoadedCompanion companion) {
            stops.merge(companion.characterId(), 1, Integer::sum);
        }
    }

    private record FakeLoaded(int characterId, int level) implements
            CompanionRuntimeAdapter.LoadedCompanion {
    }
}
