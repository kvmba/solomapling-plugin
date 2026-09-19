package soloMapling.Environment;

import org.gms.client.Character;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Rope;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.ArtificialPlayer.BotGeneration;
import soloMapling.ArtificialPlayer.BotGrindSystem.BotSpotPicker;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotMovementSystem.MovementCommands;
import soloMapling.server.ExecutorServiceManager;

import java.awt.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static soloMapling.ArtificialPlayer.BotCustomization.getRandomChairId;
import static soloMapling.ArtificialPlayer.BotMovementSystem.MovementCommands.botSitChair;
import static soloMapling.ArtificialPlayer.BotGeneration.createBotPollReadiness;
import static soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage.getBotById;
import static soloMapling.DebugUtilities.debugprint;
import static soloMapling.DebugUtilities.fmt;
import static soloMapling.Environment.PlatformSpawner.findUnoccupiedPoint;
import static soloMapling.Environment.PlatformSpawner.findUnoccupiedPoints;
import static soloMapling.server.SoloMaplingUtilities.getMapleMapById;

// The platform-placement utility library extracted from EnvironmentManager: spawning bot batches
// onto recorded platforms (with unoccupied-spot selection and retry), the filler-bot line spawners,
// and the platform lookup/query helpers (which platform is a character on, which ids exist on a
// map). Used by the environment waves, several bot types (merchants, Henesys, OPQ), and the GM
// commands — it's placement mechanics, not world-startup logic, which is why it lives apart from
// the wave orchestration. Ours (SoloMapling).
public class PlatformPlacement {

    // Tolerance for Y-coordinate matching when determining if a character is on a platform;
    // characters within this vertical distance of the platform are considered "on" it.
    private static final int Y_TOLERANCE = 10;
    // Tolerance for X-coordinate proximity when checking if a character is near platform bounds
    // (buffer beyond the recorded min/max X values).
    private static final int X_TOLERANCE = 20;

    private static final Random random = new Random();

    // A spawned bot must stand on the floor, not perched on a rope/ladder. The Free Market
    // entrance's m1/m2 platforms are FLAT and span the map's whole width
    // (m1 y=4 x∈[387,1499], m2 y=-266 x∈[425,1462]), and the map's ladders sit at x=933
    // (y -264..2) and x=1033 (y -804..-538) — inside those spans. A uniform x-pick therefore
    // lands the odd spawn straight on a ladder column, where the bot renders the standing pose
    // on the ladder sprite (a real climber needs the ROPE/LADDER stance, which a freshly spawned
    // static bot never gets). Nudge the spawn x clear of any rope/ladder column before the bot
    // is created, so it lands on open ground instead.
    private static final int LADDER_CLEAR_X = 18;  // px clear of the rope's x axis
    private static final int LADDER_CLEAR_Y = 24;  // px past the rope's ends (sprite overhang)

    /**
     * Shift {@code spawn} clear of every rope/ladder column in {@code ropes} (keeps its y, clamps x
     * to [minX, maxX]). A point already off every column is returned unchanged; a point on a column
     * is nudged to the side it already leans toward (right when exactly on the axis). An empty list
     * is non-fatal — the point is returned as-is.
     */
    static Point avoidLadderColumn(List<Rope> ropes, Point spawn, int minX, int maxX) {
        if (ropes == null || ropes.isEmpty() || spawn == null) {
            return spawn;
        }
        int x = spawn.x;
        for (Rope rope : ropes) {
            if (Math.abs(x - rope.x()) > LADDER_CLEAR_X) {
                continue;
            }
            if (spawn.y < rope.topY() - LADDER_CLEAR_Y || spawn.y > rope.bottomY() + LADDER_CLEAR_Y) {
                continue;
            }
            x = x < rope.x() ? rope.x() - LADDER_CLEAR_X : rope.x() + LADDER_CLEAR_X;
        }
        if (x == spawn.x) {
            return spawn;
        }
        return new Point(Math.max(minX, Math.min(maxX, x)), spawn.y);
    }

