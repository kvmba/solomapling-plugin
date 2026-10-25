package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.client.Character;
import org.gms.constants.game.CharacterStance;
import org.gms.server.maps.Foothold;
import org.gms.server.maps.MapleMap;

import java.awt.Point;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Adapted from GreenCatMS bot movement-skill packets (NutNNut). Credit: NutNNut.
/*
 * Movement-skill locomotion for grinding bots: mage Teleport (blink) and Hermit/Night-Lord Flash Jump
 * (air dash). Picks a foothold landing, moves the bot there, and broadcasts the v83 MOVE_PLAYER fragments
 * that make observers SEE the blink/dash. This is the "GCMovementAdvanced" layer the audit named: the RE'd
 * constants and the client-verified fragment byte layouts come from GreenCatMS's reverse-engineering (see
 * Documents/Fable "Fable Handoff - 2026-07-06 GreenCatMS Fluid Combat & Movement-Skill Extraction.md").
 *
 * Our bots are ephemeral decoration, so there are NO MP/meso gates and no skill-tree checks — MovementStyle-
 * Policy decides who blinks/dashes by job, and this just does it. Teleport is modelled at max range (150)
 * always. The admired "mage AoEs then teleports to the next pack" look is emergent: the grind brain fires,
 * then routes its next approach leg through here.
 *
 * UNTESTED: the exact fragment byte layout is what renders the blink/dash on observing clients, and a
 * mismatched fragment/animation byte can CRASH observers. Live-test one mage + one hermit on an observed
 * map before scaling.
 *
 * Teleport is an instant blink (correct — a real teleport has no arc): it warps the bot and broadcasts the
 * cmd4/3/0 fragments here. Flash jump is NOT instant — it launches a real directional jump and arms
 * pendingFlashJump; the airborne integrator (BotPhysicsEngine.stepAirborne) injects the (±550,-350) dash
 * impulse at the apex and BotMovementManager.tickAirborne broadcasts the type-6 "fj" frame, so the physics
 * engine carries a genuine arc to a natural landing (faithful GreenCatMS behavior). Ours on top: the dash
 * is TIERED — flashJumpScale = level cap (FlashJumpTiers: young Hermit 0.75, NL/Hermit-100+ full) x
 * platform fit (largest arc whose travel fits the remaining ledge run), so small platforms get a visibly
 * short dash instead of an off-the-edge overshoot.
 */
final class GCMovementSkills {
    private GCMovementSkills() {
    }

    // RE'd client constants (GreenCatMS packet captures).
    static final int TELEPORT_RANGE_PX = 150;    // max blink distance; bots always modelled at max
    static final int TELEPORT_Y_SNAP_PX = 75;    // horizontal-blink vertical snap band
    static final int FLASH_JUMP_Y_SNAP_PX = 90;  // a dash tolerates a bit more height change than a blink

    // Largest learned tier whose simulated flight and braking stay on the launch surface.
    static final float[] FJ_SCALES = {1.0f, 0.75f, 0.55f};
    private static final int MOVE_DURATION_MS = 50; // per-fragment duration (matches the driver's broadcast tick)

    // v83 MovePath fragment command bytes.
    private static final byte CMD_ABSOLUTE = 0;
    private static final byte CMD_TELEPORT_DISAPPEAR = 3; // at destination
    private static final byte CMD_TELEPORT_APPEAR = 4;    // at origin

    // ── Teleport (mage blink) ─────────────────────────────────────────────────

