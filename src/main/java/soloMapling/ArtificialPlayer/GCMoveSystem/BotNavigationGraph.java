package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.server.maps.Foothold;
import org.gms.server.maps.MapleMap;

import java.awt.*;
import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Ported from GreenCatMS. Credit: NutNNut.
final class BotNavigationGraph implements Serializable {
    // Cached nav graphs are serialized to disk. Keep explicit serialVersionUIDs so
    // harmless method-only edits do not break cache loading; use GRAPH_VERSION for
    // intentional cache invalidation when the serialized data shape changes.
    @Serial
    private static final long serialVersionUID = 1L;

    enum EdgeType {
        WALK,
        JUMP,
        DROP,
        CLIMB,
        PORTAL,
        /**
         * A class-skill blink (法师 2nd job 快速移动 / Teleport) between two regions. Baked only
         * for a profile whose class grants it (see BotMovementProfile#teleport), validated with
         * the same 150px range / 75px vertical snap band the executor applies, so the edge is
         * exactly the blink {@code GCMovementSkills.execTeleport} will perform.
         *
         * <p>Off the mage profile these edges do not exist at all: a warrior's graph must never
         * claim a route a warrior cannot walk (the LPQ stage-5 top door, whose 100px step is
         * above the whole 35-50 jump band and below one blink).
         */
        TELEPORT
    }

    static final class Segment implements Serializable {
        // Part of the on-disk BotNavigationGraph cache schema; do not remove.
        @Serial
        private static final long serialVersionUID = 1L;

        final int footholdId;
        final int x1;
        final int y1;
        final int x2;
        final int y2;
        final int minX;
        final int maxX;
        final boolean forbidFallDown;
        final boolean collidableFromBelow;

        Segment(Foothold foothold) {
            this(foothold, false);
        }

        Segment(Foothold foothold, boolean collidableFromBelow) {
            this.footholdId = foothold.getId();
            this.x1 = foothold.getX1();
            this.y1 = foothold.getY1();
            this.x2 = foothold.getX2();
            this.y2 = foothold.getY2();
            this.minX = Math.min(x1, x2);
            this.maxX = Math.max(x1, x2);
            this.forbidFallDown = foothold.isForbidFallDown();
            this.collidableFromBelow = collidableFromBelow;
        }

        boolean containsX(int x) {
            return x >= minX && x <= maxX;
        }

        int clampX(int x) {
            if (x < minX) {
                return minX;
            }
            if (x > maxX) {
                return maxX;
            }
            return x;
        }

        Point pointAt(int x) {
            int clampedX = clampX(x);
            if (x1 == x2) {
                return new Point(clampedX, Math.min(y1, y2));
            }

            double ratio = (clampedX - x1) / (double) (x2 - x1);
            int y = (int) Math.round(y1 + (y2 - y1) * ratio);
            return new Point(clampedX, y);
        }
    }

    static final class Region implements Serializable {
        // Part of the on-disk BotNavigationGraph cache schema; do not remove.
        @Serial
        private static final long serialVersionUID = 1L;

        final int id;
        final List<Segment> segments;
        final int minX;
        final int maxX;
        final int minY;
        final int maxY;
        final boolean isRopeRegion;
        final boolean isLadder;

        Region(int id, List<Segment> segments) {
            if (segments.isEmpty()) {
                throw new IllegalArgumentException("Bot nav region requires at least one segment");
            }

            this.id = id;
            this.segments = new ArrayList<>(segments);
            this.isRopeRegion = false;
            this.isLadder = false;

            int regionMinX = Integer.MAX_VALUE;
            int regionMaxX = Integer.MIN_VALUE;
            int regionMinY = Integer.MAX_VALUE;
            int regionMaxY = Integer.MIN_VALUE;
            for (Segment segment : segments) {
                regionMinX = Math.min(regionMinX, segment.minX);
                regionMaxX = Math.max(regionMaxX, segment.maxX);
                regionMinY = Math.min(regionMinY, Math.min(segment.y1, segment.y2));
                regionMaxY = Math.max(regionMaxY, Math.max(segment.y1, segment.y2));
            }

            this.minX = regionMinX;
            this.maxX = regionMaxX;
            this.minY = regionMinY;
            this.maxY = regionMaxY;
        }

