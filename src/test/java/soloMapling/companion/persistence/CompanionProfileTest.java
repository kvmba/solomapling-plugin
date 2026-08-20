package soloMapling.companion.persistence;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CompanionProfileTest {

    @Test
    void normalizesRequiredTextAndNullablePromptFields() {
        Instant now = Instant.parse("2026-08-20T10:00:00Z");
        CompanionProfile profile = new CompanionProfile(
                12, 4, "  Mira  ", " active ", true, 99L,
                null, null, null, " Australia/Sydney ", null,
                " novice ", " offline ", null, null, now, now
        );

        assertEquals("Mira", profile.displayName());
        assertEquals("active", profile.status());
        assertEquals("", profile.persona());
        assertEquals("Australia/Sydney", profile.routineTimezone());
        assertEquals("novice", profile.growthStage());
        assertEquals("offline", profile.currentMode());
    }

    @Test
    void rejectsInvalidNativeIdentity() {
        Instant now = Instant.parse("2026-08-20T10:00:00Z");

        assertThrows(IllegalArgumentException.class, () -> new CompanionProfile(
                0, 4, "Mira", "active", true, 99L,
                "", "", "", "UTC", "",
                "novice", "offline", null, null, now, now
        ));
    }
}
