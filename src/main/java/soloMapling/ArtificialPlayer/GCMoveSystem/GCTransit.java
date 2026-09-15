package soloMapling.ArtificialPlayer.GCMoveSystem;

import java.awt.Point;
import org.gms.client.Character;
import org.gms.scripting.event.EventManager;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Portal;
import java.util.List;
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

    // Not the dragon (200090500 / 200090510): it is a transform, not a ride. The Halfling turns a
    // bot into a dragon and warps it onto the flying map, where it simply walks east through an
    // ordinary portal to the gate map and on into the Temple — no event ever moves it. Listing it
    // here made GCTravel wait out a departure that was never going to come, until the transit
    // ceiling finally bare-warped the bot to the Temple and skipped the flight both ways.

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

    // The Helios Tower elevator's two cars (waiting + moving, each direction). The car is a vehicle
    // map like a deck, but a deck is a wide walkable space while the car is a tiny box: a bot ordered
    // in through the car's single entry portal stacks on that one pixel. GCTravel boards onto one of
    // the car's own spawn points instead so a boarding crowd lands spread, not piled.
    private static final Set<Integer> ELEVATOR_CARS = Set.of(
            222020110, 222020111, 222020210, 222020211
    );

    /* True if mapId is one of the Helios elevator's cars. */
    static boolean isElevatorCar(int mapId) {
        return ELEVATOR_CARS.contains(mapId);
    }

    /*
     * The event property whose "false" means the car is parked at this floor and the door is open.
     * The elevator's portal script picks it by floor: 2F watches "goingUp", 99F watches "goingDown".
     * Null for any other map. Split out so the floor<->flag mapping (the subtle half) is pinnable by
     * a test without a live server.
     */
    static String elevatorDoorFlag(int mapId) {
        return switch (mapId) {
            case 222020100 -> "goingUp";    // 2F: loadable while the car is NOT going up
            case 222020200 -> "goingDown";  // 99F: loadable while the car is NOT going down
            default -> null;
        };
    }

    /* True if mapId is one of the two Helios elevator boarding floors (the side you wait on). */
    static boolean isElevatorFloor(int mapId) {
        return elevatorDoorFlag(mapId) != null;
    }

    // Queue spread for the elevator door, which a bot may have to WAIT out (the door is open only
    // ~1/4 of the cycle, up to 3 min shut). While it is shut the bot just stands on its approach
    // point, so without a spread every waiting bot stacks on the one portal pixel. Slots are 20px
    // apart spanning +-40px — inside the ~116px door ledges (measured: 2F portal -139 on ledge
    // [-198,-82], 99F -133 on [-195,-79], >=55px either side).
    private static final int QUEUE_SLOTS = 5;
    private static final int QUEUE_STEP_PX = 20;

    /*
     * A stable lateral offset from the door portal for a waiting bot, as a pure function of its id:
     * the same bot always takes the same slot, with no state to roll or reset and no jitter between
     * polls. Consecutive ids land on different slots, so a crowd that arrives together fans out
     * along the ledge instead of piling up.
     */
    static int elevatorQueueOffset(int botId) {
        return (Math.floorMod(botId, QUEUE_SLOTS) - QUEUE_SLOTS / 2) * QUEUE_STEP_PX;
    }

    /*
     * Whether the elevator is currently letting passengers onto this floor, mirroring what its portal
     * script (elevator.js) checks before warping a player into the waiting car. The Helios elevator is
     * the one scripted door that can REFUSE entry: while the car is moving the flag is "true" and the
     * script turns the player back. GCTravel replaces that script with a bare changeMap, so without
     * this check a bot walking up mid-cycle slips into an empty car and sits there (stacked on the one
     * entry portal with every other bot doing the same) until a departure minutes off.
     *
     * Returns null only when there is no door to consult at all — mapId is not an elevator floor, or
     * the bot's map/channel is unreadable — and the caller steps through as before. An elevator floor
     * whose event is missing reads as SHUT (the script's own "电梯正在维修中" refusal): entering a car
     * nothing will ever move would strand the bot aboard, which is strictly worse than waiting in the
     * open where the transit ceiling can still recover it.
     */
    static Boolean elevatorDoorOpen(Character bot, int mapId) {
        String flag = elevatorDoorFlag(mapId);
        if (flag == null) {
            return null; // not an elevator floor — no door to gate
        }
        MapleMap map = bot == null ? null : bot.getMap();
        if (map == null || map.getChannelServer() == null) {
            return null; // can't read the door — don't gate on a guess
        }
        EventManager em = map.getChannelServer().getEventSM().getEventManager("Elevator");
        return em != null && "false".equals(em.getProperty(flag));
    }

    // Only the boat is ever attacked mid-crossing, and only on its decks: Boats.js spawns the
    // Balrog on Boat_to_Ellinia / Boat_to_Orbis and never in the cabins, which is what makes the
    // cabin a place to take shelter. Every other vehicle crosses unmolested.
    private static final Set<Integer> ATTACKABLE = Set.of(
            200090000, 200090010 // boat decks: to Ellinia, to Orbis
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
     * A spot at the rail, for a bot that would rather watch the water than pace the deck: the
     * outermost ledge on one side of the ship. Players lean on the rail the whole way across, so
     * some bots should too. Null when the map's terrain can't be read.
     */
    static GCMovement.Ledge railLedge(MapleMap map, boolean left) {
        if (map == null) {
            return null;
        }
        List<GCMovement.Ledge> ledges = GCMovement.walkableLedges(map);
        GCMovement.Ledge best = null;
        for (GCMovement.Ledge l : ledges) {
            if (best == null
                    || (left ? l.minX() < best.minX() : l.maxX() > best.maxX())) {
                best = l;
            }
        }
        return best;
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
}
