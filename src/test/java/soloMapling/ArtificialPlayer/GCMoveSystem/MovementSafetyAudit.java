package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.server.maps.Foothold;
import org.gms.server.maps.MapleMap;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Optional full-WZ audit. Replays the movement methods but does not let a watchdog mask a failure. */
class MovementSafetyAudit {
    private static final BotMovementProfile BASE = BotMovementProfile.base();
    private static final BotMovementProfile HASTE = new BotMovementProfile(140, 123);

    private enum Issue { SETTLE_LOOP, SETTLE_FALL, JUMP_UNSAFE, WALK_OFF_STALL, WALK_OFF_WRONG_LANDING,
        WALK_OFF_VOID, WALK_OFF_DEAD_LANDING, DOWN_JUMP_UNSAFE, UNSTUCK_UNSAFE, KNOCKBACK_UNSAFE, REPEATED_KNOCKBACK_UNSAFE, DEAD_RESCUE, PREMATURE_FALL_RECOVERY, AIR_BOUNDARY, WALL_TUNNEL, MAP_ERROR }

    private static final class Result {
        final EnumMap<Issue, Integer> failures = new EnumMap<>(Issue.class);
        final List<String> examples = new ArrayList<>();
        int settles, jumps, refusedJumps, drops, refusedDrops, downJumps, lips, hops, knockbacks, repeatedKnockbacks, rescues, deepFloors, boundaries, walls;

        void fail(Issue issue, int map, String detail) {
            if (failures.merge(issue, 1, Integer::sum) <= 2) examples.add(issue + " map=" + map + " " + detail);
        }
    }

    @Test
    void historicalMovementSafety() throws Exception {
        PqParkDoorExecutionSimTest.stubHostEnvironment();
        var maps = JumpWaypointAudit.mapIds();
        assertFalse(maps.isEmpty(), "No host WZ maps");
        var previousCache = BotNavigationGraphProvider.cacheDir();
        BotNavigationGraphProvider.setCacheDir(java.nio.file.Path.of("target", "jump-waypoint-audit")
                .resolve(previousCache.getFileName()));
        List<Result> results;
        AtomicInteger done = new AtomicInteger();
        try {
            results = maps.parallelStream().map(id -> {
                Result result = new Result();
                try {
                    scan(id, result);
                } catch (RuntimeException error) {
                    result.fail(Issue.MAP_ERROR, id, error.toString());
                }
                int count = done.incrementAndGet();
                if (count % 500 == 0) System.out.printf("Safety scanned %d/%d maps%n", count, maps.size());
                return result;
            }).toList();
        } finally {
            BotNavigationGraphProvider.setCacheDir(previousCache);
            MovementClock.reset();
        }
        var failures = new EnumMap<Issue, Integer>(Issue.class);
        var affected = new EnumMap<Issue, Integer>(Issue.class);
        long settles = 0, jumps = 0, refusedJumps = 0, drops = 0, refusedDrops = 0, downJumps = 0, lips = 0, hops = 0, knockbacks = 0, repeatedKnockbacks = 0, rescues = 0, deepFloors = 0, boundaries = 0, walls = 0;
        for (Result r : results) {
            settles += r.settles; jumps += r.jumps; refusedJumps += r.refusedJumps;
            drops += r.drops; refusedDrops += r.refusedDrops; downJumps += r.downJumps; lips += r.lips;
            hops += r.hops; knockbacks += r.knockbacks; repeatedKnockbacks += r.repeatedKnockbacks; rescues += r.rescues; deepFloors += r.deepFloors; boundaries += r.boundaries; walls += r.walls;
            r.failures.forEach((issue, count) -> {
                failures.merge(issue, count, Integer::sum);
                affected.merge(issue, 1, Integer::sum);
            });
            r.examples.forEach(System.out::println);
        }
        System.out.printf("SAFETY maps=%d settle=%d jump=%d unsafeJumpRefused=%d directionalDrop=%d unsafeDropRefused=%d downJump=%d lip=%d unstuck=%d knockback=%d repeatedKnockback=%d deadRescue=%d deepFloor=%d airBoundary=%d anchoredWall=%d%n",
                maps.size(), settles, jumps, refusedJumps, drops, refusedDrops, downJumps, lips, hops, knockbacks, repeatedKnockbacks, rescues, deepFloors, boundaries, walls);
        System.out.println("SAFETY failures=" + failures + " mapsByFailure=" + affected);
        assertTrue(failures.isEmpty(), "Movement failures: " + failures);
    }

