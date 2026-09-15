package soloMapling.ArtificialPlayer.BotTypes.OPQ;

import org.junit.jupiter.api.Test;

import java.util.Calendar;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the weekday -> LP record mapping for Orbis PQ's music box.
 *
 * <p>The box accepts exactly one record, chosen by the server at setup time:
 * {@code OrbisPQ.js} does {@code getReactorByName("music").setEventState(d.getDay())}, and
 * that index selects the WZ event block whose item condition is the record for that day.
 * The two sides number the week differently - JavaScript starts at Sunday = 0, Java's
 * Calendar at Sunday = 1 - so an off-by-one here silently sends every bot to the music box
 * carrying the record for the wrong day, and the stage never clears.
 */
class OrbisRecordDayTest {

    private static final int SUNDAY_RECORD = 4001056;

    @Test
    void sundayMapsToTheFirstRecord() {
        assertEquals(SUNDAY_RECORD,
                OPQOrchestrator.recordForDayOfWeek(Calendar.SUNDAY));
    }

    @Test
    void saturdayMapsToTheLastRecord() {
        assertEquals(4001062,
                OPQOrchestrator.recordForDayOfWeek(Calendar.SATURDAY));
    }

    @Test
    void eachWeekdayMapsToTheNextRecordInOrder() {
        // Sunday first, Saturday last: exactly the order the WZ event blocks are written in.
        assertEquals(4001057, OPQOrchestrator.recordForDayOfWeek(Calendar.MONDAY));
        assertEquals(4001058, OPQOrchestrator.recordForDayOfWeek(Calendar.TUESDAY));
        assertEquals(4001059, OPQOrchestrator.recordForDayOfWeek(Calendar.WEDNESDAY));
        assertEquals(4001060, OPQOrchestrator.recordForDayOfWeek(Calendar.THURSDAY));
        assertEquals(4001061, OPQOrchestrator.recordForDayOfWeek(Calendar.FRIDAY));
    }

    @Test
    void theSevenRecordsAreCoveredExactlyOnce() {
        boolean[] seen = new boolean[7];
        for (int day = Calendar.SUNDAY; day <= Calendar.SATURDAY; day++) {
            int offset = OPQOrchestrator.recordForDayOfWeek(day) - SUNDAY_RECORD;
            assertEquals(false, offset < 0 || offset > 6, "day " + day + " fell outside the set");
            assertEquals(false, seen[offset], "day " + day + " repeated a record");
            seen[offset] = true;
        }
    }
}