        Region(int id, int ropeX, int topY, int bottomY, boolean isLadder) {
            this.id = id;
            this.segments = List.of();
            this.isRopeRegion = true;
            this.isLadder = isLadder;
            this.minX = ropeX;
            this.maxX = ropeX;
            this.minY = topY;
            this.maxY = bottomY;
        }

        int width() {
            return Math.max(0, maxX - minX);
        }

        int height() {
            return Math.max(0, maxY - minY);
        }

        Point leftPoint() {
            return pointAt(minX);
        }

        Point centerPoint() {
            if (isRopeRegion) {
                return new Point(minX, minY + height() / 2);
            }
            return pointAt(minX + width() / 2);
        }

        Point rightPoint() {
            return pointAt(maxX);
        }

        Point pointAt(int x) {
            if (isRopeRegion) {
                return new Point(minX, minY + height() / 2);
            }
            Segment bestSegment = findBestSegment(x);
            return bestSegment.pointAt(x);
        }

        boolean isForbidFallDownAt(int x) {
            if (isRopeRegion || segments.isEmpty()) {
                return false;
            }
            return findBestSegment(x).forbidFallDown;
        }

        /**
         * The segment whose own line passes through {@code (x, y)}, or null when none does.
         *
         * <p>Distinct from {@link #pointAt(int)} on purpose: {@code pointAt} answers the segment
         * nearest on X alone, so on a region that unions several rows it reports the wrong one for
         * a bot standing on a different row.
         *
         * <p>Tolerance is the one-pixel truncation artefact {@code GROUND_LINE_SLACK_PX} already
         * covers, NOT {@code MAX_SLOPE_UP}: two shelf rows 13px apart would both be "on the slope"
         * under the looser number, which is exactly the ambiguity this removes.
         */
        Segment segmentAt(int x, int y) {
            if (isRopeRegion) {
                return null;
            }
            Segment best = null;
            int bestDy = Integer.MAX_VALUE;
            for (Segment segment : segments) {
                if (!segment.containsX(x)) {
                    continue;
                }
                int dy = Math.abs(segment.pointAt(x).y - y);
                if (dy <= BotPhysicsEngine.GROUND_LINE_SLACK_PX && dy < bestDy) {
                    best = segment;
                    bestDy = dy;
                }
            }
            return best;
        }

        /** Whether two of this region's segments are joined by a legal walking step. */
        static boolean adjacent(Segment a, Segment b) {
            if (a == null || b == null || a.footholdId == b.footholdId) {
                return false;
            }
            if (a.minX > b.maxX || b.minX > a.maxX) {
                return false;
            }
            // The x-span test alone is NOT enough: on 922010800's r27 the y=118 tread (fh227,
            // x 26..49) and the y=131 tread (fh233, x 41..106) overlap on x 41..49 while sitting
            // 13px apart - a gap no walk crosses. Only the fh226 ramp joins them, at its two
            // endpoints, which is the same test canWalkAcrossFootholds applied when it unioned
            // them.
            return endpointLandsOn(a.x1, a.y1, b)
                    || endpointLandsOn(a.x2, a.y2, b)
                    || endpointLandsOn(b.x1, b.y1, a)
                    || endpointLandsOn(b.x2, b.y2, a);
        }

        private static boolean endpointLandsOn(int x, int y, Segment other) {
            return other.containsX(x)
                    && Math.abs(other.pointAt(x).y - y) <= BotPhysicsEngine.GROUND_LINE_SLACK_PX;
        }

