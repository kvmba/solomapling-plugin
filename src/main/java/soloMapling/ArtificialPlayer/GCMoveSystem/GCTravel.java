package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.client.Character;
import org.gms.net.server.Server;
import org.gms.scripting.event.EventManager;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Portal;
import org.gms.server.maps.Foothold;
import soloMapling.ArtificialPlayer.BotTravelSystem.BotScriptedWarp;
import soloMapling.ArtificialPlayer.BotWanderSystem.BotWanderSystem;
import soloMapling.ArtificialPlayer.BotHealthSystem.BotDeath;
import soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands;
import soloMapling.BotLogger;
import soloMapling.Environment.BotMessages;

import java.awt.Point;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/*
 * Cross-map travel executor (the "GCTravel" half). Layers a coarse hop-by-hop orchestrator on top
 * of GCMovement: route via GCWorldGraph, then for each hop walk to the portal that
 * leads to the next map (using GCMove) and step through it; warp (changeMap) any hop with
 * no walkable portal (link/scripted/special) — pure-movement scope. The route is recomputed from
 * the live current map each poll, so landing anywhere unexpected self-corrects.
 *
 * Runs on its own ~300 ms poller per traveling bot, independent of the 50 ms physics tick.
 */
// Ported from GreenCatMS. Credit: NutNNut.
final class GCTravel {
    private GCTravel() {
    }

    // Route-search depth cap. Kept >= TrainingBot's discovery radius so anything a bot can DISCOVER it
    // can also ROUTE to (route may use taxi/ferry shortcuts, so the actual hop count is usually lower).
    //
    // Sized for the WHOLE connected world, not one continent. It was 20 — fine for Victoria Island,
    // but the world is now fully joined (boats, trains, cabins, the travel agency, the Helios
    // elevator), and route() returning null does not idle the bot: GCTravel bare-warps the whole
    // remainder of the trip. So a cap that is too small does not just avoid a long walk, it teleports
    // the bot across continents, skipping every boat and train on the way. Measured on the live graph
    // (788 maps): the widest REAL crossing is 地球防御本部 → 玩具城 = 49 hops, because it climbs the
    // whole 玩具塔 one floor at a time (221020000 → ... → 221024400); town↔town tops out at 33 and
    // the farthest reachable map from Ludibrium is 57. 64 clears all of those with headroom. The
    // BFS is O(788 nodes) with early exit, so the ceiling costs nothing at this size.
    private static final int MAX_HOPS = 64;
    private static final int POLL_MS = 300;
    // "At the portal" box: portals often sit a little above the floor the bot stands on, so the
    // vertical tolerance is generous while X stays tight.
    private static final int ENTER_X = 35;
    private static final int ENTER_Y = 100;
    // Warp a hop ONLY when the bot is genuinely stuck — neither closing on the portal NOR moving from the
    // spot it stands in for this long. A bot still walking a huge map (even one whose straight-line distance
    // to the portal isn't dropping — climbing a rope to an elevated portal, detouring round terrain, getting
    // knocked around by mobs) keeps changing position, so it's never cut off; map size is fine.
    private static final long HOP_STUCK_MS = 12_000;
    private static final int HOP_PROGRESS_EPS_PX = 16;  // min portal-distance drop that counts as closing in
    private static final int HOP_MOVE_EPS_PX = 16;      // min position change that counts as "not in the same spot"
    // Stand on the portal this long (after the walk has finished) before stepping through, so the
    // server warp never out-runs the walk packets (which read as "vanished mid-stride" to clients).
    private static final long PORTAL_ENTER_DWELL_MS = 350;
    // "Basically on top of it" snap: a bot that keeps failing to settle while near the hop target
    // just acts. 100px so it reaches a bot bouncing at the sloped lip of a portal platform, "just
    // barely off" below/beside it (the settled box above already tolerates 100px of Y, so a
    // smaller snap circle left exactly those bots uncovered). The clock survives brief excursions
    // (a wide arc, a slide back down the slope) via the grace — only staying away longer resets
    // it. A clean walk-up still normally enters through the settled dwell first.
    private static final int SNAP_ENTER_RADIUS_PX = 100;
    private static final long SNAP_ENTER_MS = 1_200;
    private static final long SNAP_NEAR_GRACE_MS = 1_500;
    // Soft-lock treadmill window: all samples confined to one small bounding box for the whole
    // window means the bot is cycling in place (jump, fall, slide back, retry). A box, not an
    // anchored circle: slope physics slides a bot a couple hundred px per failed cycle, which
    // kept escaping (and thereby resetting) the old fixed-anchor circle so it never latched.
    // A genuinely traveling bot outgrows 400px within a few seconds of walking.
    private static final int SOFT_LOCK_SPAN_PX = 400;
    private static final long SOFT_LOCK_MS = 20_000;
    // Absolute per-hop wall-clock ceiling — the backstop no movement pattern can evade. The
    // longest legitimate hop (huge map, detours, climbs) stays well under this.
    private static final long HOP_MAX_MS = 90_000;
    // Same ceiling for a bot that a scheduled ride must resolve instead of its own walking: a boat
    // cycle is ~19 min (board + depart + sail). Departure and arrival are event-driven and the
    // world travel rate is configurable, so this is sized past the longest cycle rather than
    // derived from it — it only fires if a ride never completes at all.
    private static final long WAIT_MAX_MS = 25 * 60 * 1000;
    // Share of a vehicle's passengers who take up a spot at the rail instead of walking about.
    private static final double SIGHTSEE_SHARE = 0.34;
    // How close counts as "at the rail" — generous on Y, since a ledge's centre may sit below it.
    private static final int RAIL_REACH_PX = 60;
    // Whenever a bot has to WAIT at a shared point — a shut elevator door, a ride's boarding gate,
    // a ticket counter whose sailing isn't boarding yet — it idles around that point (stroll a
    // short way, stand a beat, repeat, dropping the odd line) instead of freezing on the one pixel
    // every waiter shares, which piles a crowd on a single spot and reads as a bug. The stroll span
    // is clamped to the anchor's own ledge (see pickWaitStroll), so it never steps off the landing,
    // reaching a spot the bot can actually stand on whatever the map's shape.
    private static final int WAIT_STROLL_HALF_SPAN_PX = 60;
    private static final int WAIT_STROLL_EDGE_MARGIN_PX = 12;
    private static final long WAIT_STROLL_DWELL_MIN_MS = 3_000;
    private static final long WAIT_STROLL_DWELL_MAX_MS = 8_000;
    // Upper bound on how many lines a transit chatter set (transit.<set>.N in BotMessages) can have.
    // The actual count is probed per use (see chatterLineCount), so sets may differ in length and a
    // dropped line can never surface as a raw key in chat; this is just the probe's safety cap and
    // must stay above the largest set (currently 100 lines).
    private static final int CHATTER_MAX_LINES = 128;
    // Pacing of the idle line a waiting or riding passenger drops (see maybeWaitChatter).
    private static final long WAIT_CHATTER_MIN_MS = 25_000;
    private static final long WAIT_CHATTER_MAX_MS = 60_000;
    // The Helios elevator's door is the one boarding gate the bot waits OUT rather than at: it idles
    // back on the landing while the car is elsewhere, and only heads for the door once it opens. When
    // it does, a real passenger doesn't spring up the instant the doors part — they notice, then move.
    // This is the beat between noticing and moving: the elevator hop keeps idling that much longer, so
    // the crowd leaves the landing one at a time rather than all together, then boardTaxi's dwell
    // plays out at the door. Rolled once per door-opening so a bot doesn't re-decide every poll.
    private static final long ELEVATOR_REACTION_MIN_MS = 2_000;
    private static final long ELEVATOR_REACTION_MAX_MS = 8_000;
    // While waiting an elevator out, a bot stands a few strides back from the door — far enough to keep
    // the doorway clear, and a different distance for each waiter so the crowd spreads along the floor
    // instead of piling onto one anchor pixel (a fixed standoff would just move the pile).
    private static final int ELEVATOR_STANDOFF_MIN_PX = 60;
    private static final int ELEVATOR_STANDOFF_MAX_PX = 180;

