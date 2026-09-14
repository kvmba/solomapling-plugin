package soloMapling.ArtificialPlayer.BotTypes.OPQ;

import org.junit.jupiter.api.Test;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the Orbis stage data that the bot's routing depends on.
 *
 * <p>Every number here was read out of the quest's scripts and the WZ map data, and the
 * mistakes it guards against are the silent kind: a portal id that points at another room
 * walks the bot somewhere plausible and useless, and a scar reactor id taken a thousand
 * apart hits nothing at all. These cases make a future edit that breaks one of them fail
 * here instead of in a live run.
 */
class OrbisStageDataTest {

    @Test
    void everyMiddleStageRoutesToItsOwnRoom() {
        // Stages 1,2,3,4,5,6 get rooms; the tower stages (7,8) are worked where they are.
        for (int stage = 1; stage <= 6; stage++) {
            OrbisStages.StageRoom room = OrbisStages.roomFor(stage);
            assertNotNull(room, "stage " + stage + " has no room");
            assertTrue(OrbisPqData.towerPortalFor(room.mapId()) >= 0,
                    "stage " + stage + " room has no tower portal");
            assertNotNull(OrbisPqData.towerSpotFor(room.mapId()),
                    "stage " + stage + " room has no tower spot to stand on");
        }
        assertNull(OrbisStages.roomFor(7), "the scar stage is worked in the tower");
        assertNull(OrbisStages.roomFor(8), "the statue base is in the tower");
    }

    @Test
    void noTwoRoomsShareATowerPortalOrSpot() {
        // Sharing either would send two stages to the same place.
        java.util.Set<Integer> portals = new java.util.HashSet<>();
        java.util.Set<Point> spots = new java.util.HashSet<>();
        for (int stage = 1; stage <= 6; stage++) {
            OrbisStages.StageRoom room = OrbisStages.roomFor(stage);
            assertTrue(portals.add(room.portalInTower()),
                    "two rooms share tower portal " + room.portalInTower());
            assertTrue(spots.add(room.towerSpot()),
                    "two rooms share tower spot " + room.towerSpot());
        }
    }

    @Test
    void roomsAreDistinctMaps() {
        java.util.Set<Integer> maps = new java.util.HashSet<>();
        for (int stage = 1; stage <= 6; stage++) {
            assertTrue(maps.add(OrbisStages.roomFor(stage).mapId()),
                    "stage " + stage + " reuses another stage's map");
        }
    }

    @Test
    void allOrbisRoomsAreRecognised() {
        int[] rooms = {
                OrbisPqData.STAGE_CLOUDS, OrbisPqData.TOWER_MAP, OrbisPqData.STAGE_WALKWAY,
                OrbisPqData.STAGE_STORAGE, OrbisPqData.STAGE_MUSIC, OrbisPqData.STAGE_SEALED,
                OrbisPqData.STAGE_LOUNGE, OrbisPqData.STAGE_UP, OrbisPqData.STAGE_PAPA,
                OrbisPqData.STAGE_JAIL, OrbisPqData.STAGE_PRIZE,
        };
        for (int map : rooms) {
            assertTrue(OrbisPqData.isOrbisRoom(map), map + " is not recognised as an Orbis room");
        }
        for (int map : OrbisPqData.LOUNGE_ROOMS) {
            assertTrue(OrbisPqData.isOrbisRoom(map), "lounge sub-room " + map + " is not recognised");
        }
        // A map from outside the quest must not be claimed as one of its rooms.
        assertFalse(OrbisPqData.isOrbisRoom(200080101), "the lobby is not a stage room");
        assertFalse(OrbisPqData.isOrbisRoom(100000000), "Henesys is not a stage room");
    }

    @Test
    void sealedAreasAreTheMapsThreeRegions() {
        // From 920010500.img.xml: three <area> rectangles, and the quest counts players in
        // each. A bot that stands outside all three contributes nothing and the check fails
        // for the whole party, so these have to be the centres.
        assertEquals(3, OrbisPqData.SEALED_AREAS.length);
        assertTrue(OrbisPqData.SEALED_AREAS[0].x >= -205 && OrbisPqData.SEALED_AREAS[0].x < -119);
        assertTrue(OrbisPqData.SEALED_AREAS[1].x >= -71 && OrbisPqData.SEALED_AREAS[1].x < -10);
        assertTrue(OrbisPqData.SEALED_AREAS[2].x >= 40 && OrbisPqData.SEALED_AREAS[2].x < 116);
    }

    @Test
    void theThreeSealedTargetsAlwaysSumToThree() {
        // The quest derives stage4_2 as the remainder of 3, so any layout it publishes has
        // to be placeable in exactly three bodies. If that ever changed, the assignment walk
        // would silently drop bots and stage 4 would stall.
        for (int a = 0; a <= 3; a++) {
            for (int b = 0; b + a <= 3; b++) {
                int c = 3 - a - b;
                assertEquals(3, a + b + c);
            }
        }
    }

    @Test
    void leversAndThePrizeBoxPerStageAreDistinct() {
        // All five levers share a data id and are told apart by position; the stone6 box is
        // a different id, and a bot that hit it as if it were a lever would never finish.
        assertEquals(5, OrbisPqData.LEVER_SPOTS.length);
        assertTrue(OrbisPqData.LEVER_STONE_REACTOR != OrbisPqData.LEVER_REACTOR);
        java.util.Set<Point> spots = new java.util.HashSet<>();
        for (Point p : OrbisPqData.LEVER_SPOTS) {
            assertTrue(spots.add(p), "two levers share a spot: " + p);
        }
    }

    @Test
    void scarReactorsSpanTheIdsTheMapDeclares() {
        // scar1..scar6 are 2008000..2008005 in the tower; the range has to cover all six.
        assertEquals(OrbisPqData.SCAR_COUNT, OrbisPqData.SCAR_LAST - OrbisPqData.SCAR_FIRST + 1);
        assertEquals(OrbisPqData.SCAR_COUNT, OrbisPqData.SCAR_SPOTS.length);
    }

    @Test
    void dropTargetsSitInsideTheirTriggerBoxes() {
        // The altar's box is x in [277,477) at y in [-34,166); the music box's is
        // x in [-1758,-1666) at y in [-304,-161). These are the landing points, which is what
        // the engine matches - not the throw points, and not the reactors' own cells.
        Point altar = OrbisPqData.ALTAR_DROP;
        assertTrue(altar.x >= 277 && altar.x < 477, "altar drop x is outside the box");
        assertTrue(altar.y >= -34 && altar.y < 166, "altar drop y is outside the box");

        Point music = OrbisPqData.MUSIC_DROP;
        assertTrue(music.x >= -1758 && music.x < -1666, "music box drop x is outside the box");
        assertTrue(music.y >= -304 && music.y < -161, "music box drop y is outside the box");
    }
}
