package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.provider.wz.WZFiles;
import org.gms.server.maps.Foothold;
import org.gms.server.maps.MapleMap;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Run via scripts/wzaudit/jump_waypoint_scan.sh, not as part of the normal test suite. */
class JumpWaypointAudit {
    private static final Pattern MAP_FILE = Pattern.compile("([0-9]{9})\\.img\\.xml");
    private static final BotMovementProfile PROFILE = BotMovementProfile.base();

    private record Launch(BotNavigationGraph.Edge edge, int x, int footholdId) {}
    private record Result(int live, int dead, int detours, List<String> findings, String error) {}
    private record TreadResult(boolean hasDetour, String failure) {}

    @Test
    void waypointDoesNotSwayOrStallWhenTheSelectedLaunchIsReachable() throws Exception {
        PqParkDoorExecutionSimTest.stubHostEnvironment();
        var maps = mapIds();
        assertFalse(maps.isEmpty(), "No map XML found in the host's wz/Map.wz/Map tree");
        var originalCache = BotNavigationGraphProvider.cacheDir();
        BotNavigationGraphProvider.setCacheDir(Path.of("target", "jump-waypoint-audit")
                .resolve(originalCache.getFileName()));
        var completed = new AtomicInteger();
        List<Result> results;
        try {
            results = maps.parallelStream().map(id -> {
                try {
                    return scanMap(id);
                } catch (RuntimeException e) {
                    return new Result(0, 0, 0, List.of(), "map=" + id + ": " + e);
                } finally {
                    int count = completed.incrementAndGet();
                    if (count % 500 == 0) System.out.printf("Scanned %d/%d maps%n", count, maps.size());
                }
            }).toList();
        } finally {
            BotNavigationGraphProvider.setCacheDir(originalCache);
            MovementClock.reset();
        }
        int live = 0, dead = 0, detours = 0, hits = 0, affectedMaps = 0, errors = 0;
        for (Result result : results) {
            live += result.live;
            dead += result.dead;
            detours += result.detours;
            hits += result.findings.size();
            if (!result.findings.isEmpty()) affectedMaps++;
            result.findings.forEach(System.out::println);
            if (result.error != null) {
                errors++;
                System.err.println(result.error);
            }
        }
        String summary = "maps=%d, live FHs with a multi-tread jump approach=%d, dead FHs with jump edges skipped=%d, "
                + "FHs with an alternate straight approach=%d, affected maps=%d, affected FHs=%d, map errors=%d";
        summary = summary.formatted(maps.size(), live, dead, detours, affectedMaps, hits, errors);
        System.out.println(summary);
        assertTrue(hits == 0 && errors == 0, summary);
    }

    static TreeSet<Integer> mapIds() throws IOException {
        var ids = new TreeSet<Integer>();
        String requested = System.getProperty("jump.audit.maps", "").strip();
        if (!requested.isEmpty()) {
            for (String id : requested.split("[,\\s]+")) ids.add(Integer.parseInt(id));
            return ids;
        }
        for (Path wz : List.of(WZFiles.MAP.getBaseFile(), WZFiles.MAP.getLanguageFile())) {
            Path root = wz.resolve("Map");
            if (!Files.isDirectory(root)) continue;
            try (var files = Files.find(root, 2, (path, attrs) -> attrs.isRegularFile())) {
                files.forEach(file -> {
                    var match = MAP_FILE.matcher(file.getFileName().toString());
                    if (match.matches()) ids.add(Integer.parseInt(match.group(1)));
                });
            }
        }
        return ids;
    }

    private static Result scanMap(int id) {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(id);
        if (map.isSwim()) return new Result(0, 0, 0, List.of(), null); // no jump graph at runtime
        Map<Integer, Foothold> footholds = new HashMap<>();
        for (Foothold fh : map.getFootholds().getAllFootholds()) {
            if (!fh.isWall()) footholds.put(fh.getId(), fh);
        }
        if (footholds.size() < 2) return new Result(0, 0, 0, List.of(), null);
        var graph = BotNavigationGraphProvider.getGraph(map, PROFILE);
        var state = new BotMovementState(null, null);
        state.movementProfile = PROFILE;
        int walkStep = BotPhysicsEngine.walkStep(map, PROFILE);
        int live = 0, dead = 0, detours = 0;
        var findings = new ArrayList<String>();
        for (var region : graph.regions) {
            if (region.segments.size() < 2) continue;
            List<Launch> launches = launches(graph, region);
            if (launches.isEmpty()) continue;
            // The real guard's verdict is shared by all FHs of this walk component.
            if (!DeadPitGuard.isLivableSurface(map, footholds.get(region.segments.getFirst().footholdId), PROFILE)) {
                dead += region.segments.size();
                continue;
            }
            live += region.segments.size();
            for (var tread : region.segments) {
                TreadResult result = scanTread(map, graph, region, tread, launches, state, walkStep);
                if (result.hasDetour) detours++;
                if (result.failure != null) findings.add(result.failure);
            }
        }
        return new Result(live, dead, detours, findings, null);
    }

