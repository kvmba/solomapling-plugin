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
 * escapable - no portal, no rope to grab, and no jump chain (apex-bounded) back up to a
 * higher surface. The semantics mirror scripts/wzaudit/dead_pit_scan.py, evaluated at
 * runtime from the live foothold tree.
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
    /** A rope this far off to the side still counts as enterable from the landing. */
    private static final int ROPE_X_SLACK_PX = 60;
    /** A portal within this box of the landing counts as an exit. */
    private static final int PORTAL_X_SLACK_PX = 30;
    private static final int PORTAL_Y_ABOVE_PX = 80;
    private static final int PORTAL_Y_BELOW_PX = 30;

    /** mapId -> foothold id -> verdict. Footholds are immutable per map load; maps reload rarely. */
    private static final Map<Integer, Map<Integer, Boolean>> VERDICTS = new ConcurrentHashMap<>();
    /** Guards against re-entrant verdict computation on the same foothold (cycles in the chain). */
    private static final Map<Integer, Set<Integer>> IN_PROGRESS = new ConcurrentHashMap<>();

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
        if (map == null || foothold == null) {
            return true;
        }
        Map<Integer, Boolean> verdicts = VERDICTS.computeIfAbsent(map.getId(), k -> new ConcurrentHashMap<>());
        Boolean cached = verdicts.get(foothold.getId());
        if (cached != null) {
            return cached;
        }
        Set<Integer> inProgress = IN_PROGRESS.computeIfAbsent(map.getId(), k -> ConcurrentHashMap.newKeySet());
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

    private static boolean hasEscape(MapleMap map, Foothold foothold, BotMovementProfile profile) {
        int floorY = foothold.getY1();
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
        if (highestSurfaceY(map) >= floorY) {
            return true;
        }
        return jumpChainEscapes(map, foothold, apex, new java.util.HashSet<>());
    }

    private static int highestSurfaceY(MapleMap map) {
        // The TOPMOST walkable surface = the smallest surface y (y grows downward).
        int topmost = Integer.MAX_VALUE;
        for (Foothold foothold : walkableFootholds(map)) {
            if (foothold.isWall()) {
                continue;
            }
            topmost = Math.min(topmost, foothold.getY1());
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
            if (loX - ROPE_X_SLACK_PX <= rope.x() && rope.x() <= hiX + ROPE_X_SLACK_PX) {
                return true;
            }
        }
        return false;
    }

    /*
     * Can the bot leave {@code foothold} by jumping up (possibly across a gap) onto something
     * higher, or by stepping/jumping onto a higher surface in the same connected chain? The
     * chain matters: a shallow terrace over a dead basin is only livable if ITSELF has an exit.
     * {@code visited} bounds the search: each surface is expanded once, so the worst case is
     * O(N^2) foothold scans for a map with N surfaces (computed once per foothold, behind the
     * verdict cache).
     */
    private static boolean jumpChainEscapes(MapleMap map, Foothold foothold, int apex,
                                            Set<Integer> visited) {
        if (!visited.add(foothold.getId())) {
            return false;
        }
        int floorY = foothold.getY1();
        int loX = Math.min(foothold.getX1(), foothold.getX2());
        int hiX = Math.max(foothold.getX1(), foothold.getX2());
        for (Foothold higher : walkableFootholds(map)) {
            if (higher.getId() == foothold.getId() || higher.isWall()) {
                continue;
            }
            int higherY = higher.getY1();
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
            if (!columnClear(map, floorY, higherY, loX, hiX, hLoX, hHiX)) {
                continue;
            }
            return true;
        }
        // No direct jump out: try climbing THROUGH adjacent surfaces at (roughly) this level
        // or up to one apex above, which may themselves have a jump/rope/portal exit.
        for (Foothold neighbour : walkableFootholds(map)) {
            if (neighbour.getId() == foothold.getId() || neighbour.isWall() || visited.contains(neighbour.getId())) {
                continue;
            }
            int neighbourY = neighbour.getY1();
            if (neighbourY > floorY + apex || neighbourY < floorY - apex) {
                continue;
            }
            int nLoX = Math.min(neighbour.getX1(), neighbour.getX2());
            int nHiX = Math.max(neighbour.getX1(), neighbour.getX2());
            if (horizontalGap(loX, hiX, nLoX, nHiX) > JUMP_ACROSS_PX) {
                continue;
            }
            if (jumpChainEscapes(map, neighbour, apex, visited)) {
                return true;
            }
        }
        return false;
    }

    private static boolean columnClear(MapleMap map, int floorY, int targetY,
                                       int loX, int hiX, int tLoX, int tHiX) {
        List<Foothold> all = walkableFootholds(map);
        for (Foothold other : all) {
            if (other.isWall()) {
                continue;
            }
            int oY = other.getY1();
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
        // getAllFootholds() rebuilds a list per call; acceptable here because every caller is
        // behind the per-foothold verdict cache (computed once per foothold per map load).
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

    static void invalidate(int mapId) {
        VERDICTS.remove(mapId);
        IN_PROGRESS.remove(mapId);
    }
}
