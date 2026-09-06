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

    // Onboard maps per vehicle event (Trains.js, Cabin.js, Genie.js, Boats.js): deck and cabin for
    // each direction.
    private static final Set<Integer> VEHICLE_MAPS = Set.of(
            200090000, 200090001, // boat to Ellinia: deck, cabin
            200090010, 200090011, // boat to Orbis: deck, cabin
            200090100, 200090110, // train to Ludibrium, train to Orbis
            200090200, 200090210, // cabin to Leafre, cabin to Orbis
            200090400, 200090410  // genie to Ariant, genie to Orbis
    );

    /* True if mapId is inside a vehicle rather than a place a bot can walk around. */
    static boolean isVehicleMap(int mapId) {
        return VEHICLE_MAPS.contains(mapId);
    }

    /*
     * The deck a bot steps onto when boarding, per the event's takeoff(): each terminal loads the
     * vehicle heading the other way. Keyed by the ride's destination, which is what says which way
     * it is going — the Orbis terminal alone dispatches three different vehicles.
     */
    static int deckFor(GCTaxi.VehicleEdge vehicle) {
        return switch (vehicle.toMapId()) {
            case 200000100 -> switch (vehicle.eventName()) {           // heading to Orbis
                case "Boats" -> 200090010;
                case "Trains" -> 200090110;
                case "Cabin" -> 200090210;
                default -> 200090410;                                  // Genie
            };
            case 101000300 -> 200090000;                               // boat to Ellinia
            case 220000100 -> 200090100;                               // train to Ludibrium
            case 240000100 -> 200090200;                               // cabin to Leafre
            default -> 200090400;                                      // genie to Ariant
        };
    }
}
