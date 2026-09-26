package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.client.Character;
import org.gms.constants.game.CharacterStance;
import org.gms.server.maps.Foothold;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Rope;
import soloMapling.ArtificialPlayer.BotMovementSystem.MovementCommands;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/*
 * Public façade for GCMoveSystem — the GreenCat dynamic (calculation-based) movement engine.
 * This is the only public class in the package; everything below it (physics, nav,
 * graph baking, packet emission) is package-private plumbing.
 *
 * Two capabilities, both fire-and-forget and non-blocking:
 * 
 *   .move(Character, int, int) — walk/jump/climb to a point on the current map.
 *   .follow(Character, Character) — dynamically tail a character.
 * 
 * Both drive a raw org.gms.client.Character, so any BotSM subtype calls them via
 * getChr(), exactly like the recorded-path MovementCommands. The two engines
 * coexist via the shared MovementCommands movement lock (acquired on enable, released on
 * disable).
 */
// Ported from GreenCatMS. Credit: NutNNut.
public final class GCMovement {
    private GCMovement() {
    }

    private static final Map<Integer, BotMovementState> STATES = new ConcurrentHashMap<>();
    private static final Map<Integer, Runnable> ARRIVAL_CALLBACKS = new ConcurrentHashMap<>();

    // ── Lifecycle ───────────────────────────────────────────────────────────

    /* Put a bot under dynamic control: build its profile, warm the map graph, start the tick. */
    public static void enable(Character bot) {
        if (bot == null) {
            return;
        }
        ObserverTracker.ensureStarted(); // LOD observability poll (idempotent)
        // A handover the PREVIOUS session deferred must not survive into this one. disable() sets
        // disableAfterLanding when the bot is airborne and leaves the entry alive to finish the
        // fall; if the bot is re-enabled before it lands, computeIfAbsent hands back that same
        // entry WITHOUT running the creation lambda - so the flag has to be cleared here, outside
        // it. Otherwise the stale flag would stop this fresh session the moment the bot next
        // touches the ground.
        BotMovementState resumed = STATES.get(bot.getId());
        if (resumed != null) {
            resumed.disableAfterLanding = false;
        }
        STATES.computeIfAbsent(bot.getId(), id -> {
            // owner == null by default (no follow anchor, and avoids the nav warmup notice trying to
            // dropMessage through the shared BotClient). GCFollow sets owner to the followed character.
            BotMovementState st = new BotMovementState(bot, null);
            st.movementProfile = BotMovementProfile.fromCharacter(bot);
            if (bot.getMap() != null) {
                st.lastMapId = bot.getMapId();
                st.fhIndex = BotMovementManager.buildFhIndex(bot.getMap());
                Point cur = bot.getPosition();
                Point ground = BotPhysicsEngine.findGroundPoint(bot.getMap(), new Point(cur.x, cur.y - 1));
                BotPhysicsEngine.teleportTo(st, bot, settledPoint(cur, ground));
                BotMovementManager.resetEntryStateAfterTeleport(st);
                BotNavigationGraphProvider.warmGraphAsync(bot.getMap(), st.movementProfile);
            }
            GCMovementDriver.start(st);
            // Hold the shared movement lock for the whole dynamic session so the recorded-path
            // engine can't drive this bot concurrently.
            MovementCommands.tryAcquireMovementLock(bot);
            return st;
        });
    }

    /* Remove a bot from dynamic control and release the shared movement lock. */
    public static void disable(Character bot) {
        if (bot == null) {
            return;
        }
        GCFollow.cancel(bot);
        GCTravel.cancel(bot);
        GCFidget.cancel(bot);
        BotMovementState st = STATES.remove(bot.getId());
        if (st != null) {
            // Settle into the idle stance BEFORE the state is dropped. A bot that is still
            // mid-walk carries a WALK stance, and nothing would ever clear it: this entry is
            // about to be removed, so the tick that would have run idleOnGround() can no
            // longer reach it. The client keeps rendering the walk animation on a bot that is
            // standing still - the "walking on the spot" town bot. Worst on maps with no
            // movement recordings, where the old engine never sends a packet that overwrites it.
            //
            // inAir / climbing count too, not just movement intent: a jump clears moveDir on
            // takeoff (see BotPhysicsEngine), so a bot that hopped in place and is then disabled
            // has moveDir == 0 but is still airborne - and airborne is what makes the stance
            // render as JUMP. Testing only moveDir left those bots frozen in the jump pose.
            //
            // portalDropAtMs counts too: onMapChange lifts the bot and arms the drop with inAir
            // still FALSE (teleportTo -> clearMovementState), so for that window the bot is
            // suspended but not "airborne" yet. Without it here the whole branch is skipped and
            // finishDisable stops the driver on a bot hanging at the float point with the drop
            // never released - the jump-pose freeze, with nothing left to tick it down.
            // lastMapId counts too: it trails the bot's live map until onMapChange returns (see the
            // assignment at the end of that handler), so it flags a map change the driver has not
            // finished processing - the window in which the arrival is neither airborne nor yet
            // armed with a drop.
            if (st.moveDir != 0 || st.groundBrakeDir != 0 || st.inAir || st.climbing
                    || st.portalDropAtMs > 0L || st.lastMapId != st.bot.getMapId()) {
                // Mid-air: hand the bot over LATE instead of settling it here. A real player keeps
                // falling to the floor frame by frame; forcing the landing was a visible teleport,
                // and cutting the session mid-air was what froze bots in the jump pose. So keep the
                // driver running and let the physics land it, then finish the handover (see
                // GCMovementDriver.tick). Covers every caller at once - notably the stroll return,
                // whose travel-finish callback fires within ~300ms of the map change while the
                // map-entry float (inAir, ~60px up, 1.5-2.1s hold) is still in flight.
                //
                // On a rope: leave the pose ALONE. Hanging on a rope is a legitimate place to
                // stand, so snapping a climber down to the floor would read as it falling off -
                // the rope coords plus the ROPE stance are what it is really doing. Whoever takes
                // over re-enables GC control, and enable() resolves the position from there.
                if (!st.climbing) {
                    if (st.inAir || st.portalDropAtMs > 0L || st.lastMapId != st.bot.getMapId()) {
                        st.disableAfterLanding = true;
                        // Put the state BACK: the driver keeps ticking it until it lands, and it
                        // must stay the live entry for that. Leaving it removed would make
                        // isEnabled() false while the old entry is still driving, so a later
                        // enable() would build a SECOND state and start a second driver on the
                        // same bot - and the deferred finish would then find no state to hand over.
                        // Idempotent: disable() called twice while the fall is still in flight
                        // (a teardown racing the stroll-return callback) just re-states the same
                        // intent - the driver still owns landing it and finishing the handover.
                        //
                        // lastMapId != bot.getMapId() is the race this branch used to lose: a
                        // disable() landing between the warp and the driver's onMapChange saw
                        // neither inAir nor a pending drop, took the settle+stop path, and stopped
                        // the driver before it could run the arrival at all - leaving the bot
                        // parked at the portal with nothing left to drop it. Deferring instead
                        // lets onMapChange play the entry (float -> drop) and hand over on landing.
                        STATES.put(bot.getId(), st);
                        return; // the driver calls finishDeferredDisable once it has landed
                    }
                    settleGroundedOnDisable(st, bot);
                }
            }
            finishDisable(st, bot);
        }
        ARRIVAL_CALLBACKS.remove(bot.getId());
    }

    /* Shared tail of .disable(): stop the driver and release the lock the old engine needs. */
    private static void finishDisable(BotMovementState st, Character bot) {
        GCMovementDriver.stop(st);
        MovementCommands.releaseMovementLock(bot);
    }

