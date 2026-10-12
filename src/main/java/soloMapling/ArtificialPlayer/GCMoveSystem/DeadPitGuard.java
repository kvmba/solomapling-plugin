package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.server.maps.Foothold;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Portal;
import org.gms.server.maps.Rope;

import java.awt.Point;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/*
 * Plugin-side dead-pit guard. Independent of the WZ forbidFallDown flags and of the nav
 * graph being baked: a landing surface is a DEAD PIT FLOOR when nothing above it is
 * escapable - no portal, no rope to grab, and no real one-move escape off it either.
 *
 * The verdict is the walk-connected COMPONENT's, not one foothold's: escape options are
 * shared across the engine's own walk partition (prev/next links accepted by
 * canWalkAcrossFootholds - the same rule the graph's region merge and the runtime walk
 * use), so a component is livable iff ANY member clears an escape, and every member
 * caches that answer.
 *
 * Which moves count as an escape (all evaluated with the ENGINE'S OWN simulators, on the
 * surface's real per-column geometry):
 *   - teleport: any portal in the reach window (the map's own door);
 *   - climb:    the rope/ladder mount mechanisms the graph builder and the executor use;
 *   - the map's TOP surface is ordinary ground, never a trap (it has no higher ground to
 *     be trapped below - the documented shortcut nearestLivableGround also relies on);
 *   - down-jump: the pass-through drop (simulateDownJumpLanding; the sim itself honours
 *     forbidFallDown - the flag blocks the drop-through and nothing else);
 *   - walk-off: stepping past the lip with the move's real horizontal momentum
 *     (simulateFallLanding at the border column - legal on ffd platforms too);
 *   - arced jump / jump-off: simulateJumpLanding in the three launch-step directions
 *     (up through one-way platforms, across a gap, or off the edge down);
 *   - class abilities: a magician's Teleport landing, a Hermit/Night Lord's Flash Jump
 *     dash (BotMovementCapabilities) - only for bots whose class grants them.
 * A landing inside the SAME walk component is not an escape (the component already shares
 * the verdict); any landing on a different component is, mirroring the one-hop semantics
 * the descent branches have always used (the landing's own verdict is not chased).
 *
 * Why: the graph's dead-region prune keeps the PLANNED routes out of such pits, but a bot
 * can still reach one through paths the graph never sees - the stuck watchdog's rescue hop
 * (any simulated landing counted as valid), warmup-fallback walk-offs, air steering and
 * knockback. Every such "will I land somewhere survivable?" decision goes through
 * isLivableLanding here.
 *
 * An LPQ stage-3 pit floor (rim-to-floor 402px, base jump apex 75px) reads as dead; a 150px
 * terrace with an open sky above it reads as live. Portal/rope escapes are honoured so the
 * stage-2 tower base (a ladder chain climbs back out) never reads as dead. A swim map has
 * no dead pits at all: every underwater surface is left by swimming up.
 */
final class DeadPitGuard {

    private DeadPitGuard() {
    }

    /** Probes are one walk step apart along the surface: a drop hole a walk could cross is never stepped over. */
    private static final int MAX_PROBES_PER_FOOTHOLD = 512;
    /** A rope hanging this far above the floor can still be jumped and caught. */
    private static final int ROPE_GRAB_SLACK_PX = 40;
    /** A portal within this box of the surface counts as an exit. */
    private static final int PORTAL_X_SLACK_PX = 30;
    private static final int PORTAL_Y_ABOVE_PX = 80;
    private static final int PORTAL_Y_BELOW_PX = 30;

    /**
     * The verdict cache bucket: the caller's movement profile AND its class abilities. Every
     * dimension the engine reads - jump, speed, snowshoes, teleport, dash scale - is part of the
     * key, so a verdict can never be served across two bots the physics treats differently (the
     * earlier apex-only and profile-only buckets served same-jump/different-speed and
     * mage/warrior answers across each other).
     */
    record VerdictKey(BotMovementProfile profile, BotMovementCapabilities capabilities) {
    }

