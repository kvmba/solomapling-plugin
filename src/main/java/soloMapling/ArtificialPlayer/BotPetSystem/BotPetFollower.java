package soloMapling.ArtificialPlayer.BotPetSystem;

import org.gms.client.Character;
import org.gms.client.inventory.Pet;
import org.gms.constants.game.CharacterStance;
import org.gms.net.packet.Packet;
import org.gms.server.life.Monster;
import org.gms.server.maps.Foothold;
import org.gms.server.maps.MapItem;
import org.gms.server.maps.MapObject;
import org.gms.server.maps.MapObjectType;
import org.gms.server.maps.MapleMap;
import org.gms.server.movement.AbsoluteLifeMovement;
import org.gms.server.movement.LifeMovementFragment;
import org.gms.util.PacketCreator;
import soloMapling.ArtificialPlayer.BotClientBinding;
import soloMapling.ArtificialPlayer.BotCommandsPack.DropCommands;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.ArtificialPlayer.GCMoveSystem.MapleMovement;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Moves a bot's pets so they follow it. One shared tick for every bot that has
 * pets (mirrors {@code GrindTickRegistry}) — no per-bot thread.
 *
 * <p>Each pet runs its OWN physics (the client only renders the position/fh we send)
 * rather than being glued to a snapshot of the owner — but through the ENGINE'S OWN
 * primitives ({@link GCMovement} / {@link MapleMovement}), so it has a bot's full terrain
 * ability: it walks slopes and steps up and down, is stopped by walls, runs off a ledge and
 * falls, hops to a reachable platform above, and swims. Its hop launches at the OWNER's own jump
 * speed and probes only as high as the owner can rise, so a platform the owner can jump onto is
 * never one its pet cannot reach. Each pet follows INDEPENDENTLY at its own random distance behind
 * the owner (facing-relative, {@link #FOLLOW_MIN_PX}..{@link #FOLLOW_MAX_PX} px) — there is no
 * pet-to-pet formation — and the distance is re-rolled every time the owner stops moving, so a
 * resting bot's pets settle at fresh, independent spots. Gravity only ever pulls a pet DOWN, so a
 * jumped owner never drags it into the air; a pet whose own feet leave the ground (its owner climbed
 * a platform, or either walked off a ledge) falls under gravity and lands on the floor below. A pet
 * left too far behind HORIZONTALLY warps to the owner's side (official behaviour) — a vertical owner
 * move (a jump or a fall) is followed with the pet's own physics instead. It swims (SWIM stance
 * 12/13) while its owner swims, or in a water map whenever its own feet find no ground; a
 * rope/ladder owner makes it hang (HANG, 30/31). Every warp (too far behind, owner unreachable
 * above, forbidFallDown) lands on a REAL footing via {@link #resolveSafeLanding}, and a pet that
 * leaves the map's VR bounds is snapped back to the owner as a last resort
 * ({@link #recoverIfFallenOffMap}) — so a lost-footing pet can never free-fall off the map.</p>
 *
 * <p><b>Map changes are the engine's job.</b> On map entry the engine's own
 * {@code MapleMap.addPlayer} re-places each pet at the owner's feet and re-sends it,
 * and every observer gets it via {@code spawnPlayerMapObject} — so a pet rides along
 * through any portal / warp / death carry-home with no code here. The follower skips the tick
 * on which the map changes so its own movement never races that host placement (which used to
 * make a pet flicker on the new map).</p>
 *
 * <p><b>LOD like the bot's own movement.</b> While no real player watches the map the pet's physics
 * and per-pet packet are skipped — the tick's dominant cost, and the reason the gate exists. Each
 * pet's POSITION is still kept fresh, because the host reads it to spawn the pet for a joining player:
 * a wholly frozen position would put the pet wherever it stood when the map went dark and make it snap
 * on the next tick. The snapshot is pinned to each pet's own follow slot and snapped to the floor under
 * it by the movement engine's own per-column index — the same probe the observed tick lands on, so the
 * two agree to the pixel and there is no spawn-then-snap; the fh travels with it (0 on a rope/water,
 * never a ground id that would drag a roped pet off the rope). The first tick after a player arrives
 * re-grounds / re-homes the pet the frame they arrive; the staleness is bounded to one tick, exactly
 * as the bot's coarse position is.</p>
 *
 * <p>Pets also occasionally play one of their own WZ interactions (a chat, a pose),
 * paced per pet, and — with looting gear — pick up nearby MONSTER drops (its owner's
 * or a free-for-all one) one item a second, through the engine's own pickup path; a
 * player-thrown item is left alone.</p>
 */
public final class BotPetFollower {

    // Pet stance bytes, taken from the named GMS095 client (CPet::OnResolveMoveAction
    // — the client's own physics-to-moveaction mapping; CPet::MoveAction2RawAction
    // then maps moveaction>>1 to an action index, moveaction&1 being the facing):
    //     MOVE  = 2/3    STAND = 4/5    JUMP = 6/7    SWIM = 12/13
    // (even = right, odd = left). A pet has no separate "fly" action — in water it
    // uses SWIM. NOTE: 0 is NOT a stand for a pet — moveaction>>1 == 0 lands on the
    // MOVE animation, so a resting pet sent 0 would keep walking on the spot. (The
    // Journey/maplestory-wasm client's MOVE=0/1 table disagrees with the official
    // client and is deliberately not used.)
    private static final int PET_MOVE_RIGHT = 2;
    private static final int PET_MOVE_LEFT = 3;
    private static final int PET_STAND_RIGHT = 4;
    private static final int PET_STAND_LEFT = 5;
    private static final int PET_JUMP_RIGHT = 6;
    private static final int PET_SWIM_RIGHT = 12;
    private static final int PET_SWIM_LEFT = 13;
    private static final int PET_HANG_RIGHT = 30;
    private static final int PET_HANG_LEFT = 31;

    // ── fh (foothold id) sent with a pet — SHARED RULE with the bot ──────────────
    // fh is LIVE data, not decoration: the client reads the 16-bit value and snaps the entity onto
    // the named foothold's footing; a rope/ladder index (high bit set) instead binds it to that rope's
    // render page. The bot follows the same rule (see BotMovementManager.resolveBroadcastFhId — the
    // authoritative note, incl. the rope two's-complement encoding). For BOTH:
    //   • on land: send the REAL foothold id under the entity. The observed follow (followLand) and
    //     the unobserved snapshot (syncUnobservedPositions) must both be the SAME surface, or a
    //     joining player sees the entity spawn on one surface and the next observed tick pull it to
    //     another.
    //   • off land (rope/ladder, water, mid-air): never send the ground id under the entity — a
    //     non-zero id forces the client to snap it onto that foothold, off the rope / out of the air.
    //     The bot sends its rope's negative index (the encoding above); a PET has no rope-index form,
    //     so it sends 0 and conveys the rope through the HANG stance instead.
    //   • SUMMON (BotPetController.placeAtBot and the host's MapleMap.addPlayer re-summon): fh is
    //     always 0. A bot pet is COORDINATE-DRIVEN — the client's fh-based foothold landing applies
    //     to a real player's own character, not to a bot entity, so fh 0 tells the client to place
    //     the pet at the coordinates we send and not adhere it to a foothold. The pet starts at the
    //     owner's position; the follower grounds it from its own following ticks.
    // Every fh passed below is one of those cases; do not invent another.

    // The pet runs its OWN physics (the client only renders the position/fh/velocity
    // we send), but through the ENGINE'S OWN primitives (GCMovement / MapleMovement) so it
    // climbs, descends, hops and swims exactly like a bot — see followLand / followSwim.
    /** The engine's own tick — the frame its physics integrates on. The pet sub-steps to
     *  this so a hop and a swim run at the bot's integration rate. */
    private static final double BOT_TICK_S = GCMovement.botTickMs() / 1000.0;
    /** Air drag (bot cfg): px/s^2 toward 0, rising at terminal fall, scaled by fs. */
    private static final double AIR_DRAG_PXSS = 1.0;
    private static final double AIR_DRAG_TERMINAL_PXSS = 100.0;
    /** The bot's swim-jump cooldown — a pet bursts up at the same cadence. */
    private static final long SWIM_BURST_COOLDOWN_MS = 500L;
    /** Vertical band (px): the pet holds UP only once it has sunk this far below the target
     *  (mirrors the bot's cfg.SWIM_LEVEL_BAND_PX). */
    private static final int SWIM_LEVEL_BAND_PX = 30;
    private static final int JUMP_REACH_PX = 160;           // owner above this => warp instead
    /** Safety margin (px) under the owner's true hop rise for the up-probe bound: the probe must
     *  never offer a floor the launch cannot actually clear (see {@link GCMovement#jumpProfile}). */
    private static final int JUMP_RISE_MARGIN_PX = 8;
    private static final int GROUND_SNAP_PX = 6;            // "standing on the floor" tolerance
    private static final int LOST_PX = 500;                 // 1-D horizontal gap -> warp to the owner
    /** Slack (px) past the map's VR rectangle below/left/right at which a pet is treated as
     *  fallen out of the map and snapped back to the owner. Mirrors the bot driver's own
     *  fall-off-map recovery (see {@link #recoverIfFallenOffMap}). */
    private static final int FALL_OFF_MAP_SLACK_PX = 240;
    /** The pet holds still until its target drifts this far from its spot (a real pet does not
     *  shuffle after every tiny step — it waits, then follows). Once it IS moving it keeps going
     *  until within {@link #FOLLOW_ARRIVE_PX}, so a pet settles at its spot instead of drifting. */
    private static final int FOLLOW_DEAD_ZONE_PX = 30;
    /** How close (px) counts as "in the slot": a pet that is already moving stops here, so a pet
     *  holds a tight, even spacing instead of drifting anywhere inside the wider dead zone. */
    private static final int FOLLOW_ARRIVE_PX = 4;
    /** Vertical tolerance (px) for treating a floor as the owner's own level. */
    private static final int GROUND_STEP_PX = 40;

    // Each pet follows INDEPENDENTLY behind the owner, at its own random distance in
    // [FOLLOW_MIN_PX, FOLLOW_MAX_PX] measured along the owner's facing (so a pet is never sent in
    // front of the owner). There is no pet-to-pet formation: a pet's spot depends only on the
    // owner, never on where another pet stands. The distance is re-rolled each time the owner STOPS
    // moving (see {@link #computeFollowTargetXs}), so a resting bot's pets settle at fresh, spread-out spots and
    // do not shuffle while it walks.
    private static final int FOLLOW_MIN_PX = 30;
    private static final int FOLLOW_MAX_PX = 80;

    /** Bots that currently have pets — the only ones a tick visits. */
    private static final Set<Integer> TRACKED = ConcurrentHashMap.newKeySet();
    /** Per-pet next allowed speak time (epoch ms), keyed by pet unique id. */
    private static final Map<Integer, Long> nextSpeakAtMs = new ConcurrentHashMap<>();
    /** Per-pet next allowed pickup time (epoch ms), keyed by pet unique id. */
    private static final Map<Integer, Long> nextPickupAtMs = new ConcurrentHashMap<>();
    /** Per-pet vertical fall velocity (px/s, positive = downward), keyed by pet unique id. */
    private static final Map<Integer, Double> fallVy = new ConcurrentHashMap<>();
    /** Per-pet horizontal velocity (px/s), keyed by pet unique id. */
    private static final Map<Integer, Double> velX = new ConcurrentHashMap<>();
    /** Per-pet next allowed swim burst (epoch ms), keyed by pet unique id. */
    private static final Map<Integer, Long> nextSwimBurstAtMs = new ConcurrentHashMap<>();
    /** Pets currently mid-hop (tracking an air arc), keyed by pet unique id. */
    private static final Set<Integer> vyAir = ConcurrentHashMap.newKeySet();
    /** Per-bot last-seen map id — detects a map change so the pet skips the tick the host
     *  re-places it (avoids racing the host's placement with a stale-coordinate warp). */
    private static final Map<Integer, Integer> lastMapIdByBot = new ConcurrentHashMap<>();
    /** Per-bot whether its owner was moving on the previous tick — the fall of this edge (the owner
     *  stopping) re-rolls every pet's follow distance, so a resting bot's pets pick fresh spots. */
    private static final Map<Integer, Boolean> lastOwnerMovingByBot = new ConcurrentHashMap<>();
    /** Per-pet follow distance (px) along the owner's facing, keyed by pet unique id. Re-rolled
     *  whenever the owner stops; a pet with no entry yet (freshly granted) draws one on its first tick. */
    private static final Map<Integer, Integer> followDistanceByPet = new ConcurrentHashMap<>();
    private static ScheduledFuture<?> task;

    private BotPetFollower() {
    }

    public static synchronized void start(BotPetConfig config) {
        if (task != null) {
            return;
        }
        rescanTrackedBots();
        long period = Math.max(100L, config.followTickMs());
        task = soloMapling.server.ExecutorServiceManager.getScheduledExecutorService()
                .scheduleAtFixedRate(() -> tick(config), period, period, TimeUnit.MILLISECONDS);
        System.out.println("[BotPetFollower] started, period=" + period + "ms");
    }

    public static synchronized void stop() {
        if (task != null) {
            task.cancel(false);
            task = null;
        }
        TRACKED.clear();
        nextSpeakAtMs.clear();
        nextPickupAtMs.clear();
        fallVy.clear();
        velX.clear();
        vyAir.clear();
        nextSwimBurstAtMs.clear();
        lastMapIdByBot.clear();
        lastOwnerMovingByBot.clear();
        followDistanceByPet.clear();
    }

    /**
     * Rebuild the tracked set from live bots. start() runs after a reload too, and
     * stop() clears the set — without this, a {@code !botpet reload} would leave
     * every already-petted bot unfollowed.
     */
    private static void rescanTrackedBots() {
        TRACKED.clear();
        for (Integer id : new java.util.ArrayList<>(
                soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage.getAllBots().keySet())) {
            Character chr = soloMapling.server.SoloMaplingUtilities.getChr(id);
            if (chr != null && chr.getNoPets() > 0) {
                TRACKED.add(id);
            }
        }
    }

    /** Begin following a bot's pets. Called by {@link BotPetController} once a bot has pets. */
    public static void track(int botId) {
        TRACKED.add(botId);
    }

    /** Forget a bot's state (called when its pets are removed or it leaves the world). */
    public static void forget(int botId) {
        TRACKED.remove(botId);
        lastMapIdByBot.remove(botId);
        lastOwnerMovingByBot.remove(botId);
    }

    private static void tick(BotPetConfig config) {
        for (Integer botId : TRACKED) {
            // A broken bot must never abort the tick for every other bot (same
            // convention as GrindTickRegistry's per-participant isolation).
            try {
                tickBot(botId, config);
            } catch (Throwable t) {
                System.err.println("[BotPetFollower] tick error for bot " + botId + ": " + t);
            }
        }
    }

    private static void tickBot(int botId, BotPetConfig config) {
        // Resolve through player storage, not the BotSM: pets are granted
        // during createBot, which runs before the bot is wrapped in a BotSM
        // (setAndStartBots does that afterwards). Player storage already has
        // the bot at grant time, so this never drops a freshly-petted bot.
        Character chr = soloMapling.server.SoloMaplingUtilities.getChr(botId);
        if (chr == null || chr.getMap() == null || chr.getNoPets() == 0) {
            forget(botId); // bot gone / mid-retype / pets removed (also drops lastMapIdByBot)
            return;
        }
        MapleMap map = chr.getMap();
        // LOD, like the bot's own movement: while no real player watches the map, skip the pet's
        // physics and per-pet scheduling — the tick's dominant cost, and the reason this gate exists
        // (an unwatched world of thousands of bots stays cheap). But FIRST keep every pet's POSITION
        // fresh: the host reads pet.getPos() to spawn a pet for a joining player
        // (spawnPlayerMapObject), so a wholly frozen position would land the pet wherever it was when
        // the map went dark and make it snap/teleport on the next tick. The snapshot is one INDEXED
        // ground probe per pet (the same the observed tick lands on) plus arithmetic — no host tree
        // query, no packet (see syncUnobservedPositions).
        boolean observed = GCMovement.isMapObserved(chr.getMapId());
        if (!observed) {
            syncUnobservedPositions(chr, config);
        }
        if (!shouldResolveFoothold(false, observed)) {
            return; // LOD: nobody can see the pets — position kept fresh, physics/ground work skipped
        }

        // Iterate by the pet-ARRAY index, not a running count: the slot in
        // MOVE_PET / PET_COMMAND is the array index (the host sends it from
        // getPetIndex), so skipping a null without advancing would mis-slot a
        // pet that sits after a hole. Same loop shape as collectLoot() below.
        Pet[] pets = chr.getPets();

        // Map change: the host's MapleMap.addPlayer has already re-placed every pet at the
        // owner's feet and re-broadcast it (showPet / spawnPlayerMapObject). Do NOT also move it
        // this tick — a competing MOVE_PET or warp on the entry frame is what made the pet
        // flicker in the new map. Drop stale motion and let the host's placement be the pet's
        // frame-0 position; the next tick follows normally.
        Integer previousMap = lastMapIdByBot.put(botId, chr.getMapId());
        if (previousMap != null && previousMap != chr.getMapId()) {
            for (Pet pet : pets) {
                if (pet != null) {
                    clearMotion(pet.getUniqueId());
                }
            }
            return;
        }

        // The owner STOPPING is the moment to re-roll every pet's follow distance, so a resting
        // bot's pets pick fresh, independent spots (see the FOLLOW_MIN/MAX note). lastOwnerMovingByBot
        // makes this a one-shot edge, not every tick of the stop.
        boolean ownerMoving = GCMovement.isMoving(chr);
        if (Boolean.TRUE.equals(lastOwnerMovingByBot.put(botId, ownerMoving)) && !ownerMoving) {
            rerollFollowDistances(pets);
        }

        // Each pet's own target x: its independent distance behind the owner (no pet-to-pet formation).
        int[] followX = computeFollowTargetXs(chr, pets);

        // Swim when the owner is swimming, OR — mirroring the bot engine's own rule
        // (isSwimMap && inAir) — when the map is a swim map and the pet's own feet find no
        // ground: on a platform in a swim map the pet walks, but once it is in the water
        // column it must SWIM (with the VR-bottom clamp), NOT fall. The land fall has no
        // bottom clamp, so a pet over open water used to plummet straight out of the map
        // (and the map-entry drop into open water did the same) until the fall-off-map
        // recovery caught it far below. Using the map flag alone would instead keep the pet
        // swimming while the owner walks a platform, so gate on the pet's own footing.
        boolean swimMap = map.isSwim();
        boolean ownerSwimming = CharacterStance.isSwimming(chr.getStance());
        for (int idx = 0; idx < pets.length; idx++) {
            Pet pet = pets[idx];
            if (pet == null) {
                continue;
            }
            // Fallback: a pet that has fallen out of the map's VR bounds (below the floor or off a
            // side) is snapped back to the owner. Land falls have no bottom clamp, so nothing else
            // catches a pet that lost its footing into open space — without this it would free-fall
            // (and re-warp) forever. Runs before the swim/land split so it also rescues a pet that
            // sank out of a swim map.
            if (recoverIfFallenOffMap(chr, pet, idx, map, config, observed)) {
                continue;
            }
            // One footing probe per pet per tick, shared by the swim decision and the land walk
            // (it used to be probed twice on a swim map). Skipped entirely while the owner swims —
            // every pet swims then, so the surface under it is not consulted.
            Foothold standing = ownerSwimming ? null : findStandingFoothold(map, pet.getPos());
            boolean swim = ownerSwimming || (swimMap && standing == null);
            if (swim) {
                followSwim(chr, pet, idx, followX[idx], config, observed);
            } else {
                followLand(chr, pet, idx, followX[idx], standing, config, observed);
            }
            if (observed) {
                maybeSpeak(chr, pet, idx, config);
            }
        }
        if (observed && chr.getHp() > 0) {
            collectLoot(chr, map, pets, config);
        }
    }

    /**
     * Land follow, run as the pet's own physics (see the class note). The owner is a
     * moving target: the pet accelerates toward a point beside it (momentum, so it
     * lags then catches up rather than being snapped on) and falls under gravity when
     * its own feet are unsupported. Gravity only ever pulls DOWN, so a jumped or
     * falling owner never drags the pet up; a pet left too far from the owner is
     * warped to a real footing beside it ({@link #teleportPet} / {@link #resolveSafeLanding}).
     */
    private static void followLand(Character chr, Pet pet, int index, int targetX, Foothold standing,
                                   BotPetConfig config, boolean observed) {
        Point p = pet.getPos();
        MapleMap map = chr.getMap();
        Integer id = pet.getUniqueId();
        boolean left = isPetFacingLeft(pet);
        double dt = Math.max(0.05, config.followTickMs() / 1000.0);

        // Airborne tracking: targetX + vy once a hop starts, until it lands.
        double ax = velX.getOrDefault(id, 0.0);
        double ay = fallVy.getOrDefault(id, 0.0);
        boolean air = vyAir.contains(id);

        // Rope/ladder owner: the pet hangs on the owner's back (HANG pose) — no physics.
        if (CharacterStance.isClimbing(chr.getStance())) {
            clearMotion(id);
            // fh 0: a pet on a rope must report no foothold id — a non-zero one makes the client
            // force the pet onto that foothold and off the rope (see the fh rule at the top).
            applyAndBroadcast(chr, pet, index, chr.getPosition(), 0, 0, 0,
                    left ? PET_HANG_LEFT : PET_HANG_RIGHT, config, observed);
            return;
        }

        Point owner = chr.getPosition();

        // Warp (official: remove -> reposition -> respawn) when left behind: a large
        // HORIZONTAL lead the walk cannot make up, or an owner settled far ABOVE (a pet
        // can hop one platform but not a long climb). A jumping owner is ignored (its
        // higher y is transient).
        if (Math.abs(p.x - owner.x) > LOST_PX || (!CharacterStance.isJumping(chr.getStance())
                && owner.y < p.y - JUMP_REACH_PX)) {
            clearMotion(id);
            WarpLanding land = resolveSafeLanding(map, targetX, owner);
            teleportPet(chr, pet, index, land.pos(), land.fh(),
                    left ? PET_STAND_LEFT : PET_STAND_RIGHT, config, observed);
            return;
        }

        int nx, ny, fhVal, stance;
        if (air) {
            // Hop / drop arc, run on the bot's own terms (see simulateAirStep): sub-stepped Verlet
            // with the bot's air drag, landing via the same per-pixel terrain sweep its
            // airborne physics resolves with.
            AirStep step = simulateAirStep(map, p, ax, ay, dt);
            nx = step.point().x;
            ny = step.point().y;
            if (step.landed() != null) {
                vyAir.remove(id);
                ax = 0;
                ay = 0;
                fhVal = step.landed().getId(); // landed: the real foothold id (fh rule)
                stance = left ? PET_STAND_LEFT : PET_STAND_RIGHT;
            } else {
                ax = step.ax();
                ay = step.ay();
                fhVal = 0; // mid-air: no foothold — fh must be 0 (fh rule)
                stance = (left ? 1 : 0) | PET_JUMP_RIGHT;
            }
            velX.put(id, ax);
            fallVy.put(id, ay);
            applyAndBroadcast(chr, pet, index, new Point(nx, ny),
                    (int) Math.round(ax), (int) Math.round(ay), fhVal, stance, config, observed);
            return;
        }

        // Terrain under the pet, via the bot's OWN bidirectional probe — the same
        // findGroundFoothold the engine walks a bot with (a surface at the point, up to
        // MAX_SLOPE_UP above, or a step below). Accepted only within a step of the feet:
        // findGroundFoothold has no drop cap, so a floor far below (a swim map's seabed) must
        // not read as "standing". The old down-only probe reported "no ground" on any uphill
        // surface, so the pet could only cross flat ground: it fell at every slope (the
        // up/down bob) and never registered a platform to hop from.
        // (Provided by the caller, which already probed it once for the swim decision.)
        boolean ownerBelow = owner.y > p.y + GROUND_STEP_PX;
        if (ownerBelow && standing != null && standing.isForbidFallDown()) {
            // A forbidFallDown platform is never pass-through, so the pet cannot drop. Warp onto a
            // real footing at the owner's level (resolveSafeLanding keeps it on a surface within a step of
            // the owner, not the owner's raw y over a gap). fh is the landed foothold id, or 0 when
            // no surface is in range (the fh rule: a bogus id would snap it to the wrong surface).
            WarpLanding land = resolveSafeLanding(map, targetX, owner);
            teleportPet(chr, pet, index, land.pos(), land.fh(),
                    left ? PET_STAND_LEFT : PET_STAND_RIGHT, config, observed);
            return;
        }
        // Hop up whenever the owner is meaningfully above and a floor one hop up reaches
        // toward it; otherwise (owner above but unreachable) warp, so the pet never hops
        // at a wall forever. The probe must look ABOVE the pet — a plain findBelow only ever
        // reports floors below it — and only as far as the pet's hop can actually rise. That
        // rise comes from the OWNER's own jump profile ({@link GCMovement#jumpProfile}), so a platform
        // the owner can jump onto is never one its pet cannot reach (the height-parity bug: the
        // pet used to launch at the base-stat 555 px/s and fall short of a higher-jump bot's
        // reach). The probe stops {@link #JUMP_RISE_MARGIN_PX} short of the true rise so it never
        // offers a floor the launch cannot quite clear.
        // A jumping owner is ignored here too (same rule as the warp above): its higher y is
        // transient. Without this guard the branch fires on every jump — a pet on flat ground
        // finds no floor within a hop, so it is warped up to the owner's mid-air y, then falls,
        // then warps again (a visible flicker) instead of waiting for the owner to land.
        boolean ownerAbove = owner.y < p.y - GROUND_STEP_PX;
        if (standing != null && !ownerBelow && ownerAbove
                && !CharacterStance.isJumping(chr.getStance())) {
            GCMovement.JumpProfile jump = GCMovement.jumpProfile(chr);
            int probeRise = Math.max(GROUND_SNAP_PX + 1, jump.risePx() - JUMP_RISE_MARGIN_PX);
            Point above = GCMovement.groundAbove(map, owner.x, p.y, probeRise);
            boolean canHop = above != null && above.y < p.y - GROUND_SNAP_PX;
            if (!canHop) {
                // Owner above but no floor within a hop: warp onto a real footing at the owner's
                // level (resolveSafeLanding keeps it on a surface within a step of the owner, never the
                // owner's raw y over a gap). fh = the landed foothold id, or 0 when none is in range.
                WarpLanding land = resolveSafeLanding(map, targetX, owner);
                teleportPet(chr, pet, index, land.pos(), land.fh(),
                        left ? PET_STAND_LEFT : PET_STAND_RIGHT, config, observed);
                return;
            }
            double hopVx = GCMovement.walkVelocityPxs(chr);
            vyAir.add(id);
            ax = Math.signum(owner.x - p.x) * hopVx;
            if (ax == 0) {
                ax = hopVx;
            }
            ay = -jump.jumpSpeedPxs();
            velX.put(id, ax);
            fallVy.put(id, ay);
            // fh 0: the hop launches airborne — no foothold (fh rule).
            applyAndBroadcast(chr, pet, index, p, (int) Math.round(ax), (int) Math.round(ay), 0,
                    (left ? 1 : 0) | PET_JUMP_RIGHT, config, observed);
            return;
        }

        // Walk with the engine's OWN ground integrator: the pet steps UP and DOWN slopes and
        // ledges, is blocked by walls and detected walking off an edge — identically to a bot.
        // Hysteresis, so pets do not crowd: start moving only once the target passes the wide dead
        // zone (a tiny owner step leaves the pet standing), but keep going until within
        // FOLLOW_ARRIVE_PX, so a pet settles at its spot at a tight spacing.
        //
        // The follow tick is many engine ticks long (followTickMs default 300 vs a 50ms bot tick),
        // so one held tick carries the pet ~6x as far as FOLLOW_ARRIVE_PX: the band has to cover the
        // momentum the pet cannot shed inside a single step, or it sails past its spot, sees the
        // spot behind it on the next tick, and paces — the pet-side half of the owner's left-right
        // sway (mapGroundSlipScale is per-map, so a snow map carries even farther). Widening the
        // moving band by the input-free glide-out lets the pet coast the last stretch in.
        double gap = targetX - p.x;
        int stopBand = Math.abs(ax) > 1 ? FOLLOW_ARRIVE_PX : FOLLOW_DEAD_ZONE_PX;
        if (Math.abs(ax) > 1) {
            stopBand += (int) Math.ceil(
                    MapleMovement.stopOutPxs(ax, MapleMovement.slipScale(map)));
        }
        int followDir = Math.abs(gap) > stopBand ? (int) Math.signum(gap) : 0;
        GCMovement.GroundWalk walk = GCMovement.walkGroundTick(
                map, p, standing, followDir, ax, config.followTickMs(), chr);
        double vx = walk.velocityPxs();
        velX.put(id, vx);

        // The pet falls when the walk ran off an edge, OR when its owner has dropped to a surface
        // below and the walk did NOT bring the pet down to it. That second case is what separates a
        // down-slope from a drop: walking DOWN a slope lowers the pet (walk.point().y > p.y, handled
        // by the engine's own snap), so no fall is needed; standing on a ledge the owner has left
        // keeps the pet level, so it must fall — a genuine straight drop, not a downhill glide.
        boolean ownerBelowAndNotWalkingDown = ownerBelow && walk.point().y <= p.y;
        if (walk.lostGround() || ownerBelowAndNotWalkingDown) {
            // Fall from the walk's end point (the edge, or p + this tick's horizontal step) through
            // the engine's per-pixel sweep, keeping the horizontal step — like the bot's beginFall.
            AirStep step = simulateAirStep(map, walk.point(), vx, 0.0, dt);
            nx = step.point().x;
            ny = step.point().y;
            if (step.landed() != null) {
                fhVal = step.landed().getId();
                stance = left ? PET_STAND_LEFT : PET_STAND_RIGHT;
                vyAir.remove(id);
                fallVy.put(id, 0.0);
            } else {
                vyAir.add(id);
                fallVy.put(id, step.ay());
                fhVal = 0;
                stance = (left ? 1 : 0) | PET_JUMP_RIGHT;
            }
        } else {
            nx = walk.point().x;
            ny = walk.point().y;
            fhVal = walk.foothold() != null ? walk.foothold().getId() : 0;
            vyAir.remove(id);
            fallVy.put(id, 0.0);
            stance = Math.abs(vx) <= 1
                    ? (left ? PET_STAND_LEFT : PET_STAND_RIGHT)
                    : (vx > 0 ? PET_MOVE_RIGHT : PET_MOVE_LEFT);
        }
        applyAndBroadcast(chr, pet, index, new Point(nx, ny),
                (int) Math.round(vx), 0, fhVal, stance, config, observed);
    }

    /**
     * One sub-stepped airborne tick for the pet, on the bot's own terms: position-Verlet with
     * the engine's gravity and air drag, resolved through the same per-pixel terrain sweep the
     * bot's airborne physics uses (so slopes and thin platforms are honoured, not tunnelled).
     * Stops at the first terrain hit — a landing ({@link AirStep#landed()} set) or a wall /
     * ceiling (both velocity components shed: pets are not touched by monsters, so terrain is
     * all that deflects them).
     */
    private static AirStep simulateAirStep(MapleMap map, Point p, double ax, double ay, double dt) {
        double fs = MapleMovement.slipScale(map);
        double cx = p.x;
        double cy = p.y;
        Foothold land = null;
        int steps = Math.max(1, (int) Math.ceil(dt / BOT_TICK_S));
        double t = dt / steps;
        for (int i = 0; i < steps; i++) {
            double drag = (ay >= MapleMovement.MAX_FALL_PXS - 1e-6 ? AIR_DRAG_TERMINAL_PXSS : AIR_DRAG_PXSS) * fs * t;
            ax -= Math.signum(ax) * Math.min(Math.abs(ax), drag);
            double sx = cx + ax * t;
            double sy = cy + ay * t + 0.5 * MapleMovement.GRAVITY_PXS2 * t * t;
            double nextVy = Math.min(MapleMovement.MAX_FALL_PXS, ay + MapleMovement.GRAVITY_PXS2 * t);

            Point from = new Point((int) Math.round(cx), (int) Math.round(cy));
            Point to = new Point((int) Math.round(sx), (int) Math.round(sy));
            GCMovement.AirHit hit = GCMovement.sweepAir(map, from, to);
            if (hit == null) {
                cx = sx;
                cy = sy;
                ay = nextVy;
                continue;
            }
            cx = hit.point().x;
            cy = hit.point().y;
            if (hit.landing()) {
                land = hit.foothold();
            } else {
                ax = 0;
                ay = 0;
            }
            break;
        }
        return new AirStep(new Point((int) Math.round(cx), (int) Math.round(cy)), ax, ay, land);
    }

    /** Outcome of {@link #simulateAirStep}: the new point, the shed/tracked velocity (px/s) and the
     *  foothold landed on ({@code null} when still airborne or stopped by a wall/ceiling). */
    private record AirStep(Point point, double ax, double ay, Foothold landed) {
    }

    /**
     * The foothold the pet is actually STANDING on — the engine's bidirectional probe
     * ({@link GCMovement#groundFoothold}) accepted only within {@link #GROUND_STEP_PX} of the
     * feet. The raw probe has no drop cap (it returns the first floor at ANY depth, e.g. a swim
     * map's seabed hundreds of px down), so it cannot answer "is the pet grounded": this bounds
     * it. Null when the pet is over a gap, or its floor is too far to be its footing.
     */
    private static Foothold findStandingFoothold(MapleMap map, Point p) {
        Foothold fh = GCMovement.groundFoothold(map, p);
        return fh != null && Math.abs(p.y - fh.calculateFooting(p.x)) <= GROUND_STEP_PX ? fh : null;
    }

    /**
     * A warp landing that keeps the pet on a REAL footing instead of dropping it off a ledge or into
     * a gap. The old warps used the raw {@link GCMovement#groundFoothold} probe (no drop cap: it
     * returns the first floor at ANY depth, e.g. a platform far below, or a swim map's seabed) — so a
     * warp could deposit the pet far below the owner, or at the owner's raw y over empty air (a pet
     * dropped into space then falls / re-warps every tick: a flicker loop). This tries the pet's own
     * slot {@code (targetX, owner.y)} first — accepted only when a floor sits within {@link #GROUND_STEP_PX}
     * of the owner's level ({@link #findStandingFoothold}) — then falls back to the owner's OWN column, whose
     * floor is almost always valid. The returned fh is that floor's id, or 0 when neither column has a
     * floor in range (the fh rule: land => real id, no floor => 0, never a bogus id).
     */
    private static WarpLanding resolveSafeLanding(MapleMap map, int targetX, Point owner) {
        Foothold onSlot = findStandingFoothold(map, new Point(targetX, owner.y));
        if (onSlot != null) {
            return new WarpLanding(new Point(targetX, onSlot.calculateFooting(targetX)), onSlot.getId());
        }
        Foothold onOwner = findStandingFoothold(map, owner);
        if (onOwner != null) {
            return new WarpLanding(new Point(owner.x, onOwner.calculateFooting(owner.x)), onOwner.getId());
        }
        // No footing within a step at either column: keep the owner's raw level with fh 0 (safe only
        // because the slot is over a gap — the pet then follows normally and falls onto whatever is
        // below, with the same result as the surrounding follow logic).
        return new WarpLanding(new Point(targetX, owner.y), 0);
    }

    /** A warp landing: where to put the pet and the foothold id to report (0 = none in range). */
    private record WarpLanding(Point pos, int fh) {
    }

    /**
     * Fallback pull-back for a pet that has left the map: a pet whose y has fallen
     * {@link #FALL_OFF_MAP_SLACK_PX} below (or x off a side of) the map's VR rectangle is snapped
     * back beside its OWNER, exactly like the bot driver's own fall-off-map recovery. Land physics
     * has no bottom clamp, so a pet that lost its footing into open space would otherwise free-fall
     * forever (nothing else catches it); this bounds that. Returns true when it recovered the pet
     * (so the caller skips normal follow this tick).
     *
     * <p>Only lands on a real footing (via {@link #resolveSafeLanding}, which keeps the pet on a
     * surface within a step of the owner and reports the correct fh) and never warps on a map with
     * no usable VR bounds (nothing to say the pet is out).</p>
     */
    private static boolean recoverIfFallenOffMap(Character chr, Pet pet, int index, MapleMap map,
                                                 BotPetConfig config, boolean observed) {
        Rectangle area = map.getMapArea();
        if (area == null || area.width <= 0 || area.height <= 0) {
            return false; // no usable VR bounds — cannot tell inside from outside
        }
        Point p = pet.getPos();
        boolean belowFloor = p.y > area.y + area.height + FALL_OFF_MAP_SLACK_PX;
        boolean offSide = p.x < area.x - FALL_OFF_MAP_SLACK_PX
                || p.x > area.x + area.width + FALL_OFF_MAP_SLACK_PX;
        if (!belowFloor && !offSide) {
            return false;
        }
        clearMotion(pet.getUniqueId());
        boolean left = CharacterStance.isFacingLeft(chr.getStance());
        int facing = left ? -1 : 1;
        Point owner = chr.getPosition();
        int targetX = owner.x - facing * currentFollowDistance(pet);
        WarpLanding land = resolveSafeLanding(map, targetX, owner);
        teleportPet(chr, pet, index, land.pos(), land.fh(),
                left ? PET_STAND_LEFT : PET_STAND_RIGHT, config, observed);
        return true;
    }

    /**
     * Swim follow — the bot's swim model: the pet sinks under water gravity, and floats
     * back up (UP-held thrust, sink capped near zero) once it drops below the point above
     * the owner, with horizontal drag/accel. A sink-and-float bob that mirrors the bot's
     * swim. fh stays 0 in water. The target x is the pet's own follow slot ({@code targetX}, the
     * same independent offset as on land — no per-index stacking), and every pet floats at the
     * same {@link BotPetConfig#swimOffset} above the owner (independent, not a depth staircase).
     */
    private static void followSwim(Character chr, Pet pet, int index, int targetX,
                                   BotPetConfig config, boolean observed) {
        Point p = pet.getPos();
        Integer id = pet.getUniqueId();
        int targetY = chr.getPosition().y - config.swimOffset();
        double dt = Math.max(0.05, config.followTickMs() / 1000.0);
        vyAir.remove(id);

        if (Math.abs(p.x - targetX) > LOST_PX) {
            clearMotion(id);
            teleportPet(chr, pet, index, new Point(targetX, targetY), 0,
                    isPetFacingLeft(pet) ? PET_SWIM_LEFT : PET_SWIM_RIGHT, config, observed);
            return;
        }

        double vx = velX.getOrDefault(id, 0.0);
        double vy = fallVy.getOrDefault(id, 0.0);
        // The SHARED water model: hold toward the owner; when the owner is well above,
        // HOLD UP — the bot's own up mechanic is a burst (UP alone only slows the sink),
        // so we fire the same burst on the transition — else free-sink.
        // Same stillness rule as on land: only paddle once the target has drifted clear.
        // Hysteresis as on land: start once past the wide dead zone, keep paddling until within
        // FOLLOW_ARRIVE_PX, so a pet holds its spot instead of drifting.
        double dx = targetX - p.x;
        int stopBand = Math.abs(vx) > 1 ? FOLLOW_ARRIVE_PX : FOLLOW_DEAD_ZONE_PX;
        int moveDir = Math.abs(dx) > stopBand ? (int) Math.signum(dx) : 0;
        // y grows downward: the pet is BELOW the target when p.y > targetY (positive),
        // and only then should it hold UP (verticalHold -1). The old `targetY - p.y`
        // was negated, so it never fired while the pet sank — the pet only ever sank.
        int verticalHold = p.y - targetY > SWIM_LEVEL_BAND_PX ? -1 : 0;
        long now = System.currentTimeMillis();
        if (verticalHold < 0 && vy >= 0 && now >= nextSwimBurstAtMs.getOrDefault(id, 0L)) {
            vy = -MapleMovement.SWIM_JUMP_BURST_PXS; // rising burst (bot swim-jump)
            nextSwimBurstAtMs.put(id, now + SWIM_BURST_COOLDOWN_MS);
        }
        // The water drag is NOT step-size invariant (vx *= max(0, 1 - 4.21*t)): one 300ms step
        // zeroes vx outright, so the pet barely moved. Run the SAME 50ms sub-steps the bot's own
        // swim integrator uses — advancing the position by each sub-step's post-drag velocity, just
        // as the bot advances physX/physY — so the pet swims the same distance at the same speed.
        // This was the "swimming is far too slow" bug.
        int steps = Math.max(1, (int) Math.ceil(dt / BOT_TICK_S));
        double t = dt / steps;
        double cx = p.x;
        double cy = p.y;
        for (int i = 0; i < steps; i++) {
            MapleMovement.SwimStep swim = MapleMovement.swimStep(vx, vy, moveDir, verticalHold, t);
            vx = swim.vx();
            vy = swim.vy();
            cx += vx * t;
            cy += vy * t;
        }

        int nx = (int) Math.round(cx);
        int ny = (int) Math.round(cy);
        // Clamp at the map boundary (VR bottom) like the bot: tread water, don't sink out.
        int waterFloor = MapleMovement.swimFloorY(chr.getMap());
        if (ny > waterFloor) {
            ny = waterFloor;
            vy = 0;
        }
        velX.put(id, vx);
        fallVy.put(id, vy);
        applyAndBroadcast(chr, pet, index, new Point(nx, ny),
                (int) Math.round(vx), (int) Math.round(vy), 0,
                vx >= 0 ? PET_SWIM_RIGHT : PET_SWIM_LEFT, config, observed);
    }

    private static void clearMotion(Integer id) {
        velX.remove(id);
        fallVy.remove(id);
        vyAir.remove(id);
    }

    /**
     * Drop every per-pet entry for this pet. Pet ids are handed out monotonically
     * ({@code BotPetFactory}) and never reused, so without this the per-pet maps would
     * grow for the life of the process as bots come and go.
     */
    public static void forgetPet(int petId) {
        velX.remove(petId);
        fallVy.remove(petId);
        vyAir.remove(petId);
        nextSpeakAtMs.remove(petId);
        nextPickupAtMs.remove(petId);
        nextSwimBurstAtMs.remove(petId);
        followDistanceByPet.remove(petId);
    }

    /**
     * Whether this tick should resolve the pet's foothold/gravity work. True only when
     * the pet is landed (gravity applies) AND a real player can see the map: the ground
     * snap and the fh it reports both cost a foothold lookup, and on an unwatched map
     * nobody can see the pet, so the query is pure waste. This is the pet tick's
     * dominant cost (one lookup per pet per 200ms), so the LOD gate keeps the follower
     * nearly free while the world is unwatched; the first tick after a player arrives
     * resumes it.
     */
    static boolean shouldResolveFoothold(boolean noGravity, boolean observed) {
        return !noGravity && observed;
    }

    /**
     * The follow target x for every pet slot: each pet's OWN distance behind the owner, measured
     * along the owner's facing (so a pet is never sent in front of it). There is NO pet-to-pet
     * formation — a pet's spot depends only on the owner and its own random distance (see
     * {@link #FOLLOW_MIN_PX}..{@link #FOLLOW_MAX_PX}, re-rolled on each owner stop by
     * {@link #rerollFollowDistances}). A pet with no recorded distance yet (freshly granted, or the
     * first unobserved tick) draws one here, so the observed follow and the unobserved position sync
     * never disagree.
     */
    private static int[] computeFollowTargetXs(Character chr, Pet[] pets) {
        int[] followX = new int[pets.length];
        int facing = CharacterStance.isFacingLeft(chr.getStance()) ? -1 : 1;
        int ownerX = chr.getPosition().x;
        for (int i = 0; i < pets.length; i++) {
            Pet pet = pets[i];
            if (pet == null) {
                continue;
            }
            followX[i] = ownerX - facing * currentFollowDistance(pet);
        }
        return followX;
    }

    /**
     * This pet's own follow distance (px) behind the owner: its recorded value, or a fresh random
     * one drawn (and recorded) on first use — so a freshly granted pet and an unobserved-tick pet
     * both get a stable distance that the observed follow and the position sync then agree on.
     */
    private static int currentFollowDistance(Pet pet) {
        return followDistanceByPet.computeIfAbsent(pet.getUniqueId(), id -> randomFollowDistance());
    }

    /** Re-roll every present pet's follow distance — called the moment the owner stops moving, so a
     *  resting bot's pets pick fresh, independent spots instead of holding one fixed distance. */
    private static void rerollFollowDistances(Pet[] pets) {
        for (Pet pet : pets) {
            if (pet != null) {
                followDistanceByPet.put(pet.getUniqueId(), randomFollowDistance());
            }
        }
    }

    /** A fresh follow distance (px) in {@code [FOLLOW_MIN_PX, FOLLOW_MAX_PX]}. Package-private test
     *  seam (like {@link #shouldResolveFoothold}): pins the band a pet's random offset must fall in. */
    static int randomFollowDistance() {
        return FOLLOW_MIN_PX + ThreadLocalRandom.current().nextInt(FOLLOW_MAX_PX - FOLLOW_MIN_PX + 1);
    }

    /** The follow-distance band (px) — {@code [min, max]} — a pet's independent offset draws from.
     *  Exposed so a test can pin the band without duplicating the constants. */
    static int[] followDistanceRange() {
        return new int[]{FOLLOW_MIN_PX, FOLLOW_MAX_PX};
    }

    /**
     * Keep the pets' positions fresh on an UNOBSERVED map — snapshot arithmetic plus one INDEXED
     * ground probe per pet, no packet. The host reads {@code pet.getPos()} when it spawns a pet for a
     * joining player ({@code spawnPlayerMapObject}), so a pet frozen where it stood when the map went
     * dark would appear there and then snap when the tick resumes; pinning each pet to its own follow
     * slot beside the owner bounds that staleness to one tick.
     *
     * <p>The snapshot must agree with what the observed tick will land the pet on, or the player sees
     * it spawn in one place and get corrected the next frame. Two things matter:
     *
     * <ul>
     *   <li><b>y.</b> The slot's y is the floor UNDER it, via the movement engine's own per-column
     *       index ({@link GCMovement#groundFoothold}) — the same probe the observed tick lands on. The
     *       probe runs from the pet's OWN current y (its last footing), never the owner's: the owner
     *       may be mid-jump, and its y would strand the pet in mid-air. Only a floor within a step of
     *       that footing is accepted; otherwise (the slot is off a ledge or over a gap) the pet keeps
     *       its own y and the observed tick's lost-ground fall takes over on arrival.</li>
     *   <li><b>fh.</b> On land the true foothold id is sent, exactly as the observed tick does. On a
     *       rope/ladder (or in water) fh is 0 — a pet on a rope MUST report fh 0; a non-zero foothold
     *       id makes the client force the pet onto that foothold, off the rope. The observed
     *       climbing/swim branches do the same. The <b>y</b> matches each branch too: a climbing
     *       owner hangs the pet AT the owner's own y, a swimming one floats it
     *       {@link BotPetConfig#swimOffset} above — the snapshot uses the same y as the observed tick
     *       so a joining player sees no spawn-then-jump.</li>
     * </ul>
     */
    private static void syncUnobservedPositions(Character chr, BotPetConfig config) {
        Pet[] pets = chr.getPets();
        int[] followX = computeFollowTargetXs(chr, pets);
        MapleMap map = chr.getMap();
        int ownerY = chr.getPosition().y;
        // Owner on a rope/ladder or in water: the observed tick neither probes nor moves the pet on
        // the ground, so the snapshot inherits that instead of probing the ground beneath a rope it
        // must stay on. Climbing and swimming differ in y: a climber hangs at the owner's own y, a
        // swimmer floats swimOffset above (followSwim) — the two must agree with the observed tick.
        boolean ownerClimbing = CharacterStance.isClimbing(chr.getStance());
        boolean ownerSwimming = CharacterStance.isSwimming(chr.getStance());
        boolean ownerOffGround = ownerClimbing || ownerSwimming;
        for (int i = 0; i < pets.length; i++) {
            Pet pet = pets[i];
            if (pet == null) {
                continue;
            }
            int x = followX[i];
            int y;
            int fh;
            if (ownerOffGround) {
                y = ownerClimbing ? ownerY : ownerY - config.swimOffset();
                fh = 0;
            } else {
                // The floor under the slot, via the same probe + tolerance (findStandingFoothold) the observed
                // tick lands the pet with — so the snapshot IS where the observed tick will put it.
                // Probed from the pet's own last footing, not the owner's y: a jumping owner would
                // strand the pet in mid-air. No footing within a step (the slot is over a ledge/gap)
                // leaves the pet where it is; the observed tick's lost-ground fall handles it.
                int footing = pet.getPos().y;
                Foothold below = findStandingFoothold(map, new Point(x, footing));
                if (below != null) {
                    y = below.calculateFooting(x);
                    fh = below.getId();
                } else {
                    y = footing;
                    fh = 0;
                }
            }
            pet.setPos(new Point(x, y));
            pet.setStance(CharacterStance.isFacingLeft(chr.getStance()) ? PET_STAND_LEFT : PET_STAND_RIGHT);
            pet.setFh(fh);
        }
    }

    // All fh parameters below (applyAndBroadcast / teleportPet / broadcastMove) follow the SHARED fh rule
    // at the top of the class — land: the real foothold id; rope/water/air: 0. Do not pass another.
    private static void applyAndBroadcast(Character chr, Pet pet, int index, Point pos,
                                     int vx, int vy, int fh, int stance, BotPetConfig config, boolean observed) {
        pet.setPos(pos);
        pet.setStance(stance);
        pet.setFh(fh);
        if (observed) {
            broadcastMove(chr, pet, index, pos, vx, vy, fh, stance, config);
        }
    }

    /**
     * Warp the pet to {@code pos} the official way: remove it, update its position,
     * then re-spawn it — NOT a MOVE packet sliding it across the map. Used when the
     * pet is left too far behind.
     */
    private static void teleportPet(Character chr, Pet pet, int index, Point pos, int fh, int stance,
                                    BotPetConfig config, boolean observed) {
        if (observed) {
            chr.getMap().broadcastMessage(chr, PacketCreator.showPet(chr, pet, true, false), false);
        }
        pet.setPos(pos);
        pet.setStance(stance);
        pet.setFh(fh);
        if (observed) {
            chr.getMap().broadcastMessage(chr, PacketCreator.showPet(chr, pet, false, false), false);
        }
    }

    /**
     * Facing of a pet from its stance byte: the low bit is the facing (even =
     * right, odd = left), so STAND 4/5 and MOVE 2/3 mirror each other.
     */
    private static boolean isPetFacingLeft(Pet pet) {
        return (pet.getStance() & 1) != 0;
    }

    private static void broadcastMove(Character chr, Pet pet, int index, Point pos,
                                      int vx, int vy, int fh, int stance, BotPetConfig config) {
        AbsoluteLifeMovement move = new AbsoluteLifeMovement(0, pos, (int) config.followTickMs(), stance);
        move.setPixelsPerSecond(new Point(vx, vy));
        move.setFh(fh); // the client snaps the pet onto this foothold (see the class's fh rule)
        List<LifeMovementFragment> moves = List.of(move);
        Packet packet = PacketCreator.movePet(chr.getId(), pet.getUniqueId(), (byte) index, moves);
        chr.getMap().broadcastMessage(chr, packet, false);
    }

    /**
     * Occasionally have the pet perform one of its own WZ interactions (a sit, a
     * chat, a trick). The animation and the spoken line both come from the pet's
     * own data: each pet's {@code Item.wz/Pet/<id>.img/interact} lists the
     * commands it can do, and the client resolves the speech from
     * {@code String.wz/PetDialog.img} (localized). We only tell the client which
     * command to play, exactly as the host's own {@code PetCommandHandler} does.
     *
     * <p>Rate-limited per pet and gated to observed maps, so pets read as lively
     * without flooding a busy map.</p>
     */
    private static void maybeSpeak(Character chr, Pet pet, int index, BotPetConfig config) {
        long now = System.currentTimeMillis();
        if (now < nextSpeakAtMs.getOrDefault(pet.getUniqueId(), 0L)) {
            return;
        }
        // Bound the cooldown map: it is keyed per pet unique id, and over a very
        // long run with bot churn it would otherwise grow without limit.
        if (nextSpeakAtMs.size() > 20_000) {
            nextSpeakAtMs.clear();
        }
        if (ThreadLocalRandom.current().nextDouble() >= config.speakChance()) {
            return;
        }
        PetInteractionTable.Interact pick = PetCommandInterpreter.pick(
                PetInteractionTable.interactionsFor(pet.getItemId()), pet.getLevel(),
                ThreadLocalRandom.current());
        if (pick == null) {
            return; // no WZ behaviour for this pet / level
        }
        // prob is the pet's chance to OBEY (host rolls it to pick success/fail);
        // both branches are the pet's own act + line from its WZ, so just relay
        // which command to play and whether it obeyed.
        boolean obey = PetCommandInterpreter.succeeds(pick, ThreadLocalRandom.current());
        long lo = Math.min(config.speakMinIntervalMs(), config.speakMaxIntervalMs());
        long hi = Math.max(config.speakMinIntervalMs(), config.speakMaxIntervalMs());
        nextSpeakAtMs.put(pet.getUniqueId(),
                now + lo + ThreadLocalRandom.current().nextLong(Math.max(1, hi - lo)));
        boolean balloon = chr.hasPetChatballoon((byte) index);
        chr.getMap().broadcastMessage(chr,
                PacketCreator.commandResponse(chr.getId(), (byte) index, !obey, pick.index(), balloon), false);
    }

    /**
     * Pets with looting gear sweep nearby MONSTER drops. A pet only picks drops it
     * is entitled to (its owner's, or a free-for-all drop once owner-protection has
     * lapsed) and only drops a mob actually dropped ({@code dropper} is a
     * {@link Monster}) — never a player-thrown item — so a looting pet tidies up
     * kills, exactly like a real player's pet.
     */
    private static void collectLoot(Character chr, MapleMap map, Pet[] pets, BotPetConfig config) {
        long now = System.currentTimeMillis();
        for (int idx = 0; idx < pets.length; idx++) {
            Pet pet = pets[idx];
            if (pet == null) {
                continue;
            }
            // Per-pet pickup cooldown: a pet trots over loot, it does not vacuum it
            // up. Without this the pet (one item per follow tick) emptied a drop
            // pile almost instantly.
            if (now < nextPickupAtMs.getOrDefault(pet.getUniqueId(), 0L)) {
                continue;
            }
            if (nextPickupAtMs.size() > 20_000) {
                nextPickupAtMs.clear();
            }
            boolean items = chr.isEquippedItemPouch((byte) idx);
            boolean meso = chr.isEquippedMesoMagnet((byte) idx);
            if (!items && !meso) {
                continue;
            }
            final int petIndex = idx;
            List<MapObject> found = map.getMapObjectsInRange(
                    pet.getPos(), (double) config.pickupRange() * config.pickupRange(),
                    List.of(MapObjectType.ITEM));
            int picked = 0;
            for (MapObject obj : found) {
                if (picked >= config.pickupMaxPerTick()) {
                    break;
                }
                if (!(obj instanceof MapItem mapItem)) {
                    continue;
                }
                // Monster drops only: a player-thrown item (dropper is a Character)
                // is left where it landed.
                if (!(mapItem.getDropper() instanceof Monster)) {
                    continue;
                }
                if (!DropCommands.botCanLoot(chr, mapItem)) {
                    continue;
                }
                boolean isMeso = mapItem.getMeso() > 0;
                if (isMeso && !meso) {
                    continue;
                }
                if (!isMeso && !items) {
                    continue;
                }
                BotClientBinding.runWithBoundPlayer(chr, () -> chr.pickupItem(mapItem, petIndex));
                picked++;
            }
            if (picked > 0) {
                nextPickupAtMs.put(pet.getUniqueId(), now + config.pickupCooldownMs());
            }
        }
    }
}
