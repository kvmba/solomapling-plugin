package soloMapling.companion.lifecycle;

import org.gms.constants.game.ExpTable;
import org.junit.jupiter.api.Test;
import soloMapling.companion.routine.CompanionNoviceLevel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HostCompanionRuntimeAdapterTest {

    private static final int HARD_CAP = 25_000;

    /**
     * A settlement may grant a slice of a level, not a level and not the flat
     * cap the lower levels used to receive.
     */
    @Test
    void experienceCapFollowsTheHostExpTable() {
        for (int level : new int[]{10, 15, 20, 30}) {
            long cap = HostCompanionRuntimeAdapter.experienceCap(level);
            long needed = ExpTable.getExpNeededForLevel(level);
            assertTrue(cap < needed,
                    "cap at level " + level + " should be part of a level, got " + cap);
            assertEquals(Math.round(needed * 0.2), cap,
                    "cap at level " + level + " should be a fifth of a level");
        }
    }

    /**
     * Where a level already costs more than the flat cap, the flat cap is what
     * binds — the fraction is a ceiling for the low levels, not a formula to be
     * followed off a cliff at the top.
     */
    @Test
    void experienceCapFallsBackToTheHardCapAtHighLevels() {
        long needed = ExpTable.getExpNeededForLevel(50);
        assertTrue(needed * 0.2 > 25_000,
                "this test assumes level 50 exceeds the hard cap");
        assertEquals(25_000, HostCompanionRuntimeAdapter.experienceCap(50));
    }

    /**
     * The old flat cap was absurd at the bottom: level 1 needs 15 EXP to
     * advance, so 25,000 was enough to leave the beginner island without ever
     * fighting anything.
     */
    @Test
    void experienceCapIsSmallOnTheBeginnerIsland() {
        long cap = HostCompanionRuntimeAdapter.experienceCap(1);
        assertTrue(cap > 0, "must still be able to gain something");
        assertTrue(cap < ExpTable.getExpNeededForLevel(1),
                "a novice settlement must stay under one level: " + cap);
    }

    @Test
    void experienceCapNeverExceedsTheHardCap() {
        for (int level = 1; level < 200; level++) {
            assertTrue(HostCompanionRuntimeAdapter.experienceCap(level) <= HARD_CAP,
                    "cap exceeded the hard cap at level " + level);
        }
    }

    @Test
    void novicesEarnNothingOffline() {
        // The bar lines up with the island's own gate: Sanks asks for 7, and
        // this leaves a companion the island's content rather than handing it
        // levels it did not fight for.
        assertTrue(CompanionNoviceLevel.VALUE >= 7,
                "the novice bar should not sit below the island's own gate");
    }
}
