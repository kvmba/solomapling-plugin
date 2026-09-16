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
    void theWaitStrollStaysOnTheWaitersLedge() {
        // While waiting at a shared point a bot strolls within +-WAIT_STROLL_HALF_SPAN of it, but the
        // stroll is clamped to the anchor's own ledge (GCTravel.clampToSpan) so a waiter can never step
        // off the landing. Pin that clamp: every rolled offset lands inside the ledge, both door
        // landings included (2F portal -139 on ledge [-198,-82]; 99F -133 on [-195,-79]).
        int margin = 12;                       // WAIT_STROLL_EDGE_MARGIN_PX
        int span = 60;                         // WAIT_STROLL_HALF_SPAN_PX (>= the offset range)
        int[] doorX = {-139, -133};
        int[] lo = {-198, -195};
        int[] hi = {-82, -79};
        for (int f = 0; f < doorX.length; f++) {
            for (int offset = -span; offset <= span; offset++) {
                int x = GCTravel.clampToSpan(lo[f], hi[f], doorX[f], offset, margin);
                assertTrue(x >= lo[f] + margin && x <= hi[f] - margin,
                        "stroll x=" + x + " escaped landing " + f + " (" + lo[f] + ".." + hi[f] + ")");
            }
        }
    }

    @Test
    void aPointLedgeStandsStillInsteadOfStrollingOff() {
        // A ledge narrower than the margins leave room for must not send the stroller anywhere: it
        // stands on the anchor. This is the degenerate case that a naive clamp would blow past.
        int x = GCTravel.clampToSpan(100, 110, 105, 60, 12); // 11px ledge, margin 12
        assertTrue(x == 105, "a too-narrow ledge should stand still on the anchor, got x=" + x);
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
