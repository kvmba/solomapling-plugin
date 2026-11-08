package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.server.maps.MapleMap;

import java.awt.Point;
import java.util.List;

/*
 * Immutable analytic movement plan — the shared "where / how long" contract for the LOD layer.
 *
 * It is an ordered list of baked nav-graph edges plus the cumulative traversal time at each edge
 * boundary. Every BotNavigationGraph.Edge.cost is the simulated traversal time in ms
 * (computed once at bake time by running the physics sim), so an unobserved bot's position becomes a
 * pure function of elapsed wall-clock time — no runtime physics integration. This is the
 * linchpin of cheap background movement: the CoarseExecutor "renders the
 * plan with a clock" while the physics path "renders it with a sim", and because they share the same
 * plan they can never disagree about where the bot is or how long the trip takes.
 *
 * Pure data + pure math — no mutable cursor, no side effects — so it is trivially unit-testable
 * with a fake clock.
 */
final class MovementPlan {
    enum Kind {
        IN_MAP,
        CROSS_MAP
    }

    final Kind kind;
    final int mapId;
    final List<BotNavigationGraph.Edge> edges;
    private final long[] cumStartMs; // cumStartMs[i] = elapsed time at the START of edge i
    final long totalTimeMs;

    private MovementPlan(Kind kind, int mapId, List<BotNavigationGraph.Edge> edges,
                         long[] cumStartMs, long totalTimeMs) {
        this.kind = kind;
        this.mapId = mapId;
        this.edges = edges;
        this.cumStartMs = cumStartMs;
        this.totalTimeMs = totalTimeMs;
    }

    /*
     * Build an in-map plan from an A* edge result. Returns null when there is nothing to do
     * (no edges) — the caller treats that as "already there".
     */
    static MovementPlan inMap(int mapId, List<BotNavigationGraph.Edge> edges) {
        if (edges == null || edges.isEmpty()) {
            return null;
        }
        long[] cumStart = new long[edges.size()];
        long acc = 0;
        for (int i = 0; i < edges.size(); i++) {
            cumStart[i] = acc;
            acc += Math.max(0, edges.get(i).cost); // baked traversal time (ms); clamp defensive
        }
        return new MovementPlan(Kind.IN_MAP, mapId, List.copyOf(edges), cumStart, acc);
    }

    /*
     * A walk across ONE region - the plan A* cannot express.
     *
     * <p>A* answers a same-region target with an EMPTY edge list ("walk directly there"), which
     * {@link #inMap(int, List)} deliberately reports as "no plan". The coarse path read that null
     * as "already there" and wrote the body onto the goal's pixel - a silent teleport of up to a
     * whole platform length on any map nobody was watching (the reported "the whole bot team is
     * yanked to the NPC the instant the match ends"). A direct walk is a real trip, so it is
     * modelled as one: WALK edges laid along the floor, each priced at this body's own pace so the
     * analytic clock and the physics path disagree only by the usual fraction of a tick.
     *
     * <p>The route is sampled every {@value #SURFACE_SAMPLE_PX}px and each sample is snapped DOWN
     * to the ground under it, so an interpolated position is always a point the surface really
     * carries - the same contract the baked WALK edges keep (an unobserved position is what a
     * joining player is shown, and a diagonal lerp across a step would show them a bot in the
     * air). A sample with no ground under it aborts the plan: the caller falls back to physics.
     *
     * Returns null when there is nothing to walk (the caller treats that as an arrival).
     */
    static MovementPlan surfaceWalk(MapleMap map, Point from, Point to, double walkPxPerSecond) {
        if (map == null || from == null || to == null || from.equals(to)) {
            return null;
        }
        int dx = to.x - from.x;
        int steps = Math.max(1, (int) Math.ceil(Math.abs(dx) / (double) SURFACE_SAMPLE_PX));
        List<Point> waypoints = new java.util.ArrayList<>(steps + 1);
        // The start is snapped too: the plan's first frame is what a joining player is shown, so it
        // must already be a floor point even if the caller passed a mid-slope pixel.
        Point cursor = BotPhysicsEngine.findGroundPoint(map, from);
        if (cursor == null) {
            return null;
        }
        waypoints.add(cursor);
        // Each sample is probed from the PREVIOUS sample's row, so a long ramp is followed one
        // in-band step at a time instead of being judged against the start row's absolute height.
        for (int i = 1; i <= steps; i++) {
            if (i == steps) {
                Point end = BotPhysicsEngine.findGroundPoint(map, to);
                waypoints.add(end != null ? end : new Point(to));
                break;
            }
            int x = from.x + (int) Math.round(dx * (double) i / steps);
            Point ground = BotPhysicsEngine.findGroundPoint(map, new Point(x, cursor.y));
            if (ground == null) {
                return null; // no floor under the route: not a walk the analytic path can carry
            }
            waypoints.add(ground);
            cursor = ground;
        }
        double pace = Math.max(1.0, walkPxPerSecond);
        List<BotNavigationGraph.Edge> edges = new java.util.ArrayList<>(waypoints.size() - 1);
        for (int i = 1; i < waypoints.size(); i++) {
            Point a = waypoints.get(i - 1);
            Point b = waypoints.get(i);
            // One walk step never climbs more than the slope band or drops more than the snap
            // band (the same bounds the baked WALK edges keep). A sample pair that breaks either
            // is a ledge / a hole, not a walk: hand the trip back to physics.
            int dy = b.y - a.y;
            if (dy > BotPhysicsEngine.cfg.MAX_SNAP_DROP || dy < -BotPhysicsEngine.cfg.MAX_SLOPE_UP) {
                return null;
            }
            int cost = (int) Math.max(1L, Math.round(a.distance(b) / pace * 1000.0));
            edges.add(new BotNavigationGraph.Edge(
                    0, 0, BotNavigationGraph.EdgeType.WALK, a, b, 0, 0, 0, 0, 0, 0, 0, cost));
        }
        return inMap(map.getId(), edges);
    }