    private static final ScheduledExecutorService POOL = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "gctravel-poll");
        t.setDaemon(true);
        return t;
    });

    private static final Map<Integer, Trip> TRIPS = new ConcurrentHashMap<>();

    private static final class Trip {
        final Character bot;
        final int destMapId;
        final Consumer<Boolean> callback;
        ScheduledFuture<?> task;
        long settledAtMs;       // when the bot first arrived + stopped at the current hop's portal (0 = not yet)
        int hopBestDist = Integer.MAX_VALUE; // closest the bot has gotten to the current hop's portal
        int lastPosX = Integer.MIN_VALUE;    // last sampled bot position (liveness: is it still moving at all?)
        int lastPosY = Integer.MIN_VALUE;
        long hopProgressAtMs;   // last time the bot made progress (closed on the portal OR moved from its spot)
        long nearTargetSinceMs; // snap clock: since when the bot has been near the hop target (0 = not near)
        long lastNearTargetAtMs;// last sample inside the snap circle (grace keeps brief exits from resetting)
        int softMinX;           // bounding box of samples since the soft-lock window opened
        int softMaxX;
        int softMinY;
        int softMaxY;
        long softLockSinceMs;   // when the current soft-lock window opened (0 = unset)
        long hopStartAtMs;      // when the current hop's map was entered — feeds the absolute ceiling
        int lastMapId = -1;
        // Cab boarding: set once the bot has reached the cab NPC, then held for the dwell before the
        // bot "drives off". 0 = not boarding. Rides need this dwell to read as a ride — see GCTaxi.
        long boardingAtMs;
        long boardDwellMs;
        // Set while the bot is somewhere the ride itself must resolve: on a vehicle deck, or in a
        // terminal waiting for boarding to open. The hop ceiling is sized for walking, so a bot
        // patiently waiting out a sailing would otherwise be warped off its own ride.
        boolean waitingForTransit;
        boolean shoutedAtAttack;  // one shout per crossing, not one per poll
        boolean sheltering;      // took cover below during an attack — stay there until it clears
        Boolean sightseer;       // null undecided; true = watches from the rail, false = strolls
        Boolean railSide;        // null undecided; true = the left rail, false = the right
        // Idling at a shut elevator door: the spot the bot is strolling to on the landing, the beat
        // it stands there before choosing another, and the next time it drops an idle line.
        boolean loitering;        // true while idling on the landing for an open door
        Point loiterTarget;
        long loiterDwellUntilMs;
        long nextWaitChatterAtMs;
        // The elevator's door-opening reaction beat. One random delay per door-opening (not per poll),
        // armed while the door is still shut and only run down once it opens; the hop idles until it
        // elapses, then the bot heads in. false whenever the door is shut again, so the next opening
        // rolls afresh. See elevatorHoldAtLanding.
        boolean elevatorReacting;
        long elevatorReactUntilMs;

        Trip(Character bot, int destMapId, Consumer<Boolean> callback) {
            this.bot = bot;
            this.destMapId = destMapId;
            this.callback = callback;
        }
    }

    static void travel(Character bot, int destMapId, Consumer<Boolean> callback) {
        if (bot == null) {
            return;
        }
        cancel(bot);
        if (bot.getMap() != null && bot.getMapId() == destMapId) {
            fire(callback, true);
            return;
        }
        GCMovement.enable(bot);
        Trip trip = new Trip(bot, destMapId, callback);
        trip.hopProgressAtMs = nowMs();
        trip.hopStartAtMs = nowMs();
        trip.task = POOL.scheduleAtFixedRate(
                () -> {
                    try {
                        tick(trip);
                    } catch (Throwable ignored) {
                        // a thrown poll would cancel the periodic task
                    }
                }, 0, POLL_MS, TimeUnit.MILLISECONDS);
        TRIPS.put(bot.getId(), trip);
    }

    static void cancel(Character bot) {
        if (bot == null) {
            return;
        }
        Trip t = TRIPS.remove(bot.getId());
        if (t != null && t.task != null) {
            t.task.cancel(false);
        }
    }

    static boolean isTraveling(Character bot) {
        return bot != null && TRIPS.containsKey(bot.getId());
    }

    /*
     * Re-arm the per-hop watchdog clocks. approachAndAct judges a hop by how long its progress and
     * soft-lock windows have been open, so any time a hop's approach effectively restarts — the bot
     * lands on a new map, or resumes walking after waiting a door out — these must start over.
     * Otherwise a stale clock (a long wait skips every poll that would advance it) reads as "stuck"
     * on the very first poll after, and the hop is bare-warped past the door/portal it was heading for.
     */
    private static void resetHopWatchdogs(Trip trip) {
        trip.settledAtMs = 0L;
        trip.hopBestDist = Integer.MAX_VALUE;
        trip.lastPosX = Integer.MIN_VALUE;
        trip.lastPosY = Integer.MIN_VALUE;
        trip.hopProgressAtMs = nowMs();
        trip.nearTargetSinceMs = 0L;
        trip.softLockSinceMs = 0L;
        trip.hopStartAtMs = nowMs();
    }

    /*
     * True while a trip is parked somewhere the ride itself must resolve — waiting for boarding to
     * open, or standing on the deck mid-crossing. The bot is making no map progress on purpose, so
     * callers that treat stillness as a stall (TrainingBot's travel watchdog) must not count it.
     */
    static boolean isWaitingForTransit(Character bot) {
        if (bot == null) {
            return false;
        }
        Trip t = TRIPS.get(bot.getId());
        return t != null && t.waitingForTransit;
    }

    private static void tick(Trip trip) {
        Character bot = trip.bot;
        if (bot == null || bot.getMap() == null) {
            finish(trip, false);
            return;
        }
        int cur = bot.getMapId();
        if (cur == trip.destMapId) {
            finish(trip, true);
            return;
        }

        // New map since the last poll = a hop completed (or a warp/unexpected change): reset the
        // hop timer and clear any stale GCMove target so we re-target a portal on this map.
        if (cur != trip.lastMapId) {
            trip.lastMapId = cur;
            resetHopWatchdogs(trip);
            trip.boardingAtMs = 0L;   // this hop's cab is a different cab — board afresh
            trip.waitingForTransit = false;  // new map, new wait — don't inherit the old exemption
            trip.sheltering = false;         // new crossing, new threat — don't inherit the old cover
            trip.loiterTarget = null;        // new map, new landing — re-pick where to idle
            trip.loiterDwellUntilMs = 0L;
            trip.nextWaitChatterAtMs = 0L;   // new landing, re-arm the first wait line
            trip.loitering = false;
            trip.elevatorReacting = false;   // new floor, new door — re-arm the reaction beat
            trip.elevatorReactUntilMs = 0L;
            // Stepped off the vehicle (the event landed us, or something moved us): drop the deck
            // stroll so the bot walks its next hop instead of idling in wander mode.
            if (!GCTransit.isVehicleMap(cur) && BotWanderSystem.isWandering(bot)) {
                BotWanderSystem.stop(bot);
            }
            GCMovement.clearMoveIntent(bot);
        }

        // Already aboard a vehicle: the ride itself moves the bot, so there is no hop to walk and
        // no portal to find — the only correct move is to stay put until the event lands it.
        // Without this the route lookup below finds no path off the deck and warps the bot away,
        // skipping the crossing entirely.
        if (GCTransit.isVehicleMap(cur)) {
            trip.waitingForTransit = true;
            // The crossing is the event's to finish, so nothing here walks; but it can also never
            // finish (event stopped, script missing), which would strand the bot on the deck for
            // good. Bound the wait here — approachAndAct's ceiling is unreachable once we return.
            //
            // The bound has to follow the server's travel rate. Every leg of a scheduled ride is
            // scaled by it (getTransportationTime), so a world running boats at twice the pace
            // takes twice as long to dock, and a fixed ceiling would warp passengers off a boat
            // that was still perfectly on schedule. The rate has a floor of 1, so this never
            // tightens below the ordinary cycle.
            long aboardCeiling = waitCeilingMs();
            if (nowMs() - trip.hopStartAtMs >= aboardCeiling) {
                warp(bot, trip.destMapId, "TRANSIT-WAIT-TIMEOUT: stuck aboard map " + cur
                        + " for " + (aboardCeiling / 1000) + "s");
                return;
            }
            if (GCTransit.isUnderAttack(bot)) {
                trip.sheltering = true;
                reactToAttack(trip, bot);
                return;
            }
            // Taking shelter means staying sheltered: the cabin's only way out is back onto the
            // deck, so a stroll down here would walk a bot straight back to what it fled from and
            // the hatch would only send it below again. Stay put until the decks are clear.
            if (trip.sheltering) {
                return;
            }
            // A boat crossing is minutes long, so a bot frozen at its boarding spot for the whole
            // ride reads as a stalled bot. Stroll the deck instead, the way players do while
            // waiting to dock — the wander keeps to reachable footholds, so it stays on board.
            // An elevator is a short ride in a small box with a portal back out, so it just waits.
            if (GCTransit.isSpaciousVehicle(cur)) {
                if (trip.sightseer == null) {
                    // Roughly a third of a boat's passengers end up at the rail rather than
                    // walking about; decided once, so a bot doesn't change its mind every poll.
                    trip.sightseer = ThreadLocalRandom.current().nextDouble() < SIGHTSEE_SHARE;
                }
                if (trip.sightseer) {
                    watchTheWater(trip, bot);
                } else if (!BotWanderSystem.isWandering(bot)) {
                    BotWanderSystem.start(bot);
                }
            }
            // A passenger doesn't ride in silence: drop the odd line about the crossing so a bot
            // walking / watching the water doesn't read as a mute automaton. Each ride has its own
            // scene-accurate set (see GCTransit.onboardChatterSet) — an elevator box can't talk about
            // watching the sea, a subway car can't complain about the boat. Same self-throttle and
            // observer gate as the wait chatter. Null set (a vehicle with no lines) -> say nothing.
            String onboard = GCTransit.onboardChatterSet(cur);
            if (onboard != null) {
                maybeWaitChatter(trip, bot, nowMs(), onboard);
            }
            return;
        }

        List<Integer> route = GCWorldGraph.route(cur, trip.destMapId, MAX_HOPS);
        if (route == null) {
            // No walkable portal path at all (e.g. towns linked only by taxi/ferry, which
            // pure-movement scope drops) — warp the remainder to the destination.
            warp(bot, trip.destMapId, "no walkable portal route to " + trip.destMapId);
            return;
        }
        if (route.isEmpty()) {
            finish(trip, true);
            return;
        }
        int nextHop = route.get(0);

        // Prefer a walkable portal; else a taxi (cab) ride; else warp the hop.
        Portal portal = findPortalTo(bot.getMap(), nextHop);
        if (portal != null) {
            approachAndAct(trip, bot, portal.getPosition(), nextHop,
                    "portal " + portal.getId() + " -> map " + nextHop,
                    () -> GCPortals.enter(bot, portal)); // walk to the portal, stand a beat, step through
            return;
        }
        GCTaxi.VehicleEdge vehicle = GCTaxi.vehicle(cur, nextHop);
        if (vehicle != null) {
            Point npcPos = GCTaxi.npcPos(bot.getMap(), vehicle.npcId());
            if (npcPos == null) {
                warp(bot, nextHop, "vehicle npc " + vehicle.npcId() + " not on map " + cur);
                return;
            }
            // A waiting room's only way out is the ride itself: takeoff() warps everyone in the room
            // aboard at once, so the bot just has to be here. Idle around the inspector rather than
            // freeze on it — every waiter shares that one pixel, and a crowd piled on it reads as a
            // bug. We never walk in from here (the event moves the bot, and the map change resets).
            idleWhileWaiting(trip, bot, npcPos, "ride_wait");
            return;
        }
        GCTaxi.TransitEdge taxi = GCTaxi.edge(cur, nextHop, bot.getLevel());
        if (taxi != null) {
            Point npcPos = GCTaxi.npcPos(bot.getMap(), taxi.npcId());
            if (npcPos == null) {
                warp(bot, nextHop, "taxi npc " + taxi.npcId() + " not on map " + cur);
                return;
            }
            // A ticket counter only lets you through to the waiting room while the ride is boarding;
            // the room has no other way out, so stepping in while it is shut strands the bot in an
            // empty lounge until the next sailing. While shut, wait at the counter — idling about it
            // so a crowd doesn't stack on the counter pixel — and once boarding opens, walk up and
            // board (stand a beat, then the counter's script-equivalent warp, below).
            GCTaxi.VehicleEdge onward = GCTaxi.vehicleFrom(taxi.toMapId());
            if (onward != null && !boardingOpen(bot, onward.eventName())) {
                idleWhileWaiting(trip, bot, npcPos, "ride_wait");
                return;
            }
            stopIdlingAndWalk(trip);
            trip.waitingForTransit = false;
            approachAndAct(trip, bot, npcPos, nextHop,
                    "taxi npc " + taxi.npcId() + " -> map " + nextHop,
                    () -> boardTaxi(trip, bot, taxi));
            return;
        }
        // Curated scripted-warp portal (e.g. subway entrance): its WZ target is a script, so findPortalTo
        // can't see it. Walk to the portal's spot and warp to the destination, like the script's pi.warp.
        BotScriptedWarp.WarpEdge sw = BotScriptedWarp.edge(cur, nextHop);
        if (sw != null) {
            Point trigger = BotScriptedWarp.portalPos(bot.getMap(), sw.portalName());
            if (trigger == null) {
                warp(bot, nextHop, "scripted portal '" + sw.portalName() + "' not on map " + cur);
                return;
            }
            // The Helios elevator is the one boarding gate a bot waits OUT, not AT: its door is shut
            // most of the cycle (~3/4, up to 3 min), so freezing on the portal pixel would stack a
            // crowd on one spot. The bot idles back on the landing instead — off the door, so the
            // doorway stays clear — strolling and dropping the odd line, and only walks to the door
            // once it opens. When it does, a real passenger notices and moves a beat later, not the
            // instant the doors part; elevatorHoldAtLanding holds that beat, so the crowd leaves the
            // landing spread out rather than as one synchronised rush. While the bot idles this hop is
            // exempt from the walk watchdog (waitingForTransit), since standing about is the point;
            // approachAndAct resumes the normal walk-in once the bot is free to go.
            if (GCTransit.isElevatorFloor(cur)
                    && elevatorHoldAtLanding(trip, GCTransit.elevatorDoorOpen(bot, cur))) {
                idleWhileWaiting(trip, bot, elevatorWaitAnchor(bot, trigger), "elevator_wait");
                return;
            }
            // Door open, unreadable, or not an elevator: stop idling and walk in (re-arms the hop
            // watches on the idle->walk transition; see stopIdlingAndWalk).
            stopIdlingAndWalk(trip);
            trip.waitingForTransit = false;  // walking in now, not parked — bound by the walk ceiling
            approachAndAct(trip, bot, trigger, nextHop,
                    "scripted portal '" + sw.portalName() + "' -> map " + nextHop,
                    () -> {
                        // The Helios elevator's door is the one scripted portal that can REFUSE entry:
                        // its script (elevator.js) warps a player into the waiting car only while the
                        // car is parked at this floor, and turns them away ("the elevator is moving")
                        // mid-cycle. We replace that script with a bare changeMap, so without this a
                        // bot walking up mid-cycle would slip into an empty car and — with every other
                        // arriving bot doing the same — pile onto the car's single entry portal until
                        // a departure minutes off. Even a bot that reached the door just as it shut is
                        // turned back here and re-loiters on the landing above.
                        Boolean open = GCTransit.elevatorDoorOpen(bot, cur);
                        if (open != null && !open) {
                            trip.waitingForTransit = true; // door shut — the wait is by design
                            return;
                        }
                        if (GCTransit.isElevatorCar(sw.toMapId())) {
                            // Board onto a random player spawn of the car rather than pinning every
                            // passenger to portal 0. The car is a tiny box, so a crowd all landing on
                            // the one portal pixel reads as a pile; the elevator event itself already
                            // scatters its passengers across the car's spawn points when it moves them
                            // (warpEveryone -> changeMap -> random spawn), and boarding the same way
                            // matches that.
                            warp(bot, sw.toMapId(), "elevator ride " + cur + " -> " + sw.toMapId());
                        } else {
                            warpToPortal(bot, sw.toMapId(), sw.toPortalId(),
                                    "scripted warp " + cur + " -> " + nextHop);
                        }
                    });
            return;
        }
        warp(bot, nextHop, "no walkable portal/taxi/scripted-warp on map " + cur + " to " + nextHop);
    }

    /*
     * Board the cab: stand at it for the dwell, then drive off (warp) to the destination town.
     *
     * The dwell is the whole point. GCTravel's hop executor is otherwise uniform — walk to a spot,
     * stand 350ms so the warp packet can't out-run the walk packets, changeMap — which is right for a
     * portal (the client has no concept of "entering" one) but wrong for a cab: a player watching the
     * bot sees it walk up and BLINK OUT the same frame, which is indistinguishable from the bare warp
     * it actually is. Standing there a few seconds reads as paying the driver.
     *
     * Called every poll while the bot is at the cab: the first call opens the boarding clock (and
     * returns — the bot just stands there), the polls after it wait the dwell out, and the first one
     * past it drives off.
     */
    private static void boardTaxi(Trip trip, Character bot, GCTaxi.TransitEdge taxi) {
        long now = nowMs();
        if (trip.boardingAtMs == 0L) {
            trip.boardingAtMs = now;
            trip.boardDwellMs = ThreadLocalRandom.current()
                    .nextLong(GCTaxi.BOARD_DWELL_MIN_MS, GCTaxi.BOARD_DWELL_MAX_MS);
            return; // just got in — wait for the next poll to see if the dwell is up
        }
        if (now - trip.boardingAtMs < trip.boardDwellMs) {
            return; // still boarding — the cab hasn't left yet
        }
        // Re-check the gate: the walk-up and the dwell can span the short boarding window (the
        // subway's is only ~50s), and stepping into the room after it shut strands the bot in an
        // empty lounge until the next sailing. Shut again -> wait at the counter; the main tick
        // re-evaluates next poll and picks waiting back up.
        GCTaxi.VehicleEdge onward = GCTaxi.vehicleFrom(taxi.toMapId());
        if (onward != null && !boardingOpen(bot, onward.eventName())) {
            trip.boardingAtMs = 0L;
            trip.waitingForTransit = true; // gate shut again mid-boarding — wait it out
            return;
        }
        trip.boardingAtMs = 0L;
        warp(bot, taxi.toMapId(), "taxi ride " + bot.getMapId() + " -> " + taxi.toMapId()
                + " (npc " + taxi.npcId() + ")");
    }

    /*
     * Whether the boat/train is letting passengers into its waiting room right now.
     *
     * The event says so on itself ("entry"), the same property the counter's own script asks
     * before it warps a player through. Absent event (not running, wrong channel) reads as
     * shut rather than open: a bot that walked into a lounge whose ride never came would be
     * stuck there for good, while one that waits at the counter is still visible and can be
     * picked up by the next thing that wants it.
     */
    private static boolean boardingOpen(Character bot, String eventName) {
        return "true".equals(boardingEntry(bot, eventName));
    }

    /*
     * The raw "entry" property, for the log line: the printed value is what says whether the
     * gate is merely shut or was never set at all — a null here means the event is missing or
     * its init never ran, which is the case that would hold a bot at the counter forever.
     */
    private static String boardingEntry(Character bot, String eventName) {
        if (bot == null || bot.getMap() == null || bot.getMap().getChannelServer() == null) {
            return null;
        }
        EventManager em = bot.getMap().getChannelServer().getEventSM().getEventManager(eventName);
        return em == null ? null : em.getProperty("entry");
    }

    /*
     * How long to trust a scheduled ride before giving up on it: WAIT_MAX_MS, stretched by the
     * world's travel rate so a slower world isn't judged by a faster one's clock. Read fresh each
     * time because the rate is set by a GM command and can change under us.
     */
    private static long waitCeilingMs() {
        float rate = 1f;
        try {
            rate = Server.getInstance().getWorld(0).getTransportationTime(1000) / 1000f;
        } catch (Throwable ignored) {
            // No world to ask: before it starts up, after it shuts down, or in a test. The class can
            // be uninitializable rather than merely absent, so this catches errors too — the
            // unscaled ceiling is correct either way.
        }
        if (!(rate >= 1f)) {
            rate = 1f;   // a rate below 1 would only ever shorten the wait
        }
        long scaled = (long) (WAIT_MAX_MS * rate);
        return scaled > WAIT_MAX_MS ? scaled : WAIT_MAX_MS;
    }

    /*
     * Something boarded: stop the deck stroll, shout once, and head below. This mirrors what players
     * actually do when a Balrog shows up mid-crossing, and it keeps a bot from strolling into it.
     * The bot stays a passenger — it watches from the cabin, it does not fight.
     */
    /*
     * Take a spot at the rail and stay there, the way players lean on it for the whole crossing:
     * walk to the outermost ledge, turn to face the water, and let the idle fidget supply the odd
     * turn or hop. A bot that walked to the rail and then stood rigid would look worse than one
     * that never moved at all.
     */
    private static void watchTheWater(Trip trip, Character bot) {
        if (trip.railSide == null) {
            trip.railSide = ThreadLocalRandom.current().nextBoolean();
        }
        GCMovement.Ledge rail = GCTransit.railLedge(bot.getMap(), trip.railSide);
        if (rail == null) {
            return; // terrain not readable — nothing to lean on
        }
        Point bp = bot.getPosition();
        boolean atRail = Math.abs(bp.x - rail.centerX()) <= RAIL_REACH_PX
                && Math.abs(bp.y - rail.centerY()) <= RAIL_REACH_PX * 3;
        if (atRail) {
            if (!GCMovement.isMoving(bot)) {
                GCMovement.face(bot, trip.railSide);   // face the water, not the deck
                GCMovement.setFidget(bot, true);
            }
            return;
        }
        if (!GCMovement.isMoving(bot)) {
            GCMovement.move(bot, rail.centerX(), rail.centerY());
        }
    }

    private static void reactToAttack(Trip trip, Character bot) {
        if (BotWanderSystem.isWandering(bot)) {
            BotWanderSystem.stop(bot);
        }
        if (!trip.shoutedAtAttack) {
            trip.shoutedAtAttack = true;
            int shouts = chatterLineCount("attack_shout");
            if (shouts > 0) {
                SocialCommands.BotSpeak(bot, BotMessages.get("transit.attack_shout."
                        + ThreadLocalRandom.current().nextInt(shouts)));
            }
        }
        Point hatch = GCTransit.hatchPos(bot.getMap());
        if (hatch != null && !GCMovement.isMoving(bot)) {
            GCMovement.move(bot, hatch.x, hatch.y);
        }
    }

    /*
     * True while the elevator hop should keep idling on the landing: the door is shut, OR it has just
     * opened but the passenger's reaction beat has not run out yet. Idling is what keeps the bot OFF
     * the door while it waits, and for the first seconds after it opens, so the doorway stays clear
     * for whoever is already walking through.
     *
     * open == null (map/channel unreadable) must not idle: the bot would stroll forever. That path is
     * left to the normal walk-in with its own bounds, matching how elevatorDoorOpen's null is treated.
     * While the door is shut the beat is (re)armed with a fresh roll, so the delay is measured from the
     * opening — not from whenever the bot started waiting — and a door that opens and shuts again rolls
     * anew. Rolled once per opening (see Trip.elevatorReacting) rather than per poll.
     */
    private static boolean elevatorHoldAtLanding(Trip trip, Boolean open) {
        if (open == null) {
            return false;
        }
        if (!open) { // shut: arm the beat for the next opening, and wait it out on the landing
            trip.elevatorReacting = true;
            trip.elevatorReactUntilMs = nowMs() + ThreadLocalRandom.current()
                    .nextLong(ELEVATOR_REACTION_MIN_MS, ELEVATOR_REACTION_MAX_MS);
            return true;
        }
        return trip.elevatorReacting && nowMs() < trip.elevatorReactUntilMs; // open: hold the beat
    }

    /*
     * Where a bot idles while it waits an elevator out. The door sits on a narrow shelf at the mouth
     * of the shaft; a crowd holding that shelf (or the pixel on the door) piles up, so the bot waits
     * back on the room floor it arrived on instead — off the door, and off whoever is boarding.
     *
     * The standoff is seeded from the bot's id, so it is random across the crowd (waiters fan out
     * along the floor) yet stable for one bot across the whole wait (a fresh roll every poll would
     * walk the anchor about and drag the stroll with it). It is measured on the room's side of the
     * door — whichever side the bot approaches from — and landed on the floor below it, so the anchor
     * is a room-floor spot and not the door's own narrow shelf (where a bot that just had the door
     * shut on it happens to be standing, and where a crowd would re-pile). Falls back to the room
     * floor on the other side of the door when the first pick finds no ground — the room is wider
     * than any standoff, so one side always lands — and to the door spot itself only if neither does.
     */
    private static Point elevatorWaitAnchor(Character bot, Point trigger) {
        Point bp = bot.getPosition();
        int standoff = ELEVATOR_STANDOFF_MIN_PX
                + Math.floorMod(bot.getId(), ELEVATOR_STANDOFF_MAX_PX - ELEVATOR_STANDOFF_MIN_PX + 1);
        int dir = bp.x >= trigger.x ? 1 : -1; // back the way the bot came, off the door
        Point spot = GCMovement.groundPointBelow(bot.getMap(), trigger.x + dir * standoff, trigger.y - 1);
        if (spot == null) {
            spot = GCMovement.groundPointBelow(bot.getMap(), trigger.x - dir * standoff, trigger.y - 1);
        }
        return spot != null ? spot : trigger;
    }

    /*
     * Idle around a shared waiting point (a shut elevator door, a ride's boarding gate, a ticket
     * counter that isn't boarding yet) instead of freezing on it: stroll to a fresh spot near the
     * anchor, stand a beat, repeat — dropping the odd line. Every waiter shares the one anchor pixel,
     * so without this a crowd piles onto that single spot and reads as a bug; strolling about is what
     * a player actually does while waiting for a lift or a sailing.
     *
     * The hop is marked waitingForTransit so the walk watchdog exempts the stillness between strolls,
     * and the whole thing is bounded by the same transit ceiling a ride gets (WAIT_MAX_MS), so a gate
     * that never opens still gets the bot bare-warped onward rather than parked here for good. The
     * caller re-checks the gate every poll and, once it opens, stops calling this and walks the bot
     * in — see the "stop idling" reset at each call site.
     */
    private static void idleWhileWaiting(Trip trip, Character bot, Point anchor, String chatterSet) {
        long now = nowMs();
        trip.waitingForTransit = true; // standing about is the point — see the watchdog exemption
        trip.loitering = true;

        long ceiling = waitCeilingMs();
        if (now - trip.hopStartAtMs >= ceiling) {
            warp(bot, trip.destMapId, "TRANSIT-WAIT-TIMEOUT: waiting at map " + bot.getMapId()
                    + " for " + (ceiling / 1000) + "s");
            return;
        }

        // Drop the odd line while waiting (observed maps only, self-throttled).
        maybeWaitChatter(trip, bot, now, chatterSet);

        if (trip.loiterTarget == null) {
            // Dwelling between strolls: pick a fresh spot once the beat is up.
            if (now < trip.loiterDwellUntilMs) {
                return;
            }
            trip.loiterTarget = pickWaitStroll(bot, anchor);
            GCMovement.move(bot, trip.loiterTarget.x, trip.loiterTarget.y);
            return;
        }

        // Walking to the chosen spot: on arrival, settle into a dwell before the next stroll.
        Point bp = bot.getPosition();
        boolean arrived = Math.abs(bp.x - trip.loiterTarget.x) <= ENTER_X
                && Math.abs(bp.y - trip.loiterTarget.y) <= ENTER_Y
                && !GCMovement.isMoving(bot);
        if (arrived) {
            trip.loiterTarget = null;
            trip.loiterDwellUntilMs = now + ThreadLocalRandom.current()
                    .nextLong(WAIT_STROLL_DWELL_MIN_MS, WAIT_STROLL_DWELL_MAX_MS);
            return;
        }
        if (!GCMovement.isMoving(bot)) {
            GCMovement.move(bot, trip.loiterTarget.x, trip.loiterTarget.y); // (re)issue the stroll
        }
    }

    /*
     * A fresh standing spot near the wait anchor, clamped to the anchor's own ledge so a waiter never
     * strolls off the edge or onto a different platform. The X offset is rolled inside
     * +-WAIT_STROLL_HALF_SPAN, then squeezed to the ledge under the anchor (margin from its ends) and
     * dropped to that ledge's floor with groundPointBelow. Falls back to the anchor itself when the
     * terrain can't be read, so a waiter with no navigable graph still holds its spot rather than
     * strolling onto nothing.
     */
    private static Point pickWaitStroll(Character bot, Point anchor) {
        Point spot = null;
        for (int attempt = 0; attempt < 4 && spot == null; attempt++) {
            int offset = ThreadLocalRandom.current()
                    .nextInt(-WAIT_STROLL_HALF_SPAN_PX, WAIT_STROLL_HALF_SPAN_PX + 1);
            int x = clampToLedge(bot, anchor, offset);
            spot = GCMovement.groundPointBelow(bot.getMap(), x, anchor.y - 1);
        }
        return spot != null ? spot : anchor;
    }

    /*
     * The X offset applied to anchor, squeezed so the result stays on the ledge the anchor stands on
     * (with a margin from its ends). Returns the anchor's own X when the ledge can't be read — a
     * narrower-than-expected or unindexed map then just stands still instead of risking an edge.
     */
    private static int clampToLedge(Character bot, Point anchor, int offset) {
        Foothold ledge = GCMovement.footholdBelow(bot.getMap(), anchor.x, anchor.y - 1);
        if (ledge == null) {
            return anchor.x;
        }
        return clampToSpan(Math.min(ledge.getX1(), ledge.getX2()),
                Math.max(ledge.getX1(), ledge.getX2()), anchor.x, offset, WAIT_STROLL_EDGE_MARGIN_PX);
    }

    /*
     * The X a stroller takes: anchorX + offset, clamped to [lo, hi] inset by margin so it never steps
     * off the ledge's ends. When the ledge is narrower than the margins leave room for, returns the
     * anchor's own X (stand still) rather than an out-of-range point. Pure, so the "stay on the ledge"
     * guarantee is pinnable without a live map.
     */
    static int clampToSpan(int lo, int hi, int anchorX, int offset, int margin) {
        int left = lo + margin;
        int right = hi - margin;
        if (left > right) {
            return anchorX;
        }
        return Math.max(left, Math.min(right, anchorX + offset));
    }

    /*
     * Occasionally mutter a line while waiting or riding, so a bot standing about reads as a bored
     * player rather than a frozen one. The line comes from a localized, scene-specific set
     * (transit.<set>.N in BotMessages) chosen by the caller — the elevator, each ride and each wait
     * gate have their own, so a line never mismatches the setting. Gated on a real player being able
     * to see it (chatter is packets) and self-throttled by nextWaitChatterAtMs; the first call only
     * arms the clock, so a fresh waiter stays quiet for one interval before its first line.
     */
    private static void maybeWaitChatter(Trip trip, Character bot, long now, String chatterSet) {
        if (trip.nextWaitChatterAtMs == 0L) {
            trip.nextWaitChatterAtMs = now + ThreadLocalRandom.current()
                    .nextLong(WAIT_CHATTER_MIN_MS, WAIT_CHATTER_MAX_MS);
            return;
        }
        if (now < trip.nextWaitChatterAtMs) {
            return;
        }
        trip.nextWaitChatterAtMs = now + ThreadLocalRandom.current()
                    .nextLong(WAIT_CHATTER_MIN_MS, WAIT_CHATTER_MAX_MS);
        if (!GCMovement.isMapObserved(bot.getMapId())) {
            return; // no observer — nothing worth saying
        }
        int count = chatterLineCount(chatterSet);
        if (count <= 0) {
            return; // set missing/empty — say nothing rather than a raw key
        }
        int line = ThreadLocalRandom.current().nextInt(count);
        SocialCommands.BotSpeak(bot, BotMessages.get("transit." + chatterSet + "." + line));
    }

    /*
     * How many lines a chatter set actually has, by probing transit.<set>.0, .1, ... until one
     * resolves to itself (BotMessages returns the key on a miss) up to CHATTER_MAX_LINES. Probed
     * rather than hardcoded so sets may differ in length and a line dropped from the YAML shrinks the
     * range instead of surfacing as a raw key in chat. The result is cached per set name.
     */
    private static final Map<String, Integer> CHATTER_COUNTS = new ConcurrentHashMap<>();

    private static int chatterLineCount(String chatterSet) {
        return CHATTER_COUNTS.computeIfAbsent(chatterSet, key -> {
            int n = 0;
            while (n < CHATTER_MAX_LINES) {
                String k = "transit." + key + "." + n;
                if (BotMessages.get(k).equals(k)) {
                    break; // unresolved -> key echoed back -> past the end of the set
                }
                n++;
            }
            return n;
        });
    }

    /*
     * Stop idling and hand off to a normal walk: drop the loiter state and, on the idle->walk
     * transition only, re-arm the hop's watchdog clocks. They were opened when the bot first reached
     * the map (before the wait) and idling skipped every poll that would advance them, so after a
     * wait longer than HOP_STUCK_MS/SOFT_LOCK_MS the resumed approachAndAct would see them already
     * expired and bare-warp past the gate on its first poll — the very skip-the-gate bug the wait
     * exists to prevent. Reset exactly the new-map set, so the walk-in gets a fresh budget; the walk
     * ceiling still bounds it afterwards.
     */
    private static void stopIdlingAndWalk(Trip trip) {
        trip.loiterTarget = null;
        trip.loiterDwellUntilMs = 0L;
        if (trip.loitering) {
            trip.loitering = false;
            resetHopWatchdogs(trip);
        }
    }

    /*
     * Walk the bot to dest, wait until it has arrived AND finished the walk (not mid-stride),
     * stand a short dwell, then run action (enter the portal / ride the cab). Progress-aware:
     * a bot still closing the distance OR just moving across the map is never cut off; only a bot that stays
     * in the same spot for the whole window warps the hop.
     */
    private static void approachAndAct(Trip trip, Character bot, Point dest, int nextHop, String intent, Runnable action) {
        Point bp = bot.getPosition();
        long now = nowMs();

        // Absolute per-hop ceiling: whatever the physics looked like, a hop this old has failed.
        // A bot waiting out a scheduled ride is standing exactly where it should be, so it gets the
        // longer transit ceiling instead — WAIT_MAX_MS outlives the longest vehicle cycle (a boat:
        // 4 min boarding + 5 min to depart + 10 min sailing) and only fires if a ride never ends.
        long ceiling = trip.waitingForTransit ? waitCeilingMs() : HOP_MAX_MS;
        if (now - trip.hopStartAtMs >= ceiling) {
            warp(bot, nextHop, (trip.waitingForTransit ? "TRANSIT-WAIT-TIMEOUT" : "HOP-CEILING")
                    + ": hop " + (ceiling / 1000) + "s old on map " + bot.getMapId()
                    + " at (" + bp.x + "," + bp.y + "), intent: " + intent);
            return;
        }

        // Snap clock: time since the bot first came near the hop target. Survives brief
        // excursions (wide arc, slide back down the slope) via the grace window.
        long sdx = bp.x - dest.x;
        long sdy = bp.y - dest.y;
        boolean nearTarget = sdx * sdx + sdy * sdy <= (long) SNAP_ENTER_RADIUS_PX * SNAP_ENTER_RADIUS_PX;
        if (nearTarget) {
            trip.lastNearTargetAtMs = now;
            if (trip.nearTargetSinceMs == 0L) {
                trip.nearTargetSinceMs = now;
            }
        } else if (trip.nearTargetSinceMs != 0L && now - trip.lastNearTargetAtMs > SNAP_NEAR_GRACE_MS) {
            trip.nearTargetSinceMs = 0L;
        }

        boolean atDest = Math.abs(bp.x - dest.x) <= ENTER_X && Math.abs(bp.y - dest.y) <= ENTER_Y;
        boolean settled = atDest && !GCMovement.isMoving(bot);
        if (settled) {
            if (trip.settledAtMs == 0L) {
                trip.settledAtMs = now;                           // just arrived + stopped: start dwell
            } else if (now - trip.settledAtMs >= PORTAL_ENTER_DWELL_MS) {
                action.run();                                     // stood a beat — act now
            }
            return;
        }
        trip.settledAtMs = 0L;                                    // still walking / knocked off: restart dwell

        // Basically on top of the target but never settling — mid-arc counts as moving, so a bot
        // arc-jumping over the portal on a curved floor would hover here forever. Just act.
        if (nearTarget && now - trip.nearTargetSinceMs >= SNAP_ENTER_MS) {
            action.run();
            return;
        }

        // Soft-lock treadmill window (see SOFT_LOCK_* above): samples confined to one small
        // bounding box for the whole window — warp the hop and log where it was and what it
        // wanted, so recurring bad map+portal approaches can be mined from the log.
        if (trip.softLockSinceMs == 0L) {
            trip.softLockSinceMs = now;
            trip.softMinX = bp.x;
            trip.softMaxX = bp.x;
            trip.softMinY = bp.y;
            trip.softMaxY = bp.y;
        } else {
            trip.softMinX = Math.min(trip.softMinX, bp.x);
            trip.softMaxX = Math.max(trip.softMaxX, bp.x);
            trip.softMinY = Math.min(trip.softMinY, bp.y);
            trip.softMaxY = Math.max(trip.softMaxY, bp.y);
            if (trip.softMaxX - trip.softMinX > SOFT_LOCK_SPAN_PX
                    || trip.softMaxY - trip.softMinY > SOFT_LOCK_SPAN_PX) {
                trip.softLockSinceMs = now;                       // outgrew the box: real travel — reopen here
                trip.softMinX = bp.x;
                trip.softMaxX = bp.x;
                trip.softMinY = bp.y;
                trip.softMaxY = bp.y;
            } else if (now - trip.softLockSinceMs >= SOFT_LOCK_MS) {
                warp(bot, nextHop, "SOFT-LOCK: confined to "
                        + (trip.softMaxX - trip.softMinX) + "x" + (trip.softMaxY - trip.softMinY)
                        + "px for " + (SOFT_LOCK_MS / 1000) + "s on map " + bot.getMapId()
                        + " at (" + bp.x + "," + bp.y + "), intent: " + intent);
                return;
            }
        }

        // Progress = the bot is closing on the portal OR simply moving from where it stood. Straight-line
        // distance to the portal can plateau on a big map while the bot travels fine (climbing to an elevated
        // portal, detouring round terrain, getting knocked around by mobs), so a stalled distance alone is NOT
        // stuck. The position-displacement is measured from the last progress point (anchor), not the previous
        // tick, so slow steady movement still accumulates across the window. Only a bot that stays inside the
        // move epsilon for the whole window is genuinely wedged and gets warped.
        int dist = Math.abs(bp.x - dest.x) + Math.abs(bp.y - dest.y);
        boolean closingIn = dist < trip.hopBestDist - HOP_PROGRESS_EPS_PX;
        boolean moved = trip.lastPosX == Integer.MIN_VALUE
                || Math.abs(bp.x - trip.lastPosX) + Math.abs(bp.y - trip.lastPosY) > HOP_MOVE_EPS_PX;
        if (closingIn || moved) {
            if (closingIn) {
                trip.hopBestDist = dist;
            }
            trip.lastPosX = bp.x;
            trip.lastPosY = bp.y;
            trip.hopProgressAtMs = now;
        } else if (now - trip.hopProgressAtMs > HOP_STUCK_MS) {
            warp(bot, nextHop, "stuck " + (HOP_STUCK_MS / 1000) + "s — not moving toward hop target on map " + bot.getMapId());
            return;
        }

        if (!GCMovement.isMoving(bot)) {
            GCMovement.move(bot, dest.x, dest.y);                 // (re)issue the walk to the hop target
        }
    }

    private static Portal findPortalTo(MapleMap map, int targetMapId) {
        for (Portal p : map.getPortals()) {
            if (p.getTargetMapId() == targetMapId) {
                return p;
            }
        }
        return null;
    }

    private static void warp(Character bot, int mapId, String reason) {
        if (isDead(bot)) {
            return; // a corpse does not travel — BotDeath carries it home itself
        }
        BotLogger.log("[GCTravel] " + bot.getName() + " warped to map " + mapId + " — " + reason);
        try {
            bot.changeMap(mapId);
        } catch (Throwable ignored) {
            // a bad map id / lifecycle race shouldn't kill the trip; the next poll re-evaluates
        }
    }

    private static void warpToPortal(Character bot, int mapId, int portalId, String reason) {
        if (isDead(bot)) {
            return;
        }
        BotLogger.log("[GCTravel] " + bot.getName() + " warped to map " + mapId + " portal " + portalId + " — " + reason);
        try {
            bot.changeMap(mapId, portalId);
        } catch (Throwable ignored) {
            // a bad map id / lifecycle race shouldn't kill the trip; the next poll re-evaluates
        }
    }

    // A corpse does not travel: the poller runs on its own thread, so a bot killed mid-trip can
    // still have a poll in flight (cancel(false) does not interrupt one), and BotDeath reads the
    // bot's sanctuary off whatever map it wakes up on. isCorpse() also covers the window before
    // the episode starts — a bot the host zeroed via a map's decHP field (Aqua Road's breathing
    // damage) between movement ticks would otherwise be warped onward as a body.
    static boolean isDead(Character bot) {
        BotDeath death = BotDeath.of(bot);
        return death != null && death.isCorpse();
    }

    private static void finish(Trip trip, boolean ok) {
        TRIPS.remove(trip.bot.getId());
        if (trip.task != null) {
            trip.task.cancel(false);
        }
        // Clear the travel's move intent only — never tear down an active follow session (a
        // follow-driven trip arriving on the target's map must let GCFollow resume same-map follow).
        GCMovement.clearMoveIntent(trip.bot);
        // A trip can also end mid-crossing (cancelled, or the deck wait timed out), and the stroll
        // must not outlive the ride. Only stop it if the bot is still aboard, though: a trip that
        // ends on land may be handing the bot straight to something else that wants it walking
        // (a training bot browsing a shop), and that wander isn't ours to cancel.
        if (GCTransit.isVehicleMap(trip.bot.getMapId())) {
            BotWanderSystem.stop(trip.bot);
        }
        fire(trip.callback, ok);
    }

    private static void fire(Consumer<Boolean> cb, boolean ok) {
        if (cb != null) {
            try {
                cb.accept(ok);
            } catch (Throwable ignored) {
                // callback errors must not propagate into the poller
            }
        }
    }

    private static long nowMs() {
        return System.nanoTime() / 1_000_000L;
    }
}