    /*
     * Driver hook: a disable() deferred by an airborne bot has now landed. Settle the stance and
     * finish the handover, so the bot is left standing on the floor exactly as a real player would
     * be after the drop - no forced snap, no mid-air freeze.
     */
    static void finishDeferredDisable(Character bot) {
        if (bot == null) {
            return;
        }
        BotMovementState st = STATES.remove(bot.getId());
        if (st == null) {
            return; // already taken over (re-enabled, or disabled again) - nothing to finish
        }
        // A fall can end by grabbing a rope instead of touching down. The rope pose and coords are
        // what the bot is really doing, so leave them alone (same rule as disable()'s climbing
        // path): settling here would render it standing on nothing at the rope's mid-air Y.
        if (!st.climbing) {
            settleGroundedOnDisable(st, bot);
        }
        finishDisable(st, bot);
        ARRIVAL_CALLBACKS.remove(bot.getId());
    }

    /* Idle stance + one last frame for a bot that is standing when its session ends. */
    private static void settleGroundedOnDisable(BotMovementState st, Character bot) {
        // The settle is the LAST thing this bot's movement session does, so it must leave the bot on
        // real ground, not merely in a standing pose. A session can end while the bot is still above its
        // floor: the arrival float lifts it PORTAL_FLOAT_HEIGHT_PX up and arms the drop with inAir still
        // false, and the off-map recovery teleports to the VR-top fallback when nothing is below. Settling
        // there left the bot in mid-air with the driver stopped and its state removed - nothing left to
        // drop it: the "bot frozen in the air" report. Re-resolve the floor under it exactly as enable()
        // does on entry (the shared settledPoint rule), so the two can never drift.
        MapleMap map = bot.getMap();
        if (map != null) {
            Point position = bot.getPosition();
            Point ground = BotPhysicsEngine.findGroundPoint(map, new Point(position.x, position.y - 1));
            Point settled = settledPoint(position, ground);
            if (!settled.equals(position)) {
                BotPhysicsEngine.teleportTo(st, bot, settled);
            }
        }
        BotPhysicsEngine.idleOnGround(st, bot);
        BotMovementManager.broadcastMovement(st);
    }

    /**
     * Where a session-final settle should leave the bot: the ground under it when there is any, else
     * where it already is. Pure so the rule is unit-testable without a map (a real {@code MapleMap}
     * cannot be constructed in a test - see GroundSwayTest), and so the "never invent a position" half
     * of it is pinned: a null ground (nothing below) keeps the current point.
     */
    static Point settledPoint(Point position, Point ground) {
        return ground != null ? ground : position;
    }

    public static boolean isEnabled(Character bot) {
        return bot != null && STATES.containsKey(bot.getId());
    }

    /* Package-private snapshot of the enabled dynamic states (for LodMetrics reporting). */
    static java.util.Collection<BotMovementState> enabledStates() {
        return new java.util.ArrayList<>(STATES.values());
    }

    // ── Commands ────────────────────────────────────────────────────────────

    /* Walk/jump/climb to (x,y) on the bot's current map, then idle. */
    public static void move(Character bot, int x, int y) {
        move(bot, x, y, null);
    }

    /* As .move(Character, int, int) with an arrival callback. */
    public static void move(Character bot, int x, int y, Runnable onArrival) {
        if (bot == null) {
            return;
        }
        enable(bot);
        BotMovementState st = STATES.get(bot.getId());
        if (st == null) {
            return;
        }
        st.following = false;
        st.farmAnchor = null;
        st.farmAnchorMapId = -1;
        st.moveTarget = new Point(x, y);
        st.moveTargetPrecise = true;
        st.moveTargetSource = "gcmove";
        st.moveBestDist = Integer.MAX_VALUE;
        st.moveProgressAtMs = System.currentTimeMillis();
        if (onArrival != null) {
            ARRIVAL_CALLBACKS.put(bot.getId(), onArrival);
        } else {
            ARRIVAL_CALLBACKS.remove(bot.getId());
        }
    }

    /* Anchor at (x,y): walk there and hold position (sentry). */
    public static void farmHere(Character bot, int x, int y) {
        if (bot == null) {
            return;
        }
        enable(bot);
        BotMovementState st = STATES.get(bot.getId());
        if (st == null) {
            return;
        }
        st.following = false;
        st.farmAnchor = new Point(x, y);
        st.farmAnchorMapId = bot.getMapId();
        st.moveTarget = new Point(x, y);
        st.moveTargetPrecise = true;
        st.moveBestDist = Integer.MAX_VALUE;
        st.moveProgressAtMs = System.currentTimeMillis();
    }

    /* Dynamically tail a character — including ACROSS maps (travels to the target's map when they
     *  portal away, then resumes following on arrival). */
    public static void follow(Character bot, Character target) {
        if (bot == null || target == null) {
            return;
        }
        enable(bot);
        GCFollow.start(bot, target);
    }

    public static boolean isFollowing(Character bot) {
        return GCFollow.isFollowing(bot);
    }

    /* Cancel the current move/follow/travel; the bot idles in place (stays under dynamic control). */
    public static void stop(Character bot) {
        if (bot == null) {
            return;
        }
        GCFollow.cancel(bot);
        GCTravel.cancel(bot);
        clearMoveIntent(bot);
        BotMovementState st = STATES.get(bot.getId());
        if (st != null) {
            st.following = false;
            st.owner = null;
        }
    }

    /* Hard-teleport the bot to solid ground at/under (x,y) on its current map, resetting nav state so the
     * driver resumes cleanly from the new spot. For recovery when a bot is wedged and can't path out.
     *
     * Visible cuts are rendered as a blink (vanish at the origin, reappear at the destination) rather
     * than a plain position update — recovery snaps used to go out as a single absolute fragment, which
     * the client applies in one frame as a silent sprite jump: the "the bot just teleported" glitch.
     * Sub-frame corrections and unobserved maps still take the cheap plain path. */
    public static void teleportTo(Character bot, int x, int y) {
        if (bot == null) {
            return;
        }
        enable(bot);
        BotMovementState st = STATES.get(bot.getId());
        if (st == null) {
            return;
        }
        Point origin = bot.getPosition();
        Point ground = BotPhysicsEngine.findGroundPoint(bot.getMap(), new Point(x, y));
        Point dest = (ground != null) ? ground : new Point(x, y);
        BotPhysicsEngine.teleportTo(st, bot, dest);
        BotMovementManager.resetEntryStateAfterTeleport(st);
        switch (TeleportCutPolicy.choose(ObserverTracker.isActiveMap(bot.getMapId()), origin, dest)) {
            case ANIMATED -> GCMovementSkills.teleportCut(st, bot, origin, dest);
            case NONE -> {
                // Unobserved: keep the broadcast suppressed, but leave the snapshot stale so the
                // first observed tick re-announces the new position (mirrors doBroadcastMovement).
                BotMovementManager.invalidateBroadcastSnapshot(st);
            }
            default -> BotMovementManager.broadcastMovement(st);
        }
    }

    /* Flag the bot as combat-alerted so it renders the 5s ALERT pose. The observing client already starts
     * its own alert timer when it renders our attack packet; this keeps the bot's OWN movement broadcasts
     * carrying ALERT instead of STAND for the duration, so a following idle/move packet doesn't cancel the
     * pose. Called by the attack layer after each swing. No-op if the bot isn't under GC control. */
    public static void markAlerted(Character bot) {
        if (bot == null) {
            return;
        }
        BotMovementState st = STATES.get(bot.getId());
        if (st != null) {
            BotContactDamage.markAlerted(st);
        }
    }