    // Blink toward (targetX, targetY): pick a same-region landing up to TELEPORT_RANGE_PX toward the target,
    // warp the bot there, and broadcast the blink. Returns false (caller walks) if nothing valid is in range.
    static boolean execTeleport(BotMovementState st, Character bot, int targetX, int targetY) {
        MapleMap map = bot.getMap();
        if (map == null) {
            return false;
        }
        Point origin = bot.getPosition();
        if (origin == null) {
            return false;
        }
        // Mirrors execFlashJump: refuse while airborne or on a rope/ladder. A bot part-way up a
        // ladder that blinks at a mob above it (the vertical farY case) warps straight off the
        // ladder to the mob - the "teleported to the monster while climbing" glitch. Callers fall
        // back to walking, which finishes the climb first.
        if (st.inAir || st.climbing) {
            return false;
        }
        int dx = targetX - origin.x;
        int dy = targetY - origin.y;
        Point dest;
        if (Math.abs(dx) >= Math.abs(dy)) {
            dest = horizontalTeleportLanding(map, origin, dx >= 0 ? 1 : -1);
        } else if (dy > 0) {
            dest = downLanding(map, origin, st.movementProfile, BotMovementCapabilities.of(bot));
        } else {
            dest = upTeleportLanding(map, origin);
        }
        if (dest == null || (dest.x == origin.x && dest.y == origin.y)) {
            return false;
        }
        int hdir = dest.x >= origin.x ? 1 : -1;
        boolean downward = dest.y > origin.y + 8;
        int stance = downward ? proneStance(hdir) : standStance(hdir);
        BotPhysicsEngine.teleportTo(st, bot, dest);
        BotMovementManager.resetEntryStateAfterTeleport(st);
        if (ObserverTracker.isActiveMap(bot.getMapId())) {
            broadcastTeleport(bot, origin, dest, stance);
        }
        return true;
    }

    record TeleportApproach(BotNavigationGraph graph, int targetRegion, Point launch, Point landing, int direction) {
    }

    /** One blink connecting two ordinary routes. No invented position or map-specific waypoint. */
    static TeleportApproach findTeleportApproach(MapleMap map, BotNavigationGraph graph,
                                                 Point from, Point target) {
        int start = graph.findRegionId(map, from);
        int goal = graph.findRegionId(map, target);
        if (start < 0 || goal < 0 || graph.deadRegionIds.contains(goal)) return null;
        Map<Integer, List<Integer>> reverse = new HashMap<>();
        Map<Integer, List<Integer>> forward = new HashMap<>();
        for (var region : graph.regions) {
            for (var edge : graph.getOutgoing(region.id)) {
                if (edge.type == BotNavigationGraph.EdgeType.PORTAL) {
                    var portal = map.getPortal(edge.portalId);
                    if (portal == null || !portal.getPortalStatus()) continue;
                }
                reverse.computeIfAbsent(edge.toRegionId, ignored -> new ArrayList<>()).add(region.id);
                forward.computeIfAbsent(region.id, ignored -> new ArrayList<>()).add(edge.toRegionId);
            }
        }
        Set<Integer> reachesGoal = reachable(reverse, goal);
        Set<Integer> reachableFromStart = reachable(forward, start);
        TeleportApproach best = null;
        long bestCost = Long.MAX_VALUE;
        for (var region : graph.regions) {
            if (region.isRopeRegion || !reachableFromStart.contains(region.id) || reachesGoal.contains(region.id)) continue;
            for (var segment : region.segments) {
                for (int x = segment.minX; x <= segment.maxX; x++) {
                    Point launch = segment.pointAt(x);
                    Point[] landings = {upTeleportLanding(map, launch),
                            horizontalTeleportLanding(map, launch, -1), horizontalTeleportLanding(map, launch, 1),
                            downTeleportLanding(map, launch)};
                    for (int direction = 0; direction < landings.length; direction++) {
                        Point landing = landings[direction];
                        if (landing == null || !reachesGoal.contains(graph.findRegionId(map, landing))) continue;
                        long cost = Math.abs((long) from.x - x) + Math.abs((long) from.y - launch.y)
                                + Math.abs((long) target.x - landing.x) + Math.abs((long) target.y - landing.y);
                        if (cost < bestCost) {
                            bestCost = cost;
                            best = new TeleportApproach(graph, goal, launch, landing, direction);
                        }
                    }
                }
            }
        }
        return best;
    }

    private static Set<Integer> reachable(Map<Integer, List<Integer>> edges, int start) {
        Set<Integer> seen = new HashSet<>();
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        seen.add(start);
        queue.add(start);
        while (!queue.isEmpty()) {
            for (int next : edges.getOrDefault(queue.removeFirst(), List.of())) {
                if (seen.add(next)) queue.addLast(next);
            }
        }
        return seen;
    }

