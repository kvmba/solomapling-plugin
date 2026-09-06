package soloMapling.ArtificialPlayer.GCMoveSystem;

import java.awt.Point;
import org.gms.client.Character;
import org.gms.scripting.event.EventManager;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Portal;
import java.util.Set;

/*
 * The maps a scheduled ride owns: the decks and cabins a bot sits on while a boat/train/cabin/genie
 * carries it across. A bot standing here is mid-crossing, not lost — GCTravel has to leave it alone
 * and let the vehicle event land it, because there is no portal off a deck to walk to.
 *
 * Ids come from the vehicle event scripts, which map each ride's onboard maps.
 */
public final class GCTransit {
    private GCTransit() {
    }

    // Onboard maps per vehicle event (Trains.js, Cabin.js, Genie.js, Boats.js): deck and cabin for
    // each direction.
    private static final Set<Integer> VEHICLE_MAPS = Set.of(
            200090000, 200090001, // boat to Ellinia: deck, cabin
            200090010, 200090011, // boat to Orbis: deck, cabin
            200090100, 200090110, // train to Ludibrium, train to Orbis
            200090200, 200090210, // cabin to Leafre, cabin to Orbis
            200090400, 200090410, // genie to Ariant, genie to Orbis
            222020110, 222020111, // Helios elevator going up: waiting car, moving car
            222020210, 222020211, // Helios elevator going down: waiting car, moving car
            600010003, 600010005, // subway to New Leaf City, subway to Kerning
            540010101, 540010002  // plane to CBD, plane to Kerning
    );

    /*
     * Vehicles with room to walk around: a deck or a train car you can stroll while crossing. An
     * elevator is ridden out standing in a small box, so it is waited, not wandered.
     */
    private static final Set<Integer> SPACIOUS = Set.of(
            200090000, 200090001, 200090010, 200090011,
            200090100, 200090110, 200090200, 200090210,
            200090400, 200090410,
            600010003, 600010005 // subway cars — roomy enough to stroll
    );

    /* True if mapId is inside a vehicle rather than a place a bot can walk around. */
    public static boolean isVehicleMap(int mapId) {
        return VEHICLE_MAPS.contains(mapId);
    }

    /* True if this vehicle is big enough that a bot should stroll it rather than stand still. */
    static boolean isSpaciousVehicle(int mapId) {
        return SPACIOUS.contains(mapId);
    }

    // Only the boat is ever attacked mid-crossing (Boats.js spawns a Balrog on its decks); the
    // other events carry passengers without incident, so this stays keyed to the boat's decks.
    private static final Set<Integer> ATTACKABLE = Set.of(
            200090000, 200090001, 200090010, 200090011
    );

    /*
     * Whether something has boarded mid-crossing. Only the boat does this, and it flags it on the
     * event so the decks can react; every other vehicle crosses unmolested.
     */
    static boolean isUnderAttack(Character bot) {
        MapleMap map = bot == null ? null : bot.getMap();
        if (map == null || map.getChannelServer() == null || !ATTACKABLE.contains(bot.getMapId())) {
            return false;
        }
        EventManager em = map.getChannelServer().getEventSM().getEventManager("Boats");
        return em != null && "true".equals(em.getProperty("haveBalrog"));
    }

    /*
     * Where to go when something boards: the hatch down to the cabin (the deck's "in00" portal),
     * which is the same way players duck below. Null if this map has no hatch.
     */
    static Point hatchPos(MapleMap map) {
        if (map == null) {
            return null;
        }
        Portal hatch = map.getPortal("in00");
        return hatch == null ? null : hatch.getPosition();
    }
    /*
     * The deck a bot steps onto when boarding, per the event's takeoff(): each terminal loads the
     * vehicle heading the other way. Keyed on the vehicle first and then the direction — several
     * terminals dispatch more than one vehicle, and Kerning dispatches both a subway and a plane.
     */
    static int deckFor(GCTaxi.VehicleEdge vehicle) {
        return switch (vehicle.eventName()) {
            case "Boats" -> vehicle.toMapId() == 200000100 ? 200090010 : 200090000;
            case "Trains" -> vehicle.toMapId() == 200000100 ? 200090110 : 200090100;
            case "Cabin" -> vehicle.toMapId() == 200000100 ? 200090210 : 200090200;
            case "Genie" -> vehicle.toMapId() == 200000100 ? 200090410 : 200090400;
            case "Subway" -> vehicle.toMapId() == 600010001 ? 600010003 : 600010005;
            case "AirPlane" -> vehicle.toMapId() == 540010000 ? 540010101 : 540010002;
            default -> vehicle.toMapId() == 200000100 ? 200090010 : 200090000;
        };
    }
}
