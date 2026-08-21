package soloMapling.companion.execution;

/** Chooses a player-visible-safe recovery for stalled companion combat. */
public final class CompanionCombatRecoveryPolicy {
    public static final long REPATH_AFTER_MS = 20_000L;
    public static final long POSITION_REPAIR_AFTER_MS = 90_000L;

    public enum Recovery {
        NONE,
        REPATH,
        POSITION_REPAIR
    }

    private CompanionCombatRecoveryPolicy() {
    }

    public static Recovery choose(
            long progressAgeMs,
            boolean mapObserved,
            boolean cooldownReady) {
        if (!cooldownReady || progressAgeMs < REPATH_AFTER_MS) {
            return Recovery.NONE;
        }
        if (!mapObserved && progressAgeMs >= POSITION_REPAIR_AFTER_MS) {
            return Recovery.POSITION_REPAIR;
        }
        return Recovery.REPATH;
    }
}