    /** tree -> verdict key -> foothold id -> verdict. Keyed by tree identity (weak) so a
     *  reloaded map gets a fresh cache automatically (the COLLISION_INDEX pattern), instanced
     *  copies sharing one tree share their verdicts, and dropped trees do not pin their verdicts
     *  here forever. The verdict is a property of the walk-connected COMPONENT, not of one
     *  foothold (see isLivableSurface), so every foothold of one walk region caches the same
     *  answer. */
    private static final Map<org.gms.server.maps.FootholdTree, Map<VerdictKey, Map<Integer, Boolean>>> VERDICTS =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    /** tree -> foothold-id -> walk-region root (see walkRegionFootholds). The engine's own connected-
     *  surface partition, rebuilt once per tree from prev/next + canWalkAcrossFootholds. Weak-keyed
     *  like the verdict cache: a reloaded map starts clean, dropped trees do not leak it. */
    private static final Map<org.gms.server.maps.FootholdTree, Map<Integer, Integer>> WALK_ROOTS =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /*
     * Whether a bot landing at {@code landing} (a simulated jump/drop/walk-off landing, or the
     * bot's own standing point) can still leave the surface it would be on. Unknown footing
     * (no ground, unindexed tree) counts as livable: this guard refuses moves, it must never
     * strand a bot on ground it already occupies.
     */
    static boolean isLivableLanding(MapleMap map, Point landing, BotMovementProfile profile) {
        return isLivableLanding(map, landing, profile, BotMovementCapabilities.NONE);
    }

    static boolean isLivableLanding(MapleMap map, Point landing, BotMovementProfile profile,
                                    BotMovementCapabilities capabilities) {
        if (map == null || landing == null || map.getFootholds() == null) {
            return true;
        }
        Foothold foothold = BotPhysicsEngine.findGroundFoothold(map, landing);
        if (foothold == null) {
            return true; // no floor: the fall-off-map recovery owns that case, not this guard
        }
        return isLivableSurface(map, foothold, profile, capabilities);
    }

    static boolean isLivableSurface(MapleMap map, Foothold foothold, BotMovementProfile profile) {
        return isLivableSurface(map, foothold, profile, BotMovementCapabilities.NONE);
    }

    static boolean isLivableSurface(MapleMap map, Foothold foothold, BotMovementProfile profile,
                                    BotMovementCapabilities capabilities) {
        if (map == null || foothold == null || map.getFootholds() == null) {
            return true;
        }
        if (map.isSwim()) {
            return true; // there is no dead-pit concept in water: any surface is left by entering it
        }
        VerdictKey key = cacheKey(profile, capabilities);
        Map<Integer, Boolean> verdicts = VERDICTS
                .computeIfAbsent(map.getFootholds(), k -> new ConcurrentHashMap<>())
                .computeIfAbsent(key, k -> new ConcurrentHashMap<>());
        Boolean cached = verdicts.get(foothold.getId());
        if (cached != null) {
            return cached;
        }
        // Escape options are shared across the WALK-CONNECTED surface, not owned per foothold: a
        // bot that can walk from this surface to any other foothold of its own walk region (the
        // engine's canonical partition - prev/next links accepted by canWalkAcrossFootholds, the
        // same rule the graph's region merge and the runtime walk use) can use THAT foothold's
        // portal / rope / move-off. Judging only the probed foothold called the 103000900 floor
        // row - one continuous walk to the door portal at x=1214 - dead, after which the guard
        // refused every authorised descent onto the row and the dead-pit self-heal relocated
        // bots standing on it.
        List<Foothold> surfaces = walkableFootholds(map);
        Map<Integer, Integer> roots = WALK_ROOTS.computeIfAbsent(map.getFootholds(),
                k -> buildWalkRoots(map));
        List<Foothold> members = walkRegionFootholds(surfaces, roots, foothold);
        int componentRoot = rootOf(roots, foothold);
        boolean live = false;
        for (Foothold member : members) {
            if (hasEscape(map, member, key, surfaces, roots, componentRoot)) {
                live = true;
                break;
            }
        }
        for (Foothold member : members) {
            verdicts.put(member.getId(), live);
        }
        return live;
    }

