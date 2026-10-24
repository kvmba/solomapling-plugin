package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.client.Character;
import org.gms.server.maps.Foothold;
import org.gms.server.maps.FootholdTree;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Rope;

import java.awt.*;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Ported from GreenCatMS. Credit: NutNNut.
final class BotFallbackMovementManager {
    private BotFallbackMovementManager() {
    }

    // Bounded launch scan on the current floor; wide PQ rooms need the far-side ladder too.
    private static final int ROPE_LAUNCH_SCAN_RADIUS_PX = 400;
    private static final int ROPE_LAUNCH_SCAN_STEP_PX = 6;

    // One validated shelf/rope approach survives patrol movement and physics ticks.
    record UpwardApproach(MapleMap map, BotMovementProfile profile, Point target, int fromX,
                          Set<Integer> sourceFootholds, Point launch, int stepX,
                          Rope rope, int landingFootholdId) {
    }

    record WalkSurface(MapleMap map, Set<Integer> footholds) {
    }

    static Point resolveSteeringTarget(BotMovementState entry, Point botPos, Point targetPos) {
        UpwardApproach approach = upwardApproach(entry, botPos, targetPos);
        if (approach != null) {
            entry.navPreciseTarget = approach.launch() != null;
            // No local connection was found, not proof of global unreachability. Wait for the
            // graph instead of pretending the upstairs target's x is a ground waypoint.
            return approach.launch();
        }
        Rope rope = selectNearbyRope(entry, botPos, targetPos);
        Point ledgeTarget = resolveFallbackLedgeTarget(entry, botPos, targetPos, rope);
        if (ledgeTarget != null) {
            return ledgeTarget;
        }
        if (rope != null) {
            return new Point(rope.x(), botPos.y);
        }
        return entry.bot.getMap().isSwim() || onCurrentSurface(entry, targetPos) ? targetPos : null;
    }