    /* Mage blink toward a point on the current map: pick a same-ledge landing up to ~150px toward it and
     * broadcast the teleport (or blink down to a lower platform). Returns true if it blinked, false if there's
     * no valid landing (caller walks). The caller (MovementStylePolicy / grind approach) decides who may
     * teleport — no MP/skill checks here (bots are decoration). */
    public static boolean teleport(Character bot, int targetX, int targetY) {
        if (bot == null) {
            return false;
        }
        BotMovementState st = STATES.get(bot.getId());
        return st != null && GCMovementSkills.execTeleport(st, bot, targetX, targetY);
    }

    /* Thief flash-jump (air dash) toward a target X on the current map. scaleCap = the bot's level-tier
     * dash cap (FlashJumpTiers; 1 = full maxed dash) — the platform fit may downshift under it so a small
     * platform gets a short dash. Returns true if it dashed, false if no arc fits (caller walks). */
    public static boolean flashJump(Character bot, int targetX, float scaleCap) {
        if (bot == null) {
            return false;
        }
        BotMovementState st = STATES.get(bot.getId());
        return st != null && GCMovementSkills.execFlashJump(st, bot, targetX, scaleCap);
    }

    /* Dev/calibration (the !gcmove fj hook): fire one flash jump toward dir (+1/-1) at an exact scale,
     * bypassing the level cap and platform fit, so real dash travel can be measured on flat ground. */
    public static boolean debugFlashJump(Character bot, int dir, float scale) {
        if (bot == null) {
            return false;
        }
        BotMovementState st = STATES.get(bot.getId());
        return st != null && GCMovementSkills.execFlashJumpForced(st, bot, dir, scale);
    }

    /* The bot's current facing under dynamic control: true = facing left, false = right, or null when it
     * isn't GC-driven. Used by the grind brain's turn-around micro-beat (face+step before swinging). */
    public static Boolean isFacingLeft(Character bot) {
        if (bot == null) {
            return null;
        }
        BotMovementState st = STATES.get(bot.getId());
        return (st == null) ? null : st.facingDir < 0;
    }

    // ── Package helpers (used by GCTravel / GCFollow) ────────────────────────

    /* Clear only the move/farm target + nav (NOT follow or travel). GCTravel uses this between hops
     *  and on arrival so it never tears down an active follow session. */
    static void clearMoveIntent(Character bot) {
        BotMovementState st = bot == null ? null : STATES.get(bot.getId());
        if (st == null) {
            return;
        }
        st.moveTarget = null;
        st.moveTargetPrecise = false;
        st.farmAnchor = null;
        st.farmAnchorMapId = -1;
        BotMovementManager.clearNavigationState(st);
        ARRIVAL_CALLBACKS.remove(bot.getId());
    }

    /* GCFollow: target is on the bot's map — arm same-map follow (the driver does the walking). */
    static void armSameMapFollow(Character bot, Character target) {
        BotMovementState st = bot == null ? null : STATES.get(bot.getId());
        if (st == null || target == null) {
            return;
        }
        st.owner = target;
        st.followTargetId = target.getId();
        st.following = true;
        st.moveTarget = null;
        st.farmAnchor = null;
        st.farmAnchorMapId = -1;
    }

    /* GCFollow: target is on another map — pause same-map follow while the bot travels there. */
    static void pauseFollowForTravel(Character bot) {
        BotMovementState st = bot == null ? null : STATES.get(bot.getId());
        if (st != null) {
            st.following = false;
        }
    }

    /* GCFollow: the follow session ended (target gone) — clear follow state. */
    static void endFollowState(Character bot) {
        BotMovementState st = bot == null ? null : STATES.get(bot.getId());
        if (st != null) {
            st.following = false;
            st.owner = null;
        }
    }

    // ── Cross-map travel (GCTravel) ─────────────────────────────────────────

    /* Travel to another map by chaining GCMove-to-portal + portal entry (warp special hops). */
    public static void travel(Character bot, int destMapId) {
        GCTravel.travel(bot, destMapId, null);
    }

    /* As .travel(Character, int) with a success/fail callback fired on arrival/abort. */
    public static void travel(Character bot, int destMapId, java.util.function.Consumer<Boolean> onDone) {
        GCTravel.travel(bot, destMapId, onDone);
    }

    public static boolean isTraveling(Character bot) {
        return GCTravel.isTraveling(bot);
    }

    /*
     * True while a trip is parked for a scheduled ride rather than walking: waiting for boarding to
     * open, or standing on the deck mid-crossing. A boarding wait can run minutes, so callers that
     * treat stillness as a stall must exempt it rather than cancel the trip.
     */
    public static boolean isWaitingForTransit(Character bot) {
        return GCTravel.isWaitingForTransit(bot);
    }

    /* Maps reachable by walking through ONE portal from mapId (the visually-adjacent maps). Empty
     * until the world graph happens to exist — this never triggers the graph build, so callers get
     * "nowhere to go" rather than paying a multi-second scan. Excludes taxi/scripted hops: these are
     * the neighbours a bot can reach by physically stepping through a portal. */
    public static int[] walkableNeighbors(int mapId) {
        return GCWorldGraph.portalNeighbors(mapId);
    }

    public static void cancelTravel(Character bot) {
        GCTravel.cancel(bot);
    }

    /*
     * Travel to another map and then walk to (x,y) on it — e.g. "come to where I am".
     * Cross-map hops + final in-map navigation in one call. Captures nothing itself; the caller
     * passes the destination point (typically the commanding player's position at command time).
     */
    public static void travelTo(Character bot, int mapId, int x, int y) {
        travelTo(bot, mapId, x, y, null);
    }

    public static void travelTo(Character bot, int mapId, int x, int y, java.util.function.Consumer<Boolean> onDone) {
        if (bot == null) {
            return;
        }
        Runnable arrive = onDone == null ? null : () -> onDone.accept(true);
        if (bot.getMap() != null && bot.getMapId() == mapId) {
            move(bot, x, y, arrive); // already on the map — just navigate to the spot
            return;
        }
        GCTravel.travel(bot, mapId, ok -> {
            if (ok) {
                move(bot, x, y, arrive); // arrived on the destination map — now walk to the spot
            } else if (onDone != null) {
                onDone.accept(false);
            }
        });
    }

    /* Diagnostic: the portal-hop route from the bot's current map to destMapId (no movement). */
    public static String routeReport(Character bot, int destMapId) {
        if (bot == null || bot.getMap() == null) {
            return "GCTravel: no map.";
        }
        int from = bot.getMapId();
        long startedAt = System.nanoTime();
        java.util.List<Integer> route = GCWorldGraph.route(from, destMapId, 12);
        long ms = (System.nanoTime() - startedAt) / 1_000_000L;
        if (route == null) {
            return String.format("GCTravel route %d -> %d: NONE (warp; %d maps indexed, %dms)",
                    from, destMapId, GCWorldGraph.mapCount(), ms);
        }
        if (route.isEmpty()) {
            return "GCTravel: already on map " + destMapId;
        }
        return String.format("GCTravel route %d -> %d: %d hops %s (%dms)",
                from, destMapId, route.size(), route, ms);
    }

    public static boolean isMoving(Character bot) {
        BotMovementState st = bot == null ? null : STATES.get(bot.getId());
        return st != null && (st.moveTarget != null || st.following || st.inAir || st.climbing
                || st.navEdge != null || st.portalDropAtMs > 0L);
    }

    /* True while the bot is on a rope/ladder (cleared only once it's back on a foothold). Combat holds
     * off attacking until then so the bot doesn't swing from the rope when a mob is near the rope top. */
    public static boolean isClimbing(Character bot) {
        BotMovementState st = bot == null ? null : STATES.get(bot.getId());
        return st != null && st.climbing;
    }

