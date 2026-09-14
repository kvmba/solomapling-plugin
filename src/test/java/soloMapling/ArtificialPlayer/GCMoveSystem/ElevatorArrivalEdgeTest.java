package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/*
 * Regression guard for the Helios Tower elevator (222020100 2F <-> 222020200 99F).
 *
 * The ride is two halves. The floor->car half is a BotScriptedWarp edge (the "in00" portal is
 * scripted and drops you in the car, not on the far floor). This test pins the OTHER half — the
 * car's arrival after the Elevator event lands it — which belongs in GCTaxi.VEHICLE_RIDES like
 * every other vehicle's landing. It was missing, and without it the two floors sit in separate
 * portal components: route() can never cross the tower, so GCTravel bare-warps the whole trip and
 * no bot ever rides the elevator.
 *
 * GCTaxi is package-private, so this lives in its own package; it needs no WZ or server (the ride
 * tables are static).
 */
class ElevatorArrivalEdgeTest {

    private static boolean destinationsContain(int fromMapId, int toMapId) {
        return Arrays.stream(GCTaxi.destinations(fromMapId)).anyMatch(d -> d == toMapId);
    }

    @Test
    void upCarIsARoutableHopToTheTopFloor() {
        // The up car (222020110) must name the 99th floor (222020200) as a destination, or the
        // ascent has no second half to route.
        assertTrue(destinationsContain(222020110, 222020200),
                "the up car must route to the 99th floor, or the elevator is unreachable");
        GCTaxi.VehicleEdge e = GCTaxi.vehicle(222020110, 222020200);
        assertNotNull(e, "vehicle() must resolve the up-car arrival edge");
        assertEquals("Elevator", e.eventName());
    }

    @Test
    void downCarIsARoutableHopToTheGroundFloor() {
        assertTrue(destinationsContain(222020210, 222020100),
                "the down car must route to the 2nd floor, or the descent is a one-way trap");
        GCTaxi.VehicleEdge e = GCTaxi.vehicle(222020210, 222020100);
        assertNotNull(e, "vehicle() must resolve the down-car arrival edge");
        assertEquals("Elevator", e.eventName());
    }

    @Test
    void theBoardingFloorsStillDoNotAdvertiseTheFarFloorDirectly() {
        // The floor->car hop is BotScriptedWarp's, not GCTaxi's, and the car is a vehicle map
        // GCTravel early-returns on. GCTaxi must NOT claim the floor reaches the far floor: that
        // would route a hop GCTravel would then try to walk an NPC for and fail.
        assertTrue(!destinationsContain(222020100, 222020200),
                "2F reaches the car through the scripted portal, not a GCTaxi edge to 99F");
    }
}