    private static List<Launch> launches(BotNavigationGraph graph, BotNavigationGraph.Region region) {
        var launches = new ArrayList<Launch>();
        for (var edge : graph.getOutgoing(region.id)) {
            if (edge.type != BotNavigationGraph.EdgeType.JUMP) continue;
            // Sample each selectable tread interval, including both sides of shared endpoints.
            var bound = new TreeSet<Integer>();
            bound.add(edge.launchMinX);
            bound.add(edge.launchMaxX);
            for (var tread : region.segments) {
                if (edge.containsLaunchX(tread.minX)) bound.add(tread.minX);
                if (edge.containsLaunchX(tread.maxX)) bound.add(tread.maxX);
            }
            int previous = bound.getFirst();
            launches.add(launchAt(region, edge, previous));
            for (int x : bound.tailSet(previous, false)) {
                if (x - previous > 1) launches.add(launchAt(region, edge, previous + 1));
                if (x - previous > 3) launches.add(launchAt(region, edge, previous + (x - previous) / 2));
                if (x - previous > 2) launches.add(launchAt(region, edge, x - 1));
                launches.add(launchAt(region, edge, x));
                previous = x;
            }
        }
        return launches;
    }

    private static Launch launchAt(BotNavigationGraph.Region region, BotNavigationGraph.Edge edge, int x) {
        Point launch = region.pointAt(x);
        return new Launch(edge, x, region.segmentAt(launch.x, launch.y).footholdId);
    }

    private static TreadResult scanTread(MapleMap map, BotNavigationGraph graph, BotNavigationGraph.Region region,
                                         BotNavigationGraph.Segment tread, List<Launch> launches,
                                         BotMovementState state, int walkStep) {
        boolean detour = false;
        for (int x = tread.minX; x <= tread.maxX; x++) {
            Point feet = tread.pointAt(x);
            for (Launch launch : launches) {
                if (Math.abs(launch.x - x) <= walkStep) continue;
                state.navJumpLaunchEdge = launch.edge;
                state.navJumpLaunchX = launch.x;
                Point waypoint = BotNavigationManager.selectJumpWaypoint(graph, state, feet, launch.edge);
                if (Integer.compare(waypoint.x, x) == Integer.compare(launch.x, x)) continue;
                Foothold standing = BotPhysicsEngine.findGroundFoothold(map, feet);
                if (standing == null || standing.getId() != tread.footholdId
                        || !BotNavigationManager.canWalkStraightToLaunch(map, graph, launch.edge, feet, standing,
                                launch.x, launch.footholdId, PROFILE)) continue;
                detour = true;
                if (swaysOrStalls(map, graph, feet, launch, walkStep)) {
                    return new TreadResult(true,
                            "map=%09d fh=%d feet=(%d,%d) launch=(%d,%d) waypoint=(%d,%d) window=[%d,%d]"
                                    .formatted(map.getId(), tread.footholdId, x, feet.y, launch.x, region.pointAt(launch.x).y,
                                            waypoint.x, waypoint.y, launch.edge.launchMinX, launch.edge.launchMaxX));
                }
            }
        }
        return new TreadResult(detour, null);
    }

    // Drive the production ground controller with one fixed jump. Do not run the watchdog: it
    // can hide the bad approach by replacing it with a rescue jump after a few ticks.
    private static boolean swaysOrStalls(MapleMap map, BotNavigationGraph graph, Point start, Launch launch, int walkStep) {
        var bot = new CharacterDouble(1, map, null, null);
        var state = new BotMovementState(bot, null);
        BotPhysicsEngine.teleportTo(state, bot, start);
        state.movementProfile = PROFILE;
        state.navEdge = launch.edge;
        state.navJumpLaunchEdge = launch.edge;
        state.navJumpLaunchX = launch.x;
        state.navPreciseTarget = true;
        Point feet = start;
        int previousDir = 0, reversals = 0, still = 0;
        for (int i = 0; i < 50; i++) {
            if (Math.abs(launch.x - feet.x) <= walkStep
                    && BotNavigationManager.canExecuteJumpFromCurrentPosition(graph, map, feet, launch.edge)) return false;
            Point waypoint = BotNavigationManager.selectJumpWaypoint(graph, state, feet, launch.edge);
            BotMovementManager.tickGrounded(state, waypoint);
            Point next = bot.getPosition();
            if (state.inAir || Math.abs(next.x - start.x) > 4 * walkStep) return false;
            if (state.moveDir != 0) {
                if (state.moveDir == -previousDir) reversals++;
                previousDir = state.moveDir;
            }
            still = feet.equals(next) ? still + 1 : 0;
            feet = next;
            if (reversals >= 3 || still >= 12) return true;
        }
        return false;
    }
}