    /* True while the bot is mid portal-arrival: the map-entry float + the natural fall that follows it.
     * The idle-expression layer (BotFlavor) refuses to perform during this window, so an entering bot
     * never swings a skill / flexes a buff in mid-air. Deliberately a map-entry clock, NOT an inAir /
     * swimming pose test: swimming also carries inAir (on a swim map a bot is airborne for long
     * stretches), and a plain jump carries it too, so a pose-based gate would silence both. The window
     * is armed with the drop on map change and lapses on its own. */
    public static boolean isPortalArriving(Character bot) {
        BotMovementState st = bot == null ? null : STATES.get(bot.getId());
        return st != null && System.currentTimeMillis() < st.portalArrivalGuardUntilMs;
    }

    /**
     * The rope/ladder fh (wire value) when {@code chr} is currently on one, else 0.
     * A real client tests {@code fh & 0x8000} to tell "on a rope" from "on ground" and binds the
     * character to that rope (and takes the rope's page). So the value is the rope's
     * two's-complement NEGATIVE 1-based index — {@code (-idx) & 0xFFFF} — NOT {@code 0x8000 | idx},
     * which decodes to 32767 and silently means "no rope". Base 1 because
     * FootholdTree_FindLadderOrRope rejects index 0.
     *
     * <p>Exposed so the recorded-path engine (MovementCommands.findFootHoldId) sends the same rope
     * encoding the movement engine broadcasts with (BotMovementManager.resolveBroadcastFhId) instead
     * of a plain ground id while climbing — a positive id lets the client snap the character onto
     * that foothold, i.e. "nailed to the ground" off the rope.
     *
     * <p>Unlike the movement engine, which holds the rope in its state ({@code entry.climbRope}),
     * this resolves the rope from the character's own climbing stance + position, because the
     * recorded-path engine's characters are not under GC control and have no such state.
     */
    public static int ropeFh(Character chr) {
        // Gate on the climbing stance first: the common (non-climbing) case must not touch the map or
        // allocate the getRopes() wrapper. The pure core re-checks the stance for its direct callers.
        if (chr == null || !CharacterStance.isClimbing(chr.getStance())) {
            return 0;
        }
        MapleMap map = chr.getMap();
        Point pos = chr.getPosition();
        return ropeFh(chr.getStance(), pos.x, pos.y, map == null ? null : map.getRopes());
    }

    /**
     * Pure core of {@link #ropeFh(Character)} — no engine objects, so it is unit-testable. Returns
     * the two's-complement negative 1-based index of the rope/ladder the point sits on, or 0 when
     * the point is not climbing or not on a rope column.
     */
    static int ropeFh(int stance, int x, int y, List<Rope> ropes) {
        if (!CharacterStance.isClimbing(stance) || ropes == null) {
            return 0;
        }
        for (int i = 0; i < ropes.size(); i++) {
            Rope rope = ropes.get(i);
            if (Math.abs(x - rope.x()) <= BotPhysicsEngine.cfg.ROPE_GRAB_X
                    && y >= rope.topY() && y <= rope.bottomY()) {
                return (-(i + 1)) & 0xFFFF; // 1-based two's complement; client rejects index 0
            }
        }
        return 0;
    }

    /* True while the bot is standing on solid ground under GC control: not airborne, not swimming, not on
     * a rope. The physics pose is authoritative for a GC-driven bot. False when the bot isn't under GC
     * control (the old recorded engine owns it). Pose-pinning commands (a chair) gate on this so they never
     * fire mid-swim/mid-fall — see MovementCommands.botSitChair. */
    public static boolean isGrounded(Character bot) {
        return isGrounded(bot == null ? null : STATES.get(bot.getId()));
    }

    static boolean isGrounded(BotMovementState st) {
        return st != null && !st.inAir && !st.swimming && !st.climbing;
    }

    /* Mark the bot as actively grinding so the movement layer's grind-specific guards engage — chiefly
     * it stops idle-hanging on a rope (shouldHoldClimbIdle) and instead dismounts to keep fighting.
     * Set on GRIND entry, cleared when the bot leaves the grind. */
    public static void setGrinding(Character bot, boolean grinding) {
        if (bot == null) {
            return;
        }
        if (grinding) {
            enable(bot); // ensure a state exists to carry the flag
        }
        BotMovementState st = STATES.get(bot.getId());
        if (st != null) {
            st.grinding = grinding;
        }
    }

    /* Mark the bot as holding an explicit rest hang on a rope (a grind break's rope rest). While set, the
     * climb-idle hold stays engaged regardless of the grind guard, and the driver freezes the hang so no
     * nav / player-reaction / fidget layer can dislodge it. The bot must already be on the rope (isClimbing)
     * when this is set true; clear it before dismounting. No-op if the bot isn't under GC control when
     * clearing. */
    public static void setRestHold(Character bot, boolean resting) {
        if (bot == null) {
            return;
        }
        if (resting) {
            enable(bot); // ensure a state exists to carry the flag
        }
        BotMovementState st = STATES.get(bot.getId());
        if (st != null) {
            st.resting = resting;
        }
    }

    public static boolean isResting(Character bot) {
        BotMovementState st = bot == null ? null : STATES.get(bot.getId());
        return st != null && st.resting;
    }

    /* Jump off the rope/ladder the bot is on, biased toward dx (-1 left, +1 right, 0 straight off).
     * No-op if not currently climbing. Used by grind recovery to dismount instead of hanging. */
    public static void dismountRope(Character bot, int dx) {
        BotMovementState st = bot == null ? null : STATES.get(bot.getId());
        if (st != null && st.climbing) {
            BotMovementManager.jumpOffRope(st, bot, dx);
        }
    }

    /* True while the bot is climbing a rope/ladder as part of a committed navigation edge — i.e. the
     * driver is intentionally routing it up/down to another ledge (relocating to a fresh grind section,
     * approaching an upper-ledge mob), NOT hanging idle on a rope. Grind recovery uses this to leave a
     * deliberate traversal climb alone instead of fighting it, which would thrash mount/dismount. */
    public static boolean isNavigatingClimb(Character bot) {
        BotMovementState st = bot == null ? null : STATES.get(bot.getId());
        return st != null && st.climbing && st.navEdge != null;
    }

    // ── Idle fidget primitives (organic liveliness) ─────────────────────────

    /* A standing hop in place. */
    public static void jumpInPlace(Character bot) {
        BotMovementState st = bot == null ? null : STATES.get(bot.getId());
        if (st != null && !st.inAir && !st.climbing) {
            BotMovementManager.initiateJump(st, bot, 0);
        }
    }

    // ours: a directional (arced) engage hop toward dx (-1 left / +1 right; 0 = vertical). Unlike
    // jumpInPlace (always vertical), a non-zero dx launches a real moving arc — the manager/physics
    // already carry the ±walkStep horizontal velocity. Used by the grind brain's jump-attack so
    // thieves close/kite in an arc instead of pogo-ing. No-op if already airborne or on a rope.
    public static void jumpToward(Character bot, int dx) {
        BotMovementState st = bot == null ? null : STATES.get(bot.getId());
        if (st != null && !st.inAir && !st.climbing) {
            BotMovementManager.initiateJump(st, bot, dx);
        }
    }

    /* Flip the bot's facing (left↔right) while idle; the driver renders the new stand stance. */
    public static void turnAround(Character bot) {
        BotMovementState st = bot == null ? null : STATES.get(bot.getId());
        if (st != null) {
            st.facingDir = -st.facingDir;
        }
    }