    private static void scan(int id, Result result) {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(id);
        if (map.isSwim() || map.getFootholds().getAllFootholds().isEmpty()) return;
        var bounds = BotPhysicsEngine.standableXBounds(map);
        if (bounds == null) return;
        var graph = BotNavigationGraphProvider.getGraph(map, BASE);
        var bot = new CharacterDouble(1, map, null, null);
        int step = BotPhysicsEngine.walkStep(map, BASE);
        var vr = map.getMapArea();
        int cameraLimit = vr == null || vr.height <= 0 ? Integer.MAX_VALUE
                : vr.y + vr.height + GCMovementDriver.FALL_RECOVER_SLACK_PX;
        var dead = new ArrayList<Point>();
        Point upperLive = null;
        for (var region : graph.regions) {
            if (region.isRopeRegion) continue;
            for (var edge : graph.getOutgoing(region.id)) {
                if (edge.type == BotNavigationGraph.EdgeType.JUMP) {
                    if (BotNavigationManager.isEdgeUsable(graph, map, edge, BotMovementCapabilities.NONE)) {
                        checkJump(bot, graph, edge, result);
                    } else {
                        result.refusedJumps++;
                    }
                } else if (edge.type == BotNavigationGraph.EdgeType.DROP) {
                    if (!BotNavigationManager.isEdgeUsable(graph, map, edge, BotMovementCapabilities.NONE)) {
                        result.refusedDrops++;
                    } else if (edge.launchStepX != 0) {
                        result.drops++;
                        checkDrop(bot, graph, edge, result);
                    }
                }
            }
            for (var tread : region.segments) {
                int lo = Math.max(bounds.x, tread.minX), hi = Math.min(bounds.y, tread.maxX);
                if (lo > hi) continue;
                Point middle = tread.pointAt(lo + (hi - lo) / 2);
                Foothold under = BotPhysicsEngine.findGroundFoothold(map, middle);
                if (under == null || under.getId() != tread.footholdId) continue;
                if (DeadPitGuard.isLivableSurface(map, under, BASE)) {
                    if (upperLive == null || middle.y < upperLive.y) upperLive = middle;
                    for (int dir : new int[]{-1, 1}) {
                        int x = Math.clamp(middle.x + dir * 4 * step, lo, hi);
                        if (Math.abs(x - middle.x) > 8) {
                            result.settles++;
                            checkSettle(bot, graph, region.id, tread.pointAt(x), middle, result);
                        }
                    }
                } else {
                    dead.add(middle);
                }
                checkDownJump(bot, middle, result);
                if (middle.y > cameraLimit + 1L) checkDeepFloor(bot, middle, cameraLimit, result);
                if (lo == bounds.x) checkBoundary(map, tread.pointAt(lo), -1, bounds, result);
                if (hi == bounds.y) checkBoundary(map, tread.pointAt(hi), 1, bounds, result);
            }
            checkLip(bot, region.leftPoint(), -1, bounds, result);
            checkLip(bot, region.rightPoint(), 1, bounds, result);
        }
        for (Foothold wall : BotPhysicsEngine.collidableWalls(map)) checkWall(map, wall, bounds, result);
        if (upperLive == null) return;
        for (Point floor : dead) {
            if (floor.y <= upperLive.y) continue;
            var st = spawn(bot, floor, BASE);
            st.moveTarget = upperLive;
            result.rescues++;
            BotMovementManager.selfHealDeadPit(st);
            Point got = bot.getPosition();
            if (got.x < bounds.x || got.x > bounds.y || !DeadPitGuard.isLivableLanding(map, got, BASE)) {
                result.fail(Issue.DEAD_RESCUE, id, floor + " target=" + upperLive + " result=" + got + " bounds=" + bounds);
            }
        }
    }

    private static BotMovementState spawn(CharacterDouble bot, Point p, BotMovementProfile profile) {
        var st = new BotMovementState(bot, null);
        st.movementProfile = profile;
        st.lastMap = bot.getMap();
        BotPhysicsEngine.teleportTo(st, bot, p);
        return st;
    }

