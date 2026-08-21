package soloMapling.companion.routine;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RoutineProfileCodecTest {

    @Test
    void parsesStrictVersionedProfileInConfiguredTimezone() {
        RoutineSchedule schedule = RoutineProfileCodec.parse(
                "Australia/Sydney", "v1|09:00-12:00=TRAIN,13:00-17:00=SOCIAL");

        assertEquals(RoutineActivity.TRAIN,
                schedule.activityAt(Instant.parse("2026-08-20T00:00:00Z")));
        assertEquals(RoutineActivity.OFFLINE,
                schedule.activityAt(Instant.parse("2026-08-20T02:30:00Z")));
    }

    @Test
    void blankProfileIsBackwardsCompatibleOfflineDefault() {
        RoutineSchedule schedule = RoutineProfileCodec.parse("UTC", " ");

        assertEquals(RoutineActivity.OFFLINE,
                schedule.activityAt(Instant.parse("2026-08-20T12:00:00Z")));
    }

    @Test
    void rejectsUnversionedOrLooseProfilesWithTypedCode() {
        var exception = assertThrows(
                RoutineProfileCodec.RoutineProfileParseException.class,
                () -> RoutineProfileCodec.parse("UTC", "09:00-12:00=TRAIN"));

        assertEquals("UNSUPPORTED_VERSION", exception.code());
    }
}