    /* Face a direction (true = left) and broadcast the new idle stance immediately. Broadcasting now
     * (rather than waiting for the next tick) means the turn shows before whatever the caller does next
     * - e.g. an attack swing - and the client's last-movement stance is the new facing, so it won't snap
     * back the instant the swing ends. No-op if the bot isn't under dynamic control. */
    public static void face(Character bot, boolean left) {
        BotMovementState st = bot == null ? null : STATES.get(bot.getId());
        if (st != null) {
            st.facingDir = left ? -1 : 1;
            BotMovementManager.broadcastMovement(st);
        }
    }

    /* Crouch/duck for durationMs (the driver holds the prone pose while idle). */
    public static void duck(Character bot, int durationMs) {
        BotMovementState st = bot == null ? null : STATES.get(bot.getId());
        if (st != null && !st.inAir && !st.climbing) {
            st.duckUntilMs = System.currentTimeMillis() + Math.max(1, durationMs);
        }
    }

    /* A small step to (x,y) (used by the auto-fidget for nudges / wander-and-return). */
    public static void nudgeTo(Character bot, int x, int y) {
        move(bot, x, y);
    }

    /* Toggle idle auto-fidget (turn / duck / hop / small wander near its rest spot, returns home). */
    public static void setFidget(Character bot, boolean on) {
        if (bot == null) {
            return;
        }
        if (on) {
            enable(bot);
            GCFidget.start(bot);
        } else {
            GCFidget.cancel(bot);
        }
    }

    public static boolean isFidgeting(Character bot) {
        return GCFidget.isActive(bot);
    }

    // ── Observability (LOD tier gate for callers) ───────────────────────────

    /*
     * True when a real player is on mapId — i.e. the map is "observed" (FULL tier). This is
     * the public gate other systems use to decide REAL vs ABSTRACT behavior (e.g. a training bot runs
     * real combat only on observed maps). Wraps the package-private ObserverTracker; never
     * forces the observer poll to start (it is started by .enable).
     */
    public static boolean isMapObserved(int mapId) {
        return ObserverTracker.isFull(mapId);
    }

    /*
     * Force mapId to FULL ("observed") immediately for a short window, regardless of the ~1s observer
     * poll - so visible movement / combat / broadcast resume the same tick a real player arrives,
     * instead of up to one poll later. Used by BotMapEntryResponder when a real player enters a map.
     */
    public static void markObservedNow(int mapId) {
        ObserverTracker.markObservedNow(mapId);
    }

    /*
     * This map's current LOD tier as a label: "full" (a real player is here), "halo" (a real player is on
     * a portal-adjacent map), "dwell" (recently observed, still held at full physics by the hysteresis
     * window), or "coarse" (unobserved). Diagnostics — see !gcmove lod train.
     */
    public static String lodTier(int mapId) {
        if (ObserverTracker.isFull(mapId)) {
            return "full";
        }
        if (ObserverTracker.isHalo(mapId)) {
            return "halo";
        }
        if (ObserverTracker.isActiveMap(mapId)) {
            return "dwell";
        }
        return "coarse";
    }

    /* Snapshot of the maps currently FULL (a real player present). Diagnostics only. */
    public static java.util.Set<Integer> observedFullMaps() {
        return ObserverTracker.fullMaps();
    }

    /* Snapshot of the maps currently HALO (portal-adjacent to a real player). Diagnostics only. */
    public static java.util.Set<Integer> observedHaloMaps() {
        return ObserverTracker.haloMaps();
    }

    // ── Spatial terrain queries (generic nav-graph reads; used by grind-spot finding, placement, …) ──

    /* A walkable ground ledge: a baked nav-graph region's id + bounds + center. */
    public record Ledge(int regionId, int minX, int maxX, int centerX, int centerY) {
    }

    /* Every walkable ground ledge on the map (ropes/ladders excluded). Empty if the graph isn't baked. */
    public static List<Ledge> walkableLedges(MapleMap map) {
        BotNavigationGraph g = BotNavigationGraphProvider.getGraph(map);
        if (g == null) {
            return List.of();
        }
        List<Ledge> out = new java.util.ArrayList<>();
        for (BotNavigationGraph.Region r : g.regions) {
            if (r.isRopeRegion || r.isLadder) {
                continue;
            }
            Point c = r.centerPoint();
            out.add(new Ledge(r.id, r.minX, r.maxX, c.x, c.y));
        }
        return out;
    }

    /* The ledge (region id) under (x,y), or -1 if none. */
    public static int regionIdAt(MapleMap map, int x, int y) {
        BotNavigationGraph g = BotNavigationGraphProvider.getGraph(map);
        return g == null ? -1 : g.findRegionId(map, new Point(x, y));
    }

    /*
     * True only when (ax,ay) and (bx,by) rest on two DIFFERENT walkable ledges of an ALREADY-baked
     * graph - it never triggers a build (peek only), so it is safe on hot paths like the bot combat
     * tick. Both points resolve against the same graph instance so the region ids are comparable.
     * Returns false when the map isn't baked yet, or when either point is on no ledge (a flying mob
     * over a floor, a point mid-air): callers treat that as "can't tell, don't filter" rather than
     * "different". Lets bot attacks reject mobs standing on a separate platform above/below instead
     * of gating only by a vertical pixel box.
     */
    public static boolean onDifferentLedge(MapleMap map, int ax, int ay, int bx, int by) {
        BotNavigationGraph g = BotNavigationGraphProvider.peekGraph(map);
        if (g == null) {
            return false;
        }
        int ra = g.findRegionId(map, new Point(ax, ay));
        int rb = g.findRegionId(map, new Point(bx, by));
        return ra >= 0 && rb >= 0 && ra != rb;
    }

    /*
     * The ledge (region id) under (x,y) on an ALREADY-baked graph, or -1 when the point is on no
     * ledge. -2 when the map has no baked graph yet - distinguishable from -1 so a caller can tell
     * "this point is on no ledge" apart from "we cannot answer at all".
     *
     * Peek-only, like onDifferentLedge: it never triggers a build. regionIdAt() is NOT usable on a
     * hot path - it calls getGraph(), which joins on a graph build (potentially hundreds of ms) if
     * the map isn't baked. Combat ticks must never pay that.
     *
     * Returns -2 (not -1) for an unbaked map so callers can reproduce onDifferentLedge's "can't
     * tell, don't filter" behaviour: that method returned false for every pair when unbaked, which
     * accepts every candidate. Collapsing -2 into -1 here would silently start filtering.
     */
    public static int peekRegionIdAt(MapleMap map, int x, int y) {
        BotNavigationGraph g = BotNavigationGraphProvider.peekGraph(map);
        if (g == null) {
            return UNBAKED_REGION;
        }
        return g.findRegionId(map, new Point(x, y));
    }

    /* Sentinel from peekRegionIdAt: the map has no baked nav graph, so no ledge is answerable. */
    public static final int UNBAKED_REGION = -2;

    /*
     * The walk-region id this specific FOOTHOLD belongs to, on an ALREADY-baked graph — or
     * UNBAKED_REGION when the map isn't baked, or -1 when the foothold is in no region (e.g. a wall).
     *
     * Needed because a region (the walk-connected union of footholds) is the right granularity for
     * "same surface", while foothold identity is too fine: a single slope is stitched from many short
     * foothold segments, so two points on one slope routinely sit on two different footholds. Lets a
     * follower compare surfaces without re-deriving the region from a point. Peek-only, like
     * peekRegionIdAt: it never triggers a build.
     */
    public static int peekRegionIdOfFoothold(MapleMap map, Foothold foothold) {
        if (foothold == null) {
            return -1;
        }
        BotNavigationGraph g = BotNavigationGraphProvider.peekGraph(map);
        if (g == null) {
            return UNBAKED_REGION;
        }
        return g.regionIdByFootholdId.getOrDefault(foothold.getId(), -1);
    }

