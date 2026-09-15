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