    static boolean tryImmediateAction(BotMovementState entry, Point botPos, Point targetPos) {
        Character bot = entry.bot;
        MapleMap map = bot.getMap();
        UpwardApproach approach = upwardApproach(entry, botPos, targetPos);
        Rope rope = approach != null ? approach.rope() : selectNearbyRope(entry, botPos, targetPos);
        if (rope != null) {
            if (canDirectlyAttachToRope(botPos, rope)) {
                int attachY = Math.max(rope.topY(), Math.min(botPos.y, rope.bottomY()));
                BotPhysicsEngine.attachToRope(entry, bot, rope, attachY);
                BotMovementManager.broadcastMovement(entry);
                return true;
            }

            // Jump-grab whenever the PHYSICS predictor says the arc reaches the rope column —
            // the same simulation the launched jump follows, so its verdict is exact. The old
            // crude 2*walkStep range gate rejected arcs the physics proves (a 17px stand-off
            // from an overhung ladder), which left the bot steering instead of jumping.
            int ropeDx = approach != null && approach.rope() == rope
                    ? approach.stepX() : Integer.signum(rope.x() - botPos.x)
                            * BotPhysicsEngine.walkStep(map, entry.movementProfile);
            if (BotPhysicsEngine.simulateGroundJumpRopeGrab(map, botPos, ropeDx, rope,
                    entry.movementProfile) != null) {
                BotMovementManager.initiateRopeJump(entry, bot, ropeDx);
                entry.fixedAirArc = true;
                return true;
            }
        }

        if (approach != null && approach.launch() != null && approach.rope() == null) {
            BotPhysicsEngine.PostLandingJump landing = BotPhysicsEngine.simulateLandingStop(map,
                    BotPhysicsEngine.simulateJumpLanding(map, botPos, approach.stepX(), entry.movementProfile),
                    entry.movementProfile);
            if (landing != null && !landing.lostGround()
                    && landing.finalFoothold().getId() == approach.landingFootholdId()) {
                if (approach.stepX() == 0 && BotPhysicsEngine.slipperyGround(map)
                        && BotPhysicsEngine.carriedAirVelX(map, entry) != 0) {
                    entry.launchReadyAwaiting = true;
                    entry.moveDir = 0;
                    BotPhysicsEngine.applyGroundMotion(entry, bot, BotPhysicsEngine.standingFoothold(entry));
                    BotMovementManager.broadcastMovement(entry);
                    return true; // shed the slide before flying the validated vertical arc
                }
                BotMovementManager.initiateJump(entry, bot, approach.stepX());
                entry.fixedAirArc = true;
                return true;
            }
        }
        if (approach != null && approach.launch() == null) {
            return false;
        }

        // In swim maps, leap upward off the platform to chase an airborne
        // target above (e.g. owner swimming overhead). Once airborne, swim
        // physics owns motion and steers horizontally toward the target.
        // Without this, bot stays grounded forever since walking on platforms
        // never closes vertical distance to a swimming target.
        if (shouldJumpUpIntoSwim(entry, botPos, targetPos)) {
            BotMovementManager.initiateJump(entry, bot, 0);
            return true;
        }

        if (shouldUseDownJump(entry, botPos, targetPos, rope)) {
            BotPhysicsEngine.queueDownJump(entry, bot);
            BotMovementManager.broadcastMovement(entry);
            return true;
        }

        Point steeringTarget = rope == null ? targetPos : new Point(rope.x(), targetPos.y);
        // A feasibility probe must not mutate walk hysteresis / braking for a different
        // target. The actual local waypoint drives those once, in planGroundAction.
        int stepX = Integer.signum(steeringTarget.x - botPos.x)
                * BotPhysicsEngine.walkStep(map, entry.movementProfile);
        if (stepX == 0 || BotPhysicsEngine.canWalkGroundStep(map, botPos, stepX)) {
            return false;
        }

        // Swim-map floor wall between the bot and a target on the far side: swim maps run on the
        // heuristic fallback (no A* graph) and this kind of map has no rope, so the only way across
        // an underwater wall is to leave the platform and swim over it. Without this the bot just
        // idles against the wall forever (planGroundAction clears/idles a wall-blocked step). Hop
        // into the water; the swim controller then rises above the wall top (computeSwimIntents'
        // wall-ahead branch) and crosses it.
        if (map != null && map.isSwim()
                && BotPhysicsEngine.swimWallTopAhead(map, botPos, steeringTarget) != Integer.MIN_VALUE) {
            BotMovementManager.initiateJump(entry, bot, Integer.signum(steeringTarget.x - botPos.x));
            return true;
        }

        if (shouldUseJump(entry, botPos, steeringTarget, stepX)) {
            BotMovementManager.initiateJump(entry, bot, steeringTarget.x - botPos.x);
            return true;
        }

        return false;
    }

    private static boolean shouldJumpUpIntoSwim(BotMovementState entry, Point botPos, Point targetPos) {
        if (entry == null || entry.bot == null || botPos == null || targetPos == null) {
            return false;
        }
        if (entry.inAir || entry.climbing || entry.jumpCooldownMs > 0 || entry.downJumpPending) {
            return false;
        }
        MapleMap map = entry.bot.getMap();
        if (map == null || !map.isSwim()) {
            return false;
        }
        // Target must be sufficiently above bot. dy < 0 = target higher in MS coords.
        int dy = targetPos.y - botPos.y;
        return dy < -Math.max(BotMovementManager.cfg.JUMP_Y_THRESH * 2, 60);
    }

    static boolean shouldWalkOffLedge(BotMovementState entry, Point botPos, Point targetPos, int stepX) {
        if (entry == null || !entry.graphWarmupFallback || botPos == null || targetPos == null || stepX == 0) {
            return false;
        }
        if (targetPos.y <= botPos.y + BotPhysicsEngine.cfg.MAX_SNAP_DROP) {
            return false;
        }
        Point ahead = new Point(botPos.x + stepX, botPos.y);
        MapleMap map = entry.bot.getMap();
        if (!BotPhysicsEngine.isGroundFarBelow(map, ahead)) {
            return false;
        }
        // Dead-pit guard: "ground far below" includes pit floors the bot can never leave.
        // Simulate the walk-off's real landing and refuse it if that surface has no way out
        // (no real one-move exit, no rope, no portal) — the fallback must not walk a bot into a
        // one-way basin while its graph is still baking.
        BotPhysicsEngine.JumpLanding landing =
                BotPhysicsEngine.simulateFallLanding(map, ahead, stepX);
        return landing != null
                && DeadPitGuard.isLivableLanding(map, landing.point(), entry.movementProfile,
                        BotMovementCapabilities.of(entry.bot));
    }

