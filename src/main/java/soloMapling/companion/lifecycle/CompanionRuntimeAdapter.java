package soloMapling.companion.lifecycle;

import soloMapling.companion.persistence.CompanionProfile;
import soloMapling.companion.routine.OfflineProgressionSettlement;

/**
 * Narrow engine boundary so lifecycle behavior can be tested without a live server.
 */
public interface CompanionRuntimeAdapter {

    int persistedLevel(CompanionProfile profile);

    LoadedCompanion load(CompanionProfile profile);

    void applyProgression(LoadedCompanion companion, OfflineProgressionSettlement settlement);

    /**
     * Persists applied rewards before the profile settlement cursor advances.
     *
     * <p>The character save and profile update are separate transactions. A crash between
     * them can duplicate a settlement on retry, but cannot permanently lose an already
     * acknowledged reward. A future settlement ID can close this remaining MVP window.</p>
     */
    void saveCheckpoint(LoadedCompanion companion);

    void attachAndStart(LoadedCompanion companion);

    void stopSaveAndRemove(LoadedCompanion companion);

    interface LoadedCompanion {
        int characterId();

        int level();
    }
}
