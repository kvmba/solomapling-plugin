package soloMapling.companion.routine;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoutineScheduleTest {
    private static final ZoneId UTC = ZoneId.of("UTC");

    @Test
    void resolvesCrossMidnightBlockUsingScheduleZone() {
        RoutineBlock sleep = new RoutineBlock(
                LocalTime.of(22, 0), LocalTime.of(6, 0), RoutineActivity.SLEEP);
        RoutineSchedule schedule = new RoutineSchedule(UTC, List.of(sleep));

        assertTrue(sleep.crossesMidnight());
        assertEquals(RoutineActivity.SLEEP,
                schedule.activityAt(Instant.parse("2026-08-20T23:59:00Z")));
        assertEquals(RoutineActivity.SLEEP,
                schedule.activityAt(Instant.parse("2026-08-21T05:59:59Z")));
        assertEquals(RoutineActivity.OFFLINE,
                schedule.activityAt(Instant.parse("2026-08-21T06:00:00Z")));
    }

    @Test
    void returnsOfflineForExplicitScheduleGap() {
        RoutineSchedule schedule = new RoutineSchedule(UTC, List.of(
                new RoutineBlock(
                        LocalTime.of(9, 0), LocalTime.of(12, 0), RoutineActivity.TRAIN)));

        assertTrue(schedule.hasGaps());
        assertTrue(schedule.blockAt(Instant.parse("2026-08-20T08:00:00Z")).isEmpty());
        assertEquals(RoutineActivity.OFFLINE,
                schedule.activityAt(Instant.parse("2026-08-20T08:00:00Z")));
    }

    @Test
    void rejectsOverlapsIncludingAcrossMidnight() {
        RoutineBlock sleep = new RoutineBlock(
                LocalTime.of(22, 0), LocalTime.of(6, 0), RoutineActivity.SLEEP);
        RoutineBlock earlyTraining = new RoutineBlock(
                LocalTime.of(5, 30), LocalTime.of(8, 0), RoutineActivity.TRAIN);

        assertThrows(IllegalArgumentException.class,
                () -> new RoutineSchedule(UTC, List.of(sleep, earlyTraining)));
    }

    @Test
    void adjacentBlocksCanExpressFullDayWithoutGaps() {
        RoutineSchedule schedule = new RoutineSchedule(UTC, List.of(
                new RoutineBlock(
                        LocalTime.MIDNIGHT, LocalTime.NOON, RoutineActivity.TOWN),
                new RoutineBlock(
                        LocalTime.NOON, LocalTime.MIDNIGHT, RoutineActivity.REST)));

        assertFalse(schedule.hasGaps());
    }
}
