package soloMapling.companion.routine;

import java.time.Instant;
import java.util.Objects;

public record EncounterSelection(
        EncounterKey key,
        int mapId,
        long seed,
        Instant selectedAt,
        Instant cooldownUntil,
        String reason) {

    public EncounterSelection {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(selectedAt, "selectedAt must not be null");
        Objects.requireNonNull(cooldownUntil, "cooldownUntil must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        if (cooldownUntil.isBefore(selectedAt)) {
            throw new IllegalArgumentException("cooldownUntil must not precede selectedAt");
        }
    }
}