    /* The set of region ids reachable from the ledge under (fromX,fromY) (empty if it's on none). */
    public static java.util.Set<Integer> reachableRegions(MapleMap map, int fromX, int fromY) {
        BotNavigationGraph g = BotNavigationGraphProvider.getGraph(map);
        if (g == null) {
            return java.util.Set.of();
        }
        int start = g.findRegionId(map, new Point(fromX, fromY));
        if (start < 0) {
            return java.util.Set.of();
        }
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        java.util.Deque<Integer> queue = new java.util.ArrayDeque<>();
        seen.add(start);
        queue.add(start);
        while (!queue.isEmpty()) {
            int rid = queue.poll();
            for (BotNavigationGraph.Edge e : g.getOutgoing(rid)) {
                if (e.toRegionId != rid && seen.add(e.toRegionId)) {
                    queue.add(e.toRegionId);
                }
            }
        }
        return seen;
    }

    /* The exact ground point on a region at x (slope-aware), or null if the region is gone. Lets a
     * roaming bot leash its move target to a chosen ledge's ground line. */
    public static Point groundPointInRegion(MapleMap map, int regionId, int x) {
        BotNavigationGraph g = BotNavigationGraphProvider.getGraph(map);
        if (g == null) {
            return null;
        }
        BotNavigationGraph.Region r = g.getRegion(regionId);
        return r == null ? null : r.pointAt(x);
    }

    /* Peek-only twin of groundPointInRegion: samples a region's ground line at x WITHOUT triggering a
     * graph build (safe on a bot tick). Null when the map isn't baked or the region is unknown. */
    public static Point peekGroundPointInRegion(MapleMap map, int regionId, int x) {
        BotNavigationGraph g = BotNavigationGraphProvider.peekGraph(map);
        if (g == null) {
            return null;
        }
        BotNavigationGraph.Region r = g.getRegion(regionId);
        return r == null ? null : r.pointAt(x);
    }

    /* Peek-only: the walkable ledge (region) under (x,y), or null when the map isn't baked, the point
     * is on no ledge, or it sits on a rope. A region is the walk-connected union of all its
     * footholds, so its [minX,maxX] is the WHOLE floor band the bot can walk - letting a caller sample
     * the entire ledge instead of a fixed radius, without triggering a build. */
    public static Ledge peekLedgeAt(MapleMap map, int x, int y) {
        BotNavigationGraph g = BotNavigationGraphProvider.peekGraph(map);
        if (g == null) {
            return null;
        }
        int regionId = g.findRegionId(map, new Point(x, y));
        if (regionId < 0) {
            return null;
        }
        BotNavigationGraph.Region r = g.getRegion(regionId);
        if (r == null || r.isRopeRegion) {
            return null;
        }
        Point c = r.centerPoint();
        return new Ledge(r.id, r.minX, r.maxX, c.x, c.y);
    }

    /* Whether the baked nav graph can currently path from the bot's position to (x, y). A graph
     * still warming answers "yes" so callers run their ordinary behaviour; only a real baked
     * graph with no edge chain to the target reports false - the PQ chase uses this to detect
     * prize mobs parked on ledges no edge climbs to, and aim for the floor under them instead.
     * (The graph provider and MovementPlan are package-private; this is their public seam.) */
    public static boolean canPathTo(Character bot, int x, int y) {
        var map = bot == null ? null : bot.getMap();
        if (map == null || bot.getPosition() == null) {
            return true;
        }
        BotNavigationGraph graph =
                BotNavigationGraphProvider.peekBestGraph(map, BotMovementProfile.fromCharacter(bot));
        if (graph == null) {
            return true;
        }
        // Same platform = a plain walk, no edge chain needed - and MovementPlan.inMap answers
        // null for exactly that case (nothing to plan), so it must be checked for itself or
        // every same-platform target would read as unreachable.
        int from = graph.findRegionId(map, bot.getPosition());
        int to = graph.findRegionId(map, new Point(x, y));
        if (from >= 0 && from == to) {
            return true;
        }
        return MovementPlan.inMap(graph, map, bot.getPosition(), new Point(x, y)) != null;
    }

    /* Snap an arbitrary (possibly airborne) point down to the foothold it rests over, or null if there's
     * no floor below it. Lets a bot aim a move at a jumping/airborne mob's actual platform instead of its
     * raw y, so the pathfinder doesn't take a long detour to reach a mob that's really right in front. */
    public static Point groundPointBelow(MapleMap map, int x, int y) {
        return BotPhysicsEngine.findGroundPoint(map, new Point(x, y));
    }

    /* The foothold whose terrain covers x at/under y, via the same per-column indexed lookup the
     * movement engine uses — a drop-in for FootholdTree.findBelow that is 14-24x cheaper and
     * allocation-free (the host tree rebuilds a relevant list and sorts it per query). Null when
     * there is no floor below the point. Exposed so the pet follower can ground its pets without
     * paying the host tree's O(footholds) query on every 200ms tick of every pet. */
    public static Foothold footholdBelow(MapleMap map, int x, int y) {
        return BotPhysicsEngine.findBelowIndexed(map, new Point(x, y));
    }

    /**
     * The owner's own ground walk pace (px/s): Physics.img walkSpeed scaled by the bot's movement
     * profile — exactly the speed the bot engine walks it at. A follower walks at the owner's pace
     * instead of a fixed number (a Haste thief's pet keeps up). A null bot yields the base pace.
     */
    public static double walkVelocityPxs(Character bot) {
        return BotMovementProfile.fromCharacter(bot).walkVelocityPxs();
    }

    /**
     * As {@link #walkVelocityPxs(Character)}, for a follower whose walk stat is reduced by
     * {@code statReduction} points (floored at the base stat). The pet follower passes its per-index
     * offset so a pet is a touch slower than its owner at that exact amount.
     */
    public static double walkVelocityPxs(Character bot, int statReduction) {
        return followerProfile(bot, statReduction).walkVelocityPxs();
    }

    /**
     * A nearby follower's jump — vertical launch speed (px/s, positive up) and rise (px) — taken
     * from the SAME movement profile the engine drives {@code owner} with, so a follower hops
     * exactly as high as its owner can. The pet follower uses this so a platform the owner can jump
     * onto is never one its pet cannot reach: the pet's hop rises by the owner's own jump profile
     * (apex = v^2/2g), not a fixed base-stat hop.
     */
    public static JumpProfile jumpProfile(Character owner) {
        return jumpProfile(owner, 0);
    }

    /** As {@link #jumpProfile(Character)}, with the follower's jump stat reduced by
     *  {@code statReduction} points (floored at the base stat) for the pet's per-index offset. */
    public static JumpProfile jumpProfile(Character owner, int statReduction) {
        float jumpPxs = followerProfile(owner, statReduction).jumpSpeedPxs();
        int rise = (int) (jumpPxs * jumpPxs / (2.0 * MapleMovement.GRAVITY_PXS2));
        return new JumpProfile(jumpPxs, rise);
    }

    /** The owner's movement profile with both walk and jump stats reduced by {@code statReduction}
     *  (floored at the base stat) — the follower equivalent of {@link BotMovementProfile#fromCharacter}. */
    private static BotMovementProfile followerProfile(Character owner, int statReduction) {
        return BotMovementProfile.fromCharacter(owner).reducedBy(statReduction);
    }