        /**
         * The next tread on the walk chain from {@code from} toward {@code to}, or null when they
         * are not connected through this region.
         *
         * <p>Breadth-first over the region's own adjacency, so the answer is the literal next step
         * a walking body takes. That is what lets a fold-back be expressed to an X-only steer.
         */
        Segment nextTreadToward(Segment from, Segment to) {
            if (from == null || to == null || isRopeRegion || from.footholdId == to.footholdId) {
                return null;
            }
            java.util.Map<Integer, java.util.List<Segment>> pathTo = new java.util.HashMap<>();
            java.util.ArrayDeque<Segment> queue = new java.util.ArrayDeque<>();
            pathTo.put(from.footholdId, java.util.List.of(from));
            queue.add(from);
            while (!queue.isEmpty()) {
                Segment current = queue.poll();
                java.util.List<Segment> path = pathTo.get(current.footholdId);
                for (Segment next : segments) {
                    if (pathTo.containsKey(next.footholdId) || !adjacent(current, next)) {
                        continue;
                    }
                    java.util.List<Segment> extended = new java.util.ArrayList<>(path.size() + 1);
                    extended.addAll(path);
                    extended.add(next);
                    if (next.footholdId == to.footholdId) {
                        return extended.size() >= 2 ? extended.get(1) : null;
                    }
                    pathTo.put(next.footholdId, extended);
                    queue.add(next);
                }
            }
            return null;
        }

        /**
         * The column where two adjacent treads actually join - the shared endpoint the walk chain
         * used. Deterministic for a given pair, so the goal does not drift as the bot walks.
         */
        static int sharedColumnX(Segment a, Segment b) {
            int[] ax = {a.x1, a.x2};
            int[] ay = {a.y1, a.y2};
            for (int i = 0; i < 2; i++) {
                if (endpointLandsOn(ax[i], ay[i], b)) {
                    return ax[i];
                }
            }
            int[] bx = {b.x1, b.x2};
            int[] by = {b.y1, b.y2};
            for (int i = 0; i < 2; i++) {
                if (endpointLandsOn(bx[i], by[i], a)) {
                    return bx[i];
                }
            }
            int lo = Math.max(a.minX, b.minX);
            int hi = Math.min(a.maxX, b.maxX);
            return lo <= hi ? (lo + hi) / 2 : a.minX;
        }

        private Segment findBestSegment(int x) {
            // A single-segment region needs no table at all, and a two-segment one is cheaper to
            // scan than to index: the table costs 4 bytes per column up front.
            if (segments.size() <= 2) {
                return segments.get(scanBestSegment(x));
            }
            // A wide region can carry a hundred-plus segments, and the jump bake asks pointAt()
            // once per sampled pixel per launch class per boundary probe - measured 4.6M lookups
            // for a single region (map 922000000, 7087px, 81 segments), where this scan alone was
            // 37% of the whole map's bake. The answer depends only on x, so answer it from a
            // per-column table built once.
            int[] byX = segmentByX;
            if (byX == null) {
                byX = buildSegmentByX();
                segmentByX = byX;
            }
            // Out-of-range x clamps: every segment is then beyond the same edge, so the ordering is
            // the scan's own ordering shifted by a constant (see distanceToSegment).
            int px = Math.clamp(x, minX, maxX) - minX;
            return segments.get(byX[px]);
        }

        /* Transient: derived from `segments`, never part of the cache file's data shape. */
        private transient volatile int[] segmentByX;

        /**
         * Drop the column table. It exists only to make a bake fast; across the ~1200 mob maps the
         * tables total ~20MB of int arrays that would otherwise stay resident forever. The graph
         * provider calls this once a build finishes, and any later lookup simply rebuilds it.
         */
        void releaseSegmentTable() {
            segmentByX = null;
        }

        private int[] buildSegmentByX() {
            int width = maxX - minX + 1;
            int[] out = new int[width];
            for (int i = 0; i < width; i++) {
                out[i] = scanBestSegment(minX + i);
            }
            return out;
        }

        private int scanBestSegment(int x) {
            int best = 0;
            int bestDistance = distanceToSegment(segments.get(0), x);
            for (int i = 1; i < segments.size(); i++) {
                int distance = distanceToSegment(segments.get(i), x);
                if (distance < bestDistance) {
                    best = i;
                    bestDistance = distance;
                }
            }
            return best;
        }

