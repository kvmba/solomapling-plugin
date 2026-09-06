package soloMapling.ArtificialPlayer.GCMoveSystem;

import java.util.Set;

/*
 * The maps a scheduled ride owns: the decks and cabins a bot sits on while a boat/train/cabin/genie
 * carries it across. A bot standing here is mid-crossing, not lost — GCTravel has to leave it alone
 * and let the vehicle event land it, because there is no portal off a deck to walk to.
 *
 * Ids come from the vehicle event scripts, which map each ride's onboard maps.
 */
final class GCTransit {
    private GCTransit() {
    }

    // Boat decks and cabins (Boats.js): to Ellinia and to Orbis.
    private static final Set<Integer> VEHICLE_MAPS = Set.of(
            200090000, 200090001, // boat to Ellinia: deck, cabin
            200090010, 200090011  // boat to Orbis: deck, cabin
    );

    /* True if mapId is inside a vehicle rather than a place a bot can walk around. */
    static boolean isVehicleMap(int mapId) {
        return VEHICLE_MAPS.contains(mapId);
    }

    /*
     * The deck a bot steps onto when boarding from a terminal, per the event's takeoff(): each
     * terminal loads the boat heading the other way.
     */
    static int deckFor(GCTaxi.VehicleEdge vehicle) {
        return vehicle.fromMapId() == 101000301 ? 200090010 : 200090000;
    }
}