    /**
     * A follower's swim-jump burst (px/s, up): the shared water burst scaled by the follower's own
     * SPEED stat (floored at the base stat), mirroring how the bot engine scales its bear. Without
     * this a speed-buffed owner would burst higher than its pet; the pet matches to within its
     * per-index offset.
     */
    public static double swimBurstPxs(Character owner, int statReduction) {
        return MapleMovement.SWIM_JUMP_BURST_PXS * followerProfile(owner, statReduction).speedMultiplier();
    }

    /** A follower's hop: the launch speed (px/s, up) and the rise (px) it can clear. */
    public record JumpProfile(float jumpSpeedPxs, int risePx) {
    }

    /**
     * The foothold the bot engine itself stands a character on at {@code p} — the SAME bidirectional
     * probe the bot walks with ({@link BotPhysicsEngine#findGroundFoothold}: the surface at the
     * point OR up to {@code MAX_SLOPE_UP} above / a step below, picking the closer). A ground
     * follower uses this to decide "am I standing on terrain" so it steps UP and DOWN slopes and
     * ledges. The old down-only {@link #footholdBelow} reported "no ground" on any uphill surface.
     * Null when no floor is within reach.
     */
    public static Foothold groundFoothold(MapleMap map, Point p) {
        return BotPhysicsEngine.findGroundFoothold(map, p);
    }

    /**
     * One follower tick of ground walking — the SAME integrator the bot's own ground physics runs
     * ({@link BotPhysicsEngine#simulateGroundMotion}), looped at the bot's {@code TICK_MS} so a
     * follower that is not a bot climbs and descends slopes and steps with bit-identical terrain
     * behaviour: the bidirectional snap ({@code MAX_SLOPE_UP} up / {@code MAX_SNAP_DROP} down),
     * walk-region constraint, wall block and off-edge detection. The walker sets {@code dir} toward
     * its anchor; on {@link GroundWalk#lostGround()} it has run off an edge and must fall, and a
     * wall simply stops it (the returned speed drops to 0). This replaces the old down-only probe
     * step that left a pet stuck on flat ground and bobbing on every slope.
     *
     * @param from             current foot point
     * @param foothold         the surface the walker stands on — the caller's own footing probe
     *                         (so the ground is not looked up twice per tick); {@code null} if none
     * @param dir              held direction (-1/0/+1)
     * @param carryVelocityPxs horizontal speed (px/s) carried in from the previous step
     * @param tickMs           the follower's tick length; the integrator runs {@code tickMs/TICK_MS} steps
     * @param owner            the character the follower tails — supplies the movement profile (speed stat)
     */
    public static GroundWalk walkGroundTick(MapleMap map, Point from, Foothold foothold, int dir,
                                            double carryVelocityPxs, long tickMs, Character owner) {
        return walkGroundTick(map, from, foothold, dir, carryVelocityPxs, tickMs, owner, 0);
    }

    /**
     * As {@link #walkGroundTick(MapleMap, Point, Foothold, int, double, long, Character)}, with the
     * walker's movement profile reduced by {@code statReduction} points (floored at the base stat) —
     * the pet follower's per-index speed offset, so a pet walks just slower than its owner.
     */
    public static GroundWalk walkGroundTick(MapleMap map, Point from, Foothold foothold, int dir,
                                            double carryVelocityPxs, long tickMs, Character owner,
                                            int statReduction) {
        BotMovementProfile profile = followerProfile(owner, statReduction);
        if (foothold == null) {
            return new GroundWalk(from, null, carryVelocityPxs, true);
        }
        double stepS = MapleMovement.CLIENT_STEP_MS / 1000.0;
        // Thread the fractional x and the 8ms carry across the sub-steps (like the bot's own
        // applyGroundDisplacement), so sub-pixel progress is never truncated at a tick boundary.
        double physX = from.x;
        Point pos = from;
        Foothold fh = foothold;
        double hspeed = carryVelocityPxs * stepS;
        double carryMs = 0.0;
        boolean lost = false;
        int ticks = Math.max(1, (int) Math.round(tickMs / (double) BotPhysicsEngine.cfg.TICK_MS));
        for (int i = 0; i < ticks; i++) {
            BotPhysicsEngine.GroundStepResult r = BotPhysicsEngine.simulateGroundMotion(
                    map, pos, fh, dir,
                    new BotPhysicsEngine.GroundTravelState(physX, hspeed, carryMs), profile);
            hspeed = r.state().hspeed();
            carryMs = r.state().carryMs();
            physX = r.state().physX();
            pos = r.point();
            if (r.lostGround()) {
                lost = true;
                break;
            }
            if (r.stepX() == 0) {
                break; // no progress this step (wall or converged) — nothing more will move
            }
            if (r.foothold() != null) {
                fh = r.foothold();
            }
        }
        return new GroundWalk(pos, fh, hspeed / stepS, lost);
    }

    /** Outcome of {@link #walkGroundTick}: the new foot point, the foothold under it, the new speed
     *  (px/s), and whether the walker ran off an edge (must fall). */
    public record GroundWalk(Point point, Foothold foothold, double velocityPxs, boolean lostGround) {
    }

    /** The engine's own tick length (ms) — the frame its physics integrates on. Exposed so a
     *  follower that is not a bot can sub-step its motion to the same cadence (a swim model is
     *  not step-size invariant, so a coarse step drifts from the bot's). */
    public static double botTickMs() {
        return BotPhysicsEngine.cfg.TICK_MS;
    }

    /**
     * The furthest walkable floor directly ABOVE {@code (x,y)} within {@code maxRise} px —
     * the bot's own "can I stand up there" probe (there is no findAbove on the foothold
     * tree). Used by the pet follower to decide whether an owner one platform up is
     * reachable by a hop. Null when there is none.
     */
    public static Point groundAbove(MapleMap map, int x, int y, int maxRise) {
        return BotPhysicsEngine.findGroundPointAbove(map, new Point(x, y), maxRise);
    }

    /**
     * The first terrain hit along the segment {@code from -> to}, using the same
     * per-pixel sweep the bot's own airborne physics resolves with (walls, ceilings and
     * landings). Exposed so the pet follower can arc a pet with the identical collision
     * behaviour instead of a single-point {@link #footholdBelow} probe (which tunnels
     * through slopes and thin platforms). Returns null when the segment is clear.
     */
    public static AirHit sweepAir(MapleMap map, Point from, Point to) {
        BotPhysicsEngine.AirCollision hit = BotPhysicsEngine.resolveAirCollision(map, from, to);
        if (hit.type() == BotPhysicsEngine.AirCollisionType.NONE) {
            return null;
        }
        return new AirHit(hit.point(), hit.foothold(), hit.type() == BotPhysicsEngine.AirCollisionType.LAND);
    }

    /** Outcome of {@link #sweepAir}: where the segment stopped, the foothold (if a floor) and whether it landed. */
    public record AirHit(Point point, Foothold foothold, boolean landing) {
    }

    /* Map ids reachable from fromMapId within maxHops over WALKABLE portals plus curated scripted warps
     * (e.g. the Kerning subway entrance), so subway-style training maps are discoverable. Excludes the
     * start map and taxi/ferry hops (keeps discovery town-local). Triggers the one-time world-graph build
     * on first call. */
    public static List<Integer> mapsWithinHops(int fromMapId, int maxHops) {
        return new java.util.ArrayList<>(mapsWithinHopsByDepth(fromMapId, maxHops).keySet());
    }