    // The point a bot should really stand on for a platform: an occupancy-free spot nudged clear of
    // any rope/ladder column (see avoidLadderColumn). Mirrors the SPAWN path's nudge in
    // spawnBotsOnMapOnPlatform, but for the MOVE path (botMoveToPlatformAnyUnoccupiedSpot*) — the
    // FM merchants shuffle to "the current platform" or a named one, and the entrance's flat m1/m2
    // span the whole map width, ladder columns included. Without the nudge a shuffle target can land
    // on the ladder axis, where the bot walks to and renders a STAND pose on the ladder sprite (the
    // "merchant standing on the stairs" report).
    //
    // FLAT only: a flat platform's y is constant, so moving x never leaves the surface; on a SLOPED
    // platform the recorded y belongs to its own x, so moving x alone would drop the bot off it.
    private static Point standSpot(int mapId, Platform platform, Point spot) {
        if (!platform.isFlat()) {
            return spot;
        }
        MapleMap map = getMapleMapById(mapId);
        List<Rope> ropes = map == null ? List.of() : map.getRopes();
        return avoidLadderColumn(ropes, spot, platform.getMinX(), platform.getMaxX());
    }

    public static List<Integer> spawnBotsOnMapOnPlatform(int numBots, int mapId, String platform_id) {
        Platform flatPlatform = PlatformParser.parsePlatform(mapId, platform_id);
        List<Point> occupied = Collections.synchronizedList(new ArrayList<>());
        ConcurrentLinkedQueue<Integer> characterIds = new ConcurrentLinkedQueue<>();
        AtomicInteger failureCount = new AtomicInteger(0);

        debugprint(fmt("Spawning {} bots on {} at platform: {}", numBots, mapId, platform_id));

        MapleMap spawnMap = getMapleMapById(mapId);
        List<Rope> ropes = spawnMap == null ? List.of() : spawnMap.getRopes();
        // Pre-generate all spawn points (must be sequential to avoid overlaps)
        List<Point> spawnPoints = new ArrayList<>();
        for (int i = 0; i < numBots; i++) {
            Point spawn = findUnoccupiedPoint(flatPlatform, occupied);
            // Keep the spawn off any rope/ladder column so the bot lands on open floor
            // (see avoidLadderColumn) instead of rendering a standing pose on a ladder.
            // FLAT only: a flat platform's y is constant, so moving x never leaves the surface,
            // whereas on a SLOPED platform the recorded y belongs to its own x.
            if (flatPlatform.isFlat()) {
                spawn = avoidLadderColumn(ropes, spawn, flatPlatform.getMinX(), flatPlatform.getMaxX());
            }
            occupied.add(spawn);
            spawnPoints.add(spawn);
        }

        // Use CountDownLatch to wait for all spawns to complete
        CountDownLatch latch = new CountDownLatch(numBots);

        for (Point spawn : spawnPoints) {
            ExecutorServiceManager.runAsync(() -> {
                try {
                    Character fakechar = createBotWithRetry(spawn, mapId, 5);
                    if (fakechar != null) {
                        characterIds.add(fakechar.getId());
                    } else {
                        failureCount.incrementAndGet();
                        debugprint(fmt("Failed to create bot at point {} after retries", spawn));
                    }
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                    debugprint(fmt("Exception creating bot at {}: {}", spawn, e.getMessage()));
                } finally {
                    latch.countDown();
                }
            });
        }

        // Wait for all spawns to complete with timeout
        try {
            boolean completed = latch.await(120, TimeUnit.SECONDS);
            if (!completed) {
                debugprint(fmt("Timeout waiting for bot spawns. Completed: {}/{}",
                        characterIds.size(), numBots));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            debugprint("Bot spawning interrupted");
        }

        if (failureCount.get() > 0) {
            debugprint(fmt("Spawning complete. Success: {}, Failed: {}",
                    characterIds.size(), failureCount.get()));
        }

        return new ArrayList<>(characterIds);
    }


    public static List<Integer> spawnBotsOnMapOnPlatformInRadius(int numBots, int mapId, String platform_id, Point center, int radius) {
        Platform flatPlatform = PlatformParser.parsePlatform(mapId, platform_id);
        List<Point> occupied = Collections.synchronizedList(new ArrayList<>());
        ConcurrentLinkedQueue<Integer> characterIds = new ConcurrentLinkedQueue<>();
        AtomicInteger failureCount = new AtomicInteger(0);

        debugprint(fmt("Spawning {} bots on {} at platform: {} within radius {} of ({},{})",
                numBots, mapId, platform_id, radius, center.x, center.y));

        // Pre-generate all spawn points within radius
        List<Point> spawnPoints = new ArrayList<>();
        for (int i = 0; i < numBots; i++) {
            Point spawn = findUnoccupiedPointInRadius(flatPlatform, occupied, center, radius);
            if (spawn == null) {
                debugprint(fmt("Could not find unoccupied point for bot {} within radius", i));
                continue;
            }
            occupied.add(spawn);
            spawnPoints.add(spawn);
        }

        CountDownLatch latch = new CountDownLatch(spawnPoints.size());

        for (Point spawn : spawnPoints) {
            ExecutorServiceManager.runAsync(() -> {
                try {
                    Character fakechar = createBotWithRetry(spawn, mapId, 5);
                    if (fakechar != null) {
                        characterIds.add(fakechar.getId());
                    } else {
                        failureCount.incrementAndGet();
                        debugprint(fmt("Failed to create bot at point {} after retries", spawn));
                    }
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                    debugprint(fmt("Exception creating bot at {}: {}", spawn, e.getMessage()));
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            boolean completed = latch.await(120, TimeUnit.SECONDS);
            if (!completed) {
                debugprint(fmt("Timeout waiting for bot spawns. Completed: {}/{}",
                        characterIds.size(), spawnPoints.size()));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            debugprint("Bot spawning interrupted");
        }

        if (failureCount.get() > 0) {
            debugprint(fmt("Spawning complete. Success: {}, Failed: {}",
                    characterIds.size(), failureCount.get()));
        }

        return new ArrayList<>(characterIds);
    }

    public static List<Integer> spawnFillerBots(int numBots, int mapId, Point p1, Point p2) {
        int minX = Math.min(p1.x, p2.x);
        int maxX = Math.max(p1.x, p2.x);
        int baseY = p1.y;
        Platform adHocPlatform = new Platform(minX, maxX, baseY, List.of(p1, p2), Platform.Type.FLAT);

        List<Point> occupied = Collections.synchronizedList(new ArrayList<>());
        ConcurrentLinkedQueue<Integer> characterIds = new ConcurrentLinkedQueue<>();
        AtomicInteger failureCount = new AtomicInteger(0);

        debugprint(fmt("Spawning {} filler bots between ({},{}) and ({},{}) on map {}",
                numBots, p1.x, p1.y, p2.x, p2.y, mapId));

        List<Point> spawnPoints = findUnoccupiedPoints(adHocPlatform, occupied, numBots);
        occupied.addAll(spawnPoints);

        CountDownLatch latch = new CountDownLatch(spawnPoints.size());
        double chairChance = 0.20;

        for (Point spawn : spawnPoints) {
            ExecutorServiceManager.runAsync(() -> {
                try {
                    Character fakechar = createBotWithRetry(spawn, mapId, 5);
                    if (fakechar != null) {
                        characterIds.add(fakechar.getId());
                        if (Math.random() < chairChance) {
                            // Sit only after the spawn drop-down/turn-around finishes
                            ExecutorServiceManager.getScheduledExecutorService().schedule(
                                    () -> botSitChair(fakechar, getRandomChairId()),
                                    BotGeneration.SPAWN_CHOREOGRAPHY_MAX_MS + 500, TimeUnit.MILLISECONDS);
                        }
                    } else {
                        failureCount.incrementAndGet();
                        debugprint(fmt("Failed to create filler bot at point {} after retries", spawn));
                    }
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                    debugprint(fmt("Exception creating filler bot at {}: {}", spawn, e.getMessage()));
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            boolean completed = latch.await(120, TimeUnit.SECONDS);
            if (!completed) {
                debugprint(fmt("Timeout waiting for filler bot spawns. Completed: {}/{}",
                        characterIds.size(), spawnPoints.size()));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            debugprint("Filler bot spawning interrupted");
        }

        if (failureCount.get() > 0) {
            debugprint(fmt("Filler spawning complete. Success: {}, Failed: {}",
                    characterIds.size(), failureCount.get()));
        }

        debugprint(fmt("Filler bots spawned: {}", characterIds.size()));
        return new ArrayList<>(characterIds);
    }

    public static List<Integer> spawnFillerBotsLockedY(int numBots, int mapId, Point p1, Point p2) {
        int minX = Math.min(p1.x, p2.x);
        int maxX = Math.max(p1.x, p2.x);
        int baseY = p1.y;
        Platform adHocPlatform = new Platform(minX, maxX, baseY, List.of(p1, p2), Platform.Type.FLAT);

        List<Point> occupied = Collections.synchronizedList(new ArrayList<>());
        ConcurrentLinkedQueue<Integer> characterIds = new ConcurrentLinkedQueue<>();
        AtomicInteger failureCount = new AtomicInteger(0);

        debugprint(fmt("Spawning {} filler bots (locked Y={}) between x={} and x={} on map {}",
                numBots, baseY, minX, maxX, mapId));

        List<Point> spawnPoints = findUnoccupiedPoints(adHocPlatform, occupied, numBots);
        for (Point sp : spawnPoints) {
            sp.y = baseY;
        }
        occupied.addAll(spawnPoints);

        CountDownLatch latch = new CountDownLatch(spawnPoints.size());
        double chairChance = 0.20;

        for (Point spawn : spawnPoints) {
            ExecutorServiceManager.runAsync(() -> {
                try {
                    Character fakechar = createBotWithRetry(spawn, mapId, 5);
                    if (fakechar != null) {
                        characterIds.add(fakechar.getId());
                        if (Math.random() < chairChance) {
                            // Sit only after the spawn drop-down/turn-around finishes
                            ExecutorServiceManager.getScheduledExecutorService().schedule(
                                    () -> botSitChair(fakechar, getRandomChairId()),
                                    BotGeneration.SPAWN_CHOREOGRAPHY_MAX_MS + 500, TimeUnit.MILLISECONDS);
                        }
                    } else {
                        failureCount.incrementAndGet();
                        debugprint(fmt("Failed to create filler bot at point {} after retries", spawn));
                    }
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                    debugprint(fmt("Exception creating filler bot at {}: {}", spawn, e.getMessage()));
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            boolean completed = latch.await(120, TimeUnit.SECONDS);
            if (!completed) {
                debugprint(fmt("Timeout waiting for filler bot spawns. Completed: {}/{}",
                        characterIds.size(), spawnPoints.size()));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            debugprint("Filler bot spawning interrupted");
        }

        if (failureCount.get() > 0) {
            debugprint(fmt("Filler spawning complete. Success: {}, Failed: {}",
                    characterIds.size(), failureCount.get()));
        }

        debugprint(fmt("Filler bots spawned (locked Y): {}", characterIds.size()));
        return new ArrayList<>(characterIds);
    }

    /**
     * Spawn bots spread across a map's walkable ground, using the WZ terrain rather than a
     * recorded platform file.
     *
     * <p>The recording-based spawners ({@link #spawnBotsOnMapOnPlatform}) can only place bots on
     * maps that shipped a movement-data pack - which, for the party-quest lobbies, is Orbis's
     * alone. This variant asks the dynamic engine's terrain graph for ground points instead, so
     * a lobby the bots could never be placed in before can now be filled.
     *
     * <p>Anchored on the map's spawn portal for the reachability filter, so no bot is placed on
     * a ledge it cannot walk to. Falls back to the portal point when the graph is not baked yet
     * (the picker returns an empty list), so a spawn is never silently dropped.
     */
    public static List<Integer> spawnBotsOnMap(int numBots, int mapId) {
        return spawnBotsOnMap(numBots, mapId, null);
    }

    public static List<Integer> spawnBotsOnMap(int numBots, int mapId, Point anchor) {
        MapleMap map = getMapleMapById(mapId);
        if (map == null || numBots <= 0) {
            return List.of();
        }
        Point from = anchor != null ? anchor : spawnAnchor(map);
        List<Point> spots = BotSpotPicker.pickGroundSpots(map, from.x, from.y, numBots);
        if (spots.isEmpty()) {
            spots = new ArrayList<>(Collections.nCopies(numBots, from));
        }

        ConcurrentLinkedQueue<Integer> characterIds = new ConcurrentLinkedQueue<>();
        AtomicInteger failureCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(spots.size());
        for (Point spawn : spots) {
            ExecutorServiceManager.runAsync(() -> {
                try {
                    Character fakechar = createBotWithRetry(spawn, mapId, 5);
                    if (fakechar != null) {
                        characterIds.add(fakechar.getId());
                    } else {
                        failureCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                    debugprint(fmt("Exception creating bot at {}: {}", spawn, e.getMessage()));
                } finally {
                    latch.countDown();
                }
            });
        }
        try {
            if (!latch.await(120, TimeUnit.SECONDS)) {
                debugprint(fmt("Timeout waiting for bot spawns on map {}. Completed: {}/{}",
                        mapId, characterIds.size(), spots.size()));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (failureCount.get() > 0) {
            debugprint(fmt("Map spawn on {}: success {}, failed {}",
                    mapId, characterIds.size(), failureCount.get()));
        }
        return new ArrayList<>(characterIds);
    }

    /** A point to anchor reachability on: the map's first spawn portal, else (0,0). */
    private static Point spawnAnchor(MapleMap map) {
        var portal = map.getPortal(0);
        return portal != null ? portal.getPosition() : new Point(0, 0);
    }

    /**
     * Stroll a bot to a random reachable walking point on its current map, on the dynamic engine.
     *
     * <p>Recording-free sibling of {@link #botMoveToPlatformAnyUnoccupiedSpot} for maps that have
     * no platform pack. No-op while a stroll is already in progress, so an FSM that calls this
     * each idle tick does not thrash the target mid-walk.
     */
    public static void botStrollOnMap(Character fakechar) {
        if (fakechar == null || fakechar.getMap() == null || GCMovement.isMoving(fakechar)) {
            return;
        }
        Point pos = fakechar.getPosition();
        Point spot = BotSpotPicker.pickGroundSpot(fakechar.getMap(), pos.x, pos.y);
        if (spot != null) {
            GCMovement.move(fakechar, spot.x, spot.y);
        }
    }

    private static Point findUnoccupiedPointInRadius(Platform platform, List<Point> occupied, Point center, int radius) {
        int maxAttempts = 100;
        for (int i = 0; i < maxAttempts; i++) {
            Point candidate = findUnoccupiedPoint(platform, occupied);
            if (Math.abs(candidate.x - center.x) <= radius && Math.abs(candidate.y - center.y) <= radius) {
                return candidate;
            }
        }
        return null;
    }


    public static Character createBotWithRetry(Point spawn, int mapId, int maxRetries) {
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                Character fakechar = createBotPollReadiness(spawn, mapId);
                if (fakechar != null) {
                    return fakechar;
                }
                // If null but no exception, brief pause before retry
                if (attempt < maxRetries) {
                    Thread.sleep(200 * attempt); // Exponential-ish backoff
                }
            } catch (Exception e) {
                debugprint(fmt("Attempt {}/{} failed for bot at {}: {}",
                        attempt, maxRetries, spawn, e.getMessage()));
                if (attempt < maxRetries) {
                    try {
                        Thread.sleep(200 * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                }
            }
        }
        return null;
    }

    public static void botMoveToPlatformAnyUnoccupiedSpot(Character fakechar, String platform) {
        int mapId = fakechar.getMapId();

        // todo verify platform name exists in mapId

        List<Point> occupiedPointsOnPlatform = getListOfCharacterCoordinates(getAllCharsOnPlatform(mapId, platform));
        Platform flatPlatform = PlatformParser.parsePlatform(mapId, platform);
        Point unoccupiedPt = standSpot(mapId, flatPlatform,
                findUnoccupiedPoint(flatPlatform, occupiedPointsOnPlatform));
        MovementCommands.pathFinderBeta(fakechar, unoccupiedPt);
    }

    public static void botMoveToPlatformAnyUnoccupiedSpotAware(Character fakechar, String platform) {
        int mapId = fakechar.getMapId();

        List<Point> occupiedPointsOnPlatform = getListOfCharacterCoordinates(getAllCharsOnPlatform(mapId, platform));
        Platform flatPlatform = PlatformParser.parsePlatform(mapId, platform);
        Point unoccupiedPt = standSpot(mapId, flatPlatform,
                findUnoccupiedPoint(flatPlatform, occupiedPointsOnPlatform));
        MovementCommands.pathFinderAware(fakechar, unoccupiedPt);
    }

    // Dynamic-engine sibling of botMoveToPlatformAnyUnoccupiedSpot: same occupancy-aware target
    // pick, but the walk runs on GCMovement, which lands on the exact pixel instead of a recorded
    // path's fixed endpoints (the cause of merchant bots stacking on "spots"). Skips while a
    // dynamic stroll is already in progress so FSM ticks can't thrash the target mid-walk.
    // Note: first use puts the bot under dynamic control for good — it holds the shared movement
    // lock, so recorded-path MovementCommands silently no-op for this bot from then on.
    public static void botMoveToPlatformAnyUnoccupiedSpotDynamic(Character fakechar, String platform) {
        if (fakechar == null || platform == null || GCMovement.isMoving(fakechar)) {
            return;
        }
        int mapId = fakechar.getMapId();
        List<Point> occupiedPointsOnPlatform = getListOfCharacterCoordinates(getAllCharsOnPlatform(mapId, platform));
        Platform flatPlatform = PlatformParser.parsePlatform(mapId, platform);
        Point unoccupiedPt = standSpot(mapId, flatPlatform,
                findUnoccupiedPoint(flatPlatform, occupiedPointsOnPlatform));
        if (unoccupiedPt == null) {
            return;
        }
        GCMovement.move(fakechar, unoccupiedPt.x, unoccupiedPt.y);
    }

    /**
     * Determines which platform a character is currently standing on.
     * <p>
     * Scans all platform CSV files for the character's map and finds the best match
     * based on position proximity. For flat platforms, checks if X is within bounds
     * and Y is close to baseY. For sloped platforms, uses interpolation to estimate
     * the expected Y at the character's X position.
     *
     * @param chr The character whose platform we want to find
     * @return The platform identifier (e.g., "m1", "m2") or null if not on any known platform
     */
    public static String getCurrentPlatform(Character chr) {
        Point currentPosition = chr.getPosition();
        int mapId = chr.getMapId();
        String platform = findPlatformAtPosition(mapId, currentPosition);
        //debugprint("MapID: ", mapId, "Platform: ", platform);
        return platform;
    }

    /**
     * Finds which platform a given position belongs to on a specific map.
     *
     * <p>Only MAIN platforms ({@code m*}) are considered: a character is only ever standing on a
     * walkable surface. The {@code c*} "connector" recordings are climb/shaft paths across empty
     * space (a ladder column or a stair run), and their reference points are the mid-air positions
     * a climber passes through — so resolving a standable position onto one and then walking a bot
     * there parks it standing in the air / on the ladder sprite (the Free Market entrance report:
     * a merchant shuffling "to its current platform" on m1/m2 at an x over the ladder column landed
     * on a {@code c*} recording and stood in mid-air). Callers want the surface under the bot.
     *
     * @param mapId    The map ID to search
     * @param position The position to check
     * @return The main platform identifier (e.g., "m1", "m2") or null if not on any of them
     */
    public static String findPlatformAtPosition(int mapId, Point position) {
        List<String> platformIds = getMainPlatformIds(mapId);

        if (platformIds.isEmpty()) {
            return null;
        }

        String bestMatch = null;
        int bestScore = Integer.MAX_VALUE;

        for (String platformId : platformIds) {
            Platform platform = PlatformParser.parsePlatform(mapId, platformId);

            if (platform == null || platform.getSortedPoints().isEmpty()) {
                continue;
            }

            int score = calculatePlatformMatchScore(platform, position);

            // Score of -1 means position is definitely not on this platform
            if (score >= 0 && score < bestScore) {
                bestScore = score;
                bestMatch = platformId;
            }
        }

        return bestMatch;
    }

    /**
     * Calculates how well a position matches a platform.
     * Lower score = better match. Returns -1 if position is definitely not on the platform.
     *
     * @param platform The platform to check against
     * @param position The position to evaluate
     * @return Match score (lower is better) or -1 if not a match
     */
    private static int calculatePlatformMatchScore(Platform platform, Point position) {
        int x = position.x;
        int y = position.y;

        // Check if X is within platform bounds (with tolerance)
        if (x < platform.getMinX() - X_TOLERANCE || x > platform.getMaxX() + X_TOLERANCE) {
            return -1; // Definitely not on this platform
        }

        // Get the expected Y at this X position
        int expectedY = platform.getYAtX(x);
        int yDifference = Math.abs(y - expectedY);

        // If Y difference is too large, not on this platform
        if (yDifference > Y_TOLERANCE) {
            return -1;
        }

        // Score is based on how close we are to the expected Y
        // Also factor in how well we're within the X bounds (prefer being solidly within bounds)
        int xDistanceFromCenter = Math.abs(x - (platform.getMinX() + platform.getMaxX()) / 2);

        // Combined score: Y accuracy is more important, X centering is secondary
        return yDifference * 10 + (xDistanceFromCenter / 10);
    }

    /**
     * Gets all available platform IDs for a given map by scanning the directory.
     *
     * @param mapId The map ID
     * @return List of platform IDs (e.g., ["m1", "m2", "m3"])
     */
    public static List<String> getAvailablePlatformIds(int mapId) {
        return PluginResources.listBasenames(
                "ArtificialPlayer/BotMovementSystem/movementDataPackets/map" + mapId, ".csv");
    }

    public static List<String> getMainPlatformIds(int mapId) {
        return getAvailablePlatformIds(mapId).stream()
                .filter(id -> id.startsWith("m"))
                .collect(Collectors.toList());
    }

    public static List<String> getConnectorPlatformIds(int mapId) {
        return getAvailablePlatformIds(mapId).stream()
                .filter(id -> id.startsWith("c"))
                .collect(Collectors.toList());
    }

    /**
     * Gets all characters standing on a specific platform.
     * <p>
     * Cross-references all characters on the map against the platform's coordinate bounds.
     * Since platform coordinates are guide points (not every possible position), this method
     * uses interpolation and tolerance values to determine if a character is on the platform.
     *
     * @param mapId      The map ID
     * @param platformId The platform identifier (e.g., "m1")
     * @return List of characters currently on the specified platform
     */
    public static List<Character> getAllCharsOnPlatform(int mapId, String platformId) {
        List<Character> allCharsOnMap = getAllCharsOnMap(mapId);
        List<Character> charsOnPlatform = new ArrayList<>();

        Platform platform = PlatformParser.parsePlatform(mapId, platformId);

        if (platform == null || platform.getSortedPoints().isEmpty()) {
            return charsOnPlatform;
        }

        for (Character chr : allCharsOnMap) {
            if (isCharacterOnPlatform(chr, platform)) {
                charsOnPlatform.add(chr);
            }
        }
        //debugprint("CharsOnPlatform: ", charsOnPlatform, charsOnPlatform.size());
        return charsOnPlatform;
    }

    /**
     * Checks if a character is standing on a specific platform.
     *
     * @param chr      The character to check
     * @param platform The platform to check against
     * @return true if the character is on the platform, false otherwise
     */
    public static boolean isCharacterOnPlatform(Character chr, Platform platform) {
        Point position = chr.getPosition();
        return isPositionOnPlatform(position, platform);
    }

    /**
     * Checks if a position is on a specific platform.
     * <p>
     * For FLAT platforms: checks if X is within bounds and Y matches baseY (within tolerance).
     * For SLOPED platforms: checks if X is within bounds and Y matches the interpolated Y at that X.
     *
     * @param position The position to check
     * @param platform The platform to check against
     * @return true if the position is on the platform
     */
    public static boolean isPositionOnPlatform(Point position, Platform platform) {
        int x = position.x;
        int y = position.y;

        // Check X bounds with tolerance
        if (x < platform.getMinX() - X_TOLERANCE || x > platform.getMaxX() + X_TOLERANCE) {
            return false;
        }

        // Get expected Y at this X position (handles both flat and sloped)
        int expectedY = platform.getYAtX(x);

        // Check if actual Y is close enough to expected Y
        return Math.abs(y - expectedY) <= Y_TOLERANCE;
    }

    public static List<Character> getAllCharsOnMap(int mapId) {
        MapleMap map = getMapleMapById(mapId);
        return map.getAllPlayers();
    }

    public static List<Point> getCoordinatesOfAllCharsOnMap(int mapId) {
        return getListOfCharacterCoordinates(getAllCharsOnMap(mapId));
    }

    public static List<Point> getListOfCharacterCoordinates(List<Character> chars) {
        List<Point> characterCoords = new ArrayList<>();
        for (Character chr : chars) {
            characterCoords.add(chr.getPosition());
        }
        return characterCoords;
    }
}