        private int distanceToSegment(Segment segment, int x) {
            if (segment.containsX(x)) {
                return 0;
            }
            return x < segment.minX ? segment.minX - x : x - segment.maxX;
        }
    }

    static final class Edge implements Serializable {
        // Part of the on-disk BotNavigationGraph cache schema; do not remove.
        @Serial
        private static final long serialVersionUID = 2L;

        final int fromRegionId;
        final int toRegionId;
        final EdgeType type;
        final Point startPoint;
        final Point endPoint;
        final int launchMinX;
        final int launchMaxX;
        // Ours (LPQ stage-1 shelf loop): the PRE-INSET span the builder validated pixel by pixel.
        // When the inset collapses the window (validated span < 2 x walkStep), the executor's
        // ±walkStep launch phase band around the single stamped pixel re-reaches past it onto
        // pixels the builder REJECTED (two floors down) — the chase fired, overshot, fell, and
        // looped. The phase is clamped to this span: the arc the builder validated is the arc
        // the executor flies, at any window width. NO_VALID_SPAN = "not recorded" (older caches
        // whose pre-span launchValidX fields deserialize as 0): the executor falls back to the
        // stamped window + tolerance. A real span can legitimately be [0,0] (a window at x=0),
        // which is why the sentinel is MIN_VALUE, not 0.
        static final int NO_VALID_SPAN = Integer.MIN_VALUE;
        final int launchValidMinX;
        final int launchValidMaxX;
        final int launchStepX;
        final int portalId;
        final int ropeX;
        final int ropeTopY;
        final int ropeBottomY;
        final int cost;

        Edge(int fromRegionId,
             int toRegionId,
             EdgeType type,
             Point startPoint,
             Point endPoint,
             int launchMinX,
             int launchMaxX,
             int launchStepX,
             int portalId,
             int ropeX,
             int ropeTopY,
             int ropeBottomY,
             int cost) {
            this(fromRegionId, toRegionId, type, startPoint, endPoint,
                    launchMinX, launchMaxX, launchMinX, launchMaxX, launchStepX, portalId,
                    ropeX, ropeTopY, ropeBottomY, cost);
        }

        Edge(int fromRegionId,
             int toRegionId,
             EdgeType type,
             Point startPoint,
             Point endPoint,
             int launchMinX,
             int launchMaxX,
             int launchValidMinX,
             int launchValidMaxX,
             int launchStepX,
             int portalId,
             int ropeX,
             int ropeTopY,
             int ropeBottomY,
             int cost) {
            this.fromRegionId = fromRegionId;
            this.toRegionId = toRegionId;
            this.type = type;
            this.startPoint = new Point(startPoint);
            this.endPoint = new Point(endPoint);
            this.launchMinX = Math.min(launchMinX, launchMaxX);
            this.launchMaxX = Math.max(launchMinX, launchMaxX);
            // Legacy v68 caches carry 0,0 here for "not recorded" (the fields did not exist on
            // disk, so deserialization fills both with 0). A 0,0 input where the STAMPED window
            // itself is wider than the single pixel 0 is unambiguously legacy -> normalize to
            // the sentinel. A 0,0 input on a stamped [0,0] window (x=0) is genuinely ambiguous —
            // a real validated span at x=0 IS [0,0] — and it is resolved conservatively as REAL:
            // the executor's phase band around a one-pixel window is exactly the overfly shape
            // the span exists to stop, while a legacy edge at x=0 loses only the (irrelevant for
            // a 1px window) phase tolerance.
            int vMin = launchValidMinX;
            int vMax = launchValidMaxX;
            if (vMin == 0 && vMax == 0) {
                if (this.launchMinX != 0 || this.launchMaxX != 0) {
                    vMin = NO_VALID_SPAN;
                    vMax = NO_VALID_SPAN;
                }
            }
            this.launchValidMinX = Math.min(vMin, vMax);
            this.launchValidMaxX = Math.max(vMin, vMax);
            this.launchStepX = launchStepX;
            this.portalId = portalId;
            this.ropeX = ropeX;
            this.ropeTopY = ropeTopY;
            this.ropeBottomY = ropeBottomY;
            this.cost = cost;
        }

