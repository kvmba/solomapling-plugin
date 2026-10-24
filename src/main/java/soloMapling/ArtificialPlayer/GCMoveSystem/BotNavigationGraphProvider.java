package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.gms.server.maps.Foothold;
import org.gms.server.maps.MapManager;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Portal;
import org.gms.server.maps.Rope;

import java.awt.*;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static soloMapling.DebugUtilities.debugprint;

// Ported from GreenCatMS. Credit: NutNNut.
final class BotNavigationGraphProvider {
    private static final Logger log = LoggerFactory.getLogger(BotNavigationGraphProvider.class);

    // 47: forbidFallDown footholds stay solid during down-jump grace (landing predictions
    //     change on maps with stacked platforms) — caches must rebuild.
    // 48: WZ info/fs reinterpreted as slipperiness (was a bogus ground-speed scale): walk
    //     step on fs maps jumps back to full speed, runways stretch by 1/fs.
    // 49: down-jumps capped at DOWN_JUMP_MAX_DROP_PX (client probes a bounded range below;
    //     Orbis-tower-style 860px down-jump edges must disappear).
    // 50: indexed ground lookups (findBelowIndexed) - tie-breaks between overlapping footholds
    //     can differ from the tree's traversal order (Ellinia: 2 of 5151 edges).
    // 54: client-true landings — touchdown halves carried momentum (packet fit), so slippery
    //     post-landing brake sims stop in ~1/4 the distance and previously-rejected icy hop
    //     edges (El Nath 267->277->278->279) become stable.
    // 55: disasm-true air control (CVecCtrl::CalcFloat @ 0x9b2c3c) — input only nudges vx
    //     inside an 8.93 x fs px/s band (no walkSpeed air cap; counter-strafe pins at the
    //     band edge) and no-input flight drags 1 x fs (100 x fs at terminal fall). Committed
    //     arcs still fly the launch key held, so constant-stepX arc sims stay exact.
    private static final int GRAPH_VERSION = 74; // Complete directed reachability, including pixel-wide launch windows.

    // Warmup heuristics remain conservative; the completed graph includes all simulated
    // descents and expresses preferences through costs, not by deleting reachable ground.
    static final int DOWN_JUMP_MAX_DROP_PX = 200;
    private static final int DOWN_JUMP_COST_PENALTY_MS = 2000;
    private static final int SAME_SOLID_NEST_GAP_PX = 8;
    private static final int MAX_PROFILED_JUMP_REGIONS = 5;
    private static final int FAST_WARMUP_MAX_FOOTHOLDS = 200;
    // The on-disk cache root. Deliberately NOT final, with the accessors below as the supported
    // seam: a test must be able to redirect this away from the server's real working directory.
    // (Poking a static final Path through sun.misc.Unsafe is unsound — once the JIT has
    // constant-folded the field the write is simply not observed, which is how the cache test
    // silently wrote the REAL cache directory under a warm JVM.)
    private static volatile Path CACHE_DIR = defaultCacheDir();

    private static Path defaultCacheDir() {
        return Path.of("cache", "bot-nav", "v" + GRAPH_VERSION);
    }

    /** The cache root currently in effect (the real directory in production). */
    static Path cacheDir() {
        return CACHE_DIR;
    }

    /** Redirect the cache root — the supported test seam (see the field note). */
    static void setCacheDir(Path dir) {
        CACHE_DIR = dir;
    }
    private static final Map<GraphCacheKey, BotNavigationGraph> GRAPHS = new ConcurrentHashMap<>();
    // mapId -> the GraphCacheKeys cached for that map. GRAPHS is keyed by (map, speed, jump, snowshoes),
    // but the three hottest lookups (peekGraph(MapleMap), peekClosestGraph, peekBestGraph) only know the
    // mapId and had to scan the whole cache linearly. At ~1000 cached graphs that measured ~9.2us per
    // call, and peekBestGraph runs on every movement tick (20/s per observed bot, 4/s per coarse bot):
    // ~3.7% of a core at 1000 moving bots. This index turns those scans into a small per-map walk.
    // Maintained at both GRAPHS write sites (rebuildGraph and the async warm/load task).
    private static final Map<Integer, Set<GraphCacheKey>> GRAPH_KEYS_BY_MAP_ID = new ConcurrentHashMap<>();
    private static final Map<GraphCacheKey, CompletableFuture<BotNavigationGraph>> PENDING_GRAPHS = new ConcurrentHashMap<>();
    private static final Map<GraphCacheKey, GraphBuildReport> LAST_BUILD_REPORTS = new ConcurrentHashMap<>();
    private static final Map<Integer, Set<Integer>> COLLIDABLE_WALL_IDS_BY_MAP_ID = new ConcurrentHashMap<>();
    private static final Map<Integer, Set<Integer>> COLLIDABLE_FROM_BELOW_IDS_BY_MAP_ID = new ConcurrentHashMap<>();
    private static final ThreadLocal<BuildProfileBuilder> ACTIVE_BUILD_PROFILE = new ThreadLocal<>();
    // Small fixed pools rather than single threads, so different maps bake in parallel.
    //
    // A single thread serialised them: warmGraphsForRouteAsync runs its loop ON the warmup thread
    // and queues each map's build to that same thread, so a route's maps were built one after
    // another no matter how many cores are free. Building is deduplicated per key by
    // PENDING_GRAPHS below, so parallel workers never build the same graph twice.
    private static final ExecutorService GRAPH_WARMUP_EXECUTOR = newFixedPool("bot-nav-graph-warmup");
    private static final ExecutorService FAST_GRAPH_WARMUP_EXECUTOR = newFixedPool("bot-nav-graph-warmup-fast");