    /* As mapsWithinHops, but returns each reachable map mapped to its hop distance from fromMapId (1 =
     * adjacent). Insertion order is BFS order. Lets callers weight maps by how far out they are. */
    public static java.util.Map<Integer, Integer> mapsWithinHopsByDepth(int fromMapId, int maxHops) {
        java.util.LinkedHashMap<Integer, Integer> out = new java.util.LinkedHashMap<>();
        if (maxHops <= 0) {
            return out;
        }
        java.util.Map<Integer, int[]> g = GCWorldGraph.get();
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        java.util.ArrayDeque<Integer> frontier = new java.util.ArrayDeque<>();
        seen.add(fromMapId);
        frontier.add(fromMapId);
        int depth = 0;
        while (!frontier.isEmpty() && depth < maxHops) {
            depth++;
            for (int level = frontier.size(); level > 0; level--) {
                int current = frontier.poll();
                for (int next : g.getOrDefault(current, new int[0])) {
                    if (seen.add(next)) {
                        out.put(next, depth);
                        frontier.add(next);
                    }
                }
                // curated scripted warps (subway entrance, etc.) — a local hop, unlike taxi/ferry
                for (int next : soloMapling.ArtificialPlayer.BotTravelSystem.BotScriptedWarp.destinations(current)) {
                    if (seen.add(next)) {
                        out.put(next, depth);
                        frontier.add(next);
                    }
                }
            }
        }
        return out;
    }

    // ── LOD measurement tooling (M0) ────────────────────────────────────────

    /* Human-readable snapshot of the current dynamic-movement load (lines to drop to the GM). */
    public static List<String> lodStats() {
        return LodMetrics.stats();
    }

    /* Enable dynamic movement (+ idle fidget) on up to n idle bots to generate load. */
    public static int lodLoad(int n) {
        return LodMetrics.load(n);
    }

    /* Release every bot enabled by .lodLoad(int). */
    public static int lodUnload() {
        return LodMetrics.unload();
    }

    // ── Diagnostics (Phase-2 gate) ──────────────────────────────────────────

    /*
     * Force-bake the nav graph for the bot's current map (using its movement profile) and return
     * a human-readable summary of region / edge / rope counts. This is the "no hardcoded paths"
     * proof point — it exercises the WZ geometry load + physics-simulation edge discovery.
     */
    public static String bakeReport(Character bot) {
        if (bot == null || bot.getMap() == null) {
            return "GCMove: no map.";
        }
        BotMovementProfile profile = BotMovementProfile.fromCharacter(bot);
        long startedAt = System.nanoTime();
        BotNavigationGraph g = BotNavigationGraphProvider.rebuildGraph(bot.getMap(), profile);
        long ms = (System.nanoTime() - startedAt) / 1_000_000L;
        if (g == null) {
            return "GCMove: bake FAILED for map " + bot.getMapId();
        }
        int regions = g.regions.size();
        int walk = 0, jump = 0, drop = 0, climb = 0, portal = 0, total = 0;
        for (List<BotNavigationGraph.Edge> edges : g.outgoingByRegionId.values()) {
            for (BotNavigationGraph.Edge e : edges) {
                total++;
                switch (e.type) {
                    case WALK -> walk++;
                    case JUMP -> jump++;
                    case DROP -> drop++;
                    case CLIMB -> climb++;
                    case PORTAL -> portal++;
                }
            }
        }
        int ropes = bot.getMap().getRopes().size();
        return String.format(
                "GCMove bake map %d in %dms: regions=%d ropes=%d edges=%d "
                        + "(walk=%d jump=%d drop=%d climb=%d portal=%d)",
                bot.getMapId(), ms, regions, ropes, total, walk, jump, drop, climb, portal);
    }

    // Diagnostic for the widened rope top-exit probe. Iterates the player's map ropes, runs the
    // shared BotPhysicsEngine.findTopExitLanding, and compares against the old strict probe
    // (exactly rope.x, topY-3..topY+climbStep+2). For each rope prints x/topY/bottomY, ladder-or-rope,
    // the new landing Y (or none), old vs new pass, and which failure mode the old probe would hit.
    // Feeds tolerance calibration (TOP_EXIT_UP_TOL/DOWN_TOL/X_TOL) against real WZ geometry.
    private static final int ROPECHECK_MAX_LINES = 40;

    public static List<String> ropeCheckReport(Character player) {
        List<String> out = new ArrayList<>();
        if (player == null || player.getMap() == null) {
            out.add("GCMove ropecheck: no map.");
            return out;
        }
        MapleMap map = player.getMap();
        List<Rope> ropes = map.getRopes();
        int oldStrictBand = BotPhysicsEngine.climbStepPerTick() + 2; // topY+this was the old accept ceiling
        out.add("=== !gcmove ropecheck map " + player.getMapId() + " (" + ropes.size() + " ropes) ===");
        out.add(String.format("tol: up=%d down=%d x=%d (old band: rope.x, topY-3..topY+%d)",
                BotPhysicsEngine.TOP_EXIT_UP_TOL, BotPhysicsEngine.TOP_EXIT_DOWN_TOL,
                BotPhysicsEngine.TOP_EXIT_X_TOL, oldStrictBand));

        int oldPassCount = 0, newPassCount = 0, recovered = 0, stillFail = 0;
        int shown = 0;
        for (Rope rope : ropes) {
            int topY = rope.topY();
            Point oldGround = BotPhysicsEngine.pointBelowIndexed(map, new Point(rope.x(), topY - 3));
            boolean oldPass = oldGround != null && oldGround.y <= topY + oldStrictBand;
            Point newLanding = BotPhysicsEngine.findTopExitLanding(map, rope);
            boolean newPass = newLanding != null;

            if (oldPass) oldPassCount++;
            if (newPass) newPassCount++;
            if (!oldPass && newPass) recovered++;
            if (!oldPass && !newPass) stillFail++;

            if (shown < ROPECHECK_MAX_LINES) {
                String verdict;
                if (oldPass) {
                    verdict = "OK";
                } else if (!newPass) {
                    verdict = "STILL-FAIL (no foothold in widened band)";
                } else if (newLanding.x != rope.x()) {
                    verdict = "recovered FM-3 (off-axis dx=" + (newLanding.x - rope.x()) + ")";
                } else if (newLanding.y < topY) {
                    verdict = "recovered FM-2 (above top by " + (topY - newLanding.y) + ")";
                } else {
                    verdict = "recovered FM-1 (below top by " + (newLanding.y - topY) + ")";
                }
                out.add(String.format("  x=%d topY=%d botY=%d %s | new landY=%s | old=%s -> %s",
                        rope.x(), topY, rope.bottomY(), rope.isLadder() ? "ladder" : "rope",
                        newPass ? String.valueOf(newLanding.y) : "none",
                        oldPass ? "pass" : "fail", verdict));
                shown++;
            }
        }
        if (shown < ropes.size()) {
            out.add("  ... " + (ropes.size() - shown) + " more (capped at " + ROPECHECK_MAX_LINES + ")");
        }
        out.add(String.format("summary: old-pass=%d new-pass=%d recovered=%d still-fail=%d",
                oldPassCount, newPassCount, recovered, stillFail));
        return out;
    }

    // ── Internal driver callback ────────────────────────────────────────────

    /* Driver hook: a move was abandoned (no progress / unreachable) — drop its callback unfired. */
    static void abandonMove(BotMovementState entry) {
        if (entry != null && entry.bot != null) {
            ARRIVAL_CALLBACKS.remove(entry.bot.getId());
        }
    }

    static void fireArrival(BotMovementState entry) {
        if (entry == null || entry.bot == null) {
            return;
        }
        Runnable cb = ARRIVAL_CALLBACKS.remove(entry.bot.getId());
        if (cb != null) {
            try {
                cb.run();
            } catch (Throwable ignored) {
                // callback errors must not kill the tick
            }
        }
    }
}