        Edge(int fromRegionId,
             int toRegionId,
             EdgeType type,
             Point startPoint,
             Point endPoint,
             int launchStepX,
             int portalId,
             int ropeX,
             int ropeTopY,
             int ropeBottomY,
             int cost) {
            this(fromRegionId, toRegionId, type, startPoint, endPoint,
                    startPoint.x, startPoint.x, launchStepX, portalId, ropeX, ropeTopY, ropeBottomY, cost);
        }

        boolean containsLaunchX(int x) {
            return x >= launchMinX && x <= launchMaxX;
        }

        boolean containsLaunchX(int x, int tolerance) {
            return x >= launchMinX - tolerance && x <= launchMaxX + tolerance;
        }

        /**
         * Whether the executor's launch phase may fire from {@code x}: inside the stamped window
         * widened by {@code tolerance}, AND inside the pre-inset validated span when the edge
         * carries one. The clamp is what stops a collapsed window's phase band from re-reaching
         * rejected pixels (the LPQ shelf loop).
         */
        boolean acceptsLaunchX(int x, int tolerance) {
            if (!containsLaunchX(x, tolerance)) {
                return false;
            }
            if (launchValidMinX != NO_VALID_SPAN || launchValidMaxX != NO_VALID_SPAN) {
                return x >= launchValidMinX && x <= launchValidMaxX;
            }
            return true;
        }

        /* Launch point at the in-window x nearest to x (the x execution would actually fire from). */
        Point pointAtNearestLaunchX(int x) {
            return new Point(Math.clamp(x, launchMinX, launchMaxX), startPoint.y);
        }
    }

    final int mapId;
    final int version;
    final BotMovementProfile movementProfile;
    final List<Region> regions;
    final Map<Integer, Region> regionsById;
    final Map<Integer, Integer> regionIdByFootholdId;
    final Map<Integer, List<Edge>> outgoingByRegionId;
    final java.util.Set<Integer> collidableWallIds;
    final java.util.Set<Integer> collidableFromBelowIds;

    /**
     * Drop every region's column table. Those tables only exist to make a bake fast; across the
     * ~1200 mob maps they total ~20MB of int arrays, and once a map is baked the graph is queried
     * at a few dozen lookups per second, where the plain scan is free. Lookups rebuild on demand.
     */
    void releaseRegionTables() {
        for (Region region : regions) {
            region.releaseSegmentTable();
        }
    }

    BotNavigationGraph(int mapId,
                       int version,
                       BotMovementProfile movementProfile,
                       List<Region> regions,
                       Map<Integer, Region> regionsById,
                       Map<Integer, Integer> regionIdByFootholdId,
                       Map<Integer, List<Edge>> outgoingByRegionId,
                       java.util.Set<Integer> collidableWallIds) {
        this(mapId, version, movementProfile, regions, regionsById, regionIdByFootholdId, outgoingByRegionId, collidableWallIds, java.util.Set.of());
    }

