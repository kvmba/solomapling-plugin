package soloMapling.companion.lifecycle;

import org.junit.jupiter.api.Test;
import soloMapling.companion.persistence.CompanionProfile;
import soloMapling.companion.persistence.CompanionProfileRepository;
import soloMapling.companion.routine.OfflineProgressionPolicy;
import soloMapling.companion.routine.OfflineProgressionSettlement;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionLifecycleAccessTest {

    @Test
    void exposesOnlyRegisteredCoordinatorAndCanBeClearedSafely() {
        CompanionLifecycleAccess access = new CompanionLifecycleAccess();
        CompanionLifecycleCoordinator coordinator = coordinator();

        assertTrue(access.current().isEmpty());
        access.register(coordinator);
        assertSame(coordinator, access.current().orElseThrow());

        access.clear(coordinator());
        assertSame(coordinator, access.current().orElseThrow());
        access.clear(coordinator);
        assertTrue(access.current().isEmpty());
    }

    @Test
    void rejectsReplacingLiveRegistration() {
        CompanionLifecycleAccess access = new CompanionLifecycleAccess();
        access.register(coordinator());

        assertThrows(IllegalStateException.class, () -> access.register(coordinator()));
    }

    private static CompanionLifecycleCoordinator coordinator() {
        CompanionProfileRepository profiles = new CompanionProfileRepository() {
            @Override
            public Optional<CompanionProfile> findByCharacterId(int characterId) {
                return Optional.empty();
            }

            @Override
            public List<CompanionProfile> findEnabled() {
                return List.of();
            }

            @Override
            public void updateRuntimeState(
                    int characterId,
                    String currentMode,
                    java.time.Instant lastOnlineAt,
                    java.time.Instant lastSettledAt) {
            }
        };
        CompanionRuntimeAdapter runtime = new CompanionRuntimeAdapter() {
            @Override
            public int persistedLevel(CompanionProfile profile) {
                return 1;
            }

            @Override
            public LoadedCompanion load(CompanionProfile profile) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void applyProgression(
                    LoadedCompanion companion,
                    OfflineProgressionSettlement settlement) {
            }

            @Override
            public void saveCheckpoint(LoadedCompanion companion) {
            }

            @Override
            public void attachAndStart(LoadedCompanion companion) {
            }

            @Override
            public void stopSaveAndRemove(LoadedCompanion companion) {
            }
        };
        return new CompanionLifecycleCoordinator(
                profiles,
                runtime,
                OfflineProgressionPolicy.conservativeDefaults(),
                Clock.systemUTC());
    }
}
