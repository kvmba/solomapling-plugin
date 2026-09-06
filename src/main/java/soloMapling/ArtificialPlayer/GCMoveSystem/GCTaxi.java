package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.server.life.NPC;
import org.gms.server.maps.MapleMap;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static soloMapling.server.MapleVersionManager.isPortalinCurrentVersion;

/*
 * NPC rides: connectivity the walkable portal graph can't express. Town↔town on Victoria Island has
 * no walkable portal path, and neither does a whale flight between continents, so GCTravel can't
 * reach them by walking. This adds those edges (so routing finds them) and the executor rides them
 * by walking to the NPC, standing there a moment, then moving to the destination — "walk up, ride."
 * No fares/economy (pure-movement scope).
 */
// Ported from GreenCatMS. Credit: NutNNut.
final class GCTaxi {
    private GCTaxi() {
    }

    /*
     * How long a bot stands at the cab before it "drives off" (random inside this band).
     *
     * NOT shared with PORTAL_ENTER_DWELL_MS: stepping through a portal is instantaneous in the
     * client's eyes (the sprite is simply on the next map), so 350 ms there is only packet-ordering
     * slack. A cab ride is a transaction — a player walks up, talks to the driver, pays, and the
     * screen fades — so a bot that vanishes the instant it reaches the cab reads exactly like the
     * bare warp it actually is. Standing at the cab for a few seconds is what sells "it took a cab".
     */
    static final long BOARD_DWELL_MIN_MS = 2_000;
    static final long BOARD_DWELL_MAX_MS = 6_000;

    /* An NPC ride: stand near npcId on fromMapId, then ride to toMapId. */
    record TransitEdge(int fromMapId, int npcId, int toMapId, int minLevel) {
    }

    // {townMapId, cabNpcId} — the fully-connected Victoria Island cab network.
    private static final int[][] VICTORIA_CABS = {
            {104000000, 1002007}, // Lith Harbor
            {100000000, 1012000}, // Henesys
            {102000000, 1022001}, // Perion
            {101000000, 1032000}, // Ellinia
            {103000000, 1052016}, // Kerning City
            {120000000, 1092014}, // Nautilus Harbor
    };

    /*
     * Point-to-point NPC rides: {fromMapId, npcId, toMapId, minLevel}. Unlike the cab network these are not
     * fully connected — each row is one direction of one route, and the return trip needs its own
     * row even when the same NPC runs it.
     *
     * Hak (2090005) flies the whale route between the continents. Orbis Sky<->Mu Lung runs through
     * the Hak event; Mu Lung<->Herb Town is a plain warp on the same NPC, but reads identically to
     * the bot (walk up, stand a beat, arrive), so it rides the same edge shape.
     *
     * The Shanghai flight needs the zh-CN script pack: 9310000 flies Perion -> Bund and 9310013
     * flies back from the Plaza. Outbound and inbound are different NPCs because each one only
     * offers the leg that starts on its own continent.
     *
     * Sanks (22000) rows the beginner island's boat out to Lith Harbor. There is deliberately no
     * return row: his own script says you can never come back, and nothing in the game leads into
     * the island anyway, so a return edge would be a promise the server can't keep.
     */
    /*
     * Leaving the beginner island earns its own bar: Sanks asks only 7 (his script checks level > 6),
     * but this keeps a bot on the island's hunting grounds a little longer before it goes.
     */
    static final int LEAVE_BEGINNER_LEVEL = 8;

    /* The Time Temple's bar, mirrored here for the dragon flight that is its only way in. */
    private static final int TIME_TEMPLE_LEVEL = 90;

