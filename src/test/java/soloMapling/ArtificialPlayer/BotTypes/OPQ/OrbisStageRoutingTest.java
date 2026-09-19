package soloMapling.ArtificialPlayer.BotTypes.OPQ;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the Orbis middle-stage decision and the tower's room-portal names.
 *
 * <p>The stage choice drives which room the bot walks to; getting it wrong sends the bot to work
 * a room the party already left (or to sit in the tower forever). The flag values are taken from
 * the quest's own scripts, including stage 3's non-standard cleared value - the music box sets
 * {@code statusStg3} to 0 and Eak then to 2, so a bot that only accepted 1 would never leave it.
 */
class OrbisStageRoutingTest {

    /** statusStg1..8, all "untouched" (-1) as setup leaves them. */
    private static int[] freshFlags() {
        int[] stg = new int[9];
        java.util.Arrays.fill(stg, -1);
        return stg;
    }

    private static int[] clearedThrough(int lastClearedStage) {
        int[] stg = freshFlags();
        for (int stage = 1; stage <= lastClearedStage; stage++) {
            stg[stage] = 1;
        }
        return stg;
    }

    @Test
    void aFreshRunStartsAtStageOne() {
        assertEquals(1, OrbisStages.middleStage(freshFlags(), false));
    }

    @Test
    void eachClearedStageAdvancesToTheNext() {
        for (int cleared = 1; cleared <= 5; cleared++) {
            assertEquals(cleared + 1, OrbisStages.middleStage(clearedThrough(cleared), false),
                    "clearing through stage " + cleared + " should put stage " + (cleared + 1) + " in play");
        }
    }

    @Test
    void stageThreeIsTheOnlyStageThatClearsWithANonOneValue() {
        int[] stg = clearedThrough(2);
        stg[3] = 0; // the music box's own value, before Eak bumps it
        assertEquals(3, OrbisStages.middleStage(stg, false), "stage 3 is not cleared by the music box alone");
        stg[3] = 1;
        assertEquals(4, OrbisStages.middleStage(stg, false), "stage 3 clears when its flag reaches 1");
        stg[3] = 2; // Eak's final value
        assertEquals(4, OrbisStages.middleStage(stg, false), "stage 3 stays cleared at 2");
    }

    @Test
    void stageSevenIsScarsThenPapaPixie() {
        int[] stg = clearedThrough(6); // stage 7 flag still -1
        assertEquals(OrbisStages.SCARS_STAGE, OrbisStages.middleStage(stg, false),
                "with stage 7 unset and the scars dark, the tower work is next");
        assertEquals(OrbisStages.PAPA_STAGE, OrbisStages.middleStage(stg, true),
                "with the scars lit, Papa Pixie's room is next");
        stg[7] = 1; // the spring set it
        assertEquals(OrbisStages.STATUE_STAGE, OrbisStages.middleStage(stg, true),
                "once stage 7 is cleared the statue base is the last piece");
        stg[8] = 1;
        assertEquals(-1, OrbisStages.middleStage(stg, true), "nothing outstanding once stage 8 is cleared");
    }

    @Test
    void theScarsSentinelIsDistinctFromTheRoomStages() {
        // The sentinels must not collide with a room stage number (1..6) or a caller would
        // route to a room instead of doing the tower work.
        assertFalse(OrbisStages.SCARS_STAGE >= 1 && OrbisStages.SCARS_STAGE <= 6);
        assertFalse(OrbisStages.PAPA_STAGE >= 1 && OrbisStages.PAPA_STAGE <= 6);
        assertFalse(OrbisStages.STATUE_STAGE >= 1 && OrbisStages.STATUE_STAGE <= 6);
    }

    @Test
    void everyRoomStageMapsToADistinctTowerPortalName() {
        // The tower portals are script portals (in00..in06) that actually warp to the room, so
        // walking onto one is not enough - the name must match what the map declared.
        java.util.Set<String> names = new java.util.HashSet<>();
        for (int stage = 1; stage <= 6; stage++) {
            OrbisStages.StageRoom room = OrbisStages.roomFor(stage);
            String name = OrbisPqData.towerPortalName(room.portalInTower());
            assertTrue(name != null && name.startsWith("in"),
                    "stage " + stage + " tower portal has no in0N name");
            assertTrue(names.add(name), "two stages share tower portal " + name);
        }
    }

    @Test
    void theLoungeRotatesThroughAllFourSubRooms() {
        java.util.Set<String> doors = new java.util.HashSet<>();
        for (int i = 0; i < OrbisPqData.LOUNGE_ROOMS.size(); i++) {
            assertTrue(doors.add(OrbisPqData.loungeEntryPortal(i)),
                    "lounge visit " + i + " reuses a door");
        }
        // Wraps rather than running off the end.
        assertEquals(OrbisPqData.loungeEntryPortal(0), OrbisPqData.loungeEntryPortal(4));
        assertEquals(OrbisPqData.LOUNGE_ROOMS.size(), doors.size());
    }
}
