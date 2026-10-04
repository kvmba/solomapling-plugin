package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.server.maps.Foothold;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Portal;
import org.gms.server.maps.Rope;

import java.awt.Point;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/*
 * Plugin-side dead-pit guard. Independent of the WZ forbidFallDown flags and of the nav
 * graph being baked: a landing surface is a DEAD PIT FLOOR when nothing above it is
 * escapable - no portal, no rope to grab, no jump chain (apex-bounded) back up to a
 * higher surface, AND no way off downward either (walk-off / down-jump with a real
 * landing, or a rope bottoming at the floor to ride down). The semantics mirror
 * scripts/wzaudit/dead_pit_scan.py, evaluated at runtime from the live foothold tree.
 *
 * Why: the graph's dead-region prune keeps the PLANNED routes out of such pits, but a bot
 * can still reach one through paths the graph never sees - the stuck watchdog's rescue hop
 * (any simulated landing counted as valid), warmup-fallback walk-offs, air steering and
 * knockback. Every such "will I land somewhere survivable?" decision goes through
 * isLivableLanding here.
 *
 * An LPQ stage-3 pit floor (rim-to-floor 402px, base jump apex 77px) reads as dead; a 150px
 * terrace with an open sky above it reads as live. Portal/rope escapes are honoured so the
 * stage-2 tower base (a ladder chain climbs back out) never reads as dead.
 */
final class DeadPitGuard {

    private DeadPitGuard() {
    }

    /** A rise beyond this from the landing's own level is unreachable by a jump. */
    private static final int STEP_UP_TOLERANCE_PX = 40;
    /**
     * Jump-up reach beyond the raw ballistic apex: the scanner's calibrated JUMP_REACH
     * (apex ~77px + margin) - grab/step-up rounding makes a rise at the apex boundary
     * climbable in practice, and the stage-1 audit maps pin rises of ~100px as climbable.
     */
    private static final int REACH_MARGIN_PX = 25;
    /** Max horizontal gap between surfaces for a jump-up to be considered. */
    private static final int JUMP_ACROSS_PX = 140;
    /** A rope hanging this far above the floor can still be jumped and caught. */
    private static final int ROPE_GRAB_SLACK_PX = 40;
    /** A portal within this box of the landing counts as an exit. */
    private static final int PORTAL_X_SLACK_PX = 30;
    private static final int PORTAL_Y_ABOVE_PX = 80;
    private static final int PORTAL_Y_BELOW_PX = 30;

    /** tree -> (apex px -> foothold id -> verdict). Keyed by tree identity (weak) so a reloaded
     *  map gets a fresh cache automatically (the COLLISION_INDEX pattern), instanced copies
     *  sharing one tree share their verdicts, and dropped trees do not pin their verdicts here
     *  forever. The apex dimension is REQUIRED: the verdict's jump reach derives from the
     *  caller's profile, and a thief bot (117px apex) escapes shelves a base bot (77px) cannot -
     *  one shared verdict across profiles would serve one of them a wrong answer. */
    private static final Map<org.gms.server.maps.FootholdTree, Map<Integer, Map<Integer, Boolean>>> VERDICTS =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    /** Guards against re-entrant verdict computation on the same foothold (cycles in the chain). */
    private static final Map<org.gms.server.maps.FootholdTree, Set<Integer>> IN_PROGRESS =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /*
     * Whether a bot landing at {@code landing} (a simulated jump/drop/walk-off landing, or the
     * bot's own standing point) can still leave the surface it would be on. Unknown footing
     * (no ground, unindexed tree) counts as livable: this guard refuses moves, it must never
     * strand a bot on ground it already occupies.
     */
    static boolean isLivableLanding(MapleMap map, Point landing, BotMovementProfile profile) {
        if (map == null || landing == null || map.getFootholds() == null) {
            return true;
        }
        Foothold foothold = BotPhysicsEngine.findGroundFoothold(map, landing);
        if (foothold == null) {
            return true; // no floor: the fall-off-map recovery owns that case, not this guard
        }
        return isLivableSurface(map, foothold, profile);
    }