    private static void checkJump(CharacterDouble bot, BotNavigationGraph graph, BotNavigationGraph.Edge edge, Result result) {
        MapleMap map = bot.getMap();
        if (graph.findRegionId(map, edge.startPoint) != edge.fromRegionId
                || !BotNavigationManager.canExecuteJumpFromCurrentPosition(graph, map, edge.startPoint, edge)) return;
        result.jumps++;
        var state = spawn(bot, edge.startPoint, BASE);
        BotMovementManager.initiateFixedArcJump(state, bot, edge.launchStepX);
        Point landed = finishFlight(state, edge.endPoint);
        if (landed == null || BotNavigationManager.resolveCurrentRegionId(graph, state, map, landed) != edge.toRegionId
                || !DeadPitGuard.isLivableSurface(map, state.groundContact, BASE)) {
            result.fail(Issue.JUMP_UNSAFE, map.getId(), edgeDetails(edge, landed));
        }
    }

    private static void checkDrop(CharacterDouble bot, BotNavigationGraph graph, BotNavigationGraph.Edge edge, Result result) {
        var st = spawn(bot, edge.startPoint, BASE);
        st.navGraph = graph;
        st.navEdge = edge;
        st.navPreciseTarget = true;
        MapleMap map = bot.getMap();
        Point feet = bot.getPosition();
        boolean leftGround = false;
        int still = 0;
        for (int t = 0; t < 256; t++) {
            if (st.inAir) BotMovementManager.tickAirborne(st, edge.endPoint);
            else BotMovementManager.tickGrounded(st, BotNavigationManager.selectDropWaypoint(st, graph, feet, edge));
            Point next = bot.getPosition();
            if (!st.inAir) {
                int region = BotNavigationManager.resolveCurrentRegionId(graph, st, map, next);
                if (leftGround || region != edge.fromRegionId) {
                    if (!DeadPitGuard.isLivableSurface(map, BotPhysicsEngine.standingFoothold(st, next), BASE)) {
                        result.fail(Issue.WALK_OFF_DEAD_LANDING, map.getId(), edgeDetails(edge, next));
                    } else if (region != edge.toRegionId) {
                        result.fail(Issue.WALK_OFF_WRONG_LANDING, map.getId(), edgeDetails(edge, next));
                    }
                    return;
                }
                still = next.equals(feet) ? still + 1 : 0;
                if (still == 14) break;
            } else {
                leftGround = true;
            }
            feet = next;
        }
        result.fail(leftGround ? Issue.WALK_OFF_VOID : Issue.WALK_OFF_STALL, map.getId(), edgeDetails(edge, feet));
    }

    private static String edgeDetails(BotNavigationGraph.Edge edge, Point actual) {
        return "R" + edge.fromRegionId + ">" + edge.toRegionId + " runway=" + edge.startPoint
                + " expected=" + edge.endPoint + " actual=" + actual + " dx=" + edge.launchStepX;
    }

