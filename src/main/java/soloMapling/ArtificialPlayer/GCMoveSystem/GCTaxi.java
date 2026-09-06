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
    record TransitEdge(int fromMapId, int npcId, int toMapId) {
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
     * Point-to-point NPC rides: {fromMapId, npcId, toMapId}. Unlike the cab network these are not
     * fully connected — each row is one direction of one route, and the return trip needs its own
     * row even when the same NPC runs it.
     *
     * Hak (2090005) flies the whale route between the continents. Orbis Sky<->Mu Lung runs through
     * the Hak event; Mu Lung<->Herb Town is a plain warp on the same NPC, but reads identically to
     * the bot (walk up, stand a beat, arrive), so it rides the same edge shape.
     */
    private static final int[][] NPC_RIDES = {
            {200000141, 2090005, 250000100}, // Hak: Orbis Sky -> Mu Lung
            {250000100, 2090005, 200000141}, // Hak: Mu Lung -> Orbis Sky
            {250000100, 2090005, 251000000}, // Hak: Mu Lung -> Herb Town
            {251000000, 2090005, 250000100}, // Hak: Herb Town -> Mu Lung
    };

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
                    edges.add(new TransitEdge(from[0], from[1], to[0]));
                }
            }
            byFrom.put(from[0], List.copyOf(edges));
        }
        for (int[] ride : NPC_RIDES) {
            // same version gate: don't offer a ride into or out of gated content
            if (!isPortalinCurrentVersion(ride[0]) || !isPortalinCurrentVersion(ride[2])) {
                continue;
            }
            byFrom.computeIfAbsent(ride[0], k -> new ArrayList<>())
                    .add(new TransitEdge(ride[0], ride[1], ride[2]));
        }
        return Map.copyOf(byFrom);
    }

    static List<TransitEdge> from(int mapId) {
        return BY_FROM.getOrDefault(mapId, List.of());
    }

    /* The NPC ride from one map to another, or null if no NPC drives that route. */
    static TransitEdge edge(int fromMapId, int toMapId) {
        for (TransitEdge e : from(fromMapId)) {
            if (e.toMapId() == toMapId) {
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