    private static UpwardApproach upwardApproach(BotMovementState entry, Point botPos, Point targetPos) {
        if (entry.bot.getMap().isSwim()
                || targetPos.y - botPos.y > BotNavigationGraphProvider.DOWN_JUMP_MAX_DROP_PX) {
            entry.fallbackApproach = null;
            return null;
        }
        MapleMap map = entry.bot.getMap();
        Foothold standing = BotPhysicsEngine.standingFoothold(entry);
        if (onCurrentSurface(entry, targetPos)) {
            entry.fallbackApproach = null;
            return null; // walking uphill on one connected surface needs no ascent action
        }
        boolean targetAbove = targetPos.y < botPos.y - BotMovementManager.cfg.JUMP_Y_THRESH;
        UpwardApproach cached = entry.fallbackApproach;
        if (cached != null && cached.map() == map && cached.profile().equals(entry.movementProfile)
                && cached.target().y == targetPos.y
                && Math.abs(cached.target().x - targetPos.x) <= ROPE_LAUNCH_SCAN_RADIUS_PX / 2
                && standing != null
                && cached.sourceFootholds().contains(standing.getId())
                && Math.abs(cached.fromX() - botPos.x) <= ROPE_LAUNCH_SCAN_RADIUS_PX / 2) {
            return cached.launch() != null || targetAbove ? cached : null;
        }

        Set<Integer> source = sourceFootholds(entry, standing);
        List<Point> nearbyAnchors = grabLaunchAnchors(map, botPos, entry.movementProfile);
        List<Point> anchors = new ArrayList<>(nearbyAnchors);
        anchors.removeIf(anchor -> {
            Foothold foothold = BotPhysicsEngine.findGroundFoothold(map, anchor);
            return foothold == null || !source.contains(foothold.getId());
        });
        anchors.addFirst(new Point(botPos));
        anchors.sort((a, b) -> Integer.compare(Math.abs(a.x - botPos.x), Math.abs(b.x - botPos.x)));
        int step = BotPhysicsEngine.walkStep(map, entry.movementProfile);
        int ropeReach = BotPhysicsEngine.maxRopeGrabSimulationHorizontalTravel(map, entry.movementProfile);
        Point target = new Point(targetPos);
        // Prefer the tower's rope chain when available; every launch uses this bot's profile.
        if (targetAbove) {
            for (Point anchor : anchors) {
                for (Rope rope : map.getRopes()) {
                    if (rope.topY() >= anchor.y || Math.abs(rope.x() - anchor.x) > ropeReach) {
                        continue;
                    }
                    int stepX = Integer.signum(rope.x() - anchor.x) * step;
                    if (BotPhysicsEngine.simulateGroundJumpRopeGrab(map, anchor, stepX, rope,
                            entry.movementProfile) != null) {
                        return entry.fallbackApproach = new UpwardApproach(map, entry.movementProfile,
                                target, botPos.x, source, anchor, stepX, rope, -1);
                    }
                }
            }
        }
        Set<Integer> targetSurface = walkSurfaceFootholds(entry.fhIndex,
                BotPhysicsEngine.findGroundFoothold(map, targetPos));
        UpwardApproach ascent = null;
        UpwardApproach crossing = null;
        Map<Integer, Boolean> ropeExits = new HashMap<>();
        BotMovementCapabilities capabilities = BotMovementCapabilities.of(entry.bot);
        // Prefer reaching the goal's surface. An ascent may first cross to a slightly
        // LOWER shelf (larger y) if that shelf provides the next upward rope connection.
        int[] jumpSteps = {0, -step, step};
        for (Point anchor : anchors) {
            for (int stepX : jumpSteps) {
                BotPhysicsEngine.PostLandingJump jump = BotPhysicsEngine.simulateLandingStop(map,
                        BotPhysicsEngine.simulateJumpLanding(map, anchor, stepX, entry.movementProfile),
                        entry.movementProfile);
                if (jump == null || jump.lostGround() || source.contains(jump.finalFoothold().getId())
                        || jump.finalPoint().y - anchor.y > BotNavigationGraphProvider.DOWN_JUMP_MAX_DROP_PX
                        || !DeadPitGuard.isLivableLanding(map, jump.finalPoint(), entry.movementProfile,
                                capabilities)) {
                    continue;
                }
                UpwardApproach candidate = new UpwardApproach(map, entry.movementProfile,
                        target, botPos.x, source, anchor, stepX, null, jump.finalFoothold().getId());
                if (targetSurface.contains(jump.finalFoothold().getId())) {
                    return entry.fallbackApproach = candidate;
                }
                if (targetAbove && ascent == null
                        && jump.finalPoint().y < anchor.y - BotPhysicsEngine.cfg.MAX_SNAP_DROP
                        && Math.abs(jump.finalPoint().y - targetPos.y) < Math.abs(anchor.y - targetPos.y)) {
                    ascent = candidate;
                }
                if (targetAbove && crossing == null
                        && ropeExits.computeIfAbsent(jump.finalFoothold().getId(), ignored ->
                        hasUpwardRopeExit(entry, jump.finalFoothold(), nearbyAnchors, botPos.y, targetPos.y))) {
                    crossing = candidate;
                }
            }
        }
        if (ascent != null || crossing != null) {
            return entry.fallbackApproach = ascent != null ? ascent : crossing;
        }
        entry.fallbackApproach = new UpwardApproach(map, entry.movementProfile,
                target, botPos.x, source, null, 0, null, -1);
        // Cache unsuccessful shelf scans too, but do not suppress ordinary descent.
        return targetAbove ? entry.fallbackApproach : null;
    }

