package soloMapling.companion.routine;

/**
 * Cooldowns are scoped to one companion/player pair.
 */
public record EncounterKey(long companionId, long playerId) {
    public EncounterKey {
        if (companionId < 0 || playerId < 0) {
            throw new IllegalArgumentException("encounter identifiers must not be negative");
        }
    }
}
