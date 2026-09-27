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

    // Reach of the launch-target scan around the bot's X (px each side) and the sweep step. Bounds
    // nearestUpwardRopeLaunch: the fallback engine runs per grounded tick while a graph bakes, so
    // the scan must stay bounded — but tall PQ rooms are ~530px wide and the ladder can sit at the
    // far end from where a bot spawned, so the radius must cover the whole room.
    private static final int ROPE_LAUNCH_SCAN_RADIUS_PX = 400;
    private static final int ROPE_LAUNCH_SCAN_STEP_PX = 6;

    static Point resolveSteeringTarget(BotMovementState entry, Point botPos, Point targetPos) {
        Rope rope = selectNearbyRope(entry, botPos, targetPos);
        Point ledgeTarget = resolveFallbackLedgeTarget(entry, botPos, targetPos, rope);
        if (ledgeTarget != null) {
            return ledgeTarget;
        }
        if (rope != null) {
            return new Point(rope.x(), botPos.y);
        }
        // No actionable rope within arm's reach, but the target sits far above: on a
        // vertically-stacked PQ tower the climb is a CHAIN of rope legs (LPQ stage 1 needs two
        // ladders before the first mob floor), so the nearest VALIDATED launch — a spot the
        // grab simulator confirms can catch some upward rope — is the right steer. Without this
        // the bot paces its floor under a target it can only reach by walking to a ladder first
        // (the "walks left-right, never climbs" stage-1 report). Transient by construction: the
        // baked graph replaces this whole heuristic once it lands.
        if (targetPos.y < botPos.y - Math.max(BotMovementManager.cfg.JUMP_Y_THRESH * 2, 60)) {
            Point launch = nearestUpwardRopeLaunch(entry.bot.getMap(), botPos, entry.movementProfile);
            if (launch != null) {
                return launch;
            }
        }
        return targetPos;
    }

    static boolean tryImmediateAction(BotMovementState entry, Point botPos, Point targetPos) {
        Character bot = entry.bot;
        MapleMap map = bot.getMap();
        Rope rope = selectNearbyRope(entry, botPos, targetPos);
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
            int ropeDx = rope.x() - botPos.x;
            if (BotPhysicsEngine.canReachRopeFromGround(map, botPos, rope, entry.movementProfile)) {
                BotMovementManager.initiateRopeJump(entry, bot, ropeDx);
                return true;
            }
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
        int stepX = BotMovementManager.resolveGroundStepX(entry, botPos, steeringTarget,
                BotMovementManager.cfg.STOP_DIST, BotMovementManager.cfg.FOLLOW_DIST);
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
        return BotPhysicsEngine.isGroundFarBelow(entry.bot.getMap(), ahead);
    }

    /*
     * Nearest standing spot this bot can launch an UPWARD rope-grab from, or null when the
     * whole map has no valid launch. The launch candidates reuse the graph builder's own anchor
     * generator (feature anchors + endpoints, exactly the seeds its launch windows are grown
     * from), and each candidate is validated with the runtime grab simulator — the same
     * predictor the eventual rope jump fires from, so an approved target is one the jump
     * really lands. Standing rows are NOT restricted to the bot's own floor: on a stacked tower
     * the validated launch is the first leg of the climb chain, and the walk layer naturally
     * clamps the steer onto real ground under it. No cache: the fallback engine only runs while
     * a graph is baking (its warmup executors are the bottleneck anyway), and the scan is
     * bounded by ROPE_LAUNCH_SCAN_RADIUS_PX.
     */
    private static Point nearestUpwardRopeLaunch(MapleMap map, Point botPos,
                                                 BotMovementProfile profile) {
        // Nearest-first: one simulator pass per candidate until the first valid launch, not a
        // full-map validation sweep, per grounded tick.
        List<Point> anchors = grabLaunchAnchors(map, botPos, profile);
        anchors.sort((a, b) -> Integer.compare(
                Math.abs(a.x - botPos.x), Math.abs(b.x - botPos.x)));
        for (Point anchor : anchors) {
            if (!walkableToSameRow(map, botPos, anchor)) {
                continue;
            }
            if (!ropeGrabReachableFrom(map, anchor, profile)) {
                continue;
            }
            return anchor;
        }
        return null;
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
            int fy = fh.getY1(); // builder anchors stand on the foothold itself
            for (int x = minX; x <= maxX; x += Math.max(walkStep, ROPE_LAUNCH_SCAN_STEP_PX)) {
                out.add(new Point(x, fy));
            }
            out.add(new Point(maxX, fy));
        }
        return new ArrayList<>(out);
    }

    /* True when the bot standing at botPos can reach the anchor's column by GROUND travel:
     * the anchor must sit at the bot's own height (within snap tolerance) on the same connected
     * ledge row (footholds linked by prev/next at one Y — the walk edge grouping the builder
     * uses). A launch anchor on another row is a LATER leg of the climb (itself reached by a
     * rope, i.e. not walkable), so steering at it would grind the bot into a wall. */
    private static boolean walkableToSameRow(MapleMap map, Point botPos, Point anchor) {
        if (Math.abs(anchor.y - botPos.y) > BotPhysicsEngine.cfg.MAX_SNAP_DROP) {
            return false;
        }
        Foothold current = BotPhysicsEngine.findGroundFoothold(map, botPos);
        Foothold target = BotPhysicsEngine.findGroundFoothold(map, anchor);
        if (current == null || target == null) {
            return false;
        }
        if (target.getId() == current.getId()) {
            return true;
        }
        if (target.getY1() != current.getY1() || target.getY2() != current.getY2()) {
            return false;
        }
        Deque<Integer> queue = new ArrayDeque<>();
        Set<Integer> seen = new HashSet<>();
        queue.add(current.getId());
        seen.add(current.getId());
        Map<Integer, Foothold> byId = new HashMap<>();
        for (Foothold fh : map.getFootholds().getAllFootholds()) {
            byId.put(fh.getId(), fh);
        }
        while (!queue.isEmpty()) {
            Foothold fh = byId.get(queue.poll());
            if (fh == null) {
                continue;
            }
            if (fh.getId() == target.getId()) {
                return true;
            }
            for (int nextId : new int[]{fh.getNext(), fh.getPrev()}) {
                // >0, not !=0: WZ authors 0 for "no link" and this graph's ids are 1-based, so
                // a bare truthiness test would treat id 0 as a real foothold (none exists) but
                // a -1 sentinel would loop forever on absent links. prev/next may also name a
                // differently-sloped neighbour (e.g. the ground row's edge fh linking the wall
                // strip above), so the BFS keeps the SAME-ROW gate: only walk links whose row
                // equals the target's.
                Foothold next = nextId > 0 ? byId.get(nextId) : null;
                if (next == null || !seen.add(next.getId())) {
                    continue;
                }
                if (next.getY1() != current.getY1() || next.getY2() != current.getY2()) {
                    continue;
                }
                queue.add(next.getId());
            }
        }
        return false;
    }

    /* True when a jump launched from anchor can catch some rope ABOVE the anchor: the first leg
     * of the climb chain. Verdict comes from the TRAJECTORY simulator only — the same sim the
     * rope jump fires at runtime. (canReachRopeFromGround is a reach-ratio heuristic that is
     * deliberately looser than the arc: it passes points whose real jump sails past the ladder,
     * e.g. x=-45 for the stage-1 overhung ladder whose grab window ends at x=-55. A heuristic
     * pass here steers the bot NEXT to the window and it grinds one launch short forever.) */
    private static boolean ropeGrabReachableFrom(MapleMap map, Point anchor,
                                                 BotMovementProfile profile) {
        int jumpStep = BotPhysicsEngine.walkStep(map, profile);
        for (Rope rope : map.getRopes()) {
            if (rope.topY() >= anchor.y) {
                continue; // does not rise above the anchor: not an upward leg
            }
            for (int stepX : new int[]{-jumpStep, 0, jumpStep}) {
                if (BotPhysicsEngine.simulateGroundJumpRopeGrab(map, anchor, stepX, rope) != null) {
                    return true;
                }
            }
        }
        return false;
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
        Point best = chooseBetterLedgeTarget(botPos, targetPos, left, right);
        if (best == null) {
            return null;
        }
        return new Point(best.x, targetPos.y);
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
        boolean canDrop = map != null && map.isSwim()
                ? BotPhysicsEngine.canStartDownJump(map, botPos)
                : BotPhysicsEngine.simulateDownJumpLanding(map, botPos) != null;
        if (!canDrop) {
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