    /**
     * The verdict cache bucket for a caller: the profile and capabilities themselves, with null
     * normalized to base / NONE (the same normalization every engine call applies via
     * {@code profileOrBase}). Package-visible so the normalization and the bucket identity are
     * pinned by test. The verdict chain has no re-entrant call into isLivableSurface, so no
     * "in progress" guard is needed.
     */
    static VerdictKey cacheKey(BotMovementProfile profile, BotMovementCapabilities capabilities) {
        return new VerdictKey(profile != null ? profile : BotMovementProfile.base(),
                capabilities != null ? capabilities : BotMovementCapabilities.NONE);
    }

    /*
     * The walk-connected component of {@code foothold}: itself plus every other non-wall foothold
     * the canonical walk partition puts in the same region (see buildWalkRoots). Read once per
     * region - after the first verdict every member carries the cached component answer - so the
     * scan is a plain O(N) root comparison behind the per-foothold verdict cache.
     */
    private static List<Foothold> walkRegionFootholds(List<Foothold> surfaces,
                                                      Map<Integer, Integer> roots,
                                                      Foothold foothold) {
        List<Foothold> members = new java.util.ArrayList<>();
        members.add(foothold);
        if (foothold.isWall()) {
            return members; // walls are outside the walk partition; keep the per-surface verdict
        }
        Integer root = roots.get(foothold.getId());
        if (root == null) {
            return members;
        }
        for (Foothold other : surfaces) {
            if (other == foothold || other.isWall()) {
                continue;
            }
            if (root.equals(roots.get(other.getId()))) {
                members.add(other);
            }
        }
        return members;
    }

    /*
     * A surface's effective Y for the window checks: its LOWEST point. For horizontal footholds
     * this is the surface itself; for slopes it reads the bottom end, which biases the portal /
     * rope windows toward "dead" - the safe direction (over-calling dead merely loses an optional
     * descent, under-calling it drops a bot into a trap). Per-column probes use surfaceYAt
     * instead, so slope traversals are judged on their real geometry.
     */
    private static int surfaceY(Foothold foothold) {
        return Math.max(foothold.getY1(), foothold.getY2());
    }

    /*
     * The surface's real Y at column x (linear interpolation between the endpoints; callers pass
     * columns inside the span). Orientation-proof: the WZ authors a share of footholds
     * right-to-left (x1 > x2), which the engine's findBelow-index skips - a probe that resolved its
     * standing point through findGroundFoothold would silently skip every such surface.
     */
    private static int surfaceYAt(Foothold foothold, int x) {
        if (foothold.getY1() == foothold.getY2()) {
            return foothold.getY1();
        }
        long dy = (long) (foothold.getY2() - foothold.getY1()) * (x - foothold.getX1());
        return foothold.getY1() + (int) (dy / (foothold.getX2() - foothold.getX1()));
    }

    private static boolean hasEscape(MapleMap map, Foothold foothold, VerdictKey key,
                                     List<Foothold> surfaces, Map<Integer, Integer> roots,
                                     int componentRoot) {
        int floorY = surfaceY(foothold);
        int loX = Math.min(foothold.getX1(), foothold.getX2());
        int hiX = Math.max(foothold.getX1(), foothold.getX2());
        int apex = (int) Math.ceil(BotPhysicsEngine.calculateMaxJumpHeight(key.profile()));

        if (portalNear(map, foothold, loX, hiX)) {
            return true;
        }
        if (ropeAbove(map, foothold, floorY, loX, hiX, apex, key.profile(), roots)) {
            return true;
        }
        // No higher ground at all means this IS the map's top surface - ordinary ground,
        // not a trap. The trap verdict only applies to surfaces that sit BELOW other
        // reachable ground they cannot climb back to. Y grows downward: higher = smaller y.
        if (highestSurfaceY(surfaces) >= floorY) {
            return true;
        }
        // A rope whose BOTTOM reaches (or passes below) this floor can be mounted here and
        // ridden DOWN. ropeAbove only counts ropes to climb UP, so a mid-shaft platform
        // beside a long rope would be judged on the up-check alone.
        if (ropeBelow(map, foothold, floorY, loX, hiX, key.profile(), roots)) {
            return true;
        }
        // Every real one-move exit the engine can execute off this surface. Runs LAST: it is
        // the only branch that pays simulations, and the cheap non-sim exits above answer the
        // common cases first.
        return oneMoveEscapes(map, foothold, loX, hiX, key, roots, componentRoot);
    }

