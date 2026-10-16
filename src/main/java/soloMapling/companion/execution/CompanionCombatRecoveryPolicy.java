package soloMapling.companion.execution;

/**
 * Chooses a player-visible-safe recovery for stalled companion combat.
 *
 * <p>Only REPATH exists. POSITION_REPAIR ("teleport the companion onto its player when nobody is
 * watching") was unreachable by construction and has been removed: the branch required
 * {@code !mapObserved}, while its only caller - CompanionBot.grindTick - reaches the recovery
 * check only after asserting the target is on this map and online, and a real player on the map
 * is exactly what ObserverTracker's ~1s poll promotes to FULL (isMapObserved). The two
 * conditions could not hold simultaneously, so the branch could only fire in the sub-second race
 * between a player entering a map and the next poll - and firing a visible teleport in that
 * window is precisely what it was written to avoid.
 *
 * <p>{@code mapObserved} was a parameter of {@link #choose} and is gone with the branch: the
 * caller still computes it for its diagnostic log line, but no decision may depend on it.
 */
public final class CompanionCombatRecoveryPolicy {
    public static final long REPATH_AFTER_MS = 20_000L;

    public enum Recovery {
        NONE,
        REPATH
    }

    private CompanionCombatRecoveryPolicy() {
    }

    public static Recovery choose(
            long progressAgeMs,
            boolean cooldownReady) {
        if (!cooldownReady || progressAgeMs < REPATH_AFTER_MS) {
            return Recovery.NONE;
        }
        return Recovery.REPATH;
    }
}