    // {fromMapId, npcId, toMapId, minLevel}
    private static final int[][] NPC_RIDES = {
            {200000141, 2090005, 250000100, 1},  // Hak: Orbis Sky -> Mu Lung
            {250000100, 2090005, 200000141, 1},  // Hak: Mu Lung -> Orbis Sky
            {250000100, 2090005, 251000000, 1},  // Hak: Mu Lung -> Herb Town
            {251000000, 2090005, 250000100, 1},  // Hak: Herb Town -> Mu Lung
            {60000, 22000, 104000000, LEAVE_BEGINNER_LEVEL}, // Sanks' boat off Southperry
            {102000000, 9310000, 701000000, 1},  // Pilot Hong: Perion -> Shanghai Bund
            {701000100, 9310013, 102000000, 1},  // Pilot Hong: Shanghai Plaza -> Perion
            {220000110, 2041000, 220000111, 1},  // Ludibrium pier -> waiting room (train)
            {200000121, 2012013, 200000122, 1},  // Orbis pier -> waiting room (train)
            {200000151, 2012025, 200000152, 1},  // Orbis pier -> airport (genie)
            {260000100, 2102000, 260000110, 1},  // Ariant platform -> waiting room (genie)
            {600010001, 9201068, 600010002, 1},  // NLC station -> waiting room (subway)
            {540010000, 9270038, 540010001, 1},  // CBD airport -> waiting room (plane)
            {103000000, 9270041, 540010100, 1},  // Kerning City -> airport (plane)
            // The dragon flight only ever leads to the Time Temple, so it carries the Temple's bar:
            // a bot that hasn't earned that continent shouldn't reach it by turning into a dragon.
            {240000110, 2082003, 200090500, TIME_TEMPLE_LEVEL},
            // Gold Beach: a holiday island with no portal in, so the three boats that run to it
            // (from Lith Harbour, Orbis and Ludibrium) are the only way there.
            {104000000, 1002002, 110000000, 1},  // Lith Harbour -> Gold Beach
            {200000000, 2010005, 110000000, 1},  // Orbis -> Gold Beach
            {220000000, 2040048, 110000000, 1},  // Ludibrium -> Gold Beach
            // Maple Travel Agency (导游妮妮, 9000020): stands in every major town and runs the
            // world tour. The zh-CN script offers five destinations and lets the traveller pick,
            // so each town she stands in reaches all five — unlike the English script, which
            // decides the destination from the map and only ever offered Japan and Malaysia.
            {100000000, 9000020, 701000000, 1},  {100000000, 9000020, 702000000, 1},
            {100000000, 9000020, 541000000, 1},  {100000000, 9000020, 550000000, 1},
            {100000000, 9000020, 800000000, 1},
            {101000000, 9000020, 701000000, 1},  {101000000, 9000020, 702000000, 1},
            {101000000, 9000020, 541000000, 1},  {101000000, 9000020, 550000000, 1},
            {101000000, 9000020, 800000000, 1},
            {102000000, 9000020, 701000000, 1},  {102000000, 9000020, 702000000, 1},
            {102000000, 9000020, 541000000, 1},  {102000000, 9000020, 550000000, 1},
            {102000000, 9000020, 800000000, 1},
            {103000000, 9000020, 701000000, 1},  {103000000, 9000020, 702000000, 1},
            {103000000, 9000020, 541000000, 1},  {103000000, 9000020, 550000000, 1},
            {103000000, 9000020, 800000000, 1},
            {104000000, 9000020, 701000000, 1},  {104000000, 9000020, 702000000, 1},
            {104000000, 9000020, 541000000, 1},  {104000000, 9000020, 550000000, 1},
            {104000000, 9000020, 800000000, 1},
            {200000000, 9000020, 701000000, 30}, {200000000, 9000020, 702000000, 30},
            {200000000, 9000020, 541000000, 30}, {200000000, 9000020, 550000000, 30},
            {200000000, 9000020, 800000000, 30},
            {220000000, 9000020, 701000000, 30}, {220000000, 9000020, 702000000, 30},
            {220000000, 9000020, 541000000, 30}, {220000000, 9000020, 550000000, 30},
            {220000000, 9000020, 800000000, 30},
            {240000000, 9000020, 701000000, 70}, {240000000, 9000020, 702000000, 70},
            {240000000, 9000020, 541000000, 70}, {240000000, 9000020, 550000000, 70},
            {240000000, 9000020, 800000000, 70},
            {250000000, 9000020, 701000000, 30}, {250000000, 9000020, 702000000, 30},
            {250000000, 9000020, 541000000, 30}, {250000000, 9000020, 550000000, 30},
            {250000000, 9000020, 800000000, 30},
            {260000000, 9000020, 701000000, 30}, {260000000, 9000020, 702000000, 30},
            {260000000, 9000020, 541000000, 30}, {260000000, 9000020, 550000000, 30},
            {260000000, 9000020, 800000000, 30},
    };