    private static boolean hasUpwardRopeExit(BotMovementState entry, Foothold landing,
                                            List<Point> anchors, int sourceY, int targetY) {
        MapleMap map = entry.bot.getMap();
        Set<Integer> surface = walkSurfaceFootholds(entry.fhIndex, landing);
        int step = BotPhysicsEngine.walkStep(map, entry.movementProfile);
        int reach = BotPhysicsEngine.maxRopeGrabSimulationHorizontalTravel(map, entry.movementProfile);
        for (Point anchor : anchors) {
            Foothold foothold = BotPhysicsEngine.findGroundFoothold(map, anchor);
            if (foothold == null || !surface.contains(foothold.getId())) {
                continue;
            }
            for (Rope rope : map.getRopes()) {
                if (rope.topY() >= sourceY - BotPhysicsEngine.cfg.MAX_SNAP_DROP
                        || Math.abs(rope.topY() - targetY) >= Math.abs(sourceY - targetY)
                        || Math.abs(rope.x() - anchor.x) > reach) {
                    continue;
                }
                int stepX = Integer.signum(rope.x() - anchor.x) * step;
                if (canDirectlyAttachToRope(anchor, rope)
                        || BotPhysicsEngine.simulateGroundJumpRopeGrab(map, anchor, stepX, rope,
                        entry.movementProfile) != null) {
                    return true;
                }
            }
        }
        return false;
    }