    /*
     * Every real one-move exit from {@code foothold}, each probed with the ENGINE'S OWN
     * simulator and the move's real shape:
     *   - down-jump at every column (the sim itself refuses forbidFallDown sources);
     *   - arced jump in the three launch-step directions (up through platforms, across, off);
     *   - for a magician, the Teleport landing (up / horizontal / down, the executor's own math);
     *   - for a Hermit/Night Lord, the Flash Jump dash arc;
     *   - walk-offs at the two border columns with the move's real horizontal momentum.
     * Probe columns are one walk step apart (endpoints and midpoint always included): a drop
     * hole any walk could step into is never skipped over, which is how the earlier fixed-column
     * sampling (one midpoint on a narrow ledge, <=8 samples on a wide one) lost real exits.
     */
    private static boolean oneMoveEscapes(MapleMap map, Foothold foothold, int loX, int hiX,
                                          VerdictKey key, Map<Integer, Integer> roots,
                                          int componentRoot) {
        BotMovementProfile profile = key.profile();
        int step = Math.max(1, BotPhysicsEngine.walkStep(map, profile));
        for (int x : probeColumns(loX, hiX, step)) {
            Point standing = new Point(x, surfaceYAt(foothold, x));
            // Walk-offs are the plain-fall move: the bot carries its walk momentum past the lip
            // from exactly this column (the graph builder's own drop shape - simulateFallLanding
            // at the ledge column with a full walk step of runoff). A plain fall from a column in
            // the surface's INTERIOR answers only "self" (its first collision is this surface), so
            // only the border columns can pay off - but they must be probed: on a walk-linked
            // multi-piece row the edge columns carry the exits.
            if ((x == loX || x == hiX)
                    && landsElsewhere(BotPhysicsEngine.simulateFallLanding(map, standing,
                            x == loX ? -step : step), roots, componentRoot)) {
                return true;
            }
            if (landsElsewhere(BotPhysicsEngine.simulateDownJumpLanding(map, standing),
                    roots, componentRoot)) {
                return true;
            }
            for (int dir : new int[]{-1, 0, 1}) {
                if (landsElsewhere(BotPhysicsEngine.simulateJumpLanding(map, standing, dir * step, profile),
                        roots, componentRoot)) {
                    return true; // the arced jump: up through a platform, across a gap, or off the edge
                }
            }
            if (key.capabilities().teleport() && teleportEscapes(map, standing, roots, componentRoot)) {
                return true;
            }
            if (key.capabilities().flashJumpScale() > 0f) {
                for (int dir : new int[]{-1, 1}) {
                    if (landsElsewhere(BotPhysicsEngine.simulateFlashJumpLanding(map, standing, dir,
                            profile, key.capabilities().flashJumpScale()), roots, componentRoot)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** The magician's blink exits: the executor's own landing math, up / both sides / down. */
    private static boolean teleportEscapes(MapleMap map, Point standing,
                                           Map<Integer, Integer> roots, int componentRoot) {
        if (landsElsewherePoint(GCMovementSkills.upTeleportLanding(map, standing), map, roots, componentRoot)) {
            return true;
        }
        for (int dir : new int[]{-1, 1}) {
            if (landsElsewherePoint(GCMovementSkills.horizontalTeleportLanding(map, standing, dir),
                    map, roots, componentRoot)) {
                return true;
            }
        }
        return landsElsewherePoint(GCMovementSkills.downTeleportLanding(map, standing),
                map, roots, componentRoot);
    }

    private static boolean landsElsewhere(BotPhysicsEngine.JumpLanding landing,
                                          Map<Integer, Integer> roots, int componentRoot) {
        return landing != null && landing.foothold() != null
                && rootOf(roots, landing.foothold()) != componentRoot;
    }

    private static boolean landsElsewherePoint(Point landing, MapleMap map,
                                               Map<Integer, Integer> roots, int componentRoot) {
        if (landing == null) {
            return false;
        }
        Foothold under = BotPhysicsEngine.findGroundFoothold(map, landing);
        return under != null && rootOf(roots, under) != componentRoot;
    }

    private static int rootOf(Map<Integer, Integer> roots, Foothold foothold) {
        Integer root = roots.get(foothold.getId());
        return root != null ? root : foothold.getId();
    }

    /*
     * Probe columns along [loX..hiX]: both endpoints, the midpoint, and a one-walk-step grid
     * (bounded by MAX_PROBES_PER_FOOTHOLD so one giant floor cannot make a cold verdict pass
     * unbounded; beyond the cap the grid widens evenly instead of dropping columns).
     */
    private static int[] probeColumns(int loX, int hiX, int step) {
        int span = hiX - loX;
        int spacing = Math.max(step, (int) Math.ceil(span / (double) MAX_PROBES_PER_FOOTHOLD));
        if (spacing < 1) {
            spacing = 1;
        }
        Set<Integer> columns = new java.util.TreeSet<>();
        columns.add(loX);
        columns.add(hiX);
        columns.add((loX + hiX) / 2);
        for (int x = loX; x <= hiX; x += spacing) {
            columns.add(x);
        }
        for (int x = hiX; x >= loX; x -= spacing) {
            columns.add(x);
        }
        return columns.stream().mapToInt(Integer::intValue).toArray();
    }

    private static int highestSurfaceY(List<Foothold> surfaces) {
        // The TOPMOST walkable surface = the smallest surface y (y grows downward).
        int topmost = Integer.MAX_VALUE;
        for (Foothold foothold : surfaces) {
            if (foothold.isWall()) {
                continue;
            }
            topmost = Math.min(topmost, surfaceY(foothold));
        }
        return topmost;
    }

    private static boolean portalNear(MapleMap map, Foothold surface, int loX, int hiX) {
        for (Portal portal : map.getPortals()) {
            if (portal == null || portal.getPosition() == null) {
                continue;
            }
            Point p = portal.getPosition();
            if (p.x < loX - PORTAL_X_SLACK_PX || p.x > hiX + PORTAL_X_SLACK_PX) {
                continue;
            }
            // Judge the portal against the surface's REAL y at the portal's column (slope-aware),
            // not the whole surface's low end - a portal at a long ramp's top is still a door.
            int surfaceAt = surfaceYAt(surface, Math.clamp(p.x, loX, hiX));
            if (surfaceAt - PORTAL_Y_ABOVE_PX <= p.y && p.y <= surfaceAt + PORTAL_Y_BELOW_PX) {
                return true;
            }
        }
        return false;
    }

    private static boolean ropeAbove(MapleMap map, Foothold surface, int floorY, int loX, int hiX,
                                     int apex, BotMovementProfile profile, Map<Integer, Integer> roots) {
        for (Rope rope : map.getRopes()) {
            int ropeTop = rope.topY();
            int ropeBottom = rope.bottomY();
            if (ropeBottom < floorY - apex - ROPE_GRAB_SLACK_PX) {
                continue; // the whole rope hangs too far above any jump
            }
            if (ropeTop > floorY) {
                continue; // rope entirely below the floor: nothing to climb up
            }
            if (ropeMountableFromSurface(map, rope, surface, loX, hiX, floorY, profile, roots)) {
                return true;
            }
        }
        return false;
    }

    private static boolean ropeBelow(MapleMap map, Foothold surface, int floorY, int loX, int hiX,
                                     BotMovementProfile profile, Map<Integer, Integer> roots) {
        for (Rope rope : map.getRopes()) {
            if (rope.bottomY() < floorY - ROPE_GRAB_SLACK_PX) {
                continue; // the rope ends too far above this floor to mount it here
            }
            if (ropeMountableFromSurface(map, rope, surface, loX, hiX, floorY, profile, roots)) {
                return true;
            }
        }
        return false;
    }

    /*
     * Whether the bot standing on {@code surface} at {@code floorY} can actually GET ONTO
     * {@code rope} — the engine's own mount mechanisms, in the order a real attempt takes them:
     *
     *   1. DIRECT: the floor sits inside the rope's climbable span and the rope is within the grab
     *      column (the client's ROPE_GRAB_X): walk up and press the key.
     *   2. TOP-ATTACH: the rope head hangs up to MAX_SNAP_DROP below the floor, within the column
     *      (canAttachToRopeFromTopPlatform / the graph's canTopGrab): the executor attaches from
     *      just above the head — the standard way down a tower's face rope.
     *   3. CROUCH-DROP: simulateDownJumpRopeGrab catches the head from the stand point (the graph's
     *      own canTopStep gate; ffd surfaces are refused — the executor's down-jump honours the flag).
     *   4. JUMP-GRAB: simulateGroundJumpRopeGrab, the same fire simulation the graph builder and
     *      the executor use (canJumpGrab).
     *
     * Off-axis ropes (outside the grab column) are only honest when the bot can WALK to the rope's
     * column on its own surface: the column's ground must sit at the surface's level (the same
     * ground band a walk uses) AND belong to the same walk region — the engine's own connected-
     * surface partition (sameWalkRegion below). A rope across a gap or pit is a different region:
     * not an exit.
     */
    private static boolean ropeMountableFromSurface(MapleMap map, Rope rope, Foothold surface,
                                                    int loX, int hiX, int floorY,
                                                    BotMovementProfile profile,
                                                    Map<Integer, Integer> roots) {
        Point from;
        if (rope.x() >= loX - BotPhysicsEngine.cfg.ROPE_GRAB_X
                && rope.x() <= hiX + BotPhysicsEngine.cfg.ROPE_GRAB_X) {
            from = new Point(Math.clamp(rope.x(), loX, hiX), floorY);
        } else {
            Point step = BotPhysicsEngine.findGroundPoint(map, new Point(rope.x(), floorY));
            if (step == null || Math.abs(step.y - floorY) > BotPhysicsEngine.cfg.MAX_SLOPE_UP) {
                return false; // no ground at the rope's column at this level: not a side-step
            }
            Foothold column = BotPhysicsEngine.findGroundFoothold(map, step);
            if (column == null || !sameWalkRegion(surface, column, roots)) {
                return false; // the column's ground is not walk-connected to this surface
            }
            from = step;
        }
        if (Math.abs(from.x - rope.x()) <= BotPhysicsEngine.cfg.ROPE_GRAB_X) {
            if (from.y >= BotPhysicsEngine.firstClimbableY(rope) && from.y <= rope.bottomY()) {
                return true; // 1. direct grab within the rope's climbable span
            }
            if (from.y < rope.topY()
                    && rope.topY() - from.y <= BotPhysicsEngine.cfg.MAX_SNAP_DROP) {
                return true; // 2. top-attach: the executor's own snap band at the head
            }
        }
        // The two simulations below are gated by the SAME preconditions the graph builder uses
        // before it ever calls them (addRopeEntryEdges): a crouch-drop is tried only when the head
        // sits within a jump's Y threshold of the stand point, and a jump-grab only when the
        // builder's own reach heuristic (canReachRopeFromGround) says the arc gets there at all.
        // Mirroring the preconditions keeps the verdict faithful to the engine's model AND keeps
        // the sims off the pair-product's hot path (the heuristic is pure arithmetic).
        if (!surface.isForbidFallDown()
                && Math.abs(from.x - rope.x()) <= BotPhysicsEngine.cfg.ROPE_GRAB_X
                && from.y <= rope.topY() + BotMovementManager.cfg.JUMP_Y_THRESH
                && BotPhysicsEngine.simulateDownJumpRopeGrab(map, from, rope) != null) {
            return true; // 3. crouch-drop catches the head (the graph's canTopStep)
        }
        if (BotPhysicsEngine.canReachRopeFromGround(map, from, rope, profile)) {
            int walkStep = BotPhysicsEngine.walkStep(map, profile);
            for (int stepX : new int[]{-walkStep, 0, walkStep}) {
                if (BotPhysicsEngine.simulateGroundJumpRopeGrab(map, from, stepX, rope, profile) != null) {
                    return true; // 4. jump-grab (the graph's canJumpGrab / the executor's fire sim)
                }
            }
        }
        return false;
    }

    /*
     * Whether two footholds belong to the same WALK-CONNECTED surface: the engine's own partition,
     * rebuilt lazily per tree from the same primitives the graph builder's region merge uses —
     * each foothold unions with its prev/next link when BotPhysicsEngine.canWalkAcrossFootholds
     * accepts the step. This is the authority the runtime walk, the graph regions and the pet
     * follower all key on; asking it here keeps the guard's "can I walk there?" answer identical
     * to the engine's, without paying per-column ground probes (memoized per tree: O(N) once).
     */
    private static boolean sameWalkRegion(Foothold first, Foothold second, Map<Integer, Integer> roots) {
        if (first == null || second == null) {
            return false;
        }
        if (first.getId() == second.getId()) {
            return true;
        }
        Integer a = roots.get(first.getId());
        Integer b = roots.get(second.getId());
        return a != null && Objects.equals(a, b);
    }

    private static Map<Integer, Integer> buildWalkRoots(MapleMap map) {
        List<Foothold> all = map.getFootholds().getAllFootholds();
        Map<Integer, Foothold> byId = new java.util.HashMap<>(all.size() * 2);
        for (Foothold fh : all) {
            byId.put(fh.getId(), fh);
        }
        Map<Integer, Integer> parent = new java.util.HashMap<>(all.size() * 2);
        for (Foothold fh : all) {
            parent.put(fh.getId(), fh.getId());
        }
        for (Foothold fh : all) {
            unionWalkable(parent, fh, byId.get(fh.getPrev()));
            unionWalkable(parent, fh, byId.get(fh.getNext()));
        }
        Map<Integer, Integer> roots = new java.util.HashMap<>(parent.size() * 2);
        for (Foothold fh : all) {
            roots.put(fh.getId(), findRoot(parent, fh.getId()));
        }
        return roots;
    }

    private static void unionWalkable(Map<Integer, Integer> parent, Foothold first, Foothold second) {
        if (first == null || second == null || first.isWall() || second.isWall()) {
            return;
        }
        if (!BotPhysicsEngine.canWalkAcrossFootholds(first, second)) {
            return;
        }
        int ra = findRoot(parent, first.getId());
        int rb = findRoot(parent, second.getId());
        if (ra != rb) {
            parent.put(rb, ra);
        }
    }

    private static int findRoot(Map<Integer, Integer> parent, int id) {
        int root = id;
        while (true) {
            Integer up = parent.get(root);
            if (up == null || up == root) {
                break;
            }
            root = up;
        }
        // Path compression keeps repeated rope verdicts on stair chains cheap.
        int cursor = id;
        while (true) {
            Integer up = parent.get(cursor);
            if (up == null || up == cursor) {
                break;
            }
            parent.put(cursor, root);
            cursor = up;
        }
        return root;
    }

    private static List<Foothold> walkableFootholds(MapleMap map) {
        // getAllFootholds() rebuilds its collection per call — call it ONCE per verdict (see
        // hasEscape) and thread the list through the scans below.
        return map.getFootholds().getAllFootholds();
    }
}