    static boolean executeTeleportApproach(BotMovementState entry, TeleportApproach approach) {
        Point from = entry.bot.getPosition();
        if (from.distanceSq(approach.launch()) > 64 || entry.inAir || entry.climbing
                || MovementClock.nowMs() < entry.attackActionUntilMs) return false;
        Point landing = approach.landing();
        // Revalidate at the actual pixel, not merely the route's representative launch.
        Point actual = switch (approach.direction()) {
            case 0 -> upTeleportLanding(entry.bot.getMap(), from);
            case 1 -> horizontalTeleportLanding(entry.bot.getMap(), from, -1);
            case 2 -> horizontalTeleportLanding(entry.bot.getMap(), from, 1);
            case 3 -> downTeleportLanding(entry.bot.getMap(), from);
            default -> throw new IllegalStateException("Unknown teleport direction");
        };
        if (actual == null || approach.graph().findRegionId(entry.bot.getMap(), actual)
                != approach.graph().findRegionId(entry.bot.getMap(), landing)) return false;
        BotPhysicsEngine.teleportTo(entry, entry.bot, actual);
        BotMovementManager.resetEntryStateAfterTeleport(entry);
        if (ObserverTracker.isActiveMap(entry.bot.getMapId())) broadcastTeleport(entry.bot, from, actual,
                standStance(Integer.compare(actual.x, from.x)));
        return true;
    }

    // ── Flash Jump (thief air dash) ───────────────────────────────────────────

    // scaleCap = the bot's level-tier dash cap (FlashJumpTiers via the caller); the platform fit below
    // may downshift under it, never over it.
    static boolean execFlashJump(BotMovementState st, Character bot, int targetX, float scaleCap) {
        MapleMap map = bot.getMap();
        if (map == null) {
            return false;
        }
        Point origin = bot.getPosition();
        if (origin == null || st.inAir || st.climbing) {
            return false; // no position, or already airborne/on a rope — don't stack a dash
        }
        int dir = targetX >= origin.x ? 1 : -1;
        float scale = fitScale(map, origin, dir, scaleCap, st.movementProfile);
        if (scale <= 0f) {
            return false; // no arc fits the remaining run — caller walks (or uses the plain engage hop)
        }
        // Launch a real directional jump toward the target, then arm the dash. stepAirborne injects the
        // scaled impulse at the trigger point (apex for scale 1, part-way up for smaller); tickAirborne
        // broadcasts the type-6 "fj" frame; the physics engine carries the arc to a natural landing.
        // Faithful GreenCatMS flash jump — NOT an instant snap.
        BotMovementManager.initiateJump(st, bot, targetX - origin.x);
        st.flashJumpScale = scale;
        st.pendingFlashJump = true;
        return true;
    }

    // Dev/calibration only (the !gcmove fj hook): fire a dash at an exact scale, bypassing the level cap
    // and the platform fit, so real travel distances can be measured in-game.
    static boolean execFlashJumpForced(BotMovementState st, Character bot, int dir, float scale) {
        Point origin = bot.getPosition();
        if (bot.getMap() == null || origin == null || st.inAir || st.climbing) {
            return false;
        }
        BotMovementManager.initiateJump(st, bot, dir >= 0 ? 1 : -1);
        st.flashJumpScale = Math.max(0.2f, Math.min(scale, 1f));
        st.pendingFlashJump = true;
        return true;
    }

    static float fitScale(MapleMap map, Point origin, int dir, float cap, BotMovementProfile profile) {
        Foothold source = BotPhysicsEngine.findGroundFoothold(map, origin);
        if (source == null) return 0f;
        for (float scale : FJ_SCALES) {
            if (scale > cap + 0.001f) continue;
            BotPhysicsEngine.PostLandingJump stopped = BotPhysicsEngine.simulateLandingStop(map,
                    BotPhysicsEngine.simulateFlashJumpLanding(map, origin, dir, profile, scale), profile);
            Point onSource = stopped == null ? null : BotPhysicsEngine.findWalkRegionGroundPoint(map, source,
                    stopped.finalPoint().x, stopped.finalPoint().y);
            if (stopped != null && !stopped.lostGround() && onSource != null
                    && onSource.equals(stopped.finalPoint())) return scale;
        }
        return 0f;
    }