    /*
     * Standing points to probe for a rope-grab launch: the graph builder's rope-entry anchors
     * (edge/feature seeds the bake itself grows launch windows from), widened by a scan radius
     * so a wide floor always yields candidates. Y is the launch row the bot stands on.
     */
    private static List<Point> grabLaunchAnchors(MapleMap map, Point botPos,
                                                  BotMovementProfile profile) {
        Set<Point> out = new HashSet<>();
        FootholdTree tree = map.getFootholds();
        int walkStep = BotPhysicsEngine.walkStep(map, profile);
        // Builder-style anchors from every foothold within reach (endpoints + step grid): the same
        // seeds addRopeEntryEdges walks, so a window the graph would have baked is on this list.
        for (Foothold fh : tree.getAllFootholds()) {
            int minX = Math.min(fh.getX1(), fh.getX2());
            int maxX = Math.max(fh.getX1(), fh.getX2());
            if (maxX < botPos.x - ROPE_LAUNCH_SCAN_RADIUS_PX - walkStep
                    || minX > botPos.x + ROPE_LAUNCH_SCAN_RADIUS_PX + walkStep) {
                continue;
            }
            // Slope-aware standing Y: an endpoint value mid-slope probes a pixel off the real
            // surface (findGroundPoint then resolves it to a DIFFERENT platform or none), so the
            // anchor is never actually standable and the launch scan rejects it. pointBelowIndexed
            // reproduces the tree's own interpolation at the anchor's x.
            for (int x = minX; x <= maxX; x += Math.max(walkStep, ROPE_LAUNCH_SCAN_STEP_PX)) {
                out.add(slopeAwareAnchor(map, x, fh));
            }
            out.add(slopeAwareAnchor(map, maxX, fh));
        }
        return new ArrayList<>(out);
    }

    /** The real surface point at {@code x} on {@code fh} (endpoint value on flat footholds). */
    private static Point slopeAwareAnchor(MapleMap map, int x, Foothold fh) {
        Point surface = BotPhysicsEngine.pointBelowIndexed(map, new Point(x, fh.getY1()));
        return surface != null ? surface : new Point(x, fh.getY1());
    }

    private static boolean onCurrentSurface(BotMovementState entry, Point targetPos) {
        Foothold target = BotPhysicsEngine.findGroundFoothold(entry.bot.getMap(), targetPos);
        return target != null
                && Math.abs(BotPhysicsEngine.surfaceY(target, targetPos.x) - targetPos.y) <= BotPhysicsEngine.cfg.MAX_SLOPE_UP
                && sourceFootholds(entry, BotPhysicsEngine.standingFoothold(entry)).contains(target.getId());
    }

    private static Set<Integer> sourceFootholds(BotMovementState entry, Foothold standing) {
        WalkSurface cached = entry.fallbackSurface;
        if (cached != null && cached.map() == entry.bot.getMap() && standing != null
                && cached.footholds().contains(standing.getId())) {
            return cached.footholds();
        }
        Set<Integer> source = walkSurfaceFootholds(entry.fhIndex, standing);
        entry.fallbackSurface = new WalkSurface(entry.bot.getMap(), source);
        return source;
    }

    private static Set<Integer> walkSurfaceFootholds(Map<Integer, Foothold> byId, Foothold current) {
        Deque<Integer> queue = new ArrayDeque<>();
        Set<Integer> seen = new HashSet<>();
        if (current == null) {
            return seen;
        }
        queue.add(current.getId());
        seen.add(current.getId());
        while (!queue.isEmpty()) {
            Foothold fh = byId.get(queue.poll());
            for (int nextId : new int[]{fh.getNext(), fh.getPrev()}) {
                Foothold next = nextId > 0 ? byId.get(nextId) : null;
                if (next == null || next.isWall() || seen.contains(nextId)
                        || !BotPhysicsEngine.canWalkAcrossFootholds(fh, next)) {
                    continue;
                }
                boolean sameSurface = true;
                for (int id : seen) {
                    if (!BotPhysicsEngine.canShareWalkSurface(byId.get(id), next)) {
                        sameSurface = false;
                        break;
                    }
                }
                if (!sameSurface) {
                    continue;
                }
                seen.add(nextId);
                queue.add(next.getId());
            }
        }
        return seen;
    }

