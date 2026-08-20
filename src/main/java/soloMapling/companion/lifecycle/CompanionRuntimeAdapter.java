package soloMapling.companion.lifecycle;

import soloMapling.companion.persistence.CompanionProfile;
import soloMapling.companion.routine.OfflineProgressionSettlement;

/**
 * Narrow engine boundary so lifecycle behavior can be tested without a live server.
 */
public interface CompanionRuntimeAdapter {

    int persistedLevel(CompanionProfile profile);

    LoadedCompanion load(CompanionProfile profile);

    /**
     * Applies any level-eligible deterministic job advancements.
     */
    CareerReconciliation reconcileCareer(LoadedCompanion companion, CompanionProfile profile);

    /**
     * Returns a read-only AP/SP preview for GM diagnostics.
     */
    default String buildDiagnostics(LoadedCompanion companion, CompanionProfile profile) {
        return "";
    }

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

    record CareerReconciliation(
            int advancements,
            int apSpent,
            int spSpent,
            String detail
    ) {
        public CareerReconciliation {
            if (advancements < 0 || apSpent < 0 || spSpent < 0) {
                throw new IllegalArgumentException("career reconciliation counts must not be negative");
            }
            detail = detail == null ? "" : detail;
        }

        public CareerReconciliation(int advancements, int apSpent, int spSpent) {
            this(advancements, apSpent, spSpent, "");
        }

        public boolean changed() {
            return advancements > 0 || apSpent > 0 || spSpent > 0;
        }

        public CareerReconciliation plus(CareerReconciliation other) {
            return new CareerReconciliation(
                    advancements + other.advancements,
                    apSpent + other.apSpent,
                    spSpent + other.spSpent,
                    joinDetail(detail, other.detail));
        }

        private static String joinDetail(String left, String right) {
            if (left.isBlank()) {
                return right;
            }
            if (right.isBlank() || left.equals(right)) {
                return left;
            }
            return left + ";" + right;
        }
    }
}