    // ── Landing math (foothold-only; ported concept from BotPhysicsEngine.teleportLanding) ──
    // Package-visible: the dead-pit guard reuses the SAME landing math as an escape probe, so the
    // verdict and the executor can never disagree about where a blink ends up.

    // Nearest same-level (±TELEPORT_Y_SNAP_PX) foothold up to TELEPORT_RANGE_PX toward `dir`,
    // stepping the reach inward if the full-range spot is off the ledge (keeps the blink on the
    // bot's own platform instead of into a gap).
    static Point horizontalTeleportLanding(MapleMap map, Point origin, int dir) {
        for (int reach = TELEPORT_RANGE_PX; reach >= 40; reach -= 25) {
            int tx = origin.x + dir * reach;
            Point g = GCMovement.groundPointBelow(map, tx, origin.y - 4);
            if (g != null && Math.abs(g.y - origin.y) <= TELEPORT_Y_SNAP_PX) {
                return g;
            }
        }
        return null;
    }

    // Furthest platform within range directly above (mirror of downLanding). No foothold-tree "findAbove"
    // exists, so this goes through the bucket-index scan added to BotPhysicsEngine. Null -> caller walks.
    static Point upTeleportLanding(MapleMap map, Point origin) {
        return BotPhysicsEngine.findGroundPointAbove(map, origin, TELEPORT_RANGE_PX);
    }

    // Furthest platform within range directly below (walk platform-by-platform, keep the last in range) —
    // the RAW landing ladder; the executor's livability-filtered variant is downLanding below.
    static Point downTeleportLanding(MapleMap map, Point origin) {
        Point best = null;
        Point probe = BotPhysicsEngine.pointBelowIndexed(map, new Point(origin.x, origin.y + 1));
        while (probe != null && probe.y > origin.y && probe.y - origin.y <= TELEPORT_RANGE_PX) {
            best = probe;
            probe = BotPhysicsEngine.pointBelowIndexed(map, new Point(origin.x, probe.y + 1));
        }
        return best;
    }

    // The executor's blink-down landing: the deepest platform in range may be a basin floor the bot
    // can never leave (no real exit), so walk the ladder back UP to the deepest LIVABLE platform
    // still within blink range under THIS bot's own movement profile / class abilities — or give up
    // the blink entirely: never into a one-way trap, and never past the range contract.
    private static Point downLanding(MapleMap map, Point origin, BotMovementProfile profile,
                                     BotMovementCapabilities capabilities) {
        Point best = null;
        Point probe = BotPhysicsEngine.pointBelowIndexed(map, new Point(origin.x, origin.y + 1));
        while (probe != null && probe.y > origin.y && probe.y - origin.y <= TELEPORT_RANGE_PX) {
            if (DeadPitGuard.isLivableLanding(map, probe, profile, capabilities)) best = probe;
            probe = BotPhysicsEngine.pointBelowIndexed(map, new Point(origin.x, probe.y + 1));
        }
        return best;
    }

    // ── Broadcast (client-verified fragment layouts; see the Fable handoff doc Appendix A) ──

    // 3 fragments: cmd4 @ origin (origin fh), cmd3 @ dest (fh 0 = arrival airborne), cmd0 settle @ dest.
    // Also the recovery-snap renderer (see teleportCut) — a bare cmd-0 would slide the sprite there.
    static void broadcastTeleport(Character bot, Point origin, Point dest, int stance) {
        MapleMap map = bot.getMap();
        BotMovementManager.broadcastRawMovement(bot, teleportPath(map, origin, dest, stance));
    }