    private static Rope selectNearbyRope(BotMovementState entry, Point botPos, Point targetPos) {
        if (entry == null || entry.bot == null || botPos == null || targetPos == null) {
            return null;
        }

        int dy = targetPos.y - botPos.y;
        if (Math.abs(dy) < Math.max(BotMovementManager.cfg.JUMP_Y_THRESH * 2, 60)) {
            return null;
        }

        MapleMap map = entry.bot.getMap();
        int walkStep = BotPhysicsEngine.walkStep(map, entry.movementProfile);
        int searchX = Math.max(walkStep * 4, 90);
        Rope best = null;
        int bestScore = Integer.MAX_VALUE;
        for (Rope rope : map.getRopes()) {
            int dx = Math.abs(rope.x() - botPos.x);
            if (dx > searchX) {
                continue;
            }

            if (dy < 0) {
                if (rope.topY() >= botPos.y - BotPhysicsEngine.cfg.MAX_SNAP_DROP) {
                    continue;
                }
                // A rope whose climbable bottom hangs ABOVE the bot's floor (an overhung ladder)
                // still qualifies when a jump from the bot's own position can catch it — the
                // physics probe covers the hanging-bottom geometry the strict column test
                // rejects (LPQ stage 1's first ladder bottoms out 45px over the spawn floor,
                // which the old bottomY < botPos.y - MAX_SNAP_DROP veto turned into a permanent
                // "never climb" for every bot regardless of where it stood).
                if (rope.bottomY() < botPos.y - BotPhysicsEngine.cfg.MAX_SNAP_DROP
                        && !BotPhysicsEngine.canReachRopeFromGround(map, botPos, rope, entry.movementProfile)) {
                    continue;
                }
                if (rope.topY() > targetPos.y + BotMovementManager.cfg.FOLLOW_Y_CAP
                        && rope.topY() > botPos.y - Math.max(BotMovementManager.cfg.JUMP_Y_THRESH * 2, 60)) {
                    continue;
                }
            } else {
                if (rope.bottomY() <= botPos.y + BotPhysicsEngine.cfg.MAX_SNAP_DROP) {
                    continue;
                }
                if (rope.topY() > botPos.y + BotPhysicsEngine.cfg.MAX_SLOPE_UP) {
                    continue;
                }
                if (rope.bottomY() < targetPos.y - BotMovementManager.cfg.FOLLOW_Y_CAP) {
                    continue;
                }
            }

            int verticalPenalty = dy < 0
                    ? Math.max(0, rope.topY() - targetPos.y)
                    : Math.max(0, targetPos.y - rope.bottomY());
            int score = dx * 4 + verticalPenalty;
            if (score < bestScore) {
                best = rope;
                bestScore = score;
            }
        }
        return best;
    }

    private static boolean canDirectlyAttachToRope(Point botPos, Rope rope) {
        // Allow attach when bot is within rope's Y range, OR slightly above
        // rope.topY (within MAX_SNAP_DROP) so a player standing on a platform
        // whose surface meets the rope's head can transition into climbing
        // without first going airborne — same "press DOWN to grab from top"
        // motion as the real client. attachY snaps to rope.topY in the caller.
        return botPos != null
                && rope != null
                && Math.abs(botPos.x - rope.x()) <= BotPhysicsEngine.cfg.ROPE_GRAB_X
                && botPos.y >= rope.topY() - BotPhysicsEngine.cfg.MAX_SNAP_DROP
                && botPos.y <= rope.bottomY() + BotPhysicsEngine.cfg.MAX_SNAP_DROP;
    }

    private static Point resolveFallbackLedgeTarget(BotMovementState entry, Point botPos, Point targetPos, Rope rope) {
        if (entry == null || entry.bot == null || botPos == null || targetPos == null || rope != null) {
            return null;
        }
        MapleMap map = entry.bot.getMap();
        if (!shouldConsiderFallbackDrop(entry, map, botPos, targetPos)) {
            return null;
        }

        Foothold foothold = BotPhysicsEngine.findGroundFoothold(map, botPos);
        if (foothold == null) {
            return null;
        }

        Point left = walkOffTarget(map, foothold, entry.movementProfile, -1);
        Point right = walkOffTarget(map, foothold, entry.movementProfile, 1);
        // Same dead-pit gate the committed walk-off (shouldWalkOffLedge) enforces: the steering
        // half must not aim at a ledge the strike half would refuse — otherwise the bot walks to
        // the rim of a one-way basin and is handed over to a fall the decision guards rejected.
        left = livableWalkOff(map, entry, left);
        right = livableWalkOff(map, entry, right);
        Point best = chooseBetterLedgeTarget(botPos, targetPos, left, right);
        if (best == null) {
            return null;
        }
        return new Point(best.x, targetPos.y);
    }

