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

import java.awt.Point;
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
 * rather than being glued to a snapshot of the owner — see the movement below. On
 * land it accelerates toward a point beside the owner (momentum, so it lags and then
 * catches up), stands on the floor under its own x, and follows the owner's position,
 * not its facing, so a turn never flings it across. Gravity only ever pulls it DOWN,
 * so a jumped owner never drags the pet into the air; a pet whose own feet leave the
 * ground (its owner climbed a platform, or either walked off a ledge) falls under
 * gravity and lands on the floor below. A pet left too far behind does not sprint —
 * it flashes to the owner's side (official behaviour) and resumes. In water it bobs
 * (sinks under gravity, floats back up) in the SWIM stance (12/13); a rope/ladder
 * owner makes it hang (HANG, 30/31).</p>
 *
 * <p>Every tick the pet's position is kept current in memory; only the packets are
 * gated on observability, so a joining player never sees a stale coordinate. The
 * ground/gravity work (a foothold lookup — the tick's dominant cost) is skipped
 * entirely while no real player watches the map, so the world runs pet-free of cost.</p>
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

    /** Probe this far above a point when searching for the ground below it. */
    private static final int GROUND_PROBE_UP = 8;

    // The pet runs its OWN physics (the client only renders the position/fh/velocity
    // we send), mirroring the bot engine's kinematic model — it is not glued to the
    // owner. Numbers match the client's Physics.img as used by BotPhysicsEngine.
    private static final double GRAVITY_PXS2 = 2000.0;      // land gravity
    private static final double MAX_FALL_PXS = 670.0;       // land terminal fall
    private static final double WALK_ACCEL_PXS2 = 4000.0;   // horizontal acceleration
    private static final int GROUND_SNAP_PX = 6;            // "standing on the floor" tolerance
    private static final double SWIM_GRAVITY_PXS2 = 590.0;  // underwater sink
    private static final double SWIM_BUOYANCY_PXS2 = 900.0; // float back up (net up => a bob)
    private static final double SWIM_MAX_SPEED_PXS = 800.0;
    /** Vertical tolerance (px) for treating a floor as the owner's own level. */
    private static final int GROUND_STEP_PX = 40;

    // Each pet holds its own stable random offset beside the owner, drifting slowly
    // (a per-pet phase) so a stationary bot's pets keep jockeying for position rather
    // than holding a rigid, evenly spaced formation — they may partly overlap.
    private static final int PET_OFFSET_PX = 60;

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
    /** Per-pet stable side offset (px) beside the owner, keyed by pet unique id. */
    private static final Map<Integer, Integer> sideOffset = new ConcurrentHashMap<>();
    /** Per-pet slow drift phase (radians) for the offset wobble, keyed by pet unique id. */
    private static ScheduledFuture<?> task;

    private BotPetFollower() {
    }

    public static synchronized void start(BotPetConfig config) {
        if (task != null) {
            return;
        }
        rescan();
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
        sideOffset.clear();
    }

    /**
     * Rebuild the tracked set from live bots. start() runs after a reload too, and
     * stop() clears the set — without this, a {@code !botpet reload} would leave
     * every already-petted bot unfollowed.
     */
    private static void rescan() {
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
            forget(botId); // bot gone / mid-retype / pets removed
            return;
        }
        MapleMap map = chr.getMap();
        // The pet's position is kept in sync with the bot EVERY tick (a cheap
        // in-memory update); only the packets are gated on observability. Freezing
        // the position while unobserved made a joining player's spawnPlayerMapObject
        // carry a stale pet coordinate, which then snapped to the bot — a visible
        // flash. Syncing always bounds that staleness to one tick, like an observed
        // map. Speaking and looting stay observer-gated (they only matter to, and
        // only broadcast to, watchers).
        boolean observed = GCMovement.isMapObserved(chr.getMapId());

        // Water = the map says so, or the bot itself is currently swimming (they agree
        // for maps with info/swim set; the stance check also covers a bot mid-water in
        // a map whose flag the client and server might read differently).
        boolean swim = map.isSwim() || CharacterStance.isSwimming(chr.getStance());
        // Iterate by the pet-ARRAY index, not a running count: the slot in
        // MOVE_PET / PET_COMMAND is the array index (the host sends it from
        // getPetIndex), so skipping a null without advancing would mis-slot a
        // pet that sits after a hole. Same loop shape as loot() below.
        Pet[] pets = chr.getPets();
        for (int idx = 0; idx < pets.length; idx++) {
            Pet pet = pets[idx];
            if (pet == null) {
                continue;
            }
            if (swim) {
                followSwim(chr, pet, idx, config, observed);
            } else {
                followLand(chr, pet, idx, config, observed);
            }
            if (observed) {
                maybeSpeak(chr, pet, idx, config);
            }
        }
        if (observed && chr.getHp() > 0) {
            loot(chr, map, config);
        }
    }

    /**
     * Land follow, run as the pet's own physics (see the class note). The owner is a
     * moving target: the pet accelerates toward a point beside it (momentum, so it
     * lags then catches up rather than being snapped on), hops after a jumping owner,
     * and falls under gravity when its own feet are unsupported. Gravity only pulls
     * DOWN, so a jumped owner never drags the pet up.
     */
    private static void followLand(Character chr, Pet pet, int index, BotPetConfig config, boolean observed) {
        Point p = pet.getPos();
        double vx = velX.getOrDefault(pet.getUniqueId(), 0.0);
        double vy = fallVy.getOrDefault(pet.getUniqueId(), 0.0);

        // Rope/ladder owner: the real pet hangs on the owner's back (HANG pose).
        if (CharacterStance.isClimbing(chr.getStance())) {
            velX.remove(pet.getUniqueId());
            fallVy.remove(pet.getUniqueId());
            applyAndSend(chr, pet, index, chr.getPosition(), 0, 0, 0,
                    isPetFacingLeft(pet) ? PET_HANG_LEFT : PET_HANG_RIGHT, config, observed);
            return;
        }

        int tx = chr.getPosition().x + offsetFor(pet);
        double dt = Math.max(0.05, config.followTickMs() / 1000.0);

        // Official follow: a pet that has fallen too far behind does NOT sprint after
        // the owner — it flashes to the owner's side (a warp-like reposition) and
        // resumes following from there.
        if (Math.abs(p.x - chr.getPosition().x) > config.teleportDistPx()) {
            velX.remove(pet.getUniqueId());
            fallVy.remove(pet.getUniqueId());
            Foothold fh = GCMovement.footholdBelow(chr.getMap(), tx, chr.getPosition().y - GROUND_PROBE_UP);
            Point snap = fh == null ? new Point(tx, chr.getPosition().y) : new Point(tx, fh.calculateFooting(tx));
            applyAndSend(chr, pet, index, snap, 0, 0, fh == null ? 0 : fh.getId(),
                    isPetFacingLeft(pet) ? PET_STAND_LEFT : PET_STAND_RIGHT, config, observed);
            return;
        }

        // Unobserved: skip the foothold/gravity work (shouldLookUpFoothold is the tick's
        // dominant cost). Keep x fresh with the motor and hold y; the first watched tick
        // resumes full physics (the y fix is then a short fall, not a stale flash).
        if (!shouldLookUpFoothold(false, observed)) {
            vx = stepMotor(vx, desiredVelocity(tx - p.x, config.followSpeed()), config.followSpeed(), dt);
            velX.put(pet.getUniqueId(), vx);
            int stance = Math.abs(vx) <= 1
                    ? (isPetFacingLeft(pet) ? PET_STAND_LEFT : PET_STAND_RIGHT)
                    : (vx > 0 ? PET_MOVE_RIGHT : PET_MOVE_LEFT);
            applyAndSend(chr, pet, index, new Point(p.x + (int) Math.round(vx * dt), p.y),
                    (int) Math.round(vx), 0, pet.getFh(), stance, config, observed);
            return;
        }

        MapleMap map = chr.getMap();
        boolean ground = onGround(map, p, vy);
        // The pet never hops into the air after a jumping owner — gravity only ever
        // pulls it DOWN (a pet over a gap just falls).

        vx = stepMotor(vx, desiredVelocity(tx - p.x, config.followSpeed()), config.followSpeed(), dt);
        int nx = p.x + (int) Math.round(vx * dt);

        int ny = p.y;
        Foothold landing = null;
        if (ground) {
            vy = 0;
            landing = floorUnder(map, p);
            if (landing != null) {
                ny = landing.calculateFooting(nx);
            }
        } else {
            boolean rising = vy < 0;
            vy = Math.min(MAX_FALL_PXS, vy + GRAVITY_PXS2 * dt);
            ny = p.y + (int) Math.round(vy * dt);
            Foothold fh = GCMovement.footholdBelow(map, nx, p.y);
            if (fh != null) {
                int floorY = fh.calculateFooting(nx);
                if (!rising && ny >= floorY) { // land on the way down
                    ny = floorY;
                    vy = 0;
                    landing = fh;
                }
            }
        }

        int stance;
        if (landing == null) {
            stance = (isPetFacingLeft(pet) ? 1 : 0) | PET_JUMP_RIGHT; // airborne pose
        } else if (Math.abs(vx) <= 1) {
            stance = isPetFacingLeft(pet) ? PET_STAND_LEFT : PET_STAND_RIGHT;
        } else {
            stance = vx > 0 ? PET_MOVE_RIGHT : PET_MOVE_LEFT;
        }

        velX.put(pet.getUniqueId(), vx);
        fallVy.put(pet.getUniqueId(), vy);
        applyAndSend(chr, pet, index, new Point(nx, ny),
                (int) Math.round(vx), (int) Math.round(vy),
                landing != null ? landing.getId() : 0, stance, config, observed);
    }

    /**
     * Swim follow: the pet bobs through the water (sinks under gravity, floats back up
     * when it drops below the point above the owner) in the SWIM pose, chasing the
     * owner horizontally. fh stays 0 in water.
     */
    private static void followSwim(Character chr, Pet pet, int index, BotPetConfig config, boolean observed) {
        Point p = pet.getPos();
        int botX = chr.getPosition().x + offsetFor(pet);
        int targetY = chr.getPosition().y - config.swimOffset() * (index + 1);
        double dt = Math.max(0.05, config.followTickMs() / 1000.0);
        double vx = velX.getOrDefault(pet.getUniqueId(), 0.0);
        double vy = fallVy.getOrDefault(pet.getUniqueId(), 0.0);

        // Official follow: too far behind -> flash to the owner's side, don't sprint.
        if (Math.hypot(p.x - botX, p.y - targetY) > config.teleportDistPx()) {
            velX.remove(pet.getUniqueId());
            fallVy.remove(pet.getUniqueId());
            applyAndSend(chr, pet, index, new Point(botX, targetY), 0, 0, 0,
                    isPetFacingLeft(pet) ? PET_SWIM_LEFT : PET_SWIM_RIGHT, config, observed);
            return;
        }

        vx = stepMotor(vx, desiredVelocity(botX - p.x, config.followSpeed()), config.followSpeed(), dt);
        vy += SWIM_GRAVITY_PXS2 * dt;
        if (p.y > targetY) {
            vy -= SWIM_BUOYANCY_PXS2 * dt; // dropped below the target: float back up
        }
        vy = Math.max(-SWIM_MAX_SPEED_PXS, Math.min(SWIM_MAX_SPEED_PXS, vy));

        int nx = p.x + (int) Math.round(vx * dt);
        int ny = p.y + (int) Math.round(vy * dt);
        velX.put(pet.getUniqueId(), vx);
        fallVy.put(pet.getUniqueId(), vy);
        applyAndSend(chr, pet, index, new Point(nx, ny),
                (int) Math.round(vx), (int) Math.round(vy), 0,
                vx >= 0 ? PET_SWIM_RIGHT : PET_SWIM_LEFT, config, observed);
    }

    /** Desired horizontal velocity toward a target {@code dx} away; 0 within the dead zone. */
    private static double desiredVelocity(double dx, double maxSpeed) {
        if (Math.abs(dx) < 4) {
            return 0;
        }
        return Math.signum(dx) * maxSpeed;
    }

    /** Accelerate {@code v} toward {@code desired} at WALK_ACCEL, capped at +-maxSpeed. */
    private static double stepMotor(double v, double desired, double maxSpeed, double dt) {
        double max = WALK_ACCEL_PXS2 * dt;
        v += Math.max(-max, Math.min(max, desired - v));
        return Math.max(-maxSpeed, Math.min(maxSpeed, v));
    }

    /** Whether the pet's feet rest on a floor (within a snap) and it is not rising. */
    private static boolean onGround(MapleMap map, Point p, double vy) {
        if (map == null || vy < -1) {
            return false;
        }
        Foothold fh = GCMovement.footholdBelow(map, p.x, p.y - 2);
        return fh != null && p.y - fh.calculateFooting(p.x) <= GROUND_SNAP_PX;
    }

    /** The floor the pet stands on (within a step of its feet), or null over a gap. */
    private static Foothold floorUnder(MapleMap map, Point p) {
        if (map == null) {
            return null;
        }
        Foothold fh = GCMovement.footholdBelow(map, p.x, p.y - 2);
        return fh != null && Math.abs(p.y - fh.calculateFooting(p.x)) <= GROUND_STEP_PX ? fh : null;
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
    static boolean shouldLookUpFoothold(boolean noGravity, boolean observed) {
        return !noGravity && observed;
    }

    /**
     * A stable random x-offset for this pet beside the owner, chosen once per pet
     * (so it does not jitter) and kept in the +-{@code PET_OFFSET_PX} band. Pets are
     * independent, so two may settle partly overlapped — a natural spacing rather
     * than a rigid formation.
     */
    private static int offsetFor(Pet pet) {
        if (sideOffset.size() > 20_000) {
            sideOffset.clear(); // bound growth over a very long run with pet churn
        }
        return sideOffset.computeIfAbsent(pet.getUniqueId(),
                k -> ThreadLocalRandom.current().nextInt(2 * PET_OFFSET_PX + 1) - PET_OFFSET_PX);
    }

    private static void applyAndSend(Character chr, Pet pet, int index, Point pos,
                                     int vx, int vy, int fh, int stance, BotPetConfig config, boolean observed) {
        pet.setPos(pos);
        pet.setStance(stance);
        pet.setFh(fh);
        if (observed) {
            broadcastMove(chr, pet, index, pos, vx, vy, fh, stance, config);
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
        move.setFh(fh);
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
    private static void loot(Character chr, MapleMap map, BotPetConfig config) {
        long now = System.currentTimeMillis();
        Pet[] pets = chr.getPets();
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
