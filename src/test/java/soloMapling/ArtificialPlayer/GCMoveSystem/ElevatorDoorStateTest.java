package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/*
 * Regression guard for the Helios Tower elevator's door gate.
 *
 * The elevator's portal script (elevator.js) only lets a player into the waiting car while the car is
 * parked at that floor, and turns them back ("the elevator is moving") mid-cycle. GCTravel replaces the
 * script with a bare changeMap, so it has to reproduce that gate itself — otherwise a bot walking up
 * mid-cycle slips into an empty car and, with every other arriving bot doing the same, they pile onto
 * the car's single entry portal until a departure minutes off. These tests pin the subtle half: which
 * floor watches which event flag (2F "goingUp", 99F "goingDown"), and that the car maps are recognised.
 *
 * GCTransit is package-private in places, so this lives in its own package; the tables are static, so it
 * needs no WZ or server.
 */
class ElevatorDoorStateTest {

    @Test
    void theSecondFloorWatchesGoingUp() {
        // 2F may load only while the car is NOT going up; elevator.js asks "goingUp" here.
        assertEquals("goingUp", GCTransit.elevatorDoorFlag(222020100));
    }

    @Test
    void theNinetyNinthFloorWatchesGoingDown() {
        // 99F may load only while the car is NOT going down; elevator.js asks "goingDown" here.
        assertEquals("goingDown", GCTransit.elevatorDoorFlag(222020200));
    }

    @Test
    void onlyTheTwoFloorsHaveADoorFlag() {
        // The cars themselves and every other map have no boarding door to gate.
        assertNull(GCTransit.elevatorDoorFlag(222020110));
        assertNull(GCTransit.elevatorDoorFlag(222020210));
        assertNull(GCTransit.elevatorDoorFlag(222020000));
        assertNull(GCTransit.elevatorDoorFlag(0));
    }

    @Test
    void theTwoFloorsAreTheQueueFloors() {
        // GCTravel fans the approach target only on the boarding floors, so this must line up
        // exactly with "has a door flag" and nothing else.
        assertTrue(GCTransit.isElevatorFloor(222020100));
        assertTrue(GCTransit.isElevatorFloor(222020200));
        assertTrue(!GCTransit.isElevatorFloor(222020110)); // a car, not a waiting floor
        assertTrue(!GCTransit.isElevatorFloor(222020000)); // the lobby above the shaft
        assertTrue(!GCTransit.isElevatorFloor(0));
    }

    @Test
    void theQueueOffsetIsStableAndItsSlotsDontOverlap() {
        // A bot's slot must not move between polls (or the queue would jitter): the same id gives
        // the same offset every time.
        int a = GCTransit.elevatorQueueOffset(4242);
        assertEquals(a, GCTransit.elevatorQueueOffset(4242));
        // Consecutive ids land on different slots, so a crowd that sets out together fans out.
        assertEquals(a - 20, GCTransit.elevatorQueueOffset(4241));
        assertEquals(a + 20, GCTransit.elevatorQueueOffset(4243));
    }

    @Test
    void theQueueOffsetsStayOnBothDoorLedges() {
        // Every slot must land on the ~116px door ledge of each floor (measured: 2F portal -139 on
        // ledge [-198,-82]; 99F portal -133 on [-195,-79]), or a waiting bot would be sent off the
        // edge. Check the whole range of ids maps into both ledges.
        int[] floors = {222020100, 222020200};
        int[] doorX = {-139, -133};
        int[][] ledge = {{-198, -82}, {-195, -79}};
        for (int f = 0; f < floors.length; f++) {
            for (int id = 0; id < 100; id++) {
                int x = doorX[f] + GCTransit.elevatorQueueOffset(id);
                assertTrue(x >= ledge[f][0] && x <= ledge[f][1],
                        "slot for bot " + id + " on map " + floors[f] + " is off the ledge at x=" + x);
            }
        }
    }

    @Test
    void theCarsAreRecognisedAsElevatorCars() {
        // Both directions, waiting car and moving car — these are the maps a boarding bot lands in and
        // that must be spread along the floor instead of stacked on the entry portal.
        assertTrue(GCTransit.isElevatorCar(222020110));
        assertTrue(GCTransit.isElevatorCar(222020111));
        assertTrue(GCTransit.isElevatorCar(222020210));
        assertTrue(GCTransit.isElevatorCar(222020211));
    }

    @Test
    void theFloorsAndOtherVehiclesAreNotCars() {
        assertTrue(!GCTransit.isElevatorCar(222020100));
        assertTrue(!GCTransit.isElevatorCar(222020200));
        assertTrue(!GCTransit.isElevatorCar(200090000)); // an ordinary boat deck
    }
}