    /* Swim-map down-jump eligibility, factored out of shouldUseDownJump: a swim drop enters
     * open water (the water floor clamps), so the land-map livability probe and the bounded-
     * drop rule never apply — pinned by the swim exception in DeadPitInvariantTest. */
    static boolean swimDownJumpAllowed(BotMovementState entry, Point botPos, Point targetPos) {
        MapleMap map = entry.bot.getMap();
        return BotPhysicsEngine.canStartDownJump(map, botPos)
                && Math.abs(targetPos.x - botPos.x) <= Math.max(BotMovementManager.cfg.FOLLOW_DIST,
                BotPhysicsEngine.walkStep(map, entry.movementProfile) * 4);
    }

    private static boolean shouldUseDownJump(BotMovementState entry, Point botPos, Point targetPos, Rope rope) {
        if (entry == null || botPos == null || targetPos == null || rope != null) {
            return false;
        }
        // Ours: same humanlike pause between down-jumps as the graph DROP executor — the warmup
        // fallback must not become the fast path that dodges the cadence gate.
        if (BotPhysicsEngine.downJumpOnCadenceCooldown(entry)) {
            return false;
        }
        MapleMap map = entry.bot.getMap();
        if (!shouldConsiderFallbackDrop(entry, map, botPos, targetPos)) {
            return false;
        }
        // Ground maps need the full landing sim (bounded-drop rule included); in swim maps
        // the bot drops into open water — no landing foothold exists or is required.
        if (map != null && map.isSwim()) {
            return swimDownJumpAllowed(entry, botPos, targetPos);
        }
        BotPhysicsEngine.JumpLanding landing = BotPhysicsEngine.simulateDownJumpLanding(map, botPos);
        if (landing == null) {
            return false;
        }
        // Bounded-drop rule, mirroring the graph builder's DOWN_JUMP_MAX_DROP_PX cap
        // (validateDownJumpLaunchX): a straight down-jump deeper than the cap lands in a
        // basin the bot can never jump back out of (base-stat jump apex is 77px) - the
        // warmup fallback must not dive into LPQ-tower-style dead pits either.
        if (landing.point().y - botPos.y > BotNavigationGraphProvider.DOWN_JUMP_MAX_DROP_PX) {
            return false;
        }
        // Dead-pit guard: the cap alone trusts DOWN_JUMP_MAX_DROP_PX to catch every
        // unescapable basin. It doesn't - a 150px drop can land on a surface whose only
        // exits are jumps the WZ flags (or their absence) allow on paper but no path
        // actually serves. Refuse any landing the livability probe cannot clear.
        if (!DeadPitGuard.isLivableLanding(map, landing.point(), entry.movementProfile,
                BotMovementCapabilities.of(entry.bot))) {
            return false;
        }
        return Math.abs(targetPos.x - botPos.x) <= Math.max(BotMovementManager.cfg.FOLLOW_DIST,
                BotPhysicsEngine.walkStep(map, entry.movementProfile) * 4);
    }

    private static boolean shouldConsiderFallbackDrop(BotMovementState entry, MapleMap map, Point botPos, Point targetPos) {
        if (entry == null || map == null || botPos == null || targetPos == null) {
            return false;
        }
        int dy = targetPos.y - botPos.y;
        if (dy < Math.max(BotPhysicsEngine.cfg.MAX_SNAP_DROP * 3, 90)) {
            return false;
        }

        Foothold currentFoothold = BotPhysicsEngine.findGroundFoothold(map, botPos);
        Foothold targetFoothold = BotPhysicsEngine.findGroundFoothold(map, targetPos);
        return currentFoothold == null
                || targetFoothold == null
                || currentFoothold.getId() != targetFoothold.getId();
    }