    private static void checkLip(CharacterDouble bot, Point lip, int dir, Point bounds, Result result) {
        if (lip.x < bounds.x || lip.x > bounds.y) return;
        MapleMap map = bot.getMap();
        Foothold standing = BotPhysicsEngine.findGroundFoothold(map, lip);
        if (standing == null) return;
        if (DeadPitGuard.isLivableSurface(map, standing, BASE)) checkKnockback(bot, lip, dir, result);
        for (BotMovementProfile profile : new BotMovementProfile[]{BASE, HASTE}) {
            var st = spawn(bot, lip, profile);
            st.moveDir = dir;
            st.hspeed = dir * profile.walkVelocityPxs() * 0.008;
            var held = BotPhysicsEngine.simulateLiveGroundMotion(st, lip, standing, dir);
            if (held.lostGround()) {
                result.lips++;
                var landing = BotPhysicsEngine.simulateFallLanding(map, held.point(), held.stepX());
                BotPhysicsEngine.applyGroundMotion(st, bot, standing);
                if (st.inAir) {
                    Point got = finishFlight(st, lip);
                    if (got == null || landing == null || !got.equals(landing.point())) {
                        result.fail(Issue.WALK_OFF_VOID, map.getId(), "fh=" + standing.getId() + " lip=" + lip
                                + " dx=" + held.stepX() + " profile=" + profile + " actual=" + got);
                    } else if (!DeadPitGuard.isLivableSurface(map, st.groundContact, profile)) {
                        result.fail(Issue.WALK_OFF_DEAD_LANDING, map.getId(), "lip=" + lip + " landed at " + got);
                    }
                }
            }
            int walk = BotPhysicsEngine.walkStep(map, profile);
            var left = BotPhysicsEngine.simulateJumpLanding(map, lip, -walk, profile);
            var right = BotPhysicsEngine.simulateJumpLanding(map, lip, walk, profile);
            if (left != null && right != null
                    && DeadPitGuard.isLivableLanding(map, left.point(), profile)
                    && DeadPitGuard.isLivableLanding(map, right.point(), profile)) continue;
            result.hops++;
            st = spawn(bot, lip, profile);
            BotMovementManager.tickUnstuck(st);
            if (st.inAir) {
                var landing = st.airVelX > 0 ? right : left;
                int airVel = st.airVelX;
                Point got = finishFlight(st, lip);
                if (landing == null || !landing.point().equals(got)
                        || !DeadPitGuard.isLivableSurface(map, st.groundContact, profile)) {
                    result.fail(Issue.UNSTUCK_UNSAFE, map.getId(), "fh=" + standing.getId() + " start=" + lip
                            + " airVel=" + airVel + " profile=" + profile + " actual=" + got);
                }
            }
        }
    }

    private static void checkKnockback(CharacterDouble bot, Point lip, int dir, Result result) {
        var state = spawn(bot, lip, BASE);
        BotContactDamage.applyKnockbackMotion(state, dir * 9); // standard contact impulse at 50 ms/tick
        result.knockbacks++;
        if (!state.inAir) return;
        if (!knockbackEndsSafely(state, lip)) {
            result.fail(Issue.KNOCKBACK_UNSAFE, bot.getMapId(), "start=" + lip + " dir=" + dir + " actual=" + bot.getPosition());
        }

        // Replay separately so the second impulse cannot hide a bad single-hit landing.
        state = spawn(bot, lip, BASE);
        BotContactDamage.applyKnockbackMotion(state, dir * 9);
        int delay = dir < 0 ? 2 : 6; // cover ascent and descent, without a wall-clock wait
        for (int i = 0; i < delay && state.inAir; i++) BotMovementManager.tickAirborne(state, lip);
        if (!state.inAir) return;
        result.repeatedKnockbacks++;
        BotContactDamage.applyKnockbackMotion(state, -dir * 9);
        if (!knockbackEndsSafely(state, lip)) {
            result.fail(Issue.REPEATED_KNOCKBACK_UNSAFE, bot.getMapId(),
                    "start=" + lip + " firstDir=" + dir + " delay=" + delay + " actual=" + bot.getPosition());
        }
    }

    private static boolean knockbackEndsSafely(BotMovementState state, Point goal) {
        return finishFlight(state, goal) != null
                && (state.climbing || DeadPitGuard.isLivableSurface(state.bot.getMap(), state.groundContact, BASE));
    }

    private static void checkDeepFloor(CharacterDouble bot, Point floor, int cameraLimit, Result result) {
        Point start = new Point(floor.x, Math.max(cameraLimit + 1, floor.y - 20));
        var landing = BotPhysicsEngine.simulateFallLanding(bot.getMap(), start, 0);
        if (landing == null || !floor.equals(landing.point())) return;
        result.deepFloors++;
        var state = spawn(bot, start, BASE);
        BotPhysicsEngine.beginPortalDrop(state, bot, start);
        state.moveTarget = floor;
        for (int t = 0; t < BotPhysicsEngine.FALL_SIM_TICK_CAP && state.inAir; t++) {
            BotMovementManager.tickAirborne(state, start);
            GCMovementDriver.tickFallOffMapRecovery(state);
            if (state.moveTarget == null) {
                result.fail(Issue.PREMATURE_FALL_RECOVERY, bot.getMapId(), "start=" + start + " floor=" + floor);
                return;
            }
        }
        if (state.inAir || !floor.equals(bot.getPosition())) {
            result.fail(Issue.PREMATURE_FALL_RECOVERY, bot.getMapId(), "expected=" + floor + " got=" + bot.getPosition());
        }
    }