    static boolean isLivableSurface(MapleMap map, Foothold foothold, BotMovementProfile profile) {
        if (map == null || foothold == null || map.getFootholds() == null) {
            return true;
        }
        // The apex bucket is part of the cache key: the same surface is escapable for a
        // max-jump bot and a trap for a base-stat one (see the field doc above).
        int apexPx = (int) Math.ceil(BotPhysicsEngine.calculateMaxJumpHeight(profile));
        Map<Integer, Map<Integer, Boolean>> byApex =
                VERDICTS.computeIfAbsent(map.getFootholds(), k -> new ConcurrentHashMap<>());
        Map<Integer, Boolean> verdicts = byApex.computeIfAbsent(apexPx, k -> new ConcurrentHashMap<>());
        Boolean cached = verdicts.get(foothold.getId());
        if (cached != null) {
            return cached;
        }
        Set<Integer> inProgress = IN_PROGRESS.computeIfAbsent(map.getFootholds(), k -> ConcurrentHashMap.newKeySet());
        if (!inProgress.add(foothold.getId())) {
            return true; // chain cycle: an ancestor call is still deciding - treat as reachable
        }
        try {
            boolean live = hasEscape(map, foothold, profile);
            verdicts.put(foothold.getId(), live);
            return live;
        } finally {
            inProgress.remove(foothold.getId());
        }
    }

    /*
     * A surface's effective Y: its LOWEST point. For horizontal footholds this is the surface
     * itself; for slopes it reads the bottom end, which biases every verdict toward "dead" -
     * the safe direction (over-calling dead merely loses an optional descent, under-calling it
     * drops a bot into a trap).
     */
    private static int surfaceY(Foothold foothold) {
        return Math.max(foothold.getY1(), foothold.getY2());
    }

    private static boolean hasEscape(MapleMap map, Foothold foothold, BotMovementProfile profile) {
        // One list for the whole verdict: getAllFootholds() rebuilds its collection per call,
        // so per-candidate calls inside the chain scan below would multiply that cost.
        List<Foothold> surfaces = walkableFootholds(map);
        int floorY = surfaceY(foothold);
        int loX = Math.min(foothold.getX1(), foothold.getX2());
        int hiX = Math.max(foothold.getX1(), foothold.getX2());
        int apex = (int) Math.ceil(BotPhysicsEngine.calculateMaxJumpHeight(profile)) + REACH_MARGIN_PX;

        if (portalNear(map, floorY, loX, hiX)) {
            return true;
        }
        if (ropeAbove(map, floorY, loX, hiX, apex)) {
            return true;
        }
        // No higher ground at all means this IS the map's top surface - ordinary ground,
        // not a trap. The trap verdict only applies to surfaces that sit BELOW other
        // reachable ground they cannot climb back to. Y grows downward: higher = smaller y.
        if (highestSurfaceY(surfaces) >= floorY) {
            return true;
        }
        // Ours (LPQ stage-1 tower): a walk-off / down-jump off this surface is an exit too.
        // The old "up-only" model called the stage-1 spawn row (y=130, one walk-off to the
        // door row) and every plain drop-through ledge dead, which steered recoveries and
        // guards against platforms a bot leaves by simply stepping off. The landing's own
        // livability is that landing's verdict - not recursed here, mirroring the one-hop
        // depth of the jump chain above.
        if (fallEscape(map, foothold, floorY, loX, hiX, profile)) {
            return true;
        }
        // And the rope the other direction: a rope whose BOTTOM reaches (or passes below)
        // this floor can be mounted here and ridden DOWN. ropeAbove only counts ropes to
        // climb UP, so a mid-shaft platform beside a long rope read as dead on both checks.
        if (ropeBelow(map, floorY, loX, hiX)) {
            return true;
        }
        return jumpChainEscapes(surfaces, foothold, apex, new java.util.HashSet<>());
    }