    private static Point walkOffTarget(MapleMap map, Foothold foothold, BotMovementProfile profile, int direction) {
        if (map == null || foothold == null || direction == 0) {
            return null;
        }
        Point endpoint = direction < 0
                ? new Point(foothold.getX1(), foothold.getY1())
                : new Point(foothold.getX2(), foothold.getY2());
        int step = direction * Math.max(1, BotPhysicsEngine.walkStep(map, profile));
        Point ahead = new Point(endpoint.x + step, endpoint.y);
        return BotPhysicsEngine.isGroundFarBelow(map, ahead) ? ahead : null;
    }

    /*
     * The steering twin of shouldWalkOffLedge's landing check: simulate the fall from the
     * walk-off point and keep the target only when its landing surface is livable. Null drops
     * the candidate. Swings the same probe the decision side runs, so steering and execution can
     * never disagree about a dead basin.
     */
    private static Point livableWalkOff(MapleMap map, BotMovementState entry, Point target) {
        if (target == null || map == null || map.isSwim()) {
            return target;
        }
        BotPhysicsEngine.JumpLanding landing =
                BotPhysicsEngine.simulateFallLanding(map, target, 0);
        return landing != null
                && DeadPitGuard.isLivableLanding(map, landing.point(), entry.movementProfile,
                BotMovementCapabilities.of(entry.bot))
                ? target
                : null;
    }

    private static Point chooseBetterLedgeTarget(Point botPos, Point targetPos, Point left, Point right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }

        int desiredDirection = Integer.compare(targetPos.x, botPos.x);
        if (desiredDirection < 0 && left.x <= botPos.x) {
            return left;
        }
        if (desiredDirection > 0 && right.x >= botPos.x) {
            return right;
        }

        int leftScore = Math.abs(targetPos.x - left.x) + Math.abs(botPos.x - left.x);
        int rightScore = Math.abs(targetPos.x - right.x) + Math.abs(botPos.x - right.x);
        return leftScore <= rightScore ? left : right;
    }

    private static boolean shouldUseJump(BotMovementState entry, Point botPos, Point steeringTarget, int stepX) {
        if (entry == null || botPos == null || steeringTarget == null || stepX == 0) {
            return false;
        }
        if (shouldWalkOffLedge(entry, botPos, steeringTarget, stepX)) {
            return false;
        }

        MapleMap map = entry.bot.getMap();
        int direction = Integer.signum(stepX);
        int jumpStep = direction * BotPhysicsEngine.walkStep(map, entry.movementProfile);
        BotPhysicsEngine.JumpLanding landing =
                BotPhysicsEngine.simulateJumpLanding(map, botPos, jumpStep, entry.movementProfile);
        return isUsefulJumpProbeLanding(botPos, steeringTarget, direction, landing);
    }

    private static boolean isUsefulJumpProbeLanding(Point botPos,
                                                    Point steeringTarget,
                                                    int direction,
                                                    BotPhysicsEngine.JumpLanding landing) {
        if (landing == null || landing.point() == null || direction == 0) {
            return false;
        }
        Point landingPoint = landing.point();
        int landingDx = landingPoint.x - botPos.x;
        if (Integer.signum(landingDx) != direction) {
            return false;
        }

        int distanceBefore = Math.abs(steeringTarget.x - botPos.x);
        int distanceAfter = Math.abs(steeringTarget.x - landingPoint.x);
        if (distanceAfter >= distanceBefore) {
            return false;
        }

        boolean targetIsAboveOrLevel = steeringTarget.y <= botPos.y + BotPhysicsEngine.cfg.MAX_SNAP_DROP;
        boolean landingIsAboveOrLevel = landingPoint.y <= botPos.y + BotPhysicsEngine.cfg.MAX_SNAP_DROP;
        return targetIsAboveOrLevel && landingIsAboveOrLevel;
    }
}