    private static void checkWall(MapleMap map, Foothold wall, Point bounds, Result result) {
        int x = wall.getX1();
        int dir = Integer.compare(wall.getY1(), wall.getY2());
        if (x < bounds.x || x > bounds.y || x + 2 * dir < bounds.x || x + 2 * dir > bounds.y) return;
        int y = (wall.getY1() + wall.getY2()) / 2;
        if (y == wall.getY1() || y == wall.getY2()) return; // an endpoint can be a legal departure
        result.walls++;
        var collision = BotPhysicsEngine.resolveAirCollision(map, new Point(x, y), new Point(x + 2 * dir, y));
        if (collision.type() != BotPhysicsEngine.AirCollisionType.WALL || (collision.point().x - x) * dir > 0) {
            result.fail(Issue.WALL_TUNNEL, map.getId(), "fh=" + wall.getId() + " x=" + x + " y=" + y + " collision=" + collision);
        }
    }

    private static void checkDownJump(CharacterDouble bot, Point start, Result result) {
        var expected = BotPhysicsEngine.simulateDownJumpLanding(bot.getMap(), start);
        var state = spawn(bot, start, BASE);
        BotPhysicsEngine.beginDownJump(state, bot);
        result.downJumps++;
        if (!state.inAir) return;
        Point landed = finishFlight(state, start);
        if (expected == null || !expected.point().equals(landed)
                || !DeadPitGuard.isLivableSurface(bot.getMap(), state.groundContact, BASE)) {
            result.fail(Issue.DOWN_JUMP_UNSAFE, bot.getMapId(), "start=" + start + " landed=" + landed);
        }
    }

    private static Point finishFlight(BotMovementState state, Point goal) {
        for (int i = 0; i < BotPhysicsEngine.FALL_SIM_TICK_CAP && state.inAir; i++) BotMovementManager.tickAirborne(state, goal);
        return state.inAir ? null : state.bot.getPosition();
    }

    private static void checkBoundary(MapleMap map, Point boundary, int dir, Point bounds, Result result) {
        for (int dx : new int[]{0, dir * 4, -dir * 4}) {
            int outside = boundary.x + dir * 7;
            Point from = new Point(outside, boundary.y - 3);
            Point to = new Point(outside + dx, boundary.y + 3);
            var collision = BotPhysicsEngine.resolveAirCollision(map, from, to);
            result.boundaries++;
            if (collision.type() != BotPhysicsEngine.AirCollisionType.LAND
                    || collision.point().x < bounds.x || collision.point().x > bounds.y) {
                result.fail(Issue.AIR_BOUNDARY, map.getId(), from + " > " + to + " floor=" + boundary + " got=" + collision);
            }
        }
    }

    private static void checkSettle(CharacterDouble bot, BotNavigationGraph graph, int region, Point start,
                                    Point goal, Result result) {
        MapleMap map = bot.getMap();
        if (graph.findRegionId(map, start) != region || graph.findRegionId(map, goal) != region
                || BotPhysicsEngine.isGroundRunwayBlockedByWall(map, start, goal)) return;
        var st = spawn(bot, start, BASE);
        st.moveTarget = goal;
        st.moveTargetMap = map;
        st.moveTargetPrecise = true;
        st.navPreciseTarget = true;
        int lastDir = 0, reversals = 0;
        for (int i = 0; i < 65; i++) {
            BotMovementManager.tickGrounded(st, goal);
            if (st.inAir) {
                result.fail(Issue.SETTLE_FALL, map.getId(), "R" + region + " " + start + ">" + goal + " got=" + bot.getPosition());
                return;
            }
            GCMovementDriver.clearReachedMoveTarget(st);
            if (st.moveTarget == null) return;
            if (st.moveDir != 0) {
                if (st.moveDir == -lastDir) reversals++;
                lastDir = st.moveDir;
            }
        }
        if (reversals >= 3) result.fail(Issue.SETTLE_LOOP, map.getId(),
                "R" + region + " " + start + ">" + goal + " reversals=" + reversals + " got=" + bot.getPosition());
    }
}