    /*
     * Ours: can the bot leave {@code (loX..hiX, floorY)} by dropping into open air and
     * landing somewhere standable? Probed at the surface's endpoints and midpoint (a plain
     * midpoint can sit over a hole while an edge still lands), via the same two simulators
     * the executor's own moves use - a "yes" here is the move the bot would actually make.
     * The landing surface's own verdict is NOT chased (one-hop depth, like the jump chain);
     * it is cached on its own foothold. An ffd source is refused: the straight drop-through
     * is exactly the move the client blocks there (canStartDownJump honours the flag too).
     *
     * The down-jump probe honours the same bounded-drop rule the executor's fallback gates on
     * (DOWN_JUMP_MAX_DROP_PX): the live down-jump executor can never fire a deeper straight
     * drop, so a "depth-500 landing" must not count as an escape or the guard steers recoveries
     * onto surfaces whose only nominal exit no move can actually take. A plain walk-off fall
     * (simulateFallLanding) stays uncapped — walking off an edge is not depth-limited and the
     * fall-off-map recovery owns the no-landing case.
     */
    private static boolean fallEscape(MapleMap map, Foothold source, int floorY, int loX, int hiX,
                                      BotMovementProfile profile) {
        if (source.isForbidFallDown()) {
            return false;
        }
        // Probe density scales with width: a 1px midpoint on a wide ledge sits over the hole
        // while its edges land, and a 3px probe on a narrow one misses a narrow gap column.
        // ~1 probe per 64px (a walk step's scale) keeps the sim cost bounded — the verdict is
        // cached per foothold — while removing the width cliff the 1/3-probe split had.
        int width = hiX - loX;
        int probeXs = Math.max(1, Math.min(8, Math.round(width / 64.0f) + 1));
        for (int i = 0; i < probeXs; i++) {
            int x = probeXs == 1 ? (loX + hiX) / 2
                    : loX + Math.round(width * i / (float) (probeXs - 1));
            Point from = new Point(x, floorY);
            if (BotPhysicsEngine.simulateFallLanding(map, from, 0) != null) {
                return true;
            }
            BotPhysicsEngine.JumpLanding drop = BotPhysicsEngine.simulateDownJumpLanding(map, from);
            if (drop != null && drop.point().y - floorY <= BotNavigationGraphProvider.DOWN_JUMP_MAX_DROP_PX) {
                return true;
            }
        }
        return false;
    }

