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
    void theTwoFloorsAreTheLoiterFloors() {
        // GCTravel only idles/walks-in on the boarding floors, so this must line up exactly with
        // "has a door flag" and nothing else.
        assertTrue(GCTransit.isElevatorFloor(222020100));
        assertTrue(GCTransit.isElevatorFloor(222020200));
        assertTrue(!GCTransit.isElevatorFloor(222020110)); // a car, not a waiting floor
        assertTrue(!GCTransit.isElevatorFloor(222020000)); // the lobby above the shaft
        assertTrue(!GCTransit.isElevatorFloor(0));
    }

    @Test
    void theLoiterSpanKeepsWaitersOnBothDoorLandings() throws Exception {
        // While the door is shut a bot strolls within +-this of the portal. Both door landings are
        // ~116px wide and flat (measured: 2F portal -139 on ledge [-198,-82]; 99F portal -133 on
        // [-195,-79]), so the span must stay inside them or a waiter strolls off the edge and falls.
        java.lang.reflect.Field f = GCTravel.class.getDeclaredField("ELEVATOR_LOITER_HALF_SPAN_PX");
        f.setAccessible(true);
        int span = (int) f.get(null);
        int[] doorX = {-139, -133};
        int[][] ledge = {{-198, -82}, {-195, -79}};
        for (int i = 0; i < doorX.length; i++) {
            assertTrue(doorX[i] - span >= ledge[i][0] && doorX[i] + span <= ledge[i][1],
                    "loiter span " + span + " pushes a waiter off landing " + i
                            + " (door x=" + doorX[i] + ", ledge " + ledge[i][0] + ".." + ledge[i][1] + ")");
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