    /*
     * Scheduled vehicles: a waiting room, the inspector who boards you there, where the ride lands,
     * and the event that sails it. These are not warps — boarding only opens while the event's
     * "entry" is true, and it is the event, not us, that moves the bot onto the deck and later off
     * it (takeoff/arrived warp the whole map). So the bot walks to the inspector and then waits;
     * see GCTravel.awaitVehicle. Getting into the room in the first place is a ticket-counter row
     * in NPC_RIDES above.
     */
    private static final VehicleEdge[] VEHICLE_RIDES = {
            new VehicleEdge(101000301, 1032009, 200000100, "Boats"),    // Ellinia -> Orbis Station
            new VehicleEdge(200000112, 2012002, 101000300, "Boats"),    // Orbis -> Ellinia dock
            new VehicleEdge(200000122, 2041001, 220000100, "Trains"),   // Orbis -> Ludibrium
            new VehicleEdge(220000111, 2041001, 200000100, "Trains"),   // Ludibrium -> Orbis
            new VehicleEdge(200000132, 2012022, 240000100, "Cabin"),    // Orbis -> Leafre station
            new VehicleEdge(240000111, 2082002, 200000100, "Cabin"),    // Leafre -> Orbis
            new VehicleEdge(200000152, 2012024, 260000100, "Genie"),    // Orbis -> Ariant
            new VehicleEdge(260000110, 2102001, 200000100, "Genie"),    // Ariant -> Orbis
            new VehicleEdge(103000100, 9201057, 600010001, "Subway"),   // Kerning -> New Leaf City
            new VehicleEdge(600010002, 9201057, 103000100, "Subway"),   // NLC -> Kerning
            new VehicleEdge(540010100, 9270017, 540010000, "AirPlane"), // Kerning -> CBD
            new VehicleEdge(540010001, 9270018, 103000000, "AirPlane"), // CBD -> Kerning City
    };

    /* The scheduled vehicle from mapId to toMapId, or null if none leaves there for it. */
    static VehicleEdge vehicle(int fromMapId, int toMapId) {
        for (VehicleEdge ride : VEHICLE_RIDES) {
            if (ride.fromMapId() == fromMapId && ride.toMapId() == toMapId
                    && isPortalinCurrentVersion(ride.fromMapId())
                    && isPortalinCurrentVersion(ride.toMapId())) {
                return ride;
            }
        }
        return null;
    }

    record VehicleEdge(int fromMapId, int npcId, int toMapId, String eventName) {
    }

    private static final Map<Integer, List<TransitEdge>> BY_FROM = buildEdges();

    private static Map<Integer, List<TransitEdge>> buildEdges() {
        Map<Integer, List<TransitEdge>> byFrom = new HashMap<>();
        for (int[] from : VICTORIA_CABS) {
            if (!isPortalinCurrentVersion(from[0])) {
                continue; // never ride from a town gated out of this version
            }
            List<TransitEdge> edges = new ArrayList<>();
            for (int[] to : VICTORIA_CABS) {
                // skip self, and any destination town gated out of the current server version
                if (from[0] != to[0] && isPortalinCurrentVersion(to[0])) {
                    edges.add(new TransitEdge(from[0], from[1], to[0], 1));
                }
            }
            byFrom.put(from[0], edges);
        }
        for (int[] ride : NPC_RIDES) {
            // same version gate: don't offer a ride into or out of gated content
            if (!isPortalinCurrentVersion(ride[0]) || !isPortalinCurrentVersion(ride[2])) {
                continue;
            }
            // a ride may start in a town that already has cabs — append, don't replace
            byFrom.computeIfAbsent(ride[0], k -> new ArrayList<>())
                    .add(new TransitEdge(ride[0], ride[1], ride[2], ride[3]));
        }
        Map<Integer, List<TransitEdge>> frozen = new HashMap<>();
        byFrom.forEach((k, v) -> frozen.put(k, List.copyOf(v)));
        return Map.copyOf(frozen);
    }

    static List<TransitEdge> from(int mapId) {
        return BY_FROM.getOrDefault(mapId, List.of());
    }

    /* The NPC ride from one map to another, or null if no NPC drives that route. */
    static TransitEdge edge(int fromMapId, int toMapId, int level) {
        for (TransitEdge e : from(fromMapId)) {
            if (e.toMapId() == toMapId && level >= e.minLevel()) {
                return e;
            }
        }
        return null;
    }

    /* All ride destination map ids reachable from mapId (for world-graph connectivity). */
    static int[] destinations(int mapId) {
        List<TransitEdge> edges = from(mapId);
        int[] dests = new int[edges.size()];
        for (int i = 0; i < edges.size(); i++) {
            dests[i] = edges.get(i).toMapId();
        }
        return dests;
    }

    /* Live position of the cab NPC on map, or null if it isn't present/loaded. */
    static Point npcPos(MapleMap map, int npcId) {
        if (map == null) {
            return null;
        }
        NPC npc = map.getNPCById(npcId);
        return npc == null ? null : npc.getPosition();
    }
}