    private static ExecutorService newFixedPool(String name) {
        return Executors.newFixedThreadPool(Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors())),
                r -> {
                    Thread thread = new Thread(r, name);
                    thread.setDaemon(true);
                    return thread;
                });
    }

    private record GraphCacheKey(int mapId, int totalSpeedStat, int totalJumpStat, boolean snowShoes) {
        static GraphCacheKey from(int mapId, BotMovementProfile profile) {
            BotMovementProfile effective = profile == null ? BotMovementProfile.base() : profile;
            return new GraphCacheKey(mapId, effective.totalSpeedStat(), effective.totalJumpStat(),
                    effective.snowShoes());
        }
    }

    /* Snowshoes only change physics on slippery ground (fs < 1): strip the flag everywhere
     *  else so a snowshoe-wearing bot shares the normal map graphs instead of duplicating them. */
    private static BotMovementProfile canonicalProfile(MapleMap map, BotMovementProfile profile) {
        if (profile == null || !profile.snowShoes() || BotPhysicsEngine.slipperyGround(map)) {
            return profile;
        }
        return new BotMovementProfile(profile.totalSpeedStat(), profile.totalJumpStat(), false);
    }

    static final class GraphBuildReport {
        final long buildAnchorPointsNs;
        final int mapId;
        final int totalSpeedStat;
        final int totalJumpStat;
        final int footholdCount;
        final int walkableFootholdCount;
        final int ropeCount;
        final int regionCount;
        final int totalEdgeCount;
        final int walkEdgeCount;
        final int jumpEdgeCount;
        final int dropEdgeCount;
        final int climbEdgeCount;
        final int portalEdgeCount;
        final long collectFootholdsNs;
        final long buildRegionsNs;
        final long addRopeRegionsNs;
        final long buildFeatureXsNs;
        final long buildWalkEdgesNs;
        final long buildDropEdgesNs;
        final long buildJumpEdgesNs;
        final long buildRopeEntryEdgesNs;
        final long buildRopeExitEdgesNs;
        final long buildPortalEdgesNs;
        final long totalBuildNs;
        final long jumpSampleCount;
        final long jumpCacheHitCount;
        final long jumpCacheMissCount;
        final long jumpBoundaryRefineProbeCount;
        final List<JumpRegionProfile> slowestJumpRegions;

        GraphBuildReport(int mapId,
                         int totalSpeedStat,
                         int totalJumpStat,
                         int footholdCount,
                         int walkableFootholdCount,
                         int ropeCount,
                         int regionCount,
                         int totalEdgeCount,
                         int walkEdgeCount,
                         int jumpEdgeCount,
                         int dropEdgeCount,
                         int climbEdgeCount,
                         int portalEdgeCount,
                         long buildAnchorPointsNs,
                         long collectFootholdsNs,
                         long buildRegionsNs,
                         long addRopeRegionsNs,
                         long buildFeatureXsNs,
                         long buildWalkEdgesNs,
                         long buildDropEdgesNs,
                         long buildJumpEdgesNs,
                         long buildRopeEntryEdgesNs,
                         long buildRopeExitEdgesNs,
                         long buildPortalEdgesNs,
                         long totalBuildNs,
                         long jumpSampleCount,
                         long jumpCacheHitCount,
                         long jumpCacheMissCount,
                         long jumpBoundaryRefineProbeCount,
                         List<JumpRegionProfile> slowestJumpRegions) {
            this.buildAnchorPointsNs = buildAnchorPointsNs;
            this.mapId = mapId;
            this.totalSpeedStat = totalSpeedStat;
            this.totalJumpStat = totalJumpStat;
            this.footholdCount = footholdCount;
            this.walkableFootholdCount = walkableFootholdCount;
            this.ropeCount = ropeCount;
            this.regionCount = regionCount;
            this.totalEdgeCount = totalEdgeCount;
            this.walkEdgeCount = walkEdgeCount;
            this.jumpEdgeCount = jumpEdgeCount;
            this.dropEdgeCount = dropEdgeCount;
            this.climbEdgeCount = climbEdgeCount;
            this.portalEdgeCount = portalEdgeCount;
            this.collectFootholdsNs = collectFootholdsNs;
            this.buildRegionsNs = buildRegionsNs;
            this.addRopeRegionsNs = addRopeRegionsNs;
            this.buildFeatureXsNs = buildFeatureXsNs;
            this.buildWalkEdgesNs = buildWalkEdgesNs;
            this.buildDropEdgesNs = buildDropEdgesNs;
            this.buildJumpEdgesNs = buildJumpEdgesNs;
            this.buildRopeEntryEdgesNs = buildRopeEntryEdgesNs;
            this.buildRopeExitEdgesNs = buildRopeExitEdgesNs;
            this.buildPortalEdgesNs = buildPortalEdgesNs;
            this.totalBuildNs = totalBuildNs;
            this.jumpSampleCount = jumpSampleCount;
            this.jumpCacheHitCount = jumpCacheHitCount;
            this.jumpCacheMissCount = jumpCacheMissCount;
            this.jumpBoundaryRefineProbeCount = jumpBoundaryRefineProbeCount;
            this.slowestJumpRegions = new ArrayList<>(slowestJumpRegions);
        }
    }

    record JumpRegionProfile(int regionId,
                             int width,
                             int sampleCount,
                             int edgeCount,
                             int cacheHits,
                             int cacheMisses,
                             long elapsedNs) {
    }

    private static final class BuildProfileBuilder {
        private long buildAnchorPointsNs;
        private final int mapId;
        private final int totalSpeedStat;
        private final int totalJumpStat;
        private final long buildStartedAtNs = System.nanoTime();
        private int footholdCount;
        private int walkableFootholdCount;
        private int ropeCount;
        private int regionCount;
        private int totalEdgeCount;
        private int walkEdgeCount;
        private int jumpEdgeCount;
        private int dropEdgeCount;
        private int climbEdgeCount;
        private int portalEdgeCount;
        private long collectFootholdsNs;
        private long buildRegionsNs;
        private long addRopeRegionsNs;
        private long buildFeatureXsNs;
        private long buildWalkEdgesNs;
        private long buildDropEdgesNs;
        private long buildJumpEdgesNs;
        private long buildRopeEntryEdgesNs;
        private long buildRopeExitEdgesNs;
        private long buildPortalEdgesNs;
        private long jumpSampleCount;
        private long jumpCacheHitCount;
        private long jumpCacheMissCount;
        private long jumpBoundaryRefineProbeCount;
        private final List<JumpRegionProfile> slowestJumpRegions = new ArrayList<>();

        private BuildProfileBuilder(int mapId, BotMovementProfile movementProfile) {
            this.mapId = mapId;
            this.totalSpeedStat = movementProfile.totalSpeedStat();
            this.totalJumpStat = movementProfile.totalJumpStat();
        }

        private void recordEdge(BotNavigationGraph.EdgeType type) {
            totalEdgeCount++;
            switch (type) {
                case WALK -> walkEdgeCount++;
                case JUMP -> jumpEdgeCount++;
                case DROP -> dropEdgeCount++;
                case CLIMB -> climbEdgeCount++;
                case PORTAL -> portalEdgeCount++;
            }
        }

        private void recordJumpSample(boolean cacheHit) {
            jumpSampleCount++;
            if (cacheHit) {
                jumpCacheHitCount++;
            } else {
                jumpCacheMissCount++;
            }
        }

        private void recordJumpBoundaryRefineProbe() {
            jumpBoundaryRefineProbeCount++;
        }

        private void recordJumpRegion(JumpRegionProfile profile) {
            slowestJumpRegions.add(profile);
            slowestJumpRegions.sort(Comparator.comparingLong(JumpRegionProfile::elapsedNs).reversed());
            if (slowestJumpRegions.size() > MAX_PROFILED_JUMP_REGIONS) {
                slowestJumpRegions.removeLast();
            }
        }

        private GraphBuildReport finish() {
            return new GraphBuildReport(
                    mapId,
                    totalSpeedStat,
                    totalJumpStat,
                    footholdCount,
                    walkableFootholdCount,
                    ropeCount,
                    regionCount,
                    totalEdgeCount,
                    walkEdgeCount,
                    jumpEdgeCount,
                    dropEdgeCount,
                    climbEdgeCount,
                    portalEdgeCount,
                    buildAnchorPointsNs,
                    collectFootholdsNs,
                    buildRegionsNs,
                    addRopeRegionsNs,
                    buildFeatureXsNs,
                    buildWalkEdgesNs,
                    buildDropEdgesNs,
                    buildJumpEdgesNs,
                    buildRopeEntryEdgesNs,
                    buildRopeExitEdgesNs,
                    buildPortalEdgesNs,
                    System.nanoTime() - buildStartedAtNs,
                    jumpSampleCount,
                    jumpCacheHitCount,
                    jumpCacheMissCount,
                    jumpBoundaryRefineProbeCount,
                    slowestJumpRegions);
        }
    }

    private record JumpLaunchWindow(int minX, int maxX, int validMinX, int validMaxX,
                                    Point startPoint, Point endPoint, int landingTimeMs) {
    }

    private static final class JumpBuildStats {
        int sampleCount;
        int edgeCount;
        int cacheHits;
        int cacheMisses;
    }

    private record RopeGrabKey(int x, int y, int launchStepX, int ropeX, int ropeTopY, int ropeBottomY) {
    }

    private static final class RopeGrabCache {
        private final Map<RopeGrabKey, Point> hits = new HashMap<>();
        private final Set<RopeGrabKey> misses = new HashSet<>();
    }

    static BotNavigationGraph getGraph(MapleMap map) {
        return getGraph(map, BotMovementProfile.base());
    }

    static BotNavigationGraph getGraph(MapleMap map, BotMovementProfile movementProfile) {
        if (map == null) {
            return null;
        }
        movementProfile = canonicalProfile(map, movementProfile);
        GraphCacheKey key = GraphCacheKey.from(map.getId(), movementProfile);
        BotNavigationGraph cached = GRAPHS.get(key);
        if (cached != null) {
            return cached;
        }
        return getOrStartGraphLoad(map, movementProfile, key, false).join();
    }

    /* Returns the cached graph without triggering a build. */
    static BotNavigationGraph peekGraph(MapleMap map) {
        if (map == null) {
            return null;
        }
        Set<GraphCacheKey> keys = GRAPH_KEYS_BY_MAP_ID.get(map.getId());
        if (keys == null || keys.isEmpty()) {
            return null;
        }
        // The old full-cache scan returned whichever entry ConcurrentHashMap happened to yield
        // first — arbitrary. Stats are bucketed, so a map holds only a handful of profile keys;
        // pick the smallest one so the answer is deterministic and order-independent.
        GraphCacheKey bestKey = null;
        BotNavigationGraph bestGraph = null;
        for (GraphCacheKey key : keys) {
            BotNavigationGraph graph = GRAPHS.get(key);
            if (graph == null) {
                continue;
            }
            if (bestKey == null || compareKeys(key, bestKey) < 0) {
                bestKey = key;
                bestGraph = graph;
            }
        }
        return bestGraph;
    }

    /* Returns the cached graph for the requested profile without triggering a build. */
    static BotNavigationGraph peekGraph(MapleMap map, BotMovementProfile movementProfile) {
        if (map == null) {
            return null;
        }
        return GRAPHS.get(GraphCacheKey.from(map.getId(), canonicalProfile(map, movementProfile)));
    }

    /* Returns the closest cached graph for this map when the exact profile graph is unavailable. */
    static BotNavigationGraph peekClosestGraph(MapleMap map, BotMovementProfile movementProfile) {
        if (map == null) {
            return null;
        }

        GraphCacheKey requested = GraphCacheKey.from(map.getId(), canonicalProfile(map, movementProfile));
        BotNavigationGraph bestGraph = null;
        int bestDistance = Integer.MAX_VALUE;
        GraphCacheKey bestKey = null;
        Set<GraphCacheKey> keys = GRAPH_KEYS_BY_MAP_ID.get(map.getId());
        if (keys == null || keys.isEmpty()) {
            return null;
        }
        for (GraphCacheKey key : keys) {
            BotNavigationGraph graph = GRAPHS.get(key);
            if (graph == null) {
                continue;
            }

            // A slip/no-slip mismatch is structurally wrong (different runway/glide physics) —
            // dominate any speed/jump distance, but stay a usable last-resort fallback.
            int distance = Math.abs(key.totalSpeedStat() - requested.totalSpeedStat())
                    + Math.abs(key.totalJumpStat() - requested.totalJumpStat())
                    + (key.snowShoes() != requested.snowShoes() ? 1_000 : 0);
            // Ties are common (two speed/jump buckets equally far away), and the old full-cache
            // scan broke them by ConcurrentHashMap iteration order — arbitrary and unstable. Now
            // that we scan a small per-map set, break ties on the key itself so the chosen graph
            // is deterministic and independent of insertion/iteration order.
            if (bestGraph == null || distance < bestDistance
                    || (distance == bestDistance && bestKey != null && compareKeys(key, bestKey) < 0)) {
                bestGraph = graph;
                bestDistance = distance;
                bestKey = key;
            }
        }
        return bestGraph;
    }

    // Deterministic ordering for tie-breaking: mapId, then speed, then jump, then snowshoes.
    private static int compareKeys(GraphCacheKey a, GraphCacheKey b) {
        int c = Integer.compare(a.mapId(), b.mapId());
        if (c != 0) {
            return c;
        }
        c = Integer.compare(a.totalSpeedStat(), b.totalSpeedStat());
        if (c != 0) {
            return c;
        }
        c = Integer.compare(a.totalJumpStat(), b.totalJumpStat());
        if (c != 0) {
            return c;
        }
        return Boolean.compare(a.snowShoes(), b.snowShoes());
    }

    /*
     * Best cached graph for the requested profile: the exact-profile graph if cached, otherwise the
     * closest cached profile for this map (by speed/jump distance), or null if none cached.
     * Single source of truth for "which cached graph should a bot of this profile navigate against",
     * replacing the bare .peekGraph(MapleMap) arbitrary-first-entry pick at profile-aware
     * call sites and the open-coded exact-then-closest fallback duplicated across callers.
     */
    static BotNavigationGraph peekBestGraph(MapleMap map, BotMovementProfile movementProfile) {
        BotNavigationGraph exact = peekGraph(map, movementProfile);
        return exact != null ? exact : peekClosestGraph(map, movementProfile);
    }

    static CompletableFuture<BotNavigationGraph> warmGraphAsync(MapleMap map, BotMovementProfile movementProfile) {
        if (map == null) {
            return CompletableFuture.completedFuture(null);
        }
        movementProfile = canonicalProfile(map, movementProfile);
        GraphCacheKey key = GraphCacheKey.from(map.getId(), movementProfile);
        return getOrStartGraphLoad(map, movementProfile, key, true);
    }

    /*
     * Route-ahead prewarm for multi-hop travel: load each map and queue its graph warmup, so
     * the bot doesn't land in graph-warmup fallback at every hop. MapManager.getMap
     * itself loads the map from WZ on first touch — heavy, hence the whole loop runs on the
     * warmup executor, never on a bot tick thread.
     */
    static void warmGraphsForRouteAsync(MapManager mapFactory, List<Integer> mapIds,
                                        BotMovementProfile movementProfile) {
        GRAPH_WARMUP_EXECUTOR.execute(() -> {
            for (int mapId : mapIds) {
                try {
                    warmGraphAsync(mapFactory.getMap(mapId), movementProfile);
                } catch (RuntimeException e) {
                    log.warn("Route prewarm failed for map {}", mapId, e);
                }
            }
        });
    }

    static BotNavigationGraph rebuildGraph(MapleMap map) {
        return rebuildGraph(map, BotMovementProfile.base());
    }

    static BotNavigationGraph rebuildGraph(MapleMap map, BotMovementProfile movementProfile) {
        GraphCacheKey key = GraphCacheKey.from(map.getId(), movementProfile);
        BotNavigationGraph rebuilt = buildGraph(map, movementProfile);
        GRAPHS.put(key, rebuilt);
        GRAPH_KEYS_BY_MAP_ID.computeIfAbsent(map.getId(), ignored -> ConcurrentHashMap.newKeySet()).add(key);
        // The map's region index changed with the graph - drop any memoized runtime lookup so
        // physics can't keep querying the replaced graph's regions (BotPhysicsEngine).
        BotPhysicsEngine.invalidateWalkRegionLookup(map.getId());
        CompletableFuture<BotNavigationGraph> pending = PENDING_GRAPHS.remove(key);
        if (pending != null) {
            pending.complete(rebuilt);
        }
        saveGraph(rebuilt);
        return rebuilt;
    }

    private static CompletableFuture<BotNavigationGraph> getOrStartGraphLoad(MapleMap map,
                                                                             BotMovementProfile movementProfile,
                                                                             GraphCacheKey key,
                                                                             boolean async) {
        BotNavigationGraph cached = GRAPHS.get(key);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }

        CompletableFuture<BotNavigationGraph> existing = PENDING_GRAPHS.get(key);
        if (existing != null) {
            return existing;
        }

        CompletableFuture<BotNavigationGraph> future = new CompletableFuture<>();
        CompletableFuture<BotNavigationGraph> race = PENDING_GRAPHS.putIfAbsent(key, future);
        if (race != null) {
            return race;
        }

        Runnable task = () -> {
            try {
                BotNavigationGraph graph = loadOrBuildGraph(map, movementProfile, key);
                GRAPHS.put(key, graph);
                GRAPH_KEYS_BY_MAP_ID.computeIfAbsent(map.getId(), ignored -> ConcurrentHashMap.newKeySet())
                        .add(key);
                // New graph for this map - drop any memoized runtime lookup built from the old one.
                BotPhysicsEngine.invalidateWalkRegionLookup(map.getId());
                future.complete(graph);
            } catch (Throwable t) {
                future.completeExceptionally(t);
                log.warn("Failed to warm bot nav graph for map {} speed={} jump={}",
                        key.mapId(), key.totalSpeedStat(), key.totalJumpStat(), t);
            } finally {
                PENDING_GRAPHS.remove(key, future);
            }
        };

        if (async) {
            selectWarmupExecutor(map).execute(task);
        } else {
            task.run();
        }
        return future;
    }

    private static ExecutorService selectWarmupExecutor(MapleMap map) {
        return isFastWarmupCandidate(map) ? FAST_GRAPH_WARMUP_EXECUTOR : GRAPH_WARMUP_EXECUTOR;
    }

    private static boolean isFastWarmupCandidate(MapleMap map) {
        if (map == null || map.getFootholds() == null) {
            return false;
        }
        return map.getFootholds().getAllFootholds().size() <= FAST_WARMUP_MAX_FOOTHOLDS;
    }

    private static BotNavigationGraph loadOrBuildGraph(MapleMap map,
                                                       BotMovementProfile movementProfile,
                                                       GraphCacheKey key) {
        BotNavigationGraph cached = loadGraph(key);
        if (cached != null) {
            return cached;
        }

        BotNavigationGraph built = buildGraph(map, movementProfile);
        saveGraph(built);
        return built;
    }

    private static BotNavigationGraph loadGraph(GraphCacheKey key) {
        Path file = graphFile(key);
        if (!Files.isRegularFile(file)) {
            return null;
        }

        return readGraphFile(file, key);
    }

    private static BotNavigationGraph readGraphFile(Path file, GraphCacheKey key) {
        try (ObjectInputStream in = new ObjectInputStream(Files.newInputStream(file))) {
            Object loaded = in.readObject();
            if (!(loaded instanceof BotNavigationGraph graph)) {
                return null;
            }
            // Every key dimension is now in the filename, so validate all of them. A mismatch
            // means the file is for a different key (or a stale pre-snowshoe cache) — never
            // hand the caller a graph whose physics disagrees with the profile it asked for.
            if (graph.version != GRAPH_VERSION
                    || graph.mapId != key.mapId()
                    || graph.movementProfile.totalSpeedStat() != key.totalSpeedStat()
                    || graph.movementProfile.totalJumpStat() != key.totalJumpStat()
                    || graph.movementProfile.snowShoes() != key.snowShoes()) {
                // Name the dimension that actually differs. This branch fires on any mismatch, so
                // a message that always blamed snowShoes would send the next reader down the wrong
                // path — the common case here is a GRAPH_VERSION bump, not a profile collision.
                log.warn("Discarding bot nav graph cache {}: {} (map {} speed {} jump {} snowshoes {}"
                                + " vs wanted map {} speed {} jump {} snowshoes {})",
                        file.getFileName(),
                        graph.version != GRAPH_VERSION ? "version " + graph.version + " != " + GRAPH_VERSION
                                : "key mismatch",
                        graph.mapId, graph.movementProfile.totalSpeedStat(),
                        graph.movementProfile.totalJumpStat(), graph.movementProfile.snowShoes(),
                        key.mapId(), key.totalSpeedStat(), key.totalJumpStat(), key.snowShoes());
                return null;
            }
            seedCachedFootholdCollisionIds(graph);
            return graph;
        } catch (IOException | ClassNotFoundException e) {
            // Warn, not debug: a cache that will not load is silently rebuilt on every boot, so at
            // debug the only symptom is "why is startup slow / why do maps re-bake constantly"
            // with nothing in the log to explain it. The stack trace distinguishes a corrupt or
            // half-written file (StreamCorrupted / EOF) from a schema change (ClassNotFound /
            // InvalidClass) from an unreadable disk.
            log.warn("Failed to load bot nav graph cache {} for map {} speed={} jump={}; "
                            + "the graph will be rebuilt in memory (and the file rewritten)",
                    file.getFileName(), key.mapId(), key.totalSpeedStat(), key.totalJumpStat(), e);
            return null;
        }
    }

    private static void saveGraph(BotNavigationGraph graph) {
        Path target = graphFile(GraphCacheKey.from(graph.mapId, graph.movementProfile));
        // Two builds of the same key can race here: an async warm on the warmup executor and a
        // !gcmove bake (rebuildGraph) on the command thread. Writing straight to `target` opens it
        // with TRUNCATE_EXISTING, so the two ObjectOutputStreams interleave into one truncated
        // file and the next loadGraph fails on a half-written stream. Write a private temp file
        // and rename instead: the rename makes `target` always either the previous complete graph
        // or a complete new one, never a torn mixture.
        // No lock is needed — the loser's bytes are simply replaced by the winner's, and both
        // wrote equivalent data (same mapId + profile).
        Path tmp = null;
        try {
            Files.createDirectories(CACHE_DIR);
            tmp = Files.createTempFile(CACHE_DIR, target.getFileName() + ".", ".tmp");
            try (ObjectOutputStream out = new ObjectOutputStream(Files.newOutputStream(tmp))) {
                out.writeObject(graph);
            }
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                // Some network/overlay mounts cannot rename atomically. A non-atomic replace of a
                // complete file is still far better than two interleaved writes.
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            // Warn, not debug: a cache that cannot be written means every map re-bakes on every
            // restart, permanently — usually a full disk or a permissions problem on CACHE_DIR.
            // At debug that failure is invisible and the only symptom is a slow startup.
            log.warn("Failed to save bot nav graph cache {} for map {} (the graph stays in memory "
                            + "for this session, but every restart will re-bake it)",
                    target.getFileName(), graph.mapId, e);
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp); // no-op once the rename succeeded
                } catch (IOException ignored) {
                    // best-effort: a leftover temp file is harmless, just untidy
                }
            }
        }
    }

    /*
     * The cache filename must encode every dimension of GraphCacheKey. It used to omit snowShoes,
     * so on a slippery map (where canonicalProfile keeps the flag) a shod and an unshod bot hashed
     * to different keys but the same file — each overwrote the other on every visit, and loadGraph
     * only validated three dimensions so it could not tell it had loaded the wrong graph.
     */
    private static Path graphFile(GraphCacheKey key) {
        return CACHE_DIR.resolve(key.mapId()
                + "-s" + key.totalSpeedStat()
                + "-j" + key.totalJumpStat()
                + "-ss" + (key.snowShoes() ? 1 : 0)
                + ".bin");
    }

    private static BotNavigationGraph buildGraph(MapleMap map, BotMovementProfile movementProfile) {
        movementProfile = movementProfile == null ? BotMovementProfile.base() : movementProfile;
        BuildProfileBuilder buildProfile = new BuildProfileBuilder(map.getId(), movementProfile);
        ACTIVE_BUILD_PROFILE.set(buildProfile);
        try {
            List<Foothold> footholds = map.getFootholds() == null ? List.of() : map.getFootholds().getAllFootholds();
            Map<Integer, Foothold> footholdsById = new HashMap<>();
            List<Foothold> walkableFootholds = new ArrayList<>();
            long phaseStartedAt = System.nanoTime();
            Set<Integer> collidableWallIds = new HashSet<>();
            Set<Integer> collidableFromBelowIds;
            for (Foothold foothold : footholds) {
                footholdsById.put(foothold.getId(), foothold);
                if (!foothold.isWall()) {
                    walkableFootholds.add(foothold);
                }
            }
            collidableFromBelowIds = classifyCollidableFromBelowFootholds(footholdsById);
            for (Foothold foothold : footholds) {
                if (Foothold.isCollidableWall(foothold, footholdsById)) {
                    collidableWallIds.add(foothold.getId());
                }
            }
            buildProfile.collectFootholdsNs = System.nanoTime() - phaseStartedAt;
            buildProfile.footholdCount = footholds.size();
            buildProfile.walkableFootholdCount = walkableFootholds.size();
            buildProfile.ropeCount = map.getRopes().size();
            COLLIDABLE_WALL_IDS_BY_MAP_ID.put(map.getId(), new HashSet<>(collidableWallIds));
            COLLIDABLE_FROM_BELOW_IDS_BY_MAP_ID.put(map.getId(), new HashSet<>(collidableFromBelowIds));

            List<BotNavigationGraph.Region> regions = new ArrayList<>();
            Map<Integer, BotNavigationGraph.Region> regionsById = new HashMap<>();
            Map<Integer, Integer> regionIdByFootholdId = new HashMap<>();
            phaseStartedAt = System.nanoTime();
            buildRegions(walkableFootholds, footholdsById, collidableFromBelowIds, regions, regionsById, regionIdByFootholdId);
            buildProfile.buildRegionsNs = System.nanoTime() - phaseStartedAt;

            phaseStartedAt = System.nanoTime();
            int nextRegionId = regions.stream().mapToInt(r -> r.id).max().orElse(0) + 1;
            for (Rope rope : map.getRopes()) {
                BotNavigationGraph.Region ropeRegion = new BotNavigationGraph.Region(
                        nextRegionId++, rope.x(), rope.topY(), rope.bottomY(), rope.isLadder());
                regions.add(ropeRegion);
                regionsById.put(ropeRegion.id, ropeRegion);
            }
            buildProfile.addRopeRegionsNs = System.nanoTime() - phaseStartedAt;
            buildProfile.regionCount = regions.size();

            List<BotNavigationGraph.Region> groundRegions = new ArrayList<>();
            List<BotNavigationGraph.Region> ropeRegions = new ArrayList<>();
            for (BotNavigationGraph.Region region : regions) {
                if (region.isRopeRegion) {
                    ropeRegions.add(region);
                } else {
                    groundRegions.add(region);
                }
            }
            Map<Integer, Rope> ropeByRegionId = buildRopeByRegionId(map, ropeRegions);
            Map<Integer, List<BotNavigationGraph.Edge>> outgoing = new HashMap<>();
            Set<String> edgeKeys = new HashSet<>();
            RopeGrabCache ropeGrabCache = new RopeGrabCache();

            BotPhysicsEngine.setBuildWalkRegionLookup(map, regionsById, regionIdByFootholdId, footholdsById);

            phaseStartedAt = System.nanoTime();
            for (Foothold foothold : walkableFootholds) {
                addWalkEdges(foothold, footholdsById, regionsById, regionIdByFootholdId, outgoing, edgeKeys, movementProfile);
            }
            buildProfile.buildWalkEdgesNs = System.nanoTime() - phaseStartedAt;

            phaseStartedAt = System.nanoTime();
            for (BotNavigationGraph.Region region : groundRegions) {
                addDropEdges(region, map, regionsById, regionIdByFootholdId,
                        outgoing, edgeKeys, movementProfile);
            }
            buildProfile.buildDropEdgesNs = System.nanoTime() - phaseStartedAt;

            phaseStartedAt = System.nanoTime();
            for (BotNavigationGraph.Region region : groundRegions) {
                addJumpEdges(region, map, regionsById, regionIdByFootholdId,
                        outgoing, edgeKeys, movementProfile);
            }
            buildProfile.buildJumpEdgesNs = System.nanoTime() - phaseStartedAt;

            phaseStartedAt = System.nanoTime();
            for (BotNavigationGraph.Region region : ropeRegions) {
                addRopeEntryEdges(region, groundRegions, ropeByRegionId, map,
                        outgoing, edgeKeys, ropeGrabCache, movementProfile);
            }
            buildProfile.buildRopeEntryEdgesNs = System.nanoTime() - phaseStartedAt;

            phaseStartedAt = System.nanoTime();
            for (BotNavigationGraph.Region region : ropeRegions) {
                addRopeExitEdges(region, ropeRegions, ropeByRegionId, map, regionsById, regionIdByFootholdId, outgoing, edgeKeys, movementProfile);
            }
            buildProfile.buildRopeExitEdgesNs = System.nanoTime() - phaseStartedAt;

            phaseStartedAt = System.nanoTime();
            for (Portal portal : map.getPortals()) {
                addPortalEdges(portal, map, regionsById, regionIdByFootholdId, outgoing, edgeKeys);
            }
            // Judge traps only AFTER all physical transitions exist. Every graph consumer
            // must exclude a terminal region or a chain whose only exits end in one.
            Set<Integer> deadRegions = findDeadRegions(map, regions, regionIdByFootholdId, outgoing);
            buildProfile.buildPortalEdgesNs = System.nanoTime() - phaseStartedAt;

            BotNavigationGraph graph = new BotNavigationGraph(
                    map.getId(), GRAPH_VERSION, movementProfile, regions, regionsById, regionIdByFootholdId, outgoing,
                    collidableWallIds, collidableFromBelowIds, deadRegions);
            GraphBuildReport report = buildProfile.finish();
            LAST_BUILD_REPORTS.put(GraphCacheKey.from(map.getId(), movementProfile), report);
            debugprint("Built bot nav graph map {} speed={} jump={} in {} ms (regions={}, edges={}, drop={} ms, jump={} ms, jumpSamples={}, cacheHits={})",
                    map.getId(),
                    movementProfile.totalSpeedStat(),
                    movementProfile.totalJumpStat(),
                    String.format("%.2f", report.totalBuildNs / 1_000_000.0),
                    report.regionCount,
                    report.totalEdgeCount,
                    String.format("%.2f", report.buildDropEdgesNs / 1_000_000.0),
                    String.format("%.2f", report.buildJumpEdgesNs / 1_000_000.0),
                    report.jumpSampleCount,
                    report.jumpCacheHitCount);
            return graph;
        } finally {
            BotPhysicsEngine.clearBuildWalkRegionLookup();
            ACTIVE_BUILD_PROFILE.remove();
        }
    }

    static GraphBuildReport getLastBuildReport(int mapId) {
        return getLastBuildReport(mapId, BotMovementProfile.base());
    }

    static GraphBuildReport getLastBuildReport(int mapId, BotMovementProfile movementProfile) {
        return LAST_BUILD_REPORTS.get(GraphCacheKey.from(mapId, movementProfile));
    }

    static Set<Integer> getCachedCollidableWallIds(int mapId) {
        return COLLIDABLE_WALL_IDS_BY_MAP_ID.get(mapId);
    }

    static Set<Integer> getCachedCollidableFromBelowIds(int mapId) {
        return COLLIDABLE_FROM_BELOW_IDS_BY_MAP_ID.get(mapId);
    }

    static Set<Integer> computeCollidableFromBelowIds(MapleMap map) {
        if (map == null || map.getFootholds() == null) {
            return Set.of();
        }
        Set<Integer> cached = COLLIDABLE_FROM_BELOW_IDS_BY_MAP_ID.get(map.getId());
        if (cached != null) {
            return cached;
        }
        Map<Integer, Foothold> footholdsById = new HashMap<>();
        for (Foothold foothold : map.getFootholds().getAllFootholds()) {
            footholdsById.put(foothold.getId(), foothold);
        }
        Set<Integer> computed = classifyCollidableFromBelowFootholds(footholdsById);
        COLLIDABLE_FROM_BELOW_IDS_BY_MAP_ID.put(map.getId(), new HashSet<>(computed));
        return computed;
    }

    private static void seedCachedFootholdCollisionIds(BotNavigationGraph graph) {
        COLLIDABLE_WALL_IDS_BY_MAP_ID.put(graph.mapId, new HashSet<>(graph.collidableWallIds));
        COLLIDABLE_FROM_BELOW_IDS_BY_MAP_ID.put(graph.mapId, new HashSet<>(graph.collidableFromBelowIds));
    }

    static Set<Integer> classifyCollidableFromBelowFootholds(Map<Integer, Foothold> footholdsById) {
        List<ClassifiedLoop> loops = classifyClosedLoops(buildClosedLoops(footholdsById));
        if (loops.isEmpty()) {
            return Set.of();
        }

        Set<Integer> result = new HashSet<>();
        for (ClassifiedLoop classifiedLoop : loops) {
            if (!classifiedLoop.solid()) {
                continue;
            }

            ClosedLoop loop = classifiedLoop.loop();
            for (int footholdId : loop.footholdIds()) {
                Foothold foothold = footholdsById.get(footholdId);
                if (foothold == null || foothold.isWall() || foothold.getY1() != foothold.getY2()) {
                    continue;
                }

                double midX = (foothold.getX1() + foothold.getX2()) / 2.0;
                double y = foothold.getY1();
                boolean insideAbove = isPointInLoop(loop, midX, y - 1.0);
                boolean insideBelow = isPointInLoop(loop, midX, y + 1.0);
                if (insideAbove && !insideBelow) {
                    result.add(foothold.getId());
                }
            }
        }
        return result;
    }

    private record ClosedLoop(int[] footholdIds, double[] xs, double[] ys, double minX, double maxX, double minY, double maxY) {}

    private record ClassifiedLoop(ClosedLoop loop, int depth, boolean solid) {}

    private static List<ClosedLoop> buildClosedLoops(Map<Integer, Foothold> footholdsById) {
        List<ClosedLoop> loops = new ArrayList<>();
        Set<Integer> visited = new HashSet<>();
        for (Foothold start : footholdsById.values()) {
            if (visited.contains(start.getId())) {
                continue;
            }

            List<Foothold> chain = new ArrayList<>();
            Set<Integer> seenInChain = new HashSet<>();
            Foothold current = start;
            boolean closed = false;
            while (current != null && seenInChain.add(current.getId()) && chain.size() <= footholdsById.size()) {
                chain.add(current);
                Foothold next = footholdsById.get(current.getNext());
                if (next != null && next.getId() == start.getId()) {
                    closed = true;
                    break;
                }
                current = next;
            }

            if (!closed || chain.size() < 3 || !isEndpointConnectedLoop(chain)) {
                continue;
            }

            visited.addAll(seenInChain);
            loops.add(toClosedLoop(chain));
        }
        return loops;
    }

    private static boolean isEndpointConnectedLoop(List<Foothold> chain) {
        for (int i = 0; i < chain.size(); i++) {
            Foothold current = chain.get(i);
            Foothold next = chain.get((i + 1) % chain.size());
            if (current.getX2() != next.getX1() || current.getY2() != next.getY1()) {
                return false;
            }
        }
        return true;
    }

    private static ClosedLoop toClosedLoop(List<Foothold> chain) {
        int size = chain.size();
        int[] footholdIds = new int[size];
        double[] xs = new double[size];
        double[] ys = new double[size];
        double minX = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < size; i++) {
            Foothold foothold = chain.get(i);
            footholdIds[i] = foothold.getId();
            xs[i] = foothold.getX1();
            ys[i] = foothold.getY1();
            minX = Math.min(minX, Math.min(foothold.getX1(), foothold.getX2()));
            maxX = Math.max(maxX, Math.max(foothold.getX1(), foothold.getX2()));
            minY = Math.min(minY, Math.min(foothold.getY1(), foothold.getY2()));
            maxY = Math.max(maxY, Math.max(foothold.getY1(), foothold.getY2()));
        }
        return new ClosedLoop(footholdIds, xs, ys, minX, maxX, minY, maxY);
    }

    private static List<ClassifiedLoop> classifyClosedLoops(List<ClosedLoop> loops) {
        List<ClosedLoop> ordered = new ArrayList<>(loops);
        ordered.sort(Comparator.comparingDouble(BotNavigationGraphProvider::loopBoundingArea).reversed());

        List<ClassifiedLoop> classified = new ArrayList<>(ordered.size());
        for (ClosedLoop loop : ordered) {
            ClassifiedLoop parent = null;
            double parentArea = Double.POSITIVE_INFINITY;
            for (ClassifiedLoop candidate : classified) {
                if (loopContainsLoop(candidate.loop(), loop)) {
                    double area = loopBoundingArea(candidate.loop());
                    if (area < parentArea) {
                        parent = candidate;
                        parentArea = area;
                    }
                }
            }

            int depth = parent == null ? 0 : parent.depth() + 1;
            boolean solid = parent == null
                    || isThinNestedShell(parent.loop(), loop)
                    ? parent == null || parent.solid()
                    : !parent.solid();
            ClassifiedLoop classifiedLoop = new ClassifiedLoop(loop, depth, solid);
            classified.add(classifiedLoop);
        }
        return classified;
    }

    private static double loopBoundingArea(ClosedLoop loop) {
        return (loop.maxX() - loop.minX()) * (loop.maxY() - loop.minY());
    }

    private static boolean loopContainsLoop(ClosedLoop outer, ClosedLoop inner) {
        if (outer.minX() > inner.minX() || outer.maxX() < inner.maxX()
                || outer.minY() > inner.minY() || outer.maxY() < inner.maxY()) {
            return false;
        }
        return isPointInLoop(outer, inner.xs()[0], inner.ys()[0]);
    }

    private static boolean isThinNestedShell(ClosedLoop outer, ClosedLoop inner) {
        return inner.minX() - outer.minX() <= SAME_SOLID_NEST_GAP_PX
                && outer.maxX() - inner.maxX() <= SAME_SOLID_NEST_GAP_PX
                && inner.minY() - outer.minY() <= SAME_SOLID_NEST_GAP_PX
                && outer.maxY() - inner.maxY() <= SAME_SOLID_NEST_GAP_PX;
    }

    private static boolean isPointInLoop(ClosedLoop loop, double x, double y) {
        boolean inside = false;
        double[] xs = loop.xs();
        double[] ys = loop.ys();
        for (int i = 0, j = xs.length - 1; i < xs.length; j = i++) {
            boolean intersects = ((ys[i] > y) != (ys[j] > y))
                    && (x < (xs[j] - xs[i]) * (y - ys[i]) / ((ys[j] - ys[i]) + 0.0) + xs[i]);
            if (intersects) {
                inside = !inside;
            }
        }
        return inside;
    }

    private static void buildRegions(List<Foothold> footholds,
                                     Map<Integer, Foothold> footholdsById,
                                     Set<Integer> collidableFromBelowIds,
                                     List<BotNavigationGraph.Region> regions,
                                     Map<Integer, BotNavigationGraph.Region> regionsById,
                                     Map<Integer, Integer> regionIdByFootholdId) {
        UnionFind unionFind = new UnionFind();
        for (Foothold foothold : footholds) {
            unionFind.add(foothold);
        }

        for (Foothold foothold : footholds) {
            unionWalkableFootholds(unionFind, foothold, footholdsById.get(foothold.getPrev()));
            unionWalkableFootholds(unionFind, foothold, footholdsById.get(foothold.getNext()));
        }

        Map<Integer, List<Foothold>> groupedFootholds = new HashMap<>();
        for (Foothold foothold : footholds) {
            groupedFootholds.computeIfAbsent(unionFind.find(foothold.getId()), ignored -> new ArrayList<>()).add(foothold);
        }

        List<List<Foothold>> groups = new ArrayList<>(groupedFootholds.values());
        groups.sort(Comparator
                .comparingInt(BotNavigationGraphProvider::groupMinY)
                .thenComparingInt(BotNavigationGraphProvider::groupMinX));

        int nextRegionId = 1;
        for (List<Foothold> group : groups) {
            group.sort(Comparator
                    .comparingInt(BotNavigationGraphProvider::footholdMinX)
                    .thenComparingInt(foothold -> Math.min(foothold.getY1(), foothold.getY2()))
                    .thenComparingInt(Foothold::getId));

            List<BotNavigationGraph.Segment> segments = new ArrayList<>(group.size());
            for (Foothold foothold : group) {
                segments.add(new BotNavigationGraph.Segment(
                        foothold,
                        collidableFromBelowIds.contains(foothold.getId())));
            }

            BotNavigationGraph.Region region = new BotNavigationGraph.Region(nextRegionId++, segments);
            regions.add(region);
            regionsById.put(region.id, region);
            for (Foothold foothold : group) {
                regionIdByFootholdId.put(foothold.getId(), region.id);
            }
        }
    }

    private static void unionWalkableFootholds(UnionFind unionFind, Foothold first, Foothold second) {
        if (!BotPhysicsEngine.canWalkAcrossFootholds(first, second)) {
            return;
        }
        unionFind.union(first.getId(), second.getId());
    }

    private static void addWalkEdges(Foothold foothold,
                                     Map<Integer, Foothold> footholdsById,
                                     Map<Integer, BotNavigationGraph.Region> regionsById,
                                     Map<Integer, Integer> regionIdByFootholdId,
                                     Map<Integer, List<BotNavigationGraph.Edge>> outgoing,
                                     Set<String> edgeKeys,
                                     BotMovementProfile movementProfile) {
        addWalkEdge(foothold, footholdsById.get(foothold.getPrev()), regionsById, regionIdByFootholdId, outgoing, edgeKeys, movementProfile);
        addWalkEdge(foothold, footholdsById.get(foothold.getNext()), regionsById, regionIdByFootholdId, outgoing, edgeKeys, movementProfile);
    }

    private static void addWalkEdge(Foothold fromFoothold,
                                    Foothold targetFoothold,
                                    Map<Integer, BotNavigationGraph.Region> regionsById,
                                    Map<Integer, Integer> regionIdByFootholdId,
                                    Map<Integer, List<BotNavigationGraph.Edge>> outgoing,
                                    Set<String> edgeKeys,
                                    BotMovementProfile movementProfile) {
        if (fromFoothold == null || targetFoothold == null || targetFoothold.isWall()) {
            return;
        }

        int fromRegionId = regionIdByFootholdId.getOrDefault(fromFoothold.getId(), -1);
        int toRegionId = regionIdByFootholdId.getOrDefault(targetFoothold.getId(), -1);
        if (fromRegionId < 0 || toRegionId < 0 || fromRegionId == toRegionId) {
            return;
        }

        EndpointConnection connection = closestEndpointConnection(fromFoothold, targetFoothold);
        if (connection == null || !isWalkConnection(connection)) {
            return;
        }

        BotNavigationGraph.Region from = regionsById.get(fromRegionId);
        BotNavigationGraph.Region to = regionsById.get(toRegionId);
        if (from == null || to == null) {
            return;
        }

        Point start = from.pointAt(connection.from.x);
        Point end = to.pointAt(connection.to.x);
        int cost = estimateWalkCost(start, end, movementProfile);
        addEdge(from.id, to.id, BotNavigationGraph.EdgeType.WALK, start, end, 0, 0, cost, outgoing, edgeKeys);
        addEdge(to.id, from.id, BotNavigationGraph.EdgeType.WALK, end, start, 0, 0, cost, outgoing, edgeKeys);
    }

    private static void addDropEdges(BotNavigationGraph.Region from,
                                     MapleMap map,
                                     Map<Integer, BotNavigationGraph.Region> regionsById,
                                     Map<Integer, Integer> regionIdByFootholdId,
                                     Map<Integer, List<BotNavigationGraph.Edge>> outgoing,
                                     Set<String> edgeKeys,
                                     BotMovementProfile movementProfile) {
        addDirectionalDropEdge(from, map, regionsById, regionIdByFootholdId, -1, outgoing, edgeKeys, movementProfile);
        addDirectionalDropEdge(from, map, regionsById, regionIdByFootholdId, 1, outgoing, edgeKeys, movementProfile);

        int targetRegion = -1;
        int runStart = from.minX;
        // Landing membership is not monotonic. Sample every supported pixel once, just
        // as for jumps; sparse anchors/binary searches miss (or bridge) tiny shelves.
        for (int x = from.minX; x <= from.maxX + 1; x++) {
            BotPhysicsEngine.JumpLanding landing = x <= from.maxX && columnStandsOn(from, x)
                    && !from.isForbidFallDownAt(x)
                    ? BotPhysicsEngine.simulateDownJumpLanding(map, from.pointAt(x)) : null;
            int to = landing == null ? -1 : regionIdByFootholdId.getOrDefault(landing.foothold().getId(), -1);
            if (to == from.id || (landing != null && landing.point().y <= from.pointAt(x).y)) {
                to = -1;
            }
            if (to == targetRegion) {
                continue;
            }
            if (targetRegion >= 0) {
                Point start = from.pointAt(runStart + (x - 1 - runStart) / 2);
                BotPhysicsEngine.JumpLanding representative = BotPhysicsEngine.simulateDownJumpLanding(map, start);
                addEdge(from.id, targetRegion, BotNavigationGraph.EdgeType.DROP,
                        start, representative.point(), runStart, x - 1, 0, 0,
                        representative.timeMs() + DOWN_JUMP_COST_PENALTY_MS, outgoing, edgeKeys);
            }
            targetRegion = to;
            runStart = x;
        }
    }
    /**
     * Whether column {@code x} stands on {@code region} itself: the region has a segment whose
     * x span contains x (pointAt would otherwise clamp to a distant segment and "stand" the
     * launch off the platform's own floor).
     */
    private static boolean columnStandsOn(BotNavigationGraph.Region region, int x) {
        for (BotNavigationGraph.Segment segment : region.segments) {
            if (segment.containsX(x)) {
                return true;
            }
        }
        return false;
    }

    private static Set<Integer> findDeadRegions(MapleMap map, List<BotNavigationGraph.Region> regions,
                                               Map<Integer, Integer> regionIdByFootholdId,
                                               Map<Integer, List<BotNavigationGraph.Edge>> outgoing) {
        Map<Integer, List<Integer>> incoming = new HashMap<>();
        Map<Integer, Integer> exits = new HashMap<>();
        for (BotNavigationGraph.Region region : regions) {
            exits.put(region.id, 0);
        }
        for (List<BotNavigationGraph.Edge> edges : outgoing.values()) {
            for (BotNavigationGraph.Edge edge : edges) {
                if (edge.fromRegionId == edge.toRegionId) {
                    continue; // hopping/teleporting within the same region is not an escape
                }
                exits.merge(edge.fromRegionId, 1, Integer::sum);
                incoming.computeIfAbsent(edge.toRegionId, ignored -> new ArrayList<>()).add(edge.fromRegionId);
            }
        }
        // Inter-map exits are not navigation edges, but they absolutely are a way out.
        // Spawn-only portals (target 999999999) do not count. Open/closed status is
        // runtime state, not geometry: this cache is shared by every instance of the map.
        for (Portal portal : map.getPortals()) {
            if (portal.getTargetMapId() == 999999999
                    || portal.getTargetMapId() == map.getId()) {
                continue;
            }
            Point at = portal.getPosition();
            Foothold floor = BotPhysicsEngine.findGroundFoothold(map, at);
            if (floor != null && Math.abs(BotPhysicsEngine.surfaceY(floor, at.x) - at.y)
                    <= BotMovementManager.cfg.JUMP_Y_THRESH * 2) {
                int region = regionIdByFootholdId.getOrDefault(floor.getId(), -1);
                if (region >= 0) {
                    exits.merge(region, 1, Integer::sum);
                }
            }
        }
        Set<Integer> dead = new HashSet<>();
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        exits.forEach((id, count) -> {
            if (count == 0) {
                dead.add(id);
                queue.add(id);
            }
        });
        while (!queue.isEmpty()) {
            int region = queue.removeFirst();
            for (int predecessor : incoming.getOrDefault(region, List.of())) {
                if (exits.merge(predecessor, -1, Integer::sum) == 0 && dead.add(predecessor)) {
                    queue.addLast(predecessor);
                }
            }
        }
        return dead;
    }

    private static void addDirectionalDropEdge(BotNavigationGraph.Region from,
                                               MapleMap map,
                                               Map<Integer, BotNavigationGraph.Region> regionsById,
                                               Map<Integer, Integer> regionIdByFootholdId,
                                               int direction,
                                               Map<Integer, List<BotNavigationGraph.Edge>> outgoing,
                                               Set<String> edgeKeys,
                                               BotMovementProfile movementProfile) {
        if (direction == 0) {
            return;
        }
        Point endpoint = direction < 0 ? from.leftPoint() : from.rightPoint();

        // O(1) runway: place the edge startPoint so the bot has room to accelerate
        // to (near) max walk speed before walking off. No candidate iteration, no
        // runway simulation — just constants from config.
        int runwayPx = BotPhysicsEngine.launchRunwayPx(map, movementProfile);
        int launchX;
        if (direction < 0) {
            launchX = Math.min(from.maxX, endpoint.x + runwayPx);
        } else {
            launchX = Math.max(from.minX, endpoint.x - runwayPx);
        }
        Point startPoint = from.pointAt(launchX);
        // A ballistic fall from the lip alone can clamp onto its wall and claim a
        // landing that grounded movement cannot reach. Validate the departure too.
        BotPhysicsEngine.WalkOffLanding outcome = BotPhysicsEngine.simulateWalkOffLanding(
                map, startPoint, direction, movementProfile);
        if (outcome == null) {
            return;
        }
        BotPhysicsEngine.JumpLanding landing = outcome.landing();
        int stepX = outcome.launchStepX();

        int toRegionId = regionIdByFootholdId.getOrDefault(landing.foothold().getId(), -1);
        BotNavigationGraph.Region below = regionsById.get(toRegionId);
        if (below == null || below.id == from.id) {
            return;
        }
        BotPhysicsEngine.PostLandingJump stopped = BotPhysicsEngine.simulateLandingStop(map, landing, movementProfile);
        if (stopped.lostGround()
                || regionIdByFootholdId.getOrDefault(stopped.finalFoothold().getId(), -1) != toRegionId) {
            return;
        }
        if (landing.point().y <= endpoint.y + 4) {
            return;
        }
        addEdge(from.id, below.id, BotNavigationGraph.EdgeType.DROP,
                startPoint,
                landing.point(),
                stepX,
                0,
                outcome.travelTimeMs(),
                outgoing,
                edgeKeys);
    }

    /*
     * Jump-edge launch state: the three launchStepX classes {-walkStep, 0, +walkStep} are
     * COMPLETE even on slippery ground — no extra launch-speed classes or momentum chaining
     * state are needed. Packet-verified client physics (see Config.AIR_CONTROL_ACCEL_PXSS):
     * a jump with a direction held snaps to ±walkSpeed at takeoff regardless of ground speed
     * (so a directional hop needs no runway and loses nothing to a slow icy start or a
     * halved post-landing slide), and a no-input jump carries the current hspeed (bounded by
     * ±walkSpeed, and the executor brakes vertical jumps to ~0 first — "jump-slide" gate).
     * The simulated constant-stepX ballistic arc is exact because the arc flies with the
     * launch direction key HELD (CVecCtrl::CalcFloat @ 0x9b2c3c): held input is a no-op above
     * the 8.93 x fs px/s input band — it neither accelerates nor clamps a walkSpeed launch —
     * and the no-input drag only applies when no key is held, so vx stays constant.
     */
    private static void addJumpEdges(BotNavigationGraph.Region from,
                                     MapleMap map,
                                     Map<Integer, BotNavigationGraph.Region> regionsById,
                                     Map<Integer, Integer> regionIdByFootholdId,
                                     Map<Integer, List<BotNavigationGraph.Edge>> outgoing,
                                     Set<String> edgeKeys,
                                     BotMovementProfile movementProfile) {
        long startedAt = System.nanoTime();
        int jumpStep = BotPhysicsEngine.walkStep(map, movementProfile);
        int margin = jumpLaunchMargin(map, movementProfile);
        JumpBuildStats stats = new JumpBuildStats();
        int[] launchSteps = {-jumpStep, 0, jumpStep};
        int[] targetRegions = {-1, -1, -1};
        int[] runStarts = new int[launchSteps.length];
        // Landing membership is non-monotonic on stacked shelves. Do not bridge rejected
        // pixels with exponential probes or miss narrow windows between sparse anchors.
        for (int x = from.minX; x <= from.maxX + 1; x++) {
            boolean approachable = x <= from.maxX && isApproachableJumpLaunchX(from, map, x);
            Point launchPoint = approachable ? from.pointAt(x) : null;
            for (int i = 0; i < launchSteps.length; i++) {
                int toRegionId = -1;
                if (approachable) {
                    BotPhysicsEngine.PostLandingJump simulated = simulateJumpLanding(
                            map, launchPoint, launchSteps[i], stats, movementProfile);
                    if (simulated != null && !simulated.lostGround()) {
                        int firstRegion = regionIdByFootholdId.getOrDefault(simulated.landing().foothold().getId(), -1);
                        int finalRegion = regionIdByFootholdId.getOrDefault(simulated.finalFoothold().getId(), -1);
                        // Runtime checks the first landing; it must also remain on that region.
                        if (firstRegion == finalRegion && firstRegion != from.id && regionsById.containsKey(firstRegion)) {
                            toRegionId = firstRegion;
                        }
                    }
                }
                if (toRegionId == targetRegions[i]) {
                    continue;
                }
                if (targetRegions[i] >= 0) {
                    int validMinX = runStarts[i];
                    int validMaxX = x - 1;
                    int[] inset = insetJumpLaunchWindow(from, validMinX, validMaxX, margin);
                    Point start = from.pointAt((inset[0] + inset[1]) / 2);
                    BotPhysicsEngine.JumpLanding landing = simulateJumpLanding(
                            map, start, launchSteps[i], stats, movementProfile).landing();
                    addEdge(from.id, targetRegions[i], BotNavigationGraph.EdgeType.JUMP,
                            start, landing.point(), inset[0], inset[1], validMinX, validMaxX,
                            launchSteps[i], 0, landing.timeMs(), outgoing, edgeKeys);
                    stats.edgeCount++;
                }
                targetRegions[i] = toRegionId;
                runStarts[i] = x;
            }
        }

        BuildProfileBuilder profile = ACTIVE_BUILD_PROFILE.get();
        if (profile != null) {
            profile.recordJumpRegion(new JumpRegionProfile(
                    from.id,
                    from.width(),
                    stats.sampleCount,
                    stats.edgeCount,
                    stats.cacheHits,
                    stats.cacheMisses,
                    System.nanoTime() - startedAt));
        }
    }

    private static BotPhysicsEngine.PostLandingJump simulateJumpLanding(MapleMap map,
                                                                       Point start,
                                                                       int launchStepX,
                                                                       JumpBuildStats stats,
                                                                       BotMovementProfile movementProfile) {
        recordJumpSample(stats, false);
        BotPhysicsEngine.JumpLanding landing = BotPhysicsEngine.simulateJumpLanding(map, start, launchStepX, movementProfile);
        return BotPhysicsEngine.simulateLandingStop(map, landing, movementProfile);
    }

    private static Point simulateGroundJumpRopeGrabCached(MapleMap map,
                                                          Point start,
                                                          int launchStepX,
                                                          Rope rope,
                                                          RopeGrabCache ropeGrabCache,
                                                          JumpBuildStats stats,
                                                          BotMovementProfile movementProfile) {
        RopeGrabKey key = new RopeGrabKey(start.x, start.y, launchStepX,
                rope.x(), rope.topY(), rope.bottomY());
        Point cachedGrab = ropeGrabCache.hits.get(key);
        if (cachedGrab != null) {
            recordJumpSample(stats, true);
            return new Point(cachedGrab);
        }
        if (ropeGrabCache.misses.contains(key)) {
            recordJumpSample(stats, true);
            return null;
        }

        Point grab = BotPhysicsEngine.simulateGroundJumpRopeGrab(map, start, launchStepX, rope, movementProfile);
        if (grab == null) {
            ropeGrabCache.misses.add(key);
        } else {
            ropeGrabCache.hits.put(key, new Point(grab));
        }
        recordJumpSample(stats, false);
        return grab;
    }

    private static void recordJumpSample(JumpBuildStats stats, boolean cacheHit) {
        BuildProfileBuilder profile = ACTIVE_BUILD_PROFILE.get();
        if (profile != null) {
            profile.recordJumpSample(cacheHit);
        }
        stats.sampleCount++;
        if (cacheHit) {
            stats.cacheHits++;
        } else {
            stats.cacheMisses++;
        }
    }

    /*
     * How far inside a jump launch window the executor may actually take off — one walk step. The
     * navigator fires the jump the instant the bot is within one walk step of the selected launch x
     * (BotNavigationManager.canExecuteSelectedJumpFromCurrentPosition) and the deepen pass walks a
     * further step or two into the window, so a takeoff pixel can sit a whole walk step past the
     * window's far end. On a small platform whose arc only just clears its edge, that phase error
     * overflies the target and the bot falls — the reported "jump to another platform misses and it
     * plunges to the bottom". Insetting every window end by this keeps the whole reachable takeoff
     * span inside the window, so the arc that the builder validated is the arc the executor flies.
     * Measured on the Orbis Tower floors: with the un-inset windows ~6.5% of same-or-higher jumps
     * missed; the inset cut that to ~1.3%, and no ground region became unreachable.
     *
     * The inset never grows past the ledge, and a window narrower than two walk steps is clamped to
     * its own centre rather than emptied, so no platform loses its only launch.
     */
    static int jumpLaunchMargin(MapleMap map, BotMovementProfile movementProfile) {
        return Math.max(1, BotPhysicsEngine.walkStep(map, movementProfile));
    }

    /*
     * Inset a validated launch window's ends by the executor's launch phase, keeping at least one
     * usable pixel (or the window's own centre when it is too thin to inset). Returns {minX, maxX}.
     */
    static int[] insetJumpLaunchWindow(BotNavigationGraph.Region from, int minX, int maxX,
                                       int margin) {
        int lo = minX + margin;
        int hi = maxX - margin;
        if (lo > hi) {
            int center = Math.clamp((minX + maxX) / 2, from.minX, from.maxX);
            return new int[]{center, center};
        }
        return new int[]{lo, hi};
    }


    /*
     * Does a jump fired from {@code from} with {@code launchStepX} actually land in {@code targetRegionId}?
     *
     * The graph builder stamped an edge's launch window by validating each pixel inside it with this
     * flight sim. Execution, however, fires whenever the bot is within one walk
     * step of the selected launch x (isWithinJumpLaunchWindow tolerates the ±walkStep launch phase), and
     * a window too thin to inset collapses to a single pixel (insetJumpLaunchWindow) — so the phase
     * band can reach past the validated span. On a small ledge that pixel's arc falls back onto the
     * SOURCE platform, and the bot bounces there forever. The runtime gate re-runs this predicate from
     * the bot's live pixel, so execution can only launch an arc the builder would have accepted.
     */
    static boolean jumpArcReachesTarget(MapleMap map,
                                        Point from,
                                        int launchStepX,
                                        int targetRegionId,
                                        Map<Integer, Integer> regionIdByFootholdId,
                                        BotMovementProfile movementProfile) {
        if (map == null || from == null || regionIdByFootholdId == null) {
            return true; // no geometry to disagree with — never strand a bot on a missing map
        }
        return arcLandsInTargetRegion(
                BotPhysicsEngine.simulateJumpLanding(map, from, launchStepX, movementProfile),
                targetRegionId, regionIdByFootholdId);
    }

    /* The decision half of jumpArcReachesTarget, split out so the accept/reject rule is unit-testable
     * without a MapleMap (a real one cannot be built in a test — see SwimWallClimbSimulationTest). */
    static boolean arcLandsInTargetRegion(BotPhysicsEngine.JumpLanding landing,
                                          int targetRegionId,
                                          Map<Integer, Integer> regionIdByFootholdId) {
        if (landing == null || landing.foothold() == null) {
            return false;
        }
        return regionIdByFootholdId.getOrDefault(landing.foothold().getId(), -1) == targetRegionId;
    }

    private static boolean isApproachableJumpLaunchX(BotNavigationGraph.Region from, MapleMap map, int launchX) {
        if (from == null || from.isRopeRegion || map == null) {
            return false;
        }
        Point launchPoint = from.pointAt(launchX);
        if (isBlockedWallBoundaryLaunch(map, launchPoint)) {
            return false;
        }
        if (launchX > from.minX && canWalkToLaunchX(from, map, launchX - 1, launchX)) {
            return true;
        }
        return launchX < from.maxX && canWalkToLaunchX(from, map, launchX + 1, launchX);
    }

    private static JumpLaunchWindow expandRopeGrabLaunchWindow(BotNavigationGraph.Region from,
                                                               MapleMap map,
                                                               int anchorX,
                                                               int launchStepX,
                                                               Rope rope,
                                                               JumpBuildStats stats,
                                                               RopeGrabCache ropeGrabCache,
                                                               BotMovementProfile movementProfile) {
        if (!isValidRopeGrabLaunchX(from, map, anchorX, launchStepX, rope, stats, ropeGrabCache, movementProfile)) {
            return null;
        }

        int minX = findRopeGrabBoundary(from, map, anchorX, launchStepX, rope,
                true, stats, ropeGrabCache, movementProfile);
        int maxX = findRopeGrabBoundary(from, map, anchorX, launchStepX, rope,
                false, stats, ropeGrabCache, movementProfile);

        int representativeX = (minX + maxX) / 2;
        Point representativeStart = from.pointAt(representativeX);
        Point representativeGrab = simulateGroundJumpRopeGrabCached(
                map, representativeStart, launchStepX, rope, ropeGrabCache, stats, movementProfile);
        if (representativeGrab == null) {
            return null;
        }

        int travelMs = BotPhysicsEngine.estimateGroundJumpRopeGrabTimeMs(
                map, representativeStart, launchStepX, rope, movementProfile);
        return new JumpLaunchWindow(minX, maxX, minX, maxX, representativeStart, representativeGrab, travelMs);
    }

    private static int findRopeGrabBoundary(BotNavigationGraph.Region from,
                                            MapleMap map,
                                            int startX,
                                            int launchStepX,
                                            Rope rope,
                                            boolean searchLeft,
                                            JumpBuildStats stats,
                                            RopeGrabCache ropeGrabCache,
                                            BotMovementProfile movementProfile) {
        int limitX = searchLeft ? from.minX : from.maxX;
        int validX = startX;
        int dir = searchLeft ? -1 : 1;
        while (validX != limitX && isValidRopeGrabLaunchX(from, map, validX + dir,
                launchStepX, rope, stats, ropeGrabCache, movementProfile)) {
            validX += dir;
        }
        return validX;
    }

    private static boolean isValidRopeGrabLaunchX(BotNavigationGraph.Region from,
                                                  MapleMap map,
                                                  int launchX,
                                                  int launchStepX,
                                                  Rope rope,
                                                  JumpBuildStats stats,
                                                  RopeGrabCache ropeGrabCache,
                                                  BotMovementProfile movementProfile) {
        if (!isApproachableJumpLaunchX(from, map, launchX)) {
            return false;
        }
        Point grab = simulateGroundJumpRopeGrabCached(
                map, from.pointAt(launchX), launchStepX, rope, ropeGrabCache, stats, movementProfile);
        return grab != null;
    }

    private static boolean isBlockedWallBoundaryLaunch(MapleMap map, Point launchPoint) {
        if (map == null || map.getFootholds() == null || launchPoint == null) {
            return false;
        }

        // Probe the shared per-tree collision index instead of recollecting every foothold:
        // map.getFootholds().getAllFootholds() rebuilds a whole LinkedList on every call, and
        // this probe sits inside the launch-boundary search and runs thousands of times per bake
        // (measured: 115k recollects / 98.7M items on a 854-foothold map, ~35% of build time).
        // The index's wall list carries exactly the CollidableWall subset (same isCollidableWall
        // predicate, same source list), so the answer is unchanged — verified graph-identical.
        for (Foothold foothold : BotPhysicsEngine.collidableWalls(map)) {
            if (foothold.getX1() != launchPoint.x) {
                continue;
            }

            int minY = Math.min(foothold.getY1(), foothold.getY2());
            int maxY = Math.max(foothold.getY1(), foothold.getY2());
            if (launchPoint.y > minY && launchPoint.y <= maxY) {
                return true;
            }
        }
        return false;
    }

    private static boolean canWalkToLaunchX(BotNavigationGraph.Region from, MapleMap map, int fromX, int launchX) {
        Point fromPoint = from.pointAt(fromX);
        Point launchPoint = from.pointAt(launchX);
        if (fromPoint == null || launchPoint == null || fromPoint.equals(launchPoint)) {
            return false;
        }
        return BotPhysicsEngine.canWalkGroundStep(map, fromPoint, launchPoint.x - fromPoint.x);
    }

    // --- Rope entry edges: ground/rope region → rope region ---

    private static void addRopeEntryEdges(BotNavigationGraph.Region ropeRegion,
                                          List<BotNavigationGraph.Region> groundRegions,
                                          Map<Integer, Rope> ropeByRegionId,
                                          MapleMap map,
                                          Map<Integer, List<BotNavigationGraph.Edge>> outgoing,
                                          Set<String> edgeKeys,
                                          RopeGrabCache ropeGrabCache,
                                          BotMovementProfile movementProfile) {
        Rope rope = ropeByRegionId.get(ropeRegion.id);
        if (rope == null) {
            return;
        }

        int ropeX = rope.x();
        int walkStep = BotMovementManager.walkStep(map, movementProfile);
        JumpBuildStats stats = new JumpBuildStats();
        for (BotNavigationGraph.Region ground : groundRegions) {
            int[] coveredThrough = {ground.minX - 1, ground.minX - 1, ground.minX - 1};
            boolean directEntryRun = false;
            for (int x = ground.minX; x <= ground.maxX; x++) {
                if (!columnStandsOn(ground, x)) {
                    directEntryRun = false;
                    continue;
                }
                Point anchor = ground.pointAt(x);
                int firstClimbableY = BotPhysicsEngine.firstClimbableY(rope);
                boolean canGrab = Math.abs(anchor.x - ropeX) <= BotMovementManager.cfg.ROPE_GRAB_X
                        && anchor.y >= firstClimbableY && anchor.y <= rope.bottomY();
                boolean canTopGrab = Math.abs(anchor.x - ropeX) <= BotMovementManager.cfg.ROPE_GRAB_X
                        && anchor.y < rope.topY()
                        && rope.topY() - anchor.y <= BotPhysicsEngine.cfg.MAX_SNAP_DROP;
                boolean canJumpGrab = BotMovementManager.canReachRopeFromGround(map, anchor, rope, movementProfile);
                boolean canTopStep = anchor.y <= rope.topY() + BotMovementManager.cfg.JUMP_Y_THRESH
                        && Math.abs(anchor.x - ropeX) <= BotMovementManager.cfg.ROPE_GRAB_X;

                if (canGrab) {
                    Point ropePoint = new Point(ropeX, Math.max(firstClimbableY, Math.min(anchor.y, rope.bottomY())));
                    if (!directEntryRun) {
                        addEdge(ground.id, ropeRegion.id, BotNavigationGraph.EdgeType.CLIMB,
                                anchor, ropePoint, 0, 0, BotPhysicsEngine.cfg.TICK_MS, outgoing, edgeKeys);
                    }
                    directEntryRun = true;
                    continue;
                }

                if (canTopGrab) {
                    if (!directEntryRun) {
                        addEdge(ground.id, ropeRegion.id, BotNavigationGraph.EdgeType.CLIMB,
                                anchor, new Point(ropeX, firstClimbableY), 0, 0, BotPhysicsEngine.cfg.TICK_MS, outgoing, edgeKeys);
                    }
                    directEntryRun = true;
                    continue;
                }
                directEntryRun = false;

                if (canTopStep) {
                    Point ropeGrab = BotPhysicsEngine.simulateDownJumpRopeGrab(map, anchor, rope);
                    if (ropeGrab != null) {
                        int cost = BotPhysicsEngine.estimateDownJumpRopeGrabTimeMs(map, anchor, rope);
                        addEdge(ground.id, ropeRegion.id, BotNavigationGraph.EdgeType.CLIMB,
                                anchor, ropeGrab, 0, 0, cost, outgoing, edgeKeys);
                    }
                }

                if (canJumpGrab) {
                    for (int i = 0; i < coveredThrough.length; i++) {
                        if (x <= coveredThrough[i]) {
                            continue;
                        }
                        int jumpStep = (i - 1) * walkStep;
                        JumpLaunchWindow launchWindow = expandRopeGrabLaunchWindow(
                                ground, map, anchor.x, jumpStep, rope, stats, ropeGrabCache, movementProfile);
                        if (launchWindow == null) {
                            continue;
                        }
                        coveredThrough[i] = launchWindow.maxX();
                        addEdge(ground.id, ropeRegion.id, BotNavigationGraph.EdgeType.CLIMB,
                                launchWindow.startPoint(), launchWindow.endPoint(),
                                launchWindow.minX(), launchWindow.maxX(),
                                jumpStep, 0, launchWindow.landingTimeMs(), outgoing, edgeKeys);
                    }
                }
            }
        }
    }

    // --- Rope exit edges: rope region → ground/rope region ---

    private static void addRopeExitEdges(BotNavigationGraph.Region ropeRegion,
                                         List<BotNavigationGraph.Region> ropeRegions,
                                         Map<Integer, Rope> ropeByRegionId,
                                         MapleMap map,
                                         Map<Integer, BotNavigationGraph.Region> regionsById,
                                         Map<Integer, Integer> regionIdByFootholdId,
                                         Map<Integer, List<BotNavigationGraph.Edge>> outgoing,
                                         Set<String> edgeKeys,
                                         BotMovementProfile movementProfile) {
        Rope rope = ropeByRegionId.get(ropeRegion.id);
        if (rope == null) {
            return;
        }

        int ropeX = rope.x();
        int jumpStep = BotMovementManager.walkStep(map, movementProfile);
        int maxRopeJumpDx = BotPhysicsEngine.maxRopeGrabSimulationHorizontalTravel(map, movementProfile);

        // Direct step-off at the top of the rope
        addTopStepOffEdge(ropeRegion, rope, map, regionsById, regionIdByFootholdId, outgoing, edgeKeys);

        // A 30px height grid skips entire landing windows on short/narrow shelves.
        // Keep one exact anchor per contiguous landing run, not one edge per pixel.
        int firstY = BotPhysicsEngine.firstClimbableY(rope);
        for (int stepX : new int[]{-jumpStep, 0, jumpStep}) {
            int target = -1;
            int runStart = firstY;
            for (int y = firstY; y <= rope.bottomY() + 1; y++) {
                BotPhysicsEngine.JumpLanding landing = y <= rope.bottomY()
                        ? BotPhysicsEngine.simulateRopeJumpLanding(map, new Point(ropeX, y), stepX, movementProfile) : null;
                BotPhysicsEngine.PostLandingJump stopped = BotPhysicsEngine.simulateLandingStop(map, landing, movementProfile);
                int to = stopped == null || stopped.lostGround() ? -1
                        : regionIdByFootholdId.getOrDefault(stopped.finalFoothold().getId(), -1);
                if (landing != null && regionIdByFootholdId.getOrDefault(landing.foothold().getId(), -1) != to) {
                    to = -1;
                }
                if (to == target) {
                    continue;
                }
                if (target >= 0) {
                    Point start = new Point(ropeX, runStart + (y - 1 - runStart) / 2);
                    BotPhysicsEngine.JumpLanding representative = BotPhysicsEngine.simulateRopeJumpLanding(map, start, stepX, movementProfile);
                    addEdge(ropeRegion.id, target, BotNavigationGraph.EdgeType.CLIMB,
                            start, representative.point(), stepX, 0, representative.timeMs(), outgoing, edgeKeys);
                }
                target = to;
                runStart = y;
            }
        }

        // The same per-pixel rule applies to transfers, with one edge per valid height run.
        for (BotNavigationGraph.Region otherRope : ropeRegions) {
            Rope targetRope = ropeByRegionId.get(otherRope.id);
            if (otherRope.id == ropeRegion.id || targetRope == null
                    || Math.abs(ropeX - targetRope.x()) > maxRopeJumpDx) {
                continue;
            }
            int launchDir = targetRope.x() > ropeX ? jumpStep : -jumpStep;
            int runStart = firstY - 1;
            for (int y = firstY; y <= rope.bottomY() + 1; y++) {
                Point grab = y <= rope.bottomY()
                        ? BotPhysicsEngine.simulateRopeJumpGrab(map, new Point(ropeX, y), launchDir, targetRope, movementProfile) : null;
                if (grab != null) {
                    if (runStart < firstY) {
                        runStart = y;
                    }
                } else if (runStart >= firstY) {
                    Point start = new Point(ropeX, runStart + (y - 1 - runStart) / 2);
                    Point landing = BotPhysicsEngine.simulateRopeJumpGrab(map, start, launchDir, targetRope, movementProfile);
                    int cost = BotPhysicsEngine.estimateRopeJumpGrabTimeMs(map, start, launchDir, targetRope, movementProfile);
                    addEdge(ropeRegion.id, otherRope.id, BotNavigationGraph.EdgeType.CLIMB,
                            start, landing, launchDir, 0, cost, outgoing, edgeKeys);
                    runStart = firstY - 1;
                }
            }
        }
    }

    private static void addTopStepOffEdge(BotNavigationGraph.Region ropeRegion,
                                          Rope rope,
                                          MapleMap map,
                                          Map<Integer, BotNavigationGraph.Region> regionsById,
                                          Map<Integer, Integer> regionIdByFootholdId,
                                          Map<Integer, List<BotNavigationGraph.Edge>> outgoing,
                                          Set<String> edgeKeys) {
        // Same widened top-exit probe the runtime uses (BotPhysicsEngine.findTopExitLanding), so a
        // clean CLIMB step-off edge exists wherever physics would actually land the climb — otherwise
        // pathing falls back to ballistic top jump-offs that read as janky on uneven ground.
        Point landPoint = BotPhysicsEngine.findTopExitLanding(map, rope);
        if (landPoint == null) {
            return;
        }

        Foothold foothold = BotPhysicsEngine.findGroundFoothold(map, landPoint);
        if (foothold == null) {
            return;
        }

        BotNavigationGraph.Region ground = regionsById.get(regionIdByFootholdId.getOrDefault(foothold.getId(), -1));
        if (ground == null || ground.isRopeRegion) {
            return;
        }

        Point ropePoint = new Point(rope.x(), rope.topY());
        addEdge(ropeRegion.id, ground.id, BotNavigationGraph.EdgeType.CLIMB,
                ropePoint, landPoint, 0, 0, BotPhysicsEngine.cfg.TICK_MS, outgoing, edgeKeys);
    }
    private static Map<Integer, Rope> buildRopeByRegionId(MapleMap map, List<BotNavigationGraph.Region> ropeRegions) {
        Map<Integer, Rope> ropeByRegionId = new HashMap<>();
        for (BotNavigationGraph.Region ropeRegion : ropeRegions) {
            Rope rope = findRopeFromRegion(map, ropeRegion);
            if (rope != null) {
                ropeByRegionId.put(ropeRegion.id, rope);
            }
        }
        return ropeByRegionId;
    }

    static Rope findRopeFromRegion(MapleMap map, BotNavigationGraph.Region ropeRegion) {
        if (ropeRegion == null || !ropeRegion.isRopeRegion) {
            return null;
        }
        for (Rope rope : map.getRopes()) {
            if (rope.x() == ropeRegion.minX && rope.topY() == ropeRegion.minY && rope.bottomY() == ropeRegion.maxY) {
                return rope;
            }
        }
        return null;
    }

    private static void addPortalEdges(Portal portal,
                                       MapleMap map,
                                       Map<Integer, BotNavigationGraph.Region> regionsById,
                                       Map<Integer, Integer> regionIdByFootholdId,
                                       Map<Integer, List<BotNavigationGraph.Edge>> outgoing,
                                       Set<String> edgeKeys) {
        if (portal.getTargetMapId() != map.getId()) {
            return;
        }

        Portal targetPortal = map.getPortal(portal.getTarget());
        if (targetPortal == null || targetPortal.getId() == portal.getId()) {
            return;
        }

        // Portal WZ positions can sit a few pixels below the foothold surface they belong to,
        // causing findBelow to skip the correct foothold and land on the next lower platform.
        // Probe MAX_SNAP_DROP above the portal position so findBelow always finds the right foothold.
        int snapUp = BotPhysicsEngine.cfg.MAX_SNAP_DROP;
        BotNavigationGraph.Region from = findRegionBelow(map, regionsById, regionIdByFootholdId,
                new Point(portal.getPosition().x, portal.getPosition().y - snapUp));
        BotNavigationGraph.Region to = findRegionBelow(map, regionsById, regionIdByFootholdId,
                new Point(targetPortal.getPosition().x, targetPortal.getPosition().y - snapUp));
        if (from == null || to == null) {
            return;
        }

        Point start = from.pointAt(portal.getPosition().x);
        Point end = to.pointAt(targetPortal.getPosition().x);
        // A single portal is instantaneous, so its base edge cost is 0. The only real time a
        // portal costs is the post-use cooldown the bot pays when it chains straight into another
        // portal; that is modelled path-dependently in BotNavigationManager's A* (the viaPortal
        // state flag), not as a flat per-edge cost. See PORTAL_USE_COOLDOWN_MS.
        addEdge(from.id, to.id, BotNavigationGraph.EdgeType.PORTAL, start, end, 0, portal.getId(), 0, outgoing, edgeKeys);
    }

    private static int findRegionIdBelow(MapleMap map,
                                         Map<Integer, Integer> regionIdByFootholdId,
                                         Point point) {
        if (map.getFootholds() == null) {
            return -1;
        }

        Foothold foothold = BotPhysicsEngine.findBelowIndexed(map, point);
        if (foothold == null) {
            return -1;
        }

        return regionIdByFootholdId.getOrDefault(foothold.getId(), -1);
    }

    private static BotNavigationGraph.Region findRegionBelow(MapleMap map,
                                                             Map<Integer, BotNavigationGraph.Region> regionsById,
                                                             Map<Integer, Integer> regionIdByFootholdId,
                                                             Point point) {
        int regionId = findRegionIdBelow(map, regionIdByFootholdId, point);
        if (regionId < 0) {
            return null;
        }
        return regionsById.get(regionId);
    }

    private static boolean isWalkConnection(EndpointConnection connection) {
        int dx = Math.abs(connection.to.x - connection.from.x);
        int dy = connection.to.y - connection.from.y;
        return BotPhysicsEngine.isWalkableEndpointStep(dx, dy);
    }

    private static void addEdge(int fromRegionId,
                                int toRegionId,
                                BotNavigationGraph.EdgeType type,
                                Point startPoint,
                                Point endPoint,
                                int launchMinX,
                                int launchMaxX,
                                int launchStepX,
                                int portalId,
                                int cost,
                                Map<Integer, List<BotNavigationGraph.Edge>> outgoing,
                                Set<String> edgeKeys) {
        addEdge(fromRegionId, toRegionId, type, startPoint, endPoint, launchMinX, launchMaxX, launchStepX, portalId,
                0, 0, 0, cost, outgoing, edgeKeys);
    }

    // Ours (LPQ shelf loop): as above, plus the pre-inset validated span the executor's launch
    // phase is clamped to (see BotNavigationGraph.Edge.acceptsLaunchX).
    private static void addEdge(int fromRegionId,
                                int toRegionId,
                                BotNavigationGraph.EdgeType type,
                                Point startPoint,
                                Point endPoint,
                                int launchMinX,
                                int launchMaxX,
                                int launchValidMinX,
                                int launchValidMaxX,
                                int launchStepX,
                                int portalId,
                                int cost,
                                Map<Integer, List<BotNavigationGraph.Edge>> outgoing,
                                Set<String> edgeKeys) {
        addEdge(fromRegionId, toRegionId, type, startPoint, endPoint, launchMinX, launchMaxX,
                launchValidMinX, launchValidMaxX, launchStepX, portalId, 0, 0, 0, cost, outgoing, edgeKeys);
    }

    private static void addEdge(int fromRegionId,
                                int toRegionId,
                                BotNavigationGraph.EdgeType type,
                                Point startPoint,
                                Point endPoint,
                                int launchStepX,
                                int portalId,
                                int cost,
                                Map<Integer, List<BotNavigationGraph.Edge>> outgoing,
                                Set<String> edgeKeys) {
        addEdge(fromRegionId, toRegionId, type, startPoint, endPoint, startPoint.x, startPoint.x, launchStepX, portalId,
                0, 0, 0, cost, outgoing, edgeKeys);
    }

    private static void addEdge(int fromRegionId,
                                int toRegionId,
                                BotNavigationGraph.EdgeType type,
                                Point startPoint,
                                Point endPoint,
                                int launchMinX,
                                int launchMaxX,
                                int launchStepX,
                                int portalId,
                                int ropeX,
                                int ropeTopY,
                                int ropeBottomY,
                                int cost,
                                Map<Integer, List<BotNavigationGraph.Edge>> outgoing,
                                Set<String> edgeKeys) {
        addEdge(fromRegionId, toRegionId, type, startPoint, endPoint,
                launchMinX, launchMaxX, launchMinX, launchMaxX, launchStepX, portalId,
                ropeX, ropeTopY, ropeBottomY, cost, outgoing, edgeKeys);
    }

    private static void addEdge(int fromRegionId,
                                int toRegionId,
                                BotNavigationGraph.EdgeType type,
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
                                int cost,
                                Map<Integer, List<BotNavigationGraph.Edge>> outgoing,
                                Set<String> edgeKeys) {
        String key = fromRegionId + ":" + toRegionId + ":" + type + ":" + startPoint.x + ":" + startPoint.y + ":"
                + endPoint.x + ":" + endPoint.y + ":" + launchStepX + ":" + portalId + ":"
                + ropeX + ":" + ropeTopY + ":" + ropeBottomY + ":" + launchMinX + ":" + launchMaxX;
        if (!edgeKeys.add(key)) {
            return;
        }

        outgoing.computeIfAbsent(fromRegionId, ignored -> new ArrayList<>())
                .add(new BotNavigationGraph.Edge(fromRegionId, toRegionId, type, startPoint, endPoint,
                        launchMinX, launchMaxX, launchValidMinX, launchValidMaxX, launchStepX, portalId,
                        ropeX, ropeTopY, ropeBottomY, cost));
        BuildProfileBuilder profile = ACTIVE_BUILD_PROFILE.get();
        if (profile != null) {
            profile.recordEdge(type);
        }
    }

    private static EndpointConnection closestEndpointConnection(Foothold first, Foothold second) {
        Point[] firstEndpoints = new Point[]{
                new Point(first.getX1(), first.getY1()),
                new Point(first.getX2(), first.getY2())
        };
        Point[] secondEndpoints = new Point[]{
                new Point(second.getX1(), second.getY1()),
                new Point(second.getX2(), second.getY2())
        };

        EndpointConnection best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (Point from : firstEndpoints) {
            for (Point to : secondEndpoints) {
                int distance = Math.abs(to.x - from.x) + Math.abs(to.y - from.y);
                if (distance < bestDistance) {
                    best = new EndpointConnection(from, to);
                    bestDistance = distance;
                }
            }
        }
        return best;
    }

    private static EndpointConnection sharedEndpointConnection(Foothold first, Foothold second) {
        Point[] firstEndpoints = new Point[]{
                new Point(first.getX1(), first.getY1()),
                new Point(first.getX2(), first.getY2())
        };
        Point[] secondEndpoints = new Point[]{
                new Point(second.getX1(), second.getY1()),
                new Point(second.getX2(), second.getY2())
        };

        for (Point from : firstEndpoints) {
            for (Point to : secondEndpoints) {
                if (from.equals(to)) {
                    return new EndpointConnection(from, to);
                }
            }
        }
        return null;
    }

    private static int footholdMinX(Foothold foothold) {
        return Math.min(foothold.getX1(), foothold.getX2());
    }

    private static int groupMinX(List<Foothold> footholds) {
        int minX = Integer.MAX_VALUE;
        for (Foothold foothold : footholds) {
            minX = Math.min(minX, footholdMinX(foothold));
        }
        return minX;
    }

    private static int groupMinY(List<Foothold> footholds) {
        int minY = Integer.MAX_VALUE;
        for (Foothold foothold : footholds) {
            minY = Math.min(minY, Math.min(foothold.getY1(), foothold.getY2()));
        }
        return minY;
    }

    private static int estimateWalkCost(Point start, Point end) {
        return estimateWalkCost(start, end, BotMovementProfile.base());
    }

    private static int estimateWalkCost(Point start, Point end, BotMovementProfile movementProfile) {
        return estimateHorizontalTravelTimeMs(Math.abs(end.x - start.x), movementProfile);
    }

    private static int estimateHorizontalTravelTimeMs(int dx, BotMovementProfile movementProfile) {
        return Math.max(0, (int) Math.round((dx * 1000.0) / Math.max(1.0, movementProfile.walkVelocityPxs())));
    }

    private record EndpointConnection(Point from, Point to) {
    }



    private static final class UnionFind {
        private final Map<Integer, Integer> parent = new HashMap<>();
        private final Map<Integer, Integer> rank = new HashMap<>();
        private final Map<Integer, List<Foothold>> members = new HashMap<>();

        void add(Foothold foothold) {
            int value = foothold.getId();
            parent.putIfAbsent(value, value);
            rank.putIfAbsent(value, 0);
            members.put(value, new ArrayList<>(List.of(foothold)));
        }

        int find(int value) {
            int parentValue = parent.get(value);
            if (parentValue == value) {
                return value;
            }

            int root = find(parentValue);
            parent.put(value, root);
            return root;
        }

        void union(int first, int second) {
            int firstRoot = find(first);
            int secondRoot = find(second);
            if (firstRoot == secondRoot) {
                return;
            }
            // Connected switchbacks can overlap in x. They need WALK edges, not a
            // shared region whose pointAt(x) silently selects the wrong floor.
            for (Foothold a : members.get(firstRoot)) {
                for (Foothold b : members.get(secondRoot)) {
                    if (!BotPhysicsEngine.canShareWalkSurface(a, b)) {
                        return;
                    }
                }
            }

            int firstRank = rank.getOrDefault(firstRoot, 0);
            int secondRank = rank.getOrDefault(secondRoot, 0);
            if (firstRank < secondRank) {
                parent.put(firstRoot, secondRoot);
                members.get(secondRoot).addAll(members.remove(firstRoot));
                return;
            }
            if (firstRank > secondRank) {
                parent.put(secondRoot, firstRoot);
                members.get(firstRoot).addAll(members.remove(secondRoot));
                return;
            }

            parent.put(secondRoot, firstRoot);
            rank.put(firstRoot, firstRank + 1);
            members.get(firstRoot).addAll(members.remove(secondRoot));
        }
    }
}
