package soloMapling.ArtificialPlayer.PartyQuest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the expedition table, which is what decides whether a boss run can be attempted.
 *
 * <p>Transcribed from {@code ExpeditionType}, and the transcription is worth pinning because
 * the two mistakes it can hide are both quiet: a wrong member floor makes a party that cannot
 * start look ready, and a wrong level band admits a bot that will be refused at the door. The
 * count also matters - three Ariant variants exist, not one, which is easy to miss.
 */
class ExpeditionTypesTest {

    @Test
    void theTableCoversEveryDeclaredType() {
        // ExpeditionType declares thirteen; a missing one is a run nobody can plan for.
        assertEquals(13, ExpeditionTypes.ALL.length);
    }

    @Test
    void theAriantVariantsAreAllPresent() {
        // Three types share Ariant's limits; treating it as one would leave two unreachable.
        assertNotNull(ExpeditionTypes.byName("ARIANT"));
        assertNotNull(ExpeditionTypes.byName("ARIANT1"));
        assertNotNull(ExpeditionTypes.byName("ARIANT2"));
    }

    @Test
    void theBossExpeditionsAllowsThirty() {
        // Every boss type caps at thirty, which is why these are the last things here worth
        // automating: they are crowds, not parties.
        for (String name : new String[]{"ZAKUM", "HORNTAIL", "PINKBEAN", "CWKPQ", "SCARGA"}) {
            ExpeditionTypes.Type type = ExpeditionTypes.byName(name);
            assertNotNull(type, name + " is missing");
            assertEquals(30, type.maxMembers(), name + " should hold thirty");
        }
    }

    @Test
    void theLevelBandsMatchTheEnumsOwnNumbers() {
        assertEquals(50, ExpeditionTypes.byName("ZAKUM").minLevel());
        assertEquals(100, ExpeditionTypes.byName("HORNTAIL").minLevel());
        assertEquals(120, ExpeditionTypes.byName("CHAOS_ZAKUM").minLevel());
        assertEquals(90, ExpeditionTypes.byName("CWKPQ").minLevel());
        assertEquals(20, ExpeditionTypes.byName("ARIANT").minLevel());
        assertEquals(30, ExpeditionTypes.byName("ARIANT").maxLevel());
    }

    @Test
    void theSmallExpeditionsHaveTheirOwnFloors() {
        // Balrog Easy and Showa take three; the rest take six. Reading them all as six would
        // rule out two runs that are legal.
        assertEquals(3, ExpeditionTypes.byName("BALROG_EASY").minMembers());
        assertEquals(3, ExpeditionTypes.byName("SHOWA").minMembers());
        assertEquals(6, ExpeditionTypes.byName("ZAKUM").minMembers());
        assertEquals(2, ExpeditionTypes.byName("ARIANT").minMembers());
        assertEquals(7, ExpeditionTypes.byName("ARIANT").maxMembers());
    }

    @Test
    void aPartySizeIsCheckedAgainstTheTypeThatWasAskedFor() {
        assertTrue(ExpeditionTypes.partySizeFits("ZAKUM", 6));
        assertTrue(ExpeditionTypes.partySizeFits("ZAKUM", 30));
        // Below the floor and above the ceiling both refuse.
        org.junit.jupiter.api.Assertions.assertFalse(ExpeditionTypes.partySizeFits("ZAKUM", 5));
        org.junit.jupiter.api.Assertions.assertFalse(ExpeditionTypes.partySizeFits("ZAKUM", 31));
        org.junit.jupiter.api.Assertions.assertFalse(ExpeditionTypes.partySizeFits("NOPE", 6));
    }

    @Test
    void theBotCountReachesTheTypesFloor() {
        // The number that decides whether a run is worth attempting: how many bots it takes to
        // reach the minimum from what the player already has.
        assertEquals(5, ExpeditionTypes.botsNeededFor("ZAKUM", 1));
        assertEquals(0, ExpeditionTypes.botsNeededFor("ZAKUM", 8));
        assertEquals(2, ExpeditionTypes.botsNeededFor("BALROG_EASY", 1));
        assertEquals(1, ExpeditionTypes.botsNeededFor("ARIANT", 1));
        assertEquals(-1, ExpeditionTypes.botsNeededFor("NOPE", 1));
    }

    @Test
    void anUnknownTypeIsNotSilentlyTreatedAsEligible() {
        assertNull(ExpeditionTypes.byName("NOT_A_TYPE"));
        org.junit.jupiter.api.Assertions.assertFalse(ExpeditionTypes.partySizeFits("NOT_A_TYPE", 30));
    }
}
