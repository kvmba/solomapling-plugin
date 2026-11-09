package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.server.maps.Foothold;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Portal;
import org.gms.server.maps.Rope;

import java.util.HashMap;
import java.util.Map;

/**
 * Bakes a test map's nav graph only when that map's GEOMETRY actually changed.
 *
 * <p>{@code rebuildGraph} replaces the in-memory graph for a map id, which a test needs whenever it
 * hand-builds a room ({@code new MapleMap(...)} + inserted footholds): several synthetic rooms reuse
 * a real map id, and the bake must follow the geometry under test rather than a graph another test
 * cached. Calling it unconditionally is pure waste in the common case, though - a test that loads
 * the room from WZ and never touches its footholds would re-bake an identical graph every time.
 * Measured on {@code LpqReviewRegressionTest}: 138 bakes over 69 cases, 17-19s of a ~30s class, in
 * service of at most four distinct geometries.
 *
 * <p>The signature covers everything the bake reads from the map: the foothold set (id + both
 * endpoints), the map's line boundings, its ropes and its portals. An equal signature means an
 * equal graph.
 */
final class TestGraphBake {

    private TestGraphBake() {
    }

    /** mapId -> signature of the geometry the cached graph was baked from. */
    private static final Map<Integer, String> BAKED = new HashMap<>();

    static void ensure(MapleMap map, BotMovementProfile profile) {
        String signature = signature(map);
        if (signature.equals(BAKED.get(map.getId()))) {
            return;
        }
        BotNavigationGraphProvider.rebuildGraph(map, profile);
        BAKED.put(map.getId(), signature);
    }

    /** The bake's own view of the map; footholds come back in tree order, so the text is stable. */
    private static String signature(MapleMap map) {
        StringBuilder sb = new StringBuilder();
        if (map.getMapArea() != null) {
            sb.append(map.getMapArea());
        }
        if (map.getFootholds() != null) {
            for (Foothold fh : map.getFootholds().getAllFootholds()) {
                sb.append('|').append(fh.getId())
                        .append(':').append(fh.getX1()).append(',').append(fh.getY1())
                        .append('-').append(fh.getX2()).append(',').append(fh.getY2());
            }
        }
        for (Rope rope : map.getRopes()) {
            sb.append("|r").append(rope.x()).append(',').append(rope.y1())
                    .append(',').append(rope.y2()).append(',').append(rope.isLadder());
        }
        for (Portal portal : map.getPortals()) {
            sb.append("|p").append(portal.getId()).append(':').append(portal.getPosition())
                    .append(',').append(portal.getType()).append(',').append(portal.getTargetMapId());
        }
        return sb.toString();
    }
}