    /*
     * Ours: a rope whose climbable bottom reaches this floor (within the same slack the
     * up-direction grants) can be grabbed here and ridden down - an exit, not scenery.
     */
    private static boolean ropeBelow(MapleMap map, int floorY, int loX, int hiX) {
        for (Rope rope : map.getRopes()) {
            if (rope.bottomY() < floorY - ROPE_GRAB_SLACK_PX) {
                continue; // the rope ends too far above this floor to mount it here
            }
            if (ropeEnterableFromSurface(map, rope.x(), loX, hiX, floorY)) {
                return true;
            }
        }
        return false;
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

    private static boolean portalNear(MapleMap map, int floorY, int loX, int hiX) {
        for (Portal portal : map.getPortals()) {
            if (portal == null || portal.getPosition() == null) {
                continue;
            }
            Point p = portal.getPosition();
            if (floorY - PORTAL_Y_ABOVE_PX <= p.y && p.y <= floorY + PORTAL_Y_BELOW_PX
                    && loX - PORTAL_X_SLACK_PX <= p.x && p.x <= hiX + PORTAL_X_SLACK_PX) {
                return true;
            }
        }
        return false;
    }

    private static boolean ropeAbove(MapleMap map, int floorY, int loX, int hiX, int apex) {
        for (Rope rope : map.getRopes()) {
            int ropeTop = rope.topY();
            int ropeBottom = rope.bottomY();
            if (ropeBottom < floorY - apex - ROPE_GRAB_SLACK_PX) {
                continue; // the whole rope hangs too far above any jump
            }
            if (ropeTop > floorY) {
                continue; // rope entirely below the floor: nothing to climb up
            }
            if (ropeEnterableFromSurface(map, rope.x(), loX, hiX, floorY)) {
                return true;
            }
        }
        return false;
    }

    /*
     * Whether a rope at {@code ropeX} beside the surface [loX..hiX] at level {@code floorY} can
     * actually be reached from it. Within the grab column (ROPE_GRAB_X) the mount is direct;
     * beyond it the slack is only honest when the side-step's landing column has real ground at
     * the surface's own level — a rope hanging off the edge over a pit is NOT an exit, and
     * counting it was steering recoveries toward a leap into the void (the guard's own purpose).
     * The probe reuses the executor's ground lookup at the rope's x, judged by the SURFACE's own
     * level (not MAX_SLOPE_UP above): the bot steps off the edge, it does not float to the rope.
     */
    private static boolean ropeEnterableFromSurface(MapleMap map, int ropeX, int loX, int hiX, int floorY) {
        if (ropeX >= loX - BotPhysicsEngine.cfg.ROPE_GRAB_X
                && ropeX <= hiX + BotPhysicsEngine.cfg.ROPE_GRAB_X) {
            return true; // within grab distance of the surface itself
        }
        Point step = BotPhysicsEngine.findGroundPoint(map, new Point(ropeX, floorY));
        return step != null && Math.abs(step.y - floorY) <= BotPhysicsEngine.cfg.MAX_SLOPE_UP;
    }

    /*
     * Can the bot leave {@code foothold} by jumping up (possibly across a gap) onto something
     * higher, or by stepping/jumping onto a higher surface in the same connected chain? The
     * chain matters: a shallow terrace over a dead basin is only livable if ITSELF has an exit.
     * {@code visited} bounds the search: each surface is expanded once, so the worst case is
     * O(N^2) foothold scans for a map with N surfaces (computed once per foothold, behind the
     * verdict cache).
     */
    private static boolean jumpChainEscapes(List<Foothold> surfaces, Foothold foothold, int apex,
                                            Set<Integer> visited) {
        if (!visited.add(foothold.getId())) {
            return false;
        }
        int floorY = surfaceY(foothold);
        int loX = Math.min(foothold.getX1(), foothold.getX2());
        int hiX = Math.max(foothold.getX1(), foothold.getX2());
        for (Foothold higher : surfaces) {
            if (higher.getId() == foothold.getId() || higher.isWall()) {
                continue;
            }
            int higherY = surfaceY(higher);
            if (higherY >= floorY - STEP_UP_TOLERANCE_PX || higherY < floorY - apex) {
                continue; // not above the reach band (beyond trivial step tolerance, within apex)
            }
            int hLoX = Math.min(higher.getX1(), higher.getX2());
            int hHiX = Math.max(higher.getX1(), higher.getX2());
            int gap = horizontalGap(loX, hiX, hLoX, hHiX);
            if (gap > JUMP_ACROSS_PX) {
                continue;
            }
            // No surface may sit between this floor and the target band over the overlap -
            // otherwise the jump would land on the intermediate surface, which the chain
            // explores as its own foothold instead of assuming a pass-through.
            if (!columnClear(surfaces, floorY, higherY, loX, hiX, hLoX, hHiX)) {
                continue;
            }
            return true;
        }
        // No direct jump out: try climbing THROUGH adjacent surfaces at (roughly) this level
        // or up to one apex above, which may themselves have a jump/rope/portal exit.
        for (Foothold neighbour : surfaces) {
            if (neighbour.getId() == foothold.getId() || neighbour.isWall() || visited.contains(neighbour.getId())) {
                continue;
            }
            int neighbourY = surfaceY(neighbour);
            if (neighbourY > floorY + apex || neighbourY < floorY - apex) {
                continue;
            }
            int nLoX = Math.min(neighbour.getX1(), neighbour.getX2());
            int nHiX = Math.max(neighbour.getX1(), neighbour.getX2());
            if (horizontalGap(loX, hiX, nLoX, nHiX) > JUMP_ACROSS_PX) {
                continue;
            }
            if (jumpChainEscapes(surfaces, neighbour, apex, visited)) {
                return true;
            }
        }
        return false;
    }

    private static boolean columnClear(List<Foothold> surfaces, int floorY, int targetY,
                                       int loX, int hiX, int tLoX, int tHiX) {
        for (Foothold other : surfaces) {
            if (other.isWall()) {
                continue;
            }
            int oY = surfaceY(other);
            if (oY >= floorY - STEP_UP_TOLERANCE_PX || oY <= targetY) {
                continue; // not strictly inside the climbed band
            }
            int oLoX = Math.min(other.getX1(), other.getX2());
            int oHiX = Math.max(other.getX1(), other.getX2());
            if (overlap(loX, hiX, oLoX, oHiX) > 0 || overlap(tLoX, tHiX, oLoX, oHiX) > 0) {
                return false; // an intermediate surface would catch the jump first
            }
        }
        return true;
    }

    private static List<Foothold> walkableFootholds(MapleMap map) {
        // getAllFootholds() rebuilds its collection per call — call it ONCE per verdict (see
        // hasEscape) and thread the list through the scans below.
        return map.getFootholds().getAllFootholds();
    }

    private static int horizontalGap(int aLo, int aHi, int bLo, int bHi) {
        if (overlap(aLo, aHi, bLo, bHi) > 0) {
            return 0;
        }
        return Math.max(bLo - aHi, aLo - bHi);
    }

    private static int overlap(int aLo, int aHi, int bLo, int bHi) {
        return Math.min(aHi, bHi) - Math.max(aLo, bLo);
    }
}
