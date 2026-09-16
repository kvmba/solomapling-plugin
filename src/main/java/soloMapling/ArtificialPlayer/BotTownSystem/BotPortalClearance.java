package soloMapling.ArtificialPlayer.BotTownSystem;

import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Portal;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

import java.awt.Point;
import java.util.List;

// Shared town doorway clearance. A bot that arrived through a portal stands at that map's entry pixel,
// and a stream of arrivals piles up there as a clump of statues. Every town consumer that moves bots
// (the stationed SocialBot crowd's walk-off, the returning-training-bot scatter, the roaming wanderer)
// needs the same two questions answered: "is this point on a doorway?" and "which of these candidate
// spots is the least door-like?".
//
// Deliberately TERRAIN-ONLY (portals + foothold ground), never the nav graph's reachability. The graph
// carries walk/jump/drop/climb/portal edges but no SWIM, and it only mints an edge when the ballistic /
// tether sim can land on a baked foothold - so an arrival platform whose only way off is a swim or an
// unmodelled hop reports "reachable = just this platform". A clearance routine that trusted that would
// find no off-door spot and leave the crowd stacked on the door. Pure portal/foothold reads avoid that.
//
// Our own creation (extracted from SocialBot's door clear).
public final class BotPortalClearance {

    private BotPortalClearance() {
    }

    // Doorway box around a portal pixel. X is what marks the door (a portal's pixel column, a tight
    // neighbourhood), Y is generous because two resting spots must be covered vertically: a bot put on
    // the portal pixel itself (a plain warp / spawn fallback) and one dropped onto the floor UNDER the
    // portal (a stroll / coarse arrival). A portal may sit well above its floor, so test both, not the
    // whole column between.
    public static final int DOORWAY_X = 40;
    public static final int DOORWAY_Y = 60;

    // A precise moveTarget counts as reached within 8px per axis (GCMovementDriver), so an off-door
    // destination must clear the doorway box by more than that or the bot could halt just inside it and
    // the clear would re-fire every tick. 16 leaves a safe margin over the 8px tolerance.
    public static final int DOORWAY_ARRIVAL_PAD = 16;

    // True when p sits inside the doorway box of any portal on the map, widened by (padX, padY).
    public static boolean onDoorway(MapleMap map, Point p) {
        return onDoorway(map, p, 0, 0);
    }

    public static boolean onDoorway(MapleMap map, Point p, int padX, int padY) {
        if (map == null || p == null) {
            return false;
        }
        for (Portal portal : map.getPortals()) {
            Point pp = portal.getPosition();
            if (pp == null || Math.abs(pp.x - p.x) > DOORWAY_X + padX) {
                continue; // not this portal's column - a doorway is an X neighbourhood
            }
            Point floor = GCMovement.groundPointBelow(map, pp.x, pp.y);
            int dyPixel = Math.abs(pp.y - p.y);
            int dyFloor = floor != null ? Math.abs(floor.y - p.y) : Integer.MAX_VALUE;
            if (Math.min(dyPixel, dyFloor) <= DOORWAY_Y + padY) {
                return true;
            }
        }
        return false;
    }

    // Squared distance from p to the nearest portal on the map (Long.MAX_VALUE when there are none).
    public static long nearestPortalDistSq(MapleMap map, Point p) {
        long best = Long.MAX_VALUE;
        if (map == null || p == null) {
            return best;
        }
        for (Portal portal : map.getPortals()) {
            Point pp = portal.getPosition();
            if (pp != null) {
                long dx = pp.x - p.x;
                long dy = pp.y - p.y;
                best = Math.min(best, dx * dx + dy * dy);
            }
        }
        return best;
    }

    // The candidate that clears the doorway box by DOORWAY_ARRIVAL_PAD on both axes AND sits farthest
    // from any portal, or null when every candidate still lands in a doorway. Callers that must not
    // re-fire (a walk that halts inside the box would clear again next tick) require the clearance
    // guarantee, so "can't clear" stays distinguishable from "got a spot".
    public static Point farthestOffDoorway(MapleMap map, List<Point> candidates) {
        if (map == null || candidates == null) {
            return null;
        }
        Point best = null;
        long bestDistSq = -1;
        for (Point s : candidates) {
            if (s == null || onDoorway(map, s, DOORWAY_ARRIVAL_PAD, DOORWAY_ARRIVAL_PAD)) {
                continue; // would halt inside the door
            }
            long d = nearestPortalDistSq(map, s);
            if (d > bestDistSq) {
                bestDistSq = d;
                best = s;
            }
        }
        return best;
    }

    // The candidate farthest from any portal, with NO clearance requirement - the overflow fallback for
    // a platform whose reachable ground all sits inside the doorway box (a narrow ledge): still spread
    // the crowd along the ledge instead of stacking it on the exact pixel. Null only for empty input.
    public static Point farthestFromPortal(MapleMap map, List<Point> candidates) {
        if (map == null || candidates == null) {
            return null;
        }
        Point best = null;
        long bestDistSq = -1;
        for (Point s : candidates) {
            if (s == null) {
                continue;
            }
            long d = nearestPortalDistSq(map, s);
            if (d > bestDistSq) {
                bestDistSq = d;
                best = s;
            }
        }
        return best;
    }
}
