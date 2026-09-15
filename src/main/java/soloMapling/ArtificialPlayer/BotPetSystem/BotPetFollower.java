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
 * land it walks with a smooth velocity ramp (no sudden accel/decel) toward a point
 * beside the owner, stands on the floor under its own x, and follows the owner's
 * position, not its facing, so a turn never flings it across. Gravity only ever pulls
 * it DOWN, so a jumped owner never drags the pet into the air; a pet whose own feet
 * leave the ground (its owner climbed a platform, or either walked off a ledge) falls
 * under gravity and lands on the floor below. A pet left too far behind HORIZONTALLY
 * warps to the owner's side (official behaviour) — a vertical owner move (a jump or a
 * fall) is followed with the pet's own physics instead. In water it bobs (sinks under
 * gravity, floats back up) in the SWIM stance (12/13); a rope/ladder owner makes it
 * hang (HANG, 30/31).</p>
 *
 * <p><b>Map changes are the engine's job.</b> On map entry the engine's own
 * {@code MapleMap.addPlayer} re-places each pet at the owner's feet and re-sends it,
 * and every observer gets it via {@code spawnPlayerMapObject} — so a pet rides along
 * through any portal / warp / death carry-home with no code here.</p>
 *
 * <p><b>LOD like the bot's own movement.</b> While no real player watches the map the
 * whole pet tick is skipped — no physics, no foothold lookup, no packets. The first
 * tick after a player arrives runs the full physics once, which places / falls /
 * re-homes the pet to where it belongs, so a joining player never sees a stale pet.</p>
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
    private static final double WALK_ACCEL_PXS2 = 900.0;    // smooth ramp, no sudden accel/decel
    private static final double APPROACH_TAU_S = 0.25;      // ease in near the target
    private static final int GROUND_SNAP_PX = 6;            // "standing on the floor" tolerance
    private static final int LOST_PX = 500;                 // 1-D horizontal gap -> warp to the owner
    private static final double SWIM_GRAVITY_PXS2 = 590.0;  // underwater sink
    private static final double SWIM_BUOYANCY_PXS2 = 900.0; // float back up (net up => a bob)
    private static final double SWIM_MAX_SPEED_PXS = 800.0;
    /** Vertical tolerance (px) for treating a floor as the owner's own level. */
    private static final int GROUND_STEP_PX = 40;

    // Each pet holds its own stable random offset beside the owner (±PET_OFFSET_PX,
    // chosen once per pet), so a stationary bot's pets are not evenly spaced and may
    // partly overlap — a natural spacing, not a rigid formation.
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
        // LOD, like the bot's own movement: while no real player watches the map, do
        // NOTHING for the pets — no physics, no foothold lookup, no moving. The first
        // tick after a player arrives runs the full physics once, which places/falls/
        // re-homes the pet to where it should be (official pets warp when left behind).
        boolean observed = GCMovement.isMapObserved(chr.getMapId());
        if (!shouldLookUpFoothold(false, observed)) {
            return; // LOD: nobody can see the pets — skip the whole pet tick
        }

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
     * lags then catches up rather than being snapped on) and falls under gravity when
     * its own feet are unsupported. Gravity only ever pulls DOWN, so a jumped or
     * falling owner never drags the pet up; a pet left too far from the owner is
     * warped to its side ({@link #teleportPet}).
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

        Point owner = chr.getPosition();
        int tx = owner.x + offsetFor(pet);
        double dt = Math.max(0.05, config.followTickMs() / 1000.0);

        // Official follow: warp when the pet is left too far behind. A large HORIZONTAL
        // lead (a fast-running owner, or a same-map teleport) beats the pet's walk; and
        // an owner that has SETTLED on a higher platform cannot be reached by walking
        // (a pet never jumps up), so it warps up too. A jumping owner is ignored here —
        // its higher y is transient, so wait rather than flash.
        boolean ownerAir = CharacterStance.isJumping(chr.getStance());
        if (Math.abs(p.x - owner.x) > LOST_PX || (!ownerAir && owner.y < p.y - GROUND_STEP_PX)) {
            velX.remove(pet.getUniqueId());
            fallVy.remove(pet.getUniqueId());
            Foothold fh = GCMovement.footholdBelow(chr.getMap(), tx, owner.y - GROUND_PROBE_UP);
            Point snap = fh == null ? new Point(tx, owner.y) : new Point(tx, fh.calculateFooting(tx));
            teleportPet(chr, pet, index, snap, fh == null ? 0 : fh.getId(),
                    isPetFacingLeft(pet) ? PET_STAND_LEFT : PET_STAND_RIGHT, config, observed);
            return;
        }

        MapleMap map = chr.getMap();
        // The pet stands unless the owner is clearly BELOW its floor: then it is
        // unsupported — it drops / down-jumps THROUGH the platform to follow the owner
        // down (landing on the first floor between it and the owner, repeating until it
        // reaches the owner's level). A forbidFallDown platform is never pass-through
        // (matches the client / bot down-jump), so a pet on one cannot drop and warps
        // to the owner instead. Gravity only ever pulls DOWN.
        boolean ownerBelow = owner.y > p.y + GROUND_STEP_PX;
        boolean onPlatform = onGround(map, p, vy);
        Foothold standing = onPlatform ? floorUnder(map, p) : null;
        if (ownerBelow && standing != null && standing.isForbidFallDown()) {
            teleportPet(chr, pet, index, new Point(tx, owner.y), 0,
                    isPetFacingLeft(pet) ? PET_STAND_LEFT : PET_STAND_RIGHT, config, observed);
            return;
        }
        boolean ground = onPlatform && !ownerBelow;

        vx = stepMotor(vx, tx - p.x, config.followSpeed(), dt);
        int nx = p.x + (int) Math.round(vx * dt);

        int ny = p.y;
        Foothold landing = null;
        // Re-query the floor at the NEW x: if the pet stepped off the platform edge the
        // result is null and it must FALL, not keep standing on an extrapolated slope.
        Foothold stepFloor = ground ? floorUnder(map, new Point(nx, p.y)) : null;
        if (stepFloor != null) {
            vy = 0;
            landing = stepFloor;
            ny = stepFloor.calculateFooting(nx);
        } else {
            boolean rising = vy < 0;
            vy = Math.min(MAX_FALL_PXS, vy + GRAVITY_PXS2 * dt);
            ny = p.y + (int) Math.round(vy * dt);
            Foothold fh = GCMovement.footholdBelow(map, nx, p.y);
            if (fh != null) {
                int floorY = fh.calculateFooting(nx);
                // Only land on a floor strictly BELOW the pet: during a down-jump the
                // platform it just dropped through is above it and must be ignored, or
                // it would catch the pet back on top.
                if (floorY > p.y && !rising && ny >= floorY) {
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
        if (Math.abs(p.x - botX) > LOST_PX) {
            velX.remove(pet.getUniqueId());
            fallVy.remove(pet.getUniqueId());
            teleportPet(chr, pet, index, new Point(botX, targetY), 0,
                    isPetFacingLeft(pet) ? PET_SWIM_LEFT : PET_SWIM_RIGHT, config, observed);
            return;
        }

        vx = stepMotor(vx, botX - p.x, config.followSpeed(), dt);
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

    /**
     * Horizontal motor: a smooth velocity ramp — accelerate toward the target at
     * WALK_ACCEL, ease into a stop within APPROACH_TAU seconds as it nears, and glide
     * to a halt on arrival. No sudden accel/decel (unlike a bang-bang controller), so
     * the pet reads like it is walking. Accelerating and decelerating are symmetric.
     */
    private static double stepMotor(double v, double dx, double maxSpeed, double dt) {
        double stopDist = Math.abs(v) * APPROACH_TAU_S;
        double desired = Math.abs(dx) <= Math.max(2.0, stopDist) ? 0.0 : Math.signum(dx) * maxSpeed;
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
