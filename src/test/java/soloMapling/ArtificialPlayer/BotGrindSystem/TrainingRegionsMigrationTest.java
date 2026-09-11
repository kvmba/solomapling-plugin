package soloMapling.ArtificialPlayer.BotGrindSystem;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Continent migration: every continent in the table must be a place a bot can
 * actually leave, and the answer must only ever be somewhere the bot is high
 * enough for. The old ladder matched the bot's home map id against a hard-coded
 * list of four towns, so a bot based anywhere else — most cohorts — never moved
 * at all; these pin the behaviour that replaced it.
 */
class TrainingRegionsMigrationTest {

    // One town per continent in ALLOWED, paired with the bar that continent carries.
    // Anchors are window[0]; the island (0) is excluded — it is a way out, never a way back.
    private static final int[][] CONTINENTS = {
            {100_000_000, 1},   // Victoria Island
            {200_000_000, 30},  // Orbis
            {211_000_000, 30},  // El Nath
            {220_000_000, 30},  // Ludibrium
            {230_000_000, 30},  // Aquarium
            {240_000_000, 70},  // Leafre
            {250_000_000, 30},  // Mu Lung
            {251_000_000, 40},  // Herb Town
            {260_000_000, 30},  // Ariant
            {270_000_000, 90},  // Time Temple
            {300_000_000, 30},  // Arta camp
            {540_000_000, 1},   // Singapore
            {550_000_000, 30},  // Malaysia
            {600_000_000, 1},   // New Leaf City
            {700_000_000, 1},   // Shanghai
            {800_000_000, 30},  // Japan
            {110_000_000, 30},  // Gold Beach
            {120_000_000, 1},   // Nautilus
    };

    /**
     * Anchors a wider window already swallows. 702000000 (Songshan) sits inside Shanghai's
     * 700000000-783000000, so it is not a continent of its own — naming it would send a bot
     * "to another continent" that is the one it is already standing in.
     */
    private static final int[] SWALLOWED_ANCHORS = {702_000_000};

    /** A sub-town, not a continent anchor: the case the old ladder dropped on the floor. */
    private static final int SLEEPYWOOD = 105_040_300;
    private static final int LEAFRE_MAZE = 240_070_100;

    @Test
    void everyContinentCanBeLeftByABotThatQualifiesForSomewhereElse() {
        for (int[] continent : CONTINENTS) {
            int from = continent[0];
            int dest = TrainingRegions.migrationTarget(from, TrainingRegions.FREE_MOVE_LEVEL);
            assertTrue(dest > 0, "continent " + from + " has nowhere to go at the free-move level");
            assertTrue(TrainingRegions.isAllowed(dest, TrainingRegions.FREE_MOVE_LEVEL),
                    "continent " + from + " picked " + dest + ", which it is too low for");
        }
    }

    @Test
    void aSubTownIsNoLongerStranded() {
        // Sleepywood is Victoria's: its bar is 1, so at 120 it has somewhere to go.
        assertTrue(TrainingRegions.migrationTarget(SLEEPYWOOD, TrainingRegions.FREE_MOVE_LEVEL) > 0);
        // Leafre's deep maps are Leafre's too.
        assertTrue(TrainingRegions.migrationTarget(LEAFRE_MAZE, TrainingRegions.FREE_MOVE_LEVEL) > 0);
    }

    @Test
    void belowTheFreeMoveLevelABotOnlyClimbs() {
        // Victoria (bar 1) at 50: everything harder is open, nothing easier is.
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < 400; i++) {
            int dest = TrainingRegions.migrationTarget(100_000_000, 50);
            if (dest > 0) {
                seen.add(dest);
                assertTrue(TrainingRegions.isAllowed(dest, 50), "picked " + dest + " at lv 50");
                // Bar must be strictly above Victoria's: no sideways or downward move.
                assertTrue(barOf(dest) > 1, "climbed to " + dest + " whose bar is not higher");
            }
        }
        assertTrue(seen.size() > 1, "a climb should have more than one possible continent");
    }

    @Test
    void aFreeMoverMayGoDownAsWellAsUp() {
        // Time Temple (bar 90) at 120 is the case the ladder got wrong: it was the terminal rung,
        // so a bot that reached it could never leave.
        Set<Integer> fromTemple = new HashSet<>();
        for (int i = 0; i < 400; i++) {
            fromTemple.add(TrainingRegions.migrationTarget(270_000_000, 200));
        }
        assertTrue(fromTemple.size() > 1, "a free mover should have several continents to choose from");
        assertTrue(fromTemple.contains(100_000_000),
                "a free mover must be able to come back down to Victoria, got " + fromTemple);
    }

    @Test
    void theOptionalMoveChanceIsTwentyFivePercentAndShared() {
        // Both bot kinds roll the same dice, so the figure lives in one place and neither can
        // drift into moving twice as readily as the other.
        assertEquals(0.25, TrainingRegions.OPTIONAL_MOVE_CHANCE, 0.0);
        assertTrue(TrainingRegions.OPTIONAL_MOVE_CHANCE > 0.0
                && TrainingRegions.OPTIONAL_MOVE_CHANCE < 1.0,
                "must be a real chance, not always or never");
    }

    @Test
    void aBotTooLowForAnywhereElseStaysPut() {
        // Level 20 clears Victoria (1) and nothing else, so there is nowhere to climb to.
        assertEquals(0, TrainingRegions.migrationTarget(100_000_000, 20));
        // ...but it may still visit, and only somewhere it qualifies for.
        for (int i = 0; i < 200; i++) {
            int dest = TrainingRegions.returnTarget(100_000_000, 20);
            if (dest > 0) {
                assertTrue(TrainingRegions.isAllowed(dest, 20), "visit to " + dest + " at lv 20");
            }
        }
    }

    @Test
    void theBeginnerIslandStillOnlyLeavesAtEight() {
        // 2000000 is Southperry: the dock Sanks rows you away from.
        assertEquals(0, TrainingRegions.migrationTarget(2_000_000, 7));
        assertEquals(100_000_000, TrainingRegions.migrationTarget(2_000_000, 8));
    }

    @Test
    void theIslandIsNeverADestination() {
        for (int i = 0; i < 300; i++) {
            int dest = TrainingRegions.returnTarget(100_000_000, 200);
            assertTrue(dest != 0, "anchor 0 (the island) must never be a destination");
        }
    }

    @Test
    void aBotNeverMigratesToItsOwnContinent() {
        for (int[] continent : CONTINENTS) {
            int from = continent[0];
            for (int i = 0; i < 60; i++) {
                assertTrue(TrainingRegions.migrationTarget(from, 200) != from);
                assertTrue(TrainingRegions.returnTarget(from, 200) != from);
            }
        }
        // A map inside a swallowed window is part of the continent that swallows it, so standing
        // there must never offer that same anchor as somewhere else to go.
        for (int swallowed : SWALLOWED_ANCHORS) {
            assertTrue(TrainingRegions.isAllowed(swallowed, 1),
                    "test table assumes " + swallowed + " is in Shanghai's window");
            for (int i = 0; i < 300; i++) {
                assertTrue(TrainingRegions.migrationTarget(swallowed, 200) != swallowed);
                assertTrue(TrainingRegions.returnTarget(swallowed, 200) != swallowed);
            }
        }
    }

    private static int barOf(int anchor) {
        for (int[] continent : CONTINENTS) {
            if (continent[0] == anchor) {
                return continent[1];
            }
        }
        throw new AssertionError("test table is missing anchor " + anchor);
    }
}
