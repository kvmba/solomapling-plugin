package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.server.maps.Foothold;
import org.gms.server.maps.MapleMap;

import java.awt.Point;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Landing safety is exit reachability, never merely the existence of another landing. */
final class DeadPitGuard {
    private DeadPitGuard() {
    }

    record VerdictKey(BotMovementProfile profile, BotMovementCapabilities capabilities) {
    }

    // A rebuilt graph changes the answer. Weak keys release retired maps and profiles.
    private static final Map<BotNavigationGraph, Map<VerdictKey, Map<Integer, Boolean>>> VERDICTS =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    static VerdictKey cacheKey(BotMovementProfile profile, BotMovementCapabilities capabilities) {
        return new VerdictKey(profile != null ? profile : BotMovementProfile.base(),
                capabilities != null ? capabilities : BotMovementCapabilities.NONE);
    }

    static boolean isLivableLanding(MapleMap map, Point landing, BotMovementProfile profile) {
        return isLivableLanding(map, landing, profile, BotMovementCapabilities.NONE);
    }

    static boolean isLivableLanding(MapleMap map, Point landing, BotMovementProfile profile,
                                    BotMovementCapabilities capabilities) {
        if (map == null || landing == null || map.getFootholds() == null) return true;
        Foothold foothold = BotPhysicsEngine.findGroundFoothold(map, landing);
        return foothold == null || isLivableSurface(map, foothold, profile, capabilities);
    }

    static boolean isLivableSurface(MapleMap map, Foothold foothold, BotMovementProfile profile) {
        return isLivableSurface(map, foothold, profile, BotMovementCapabilities.NONE);
    }

    static boolean isLivableSurface(MapleMap map, Foothold foothold, BotMovementProfile profile,
                                    BotMovementCapabilities capabilities) {
        if (map == null || foothold == null || map.getFootholds() == null || map.isSwim()) return true;
        BotNavigationGraph graph = BotNavigationGraphProvider.peekGraph(map, profile);
        if (graph == null) {
            BotNavigationGraphProvider.warmGraphAsync(map, profile);
            // Sparse local probes cannot prove an exit from a closed basin. Hold optional
            // departures until the exact graph is ready; never block a movement tick baking it.
            return false;
        }
        int region = graph.regionIdByFootholdId.getOrDefault(foothold.getId(), -1);
        if (region < 0) return false;
        if (!graph.deadRegionIds.contains(region)) return true;
        VerdictKey key = cacheKey(profile, capabilities);
        if (key.capabilities().equals(BotMovementCapabilities.NONE)) return false;
        Map<Integer, Boolean> verdicts = VERDICTS.computeIfAbsent(graph, ignored -> new ConcurrentHashMap<>())
                .computeIfAbsent(key, ignored -> new ConcurrentHashMap<>());
        Boolean cached = verdicts.get(region);
        if (cached != null) return cached;
        boolean live = skillReachesExit(map, graph, graph.getRegion(region), key);
        verdicts.put(region, live);
        return live;
    }

    private static boolean skillReachesExit(MapleMap map, BotNavigationGraph graph,
                                            BotNavigationGraph.Region source, VerdictKey key) {
        for (BotNavigationGraph.Segment segment : source.segments) {
            for (int x = segment.minX; x <= segment.maxX; x++) {
                Point from = segment.pointAt(x);
                if (key.capabilities().teleport()) {
                    if (safePoint(map, graph, GCMovementSkills.upTeleportLanding(map, from))) return true;
                    for (int dir : new int[]{-1, 1}) {
                        if (safePoint(map, graph, GCMovementSkills.horizontalTeleportLanding(map, from, dir))) return true;
                    }
                    Point below = BotPhysicsEngine.pointBelowIndexed(map, new Point(x, from.y + 1));
                    while (below != null && below.y - from.y <= GCMovementSkills.TELEPORT_RANGE_PX) {
                        if (safePoint(map, graph, below)) return true;
                        below = BotPhysicsEngine.pointBelowIndexed(map, new Point(x, below.y + 1));
                    }
                }
                if (key.capabilities().flashJumpScale() > 0f) {
                    for (int dir : new int[]{-1, 1}) {
                        BotPhysicsEngine.PostLandingJump stopped = BotPhysicsEngine.simulateLandingStop(map,
                                BotPhysicsEngine.simulateFlashJumpLanding(map, from, dir, key.profile(),
                                        key.capabilities().flashJumpScale()), key.profile());
                        if (stopped != null && !stopped.lostGround()
                                && safePoint(map, graph, stopped.finalPoint())) return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean safePoint(MapleMap map, BotNavigationGraph graph, Point point) {
        if (point == null) return false;
        int region = graph.findRegionId(map, point);
        return region >= 0 && !graph.deadRegionIds.contains(region);
    }
}