    BotNavigationGraph(int mapId,
                       int version,
                       BotMovementProfile movementProfile,
                       List<Region> regions,
                       Map<Integer, Region> regionsById,
                       Map<Integer, Integer> regionIdByFootholdId,
                       Map<Integer, List<Edge>> outgoingByRegionId,
                       java.util.Set<Integer> collidableWallIds,
                       java.util.Set<Integer> collidableFromBelowIds) {
        this.mapId = mapId;
        this.version = version;
        this.movementProfile = movementProfile;
        this.regions = new ArrayList<>(regions);
        this.regionsById = new HashMap<>(regionsById);
        this.regionIdByFootholdId = new HashMap<>(regionIdByFootholdId);
        this.outgoingByRegionId = new HashMap<>();
        for (Map.Entry<Integer, List<Edge>> entry : outgoingByRegionId.entrySet()) {
            this.outgoingByRegionId.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        this.collidableWallIds = new java.util.HashSet<>(collidableWallIds);
        this.collidableFromBelowIds = new java.util.HashSet<>(collidableFromBelowIds);
    }

    BotNavigationGraph(int mapId,
                       int version,
                       List<Region> regions,
                       Map<Integer, Region> regionsById,
                       Map<Integer, Integer> regionIdByFootholdId,
                       Map<Integer, List<Edge>> outgoingByRegionId,
                       java.util.Set<Integer> collidableWallIds) {
        this(mapId, version, BotMovementProfile.base(), regions, regionsById, regionIdByFootholdId, outgoingByRegionId, collidableWallIds, java.util.Set.of());
    }

    BotNavigationGraph(int mapId,
                       int version,
                       List<Region> regions,
                       Map<Integer, Region> regionsById,
                       Map<Integer, Integer> regionIdByFootholdId,
                       Map<Integer, List<Edge>> outgoingByRegionId,
                       java.util.Set<Integer> collidableWallIds,
                       java.util.Set<Integer> collidableFromBelowIds) {
        this(mapId, version, BotMovementProfile.base(), regions, regionsById, regionIdByFootholdId, outgoingByRegionId, collidableWallIds, collidableFromBelowIds);
    }

    Region getRegion(int regionId) {
        return regionsById.get(regionId);
    }

    List<Edge> getOutgoing(int regionId) {
        return outgoingByRegionId.getOrDefault(regionId, List.of());
    }

    boolean hasInterRegionEdge(int fromRegionId, int toRegionId) {
        for (Edge edge : getOutgoing(fromRegionId)) {
            if (edge.fromRegionId != edge.toRegionId && edge.toRegionId == toRegionId) {
                return true;
            }
        }
        return false;
    }

    Set<Integer> getMutualAdjacentRegionIds(int regionId) {
        Set<Integer> adjacent = new HashSet<>();
        for (Edge edge : getOutgoing(regionId)) {
            if (edge.fromRegionId == edge.toRegionId) {
                continue;
            }
            if (hasInterRegionEdge(edge.toRegionId, regionId)) {
                adjacent.add(edge.toRegionId);
            }
        }
        return adjacent;
    }

    int findRegionId(MapleMap map, Point position) {
        if (position == null || map.getFootholds() == null) {
            return -1;
        }

        Foothold foothold = BotPhysicsEngine.findGroundFoothold(map, position);
        if (foothold != null) {
            int regionId = regionIdByFootholdId.getOrDefault(foothold.getId(), -1);
            if (regionId >= 0) {
                return regionId;
            }
        }

        return findRopeRegionId(position);
    }

    int findRopeRegionId(Point position) {
        for (Region region : regions) {
            if (!region.isRopeRegion) {
                continue;
            }
            if (Math.abs(position.x - region.minX) <= BotPhysicsEngine.cfg.ROPE_GRAB_X
                    && position.y >= region.minY
                    && position.y <= region.maxY) {
                return region.id;
            }
        }
        return -1;
    }

    /*
     * Nearest rope region whose Y-span contains position and whose X is within xTol of it (the
     * closest rope wins when several qualify). findRopeRegionId is the tight grip column
     * (ROPE_GRAB_X) used for grip/current-region lookups; this wider form is for a mid-air TARGET
     * such as a portal that hangs a little off the rope's axis — see
     * BotNavigationManager.resolvePointTargetRegionId. The rope must still span position.y, so a
     * climbing bot physically passes the target's height.
     */
    int findNearestRopeRegionId(Point position, int xTol) {
        int best = -1;
        int bestDx = Integer.MAX_VALUE;
        for (Region region : regions) {
            if (!region.isRopeRegion) {
                continue;
            }
            int dx = Math.abs(position.x - region.minX);
            if (dx <= xTol
                    && position.y >= region.minY
                    && position.y <= region.maxY
                    && dx < bestDx) {
                best = region.id;
                bestDx = dx;
            }
        }
        return best;
    }
}