    /** Ground-sampling stride for {@link #surfaceWalk}: short enough that a step-up is a real step. */
    private static final int SURFACE_SAMPLE_PX = 40;

    /*
     * Plan an in-map route on a live map+graph from startPos to targetPos. Resolves
     * both endpoints to nav regions and runs the same A* the physics path uses, so the analytic and
     * physics routes match. Returns null when either endpoint has no region or no path exists.
     *
     * <p>An EMPTY edge list is kept distinct from an unreachable target here: A* answers "same
     * region, walk straight there" with an empty path, and answers "cannot get there at all" with
     * null. Both collapse to a null plan for this API (a direct walk is not a route), but callers
     * that must tell them apart (the driver's same-region steering, reachability checks) read the
     * A* result itself, not this plan.
     */
    static MovementPlan inMap(BotNavigationGraph graph, MapleMap map, Point startPos, Point targetPos) {
        return inMap(graph, map, startPos, targetPos, true);
    }

    /*
     * As above for a specific body: {@code canBlink=false} keeps blinks out of the plan. The graph
     * is class-neutral (one map = one bake), so an unobserved bot's analytic route must apply the
     * same class filter the physics path does, or the two plans disagree about where it can go.
     */
    static MovementPlan inMap(BotNavigationGraph graph, MapleMap map, Point startPos, Point targetPos,
                              boolean canBlink) {
        if (graph == null || map == null || startPos == null || targetPos == null) {
            return null;
        }
        int startRegion = graph.findRegionId(map, startPos);
        int targetRegion = graph.findRegionId(map, targetPos);
        if (startRegion < 0 || targetRegion < 0) {
            return null;
        }
        List<BotNavigationGraph.Edge> edges =
                BotNavigationManager.findPath(graph, map, startPos, startRegion, targetRegion, targetPos, canBlink);
        return inMap(map.getId(), edges);
    }

    boolean isComplete(long elapsedMs) {
        return elapsedMs >= totalTimeMs;
    }

    /*
     * The position at elapsedMs, clamped to [0, totalTimeMs]: the point on whichever edge is in
     * flight (see pointOnEdge). Computed on demand only (lazy) — a coarse bot pays nothing per tick
     * unless something actually asks where it is.
     */
    Point positionAt(long elapsedMs) {
        if (edges.isEmpty()) {
            return null;
        }
        if (elapsedMs <= 0) {
            return new Point(edges.get(0).startPoint);
        }
        if (elapsedMs >= totalTimeMs) {
            return new Point(edges.get(edges.size() - 1).endPoint);
        }
        int i = edgeIndexAt(elapsedMs);
        BotNavigationGraph.Edge e = edges.get(i);
        long into = elapsedMs - cumStartMs[i];
        long dur = Math.max(1, e.cost);
        double t = Math.min(1.0, (double) into / dur);
        return pointOnEdge(e, t);
    }

    /*
     * Where the bot sits partway along one edge. This plan is what an UNOBSERVED bot shows the instant
     * a real player arrives — MapleMap spawns the bot at the current coarse position and the promoted
     * physics then continues from it — so the point must be one a player can believe.
     *
     * A WALK edge is the only edge that lies along one walkable surface, so it is interpolated. Every
     * other edge (JUMP / DROP / CLIMB / PORTAL) crosses empty space — between two ledges, or onto a
     * rope — so a straight lerp would float the bot in mid-air, which the player's spawn then shows
     * and the promoted physics snaps away; those quantise to a real standing point (the launch point,
     * then the landing point at the end) instead.
     */
    private static Point pointOnEdge(BotNavigationGraph.Edge e, double t) {
        if (e.type != BotNavigationGraph.EdgeType.WALK) {
            return new Point(t < 1.0 ? e.startPoint : e.endPoint);
        }
        int x = (int) Math.round(e.startPoint.x + (e.endPoint.x - e.startPoint.x) * t);
        int y = (int) Math.round(e.startPoint.y + (e.endPoint.y - e.startPoint.y) * t);
        return new Point(x, y);
    }

    /* Index of the edge in flight at elapsedMs (skips zero-duration edges by taking the last match). */
    int edgeIndexAt(long elapsedMs) {
        int idx = 0;
        for (int i = 0; i < edges.size(); i++) {
            if (elapsedMs >= cumStartMs[i]) {
                idx = i;
            } else {
                break;
            }
        }
        return idx;
    }
}