    // The fragment path itself: [numCommands][cmd4 @ origin][cmd3 @ dest][cmd0 settle @ dest].
    // Pure byte assembly — pulled out of broadcastTeleport so the layout is directly testable
    // (a wrong fragment order or count is exactly how the sprite silently teleports instead of blinking).
    // fh per the SHARED fh rule (BotMovementManager.resolveBroadcastFhId / BotPetFollower's header):
    // the ground id on land, 0 while airborne (the cmd3 appear-fragment lands mid-air -> fh 0).
    static byte[] teleportPath(MapleMap map, Point origin, Point dest, int stance) {
        byte[] data = new byte[1 + 10 + 10 + 14];
        int i = 0;
        data[i++] = 3; // numCommands
        i = putTeleportFrag(data, i, CMD_TELEPORT_APPEAR, origin.x, origin.y, footholdIdAt(map, origin), stance);
        i = putTeleportFrag(data, i, CMD_TELEPORT_DISAPPEAR, dest.x, dest.y, 0, stance); // mid-air: fh 0
        putAbsoluteFrag(data, i, dest.x, dest.y, 0, 0, footholdIdAt(map, dest), stance, MOVE_DURATION_MS);
        return data;
    }

    /*
     * Render a position CUT the driver did with teleportTo (grind anchor return, wedge repair,
     * companion position repair) — moves the bot did for recovery reasons, which historically went
     * out as a single absolute cmd-0 fragment and so arrived at observers as a silent sprite jump.
     *
     * Reuses the mage blink's disappear/appear fragments so the client plays a puff at the origin
     * and a reappear at the destination instead. Falls back to the driver's plain broadcast for a
     * sub-frame cut (see TeleportCutPolicy). Callers must have already moved the bot to `dest`.
     */
    static void teleportCut(BotMovementState st, Character bot, Point origin, Point dest) {
        if (bot == null || bot.getMap() == null) {
            return;
        }
        int hdir = (origin != null && dest != null && dest.x < origin.x) ? -1 : 1;
        int stance = (origin != null && dest != null && dest.y > origin.y + 8)
                ? proneStance(hdir)
                : standStance(hdir);
        broadcastTeleport(bot, origin, dest, stance);
        // Re-seed the driver's dedup snapshot from the rendered pose, or its next plain broadcast
        // compares against pre-cut coords and re-emits the same position as a second jump.
        if (st != null) {
            BotMovementManager.invalidateBroadcastSnapshot(st);
        }
    }

    // cmd 0 absolute: x,y,velX,velY,fh,stance,duration (same layout as sendMovementPacket's single fragment).
    private static int putAbsoluteFrag(byte[] d, int i, int x, int y, int velX, int velY, int fh, int stance, int durMs) {
        d[i++] = CMD_ABSOLUTE;
        i = putShort(d, i, x);
        i = putShort(d, i, y);
        i = putShort(d, i, velX);
        i = putShort(d, i, velY);
        i = putShort(d, i, fh);
        d[i++] = (byte) stance;
        return putShort(d, i, durMs);
    }

    // cmd 3/4: authored for the v83 CLIENT decode — x,y,fh,stance,elapse(=0 blink) — NOT the server parse
    // layout (x,y,xwobble,ywobble,stance). Wrong layout makes observers read elapse=stance<<8 and the frame
    // lingers ~1s. See the Fable handoff doc / GreenCatMS kb_bot_movement_skills_teleport_flashjump.
    private static int putTeleportFrag(byte[] d, int i, byte cmd, int x, int y, int fh, int stance) {
        d[i++] = cmd;
        i = putShort(d, i, x);
        i = putShort(d, i, y);
        i = putShort(d, i, fh);
        d[i++] = (byte) stance;
        return putShort(d, i, 0); // elapse = 0
    }

    private static int putShort(byte[] d, int i, int v) {
        d[i] = (byte) (v & 0xFF);
        d[i + 1] = (byte) ((v >> 8) & 0xFF);
        return i + 2;
    }

    private static int footholdIdAt(MapleMap map, Point p) {
        Foothold fh = BotPhysicsEngine.findGroundFoothold(map, p);
        return fh != null ? fh.getId() : 0;
    }

    private static int standStance(int dir) {
        return dir < 0 ? CharacterStance.STAND_LEFT_STANCE : CharacterStance.STAND_RIGHT_STANCE;
    }

    private static int proneStance(int dir) {
        return dir < 0 ? CharacterStance.PRONE_LEFT_STANCE : CharacterStance.PRONE_RIGHT_STANCE;
    }
}
