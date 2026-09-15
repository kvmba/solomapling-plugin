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
 * <p>Land: the pet CHASES the owner at a fixed speed (not a lerp — it runs to catch
 * up, so a gap reads as a pursuit), heading for a stable random point beside the
 * owner (each pet picks its own spot, so a stationary bot's pets are not evenly
 * spaced and may partly overlap). It stands on the floor under its own x and follows
 * the owner's position, not its facing, so a turn never flings it across. When the
 * owner jumps or walks off a ledge the pet does NOT teleport to the owner's height:
 * it keeps its own ground and, once its own feet are unsupported, falls under its own
 * gravity (gravity only ever pulls DOWN, so a jumped owner never drags it up). Water
 * (swim maps): the pet floats (fh 0) and chases a point above the bot in the SWIM
 * stance (12/13). A rope/ladder owner makes the pet hang (HANG, 30/31). Whenever the
 * pet floats (water / rope) its foothold is cleared to 0, or a stale land foothold
 * would anchor it to the seabed and render it walking.</p>
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
    private static final int PET_JUMP_LEFT = 7;
    private static final int PET_SWIM_RIGHT = 12;
    private static final int PET_SWIM_LEFT = 13;
    private static final int PET_HANG_RIGHT = 30;
    private static final int PET_HANG_LEFT = 31;

    /** Probe this far above a point when searching for the ground below it. */
    private static final int GROUND_PROBE_UP = 8;

    // The pet walks under its own gravity (the client drives its animation from
    // the position/fh we send), instead of being glued to the owner mid-air. The
    // numbers mirror the client's Physics.img as used by the bot engine.
    private static final double GRAVITY_PXS2 = 2000.0;
    private static final double MAX_FALL_PXS = 670.0;
    /** Vertical tolerance (px) for treating a floor as the owner's own level. */
    private static final int GROUND_STEP_PX = 40;

    // Each pet settles at its own stable random offset beside the owner, so a
    // stationary bot's pets are not evenly spaced and may partly overlap — like a
    // real pet that picks its own spot rather than a rigid formation.
    private static final int PET_OFFSET_PX = 60;

    /** Bots that currently have pets — the only ones a tick visits. */
    private static final Set<Integer> TRACKED = ConcurrentHashMap.newKeySet();
    /** Per-pet next allowed speak time (epoch ms), keyed by pet unique id. */
    private static final Map<Integer, Long> nextSpeakAtMs = new ConcurrentHashMap<>();
    /** Per-pet next allowed pickup time (epoch ms), keyed by pet unique id. */
    private static final Map<Integer, Long> nextPickupAtMs = new ConcurrentHashMap<>();
    /** Per-pet vertical fall velocity (px/s, positive = downward), keyed by pet unique id. */
    private static final Map<Integer, Double> fallVy = new ConcurrentHashMap<>();
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
     * Land follow. The pet walks toward a point fanned out beside the owner and
     * always stands on the floor under its own x. It follows the owner's position,
     * not its facing, so a turn never flings the pet across. When the owner jumps or
     * walks off a ledge the pet does NOT teleport to the owner's height: it stays
     * pinned to the ground and, if it is left hanging over a gap, falls under its own
     * gravity (see {@link #falling}). Gravity only ever pulls the pet DOWN, so a
     * jumped owner never drags it into the air.
     */
    private static void followLand(Character chr, Pet pet, int index, BotPetConfig config, boolean observed) {
        Point botPos = chr.getPosition();
        MapleMap map = chr.getMap();
        int tx = botPos.x + offsetFor(pet);

        // On a rope/ladder the real pet hangs on the owner's back (HANG pose).
        if (CharacterStance.isClimbing(chr.getStance())) {
            fallVy.remove(pet.getUniqueId());
            moveTowards(chr, pet, index, pet.getPos(), botPos,
                    PET_HANG_RIGHT, PET_HANG_LEFT, PET_HANG_RIGHT, PET_HANG_LEFT, config, observed, true, 0);
            return;
        }

        // Unobserved: no foothold/gravity work at all (shouldLookUpFoothold is the tick's
        // dominant cost). Walk horizontally toward the owner on the pet's own floor/y.
        if (!shouldLookUpFoothold(false, observed)) {
            walkOwnFloor(chr, pet, index, tx, config, observed);
            return;
        }

        // The pet falls the moment the floor under its own feet is gone (it stepped off
        // an edge); this runs first so a falling pet is never also walked.
        if (falling(chr, pet, index, map, config, observed)) {
            return;
        }

        // Floor at the owner's target x, at roughly the owner's level? If so the pet walks
        // onto it; otherwise the owner is on another platform / over a gap, so the pet walks
        // toward the owner's x on its own floor (and falls if that x is over a gap).
        Foothold under = GCMovement.footholdBelow(map, tx, botPos.y - GROUND_PROBE_UP);
        int underY = under == null ? Integer.MIN_VALUE : under.calculateFooting(tx);
        if (under != null && Math.abs(underY - botPos.y) <= GROUND_STEP_PX) {
            moveTowards(chr, pet, index, pet.getPos(), new Point(tx, underY),
                    PET_MOVE_RIGHT, PET_MOVE_LEFT, PET_STAND_RIGHT, PET_STAND_LEFT, config, observed, false, under.getId());
        } else {
            walkOwnFloor(chr, pet, index, tx, config, observed);
        }
    }

    /** Walk toward {@code tx} on the pet's own floor/y, keeping its own foothold. */
    private static void walkOwnFloor(Character chr, Pet pet, int index, int tx,
                                     BotPetConfig config, boolean observed) {
        moveTowards(chr, pet, index, pet.getPos(), new Point(tx, pet.getPos().y),
                PET_MOVE_RIGHT, PET_MOVE_LEFT, PET_STAND_RIGHT, PET_STAND_LEFT, config, observed, false, pet.getFh());
    }

    /**
     * Step a pet whose own feet are unsupported: it accelerates downward under gravity
     * and lands on the first floor below. Returns true when it was airborne (so the
     * caller must not also walk it this tick). Gravity only ever pulls DOWN, so a
     * jumped owner never drags the pet up.
     */
    private static boolean falling(Character chr, Pet pet, int index, MapleMap map,
                                   BotPetConfig config, boolean observed) {
        Point cur = pet.getPos();
        Foothold below = GCMovement.footholdBelow(map, cur.x, cur.y - GROUND_PROBE_UP);
        int floorY = below == null ? Integer.MAX_VALUE : below.calculateFooting(cur.x);
        if (cur.y >= floorY) {
            fallVy.remove(pet.getUniqueId()); // feet on the floor — not falling
            return false;
        }
        double dt = Math.max(0.05, config.followTickMs() / 1000.0);
        double vy = Math.min(MAX_FALL_PXS, fallVy.getOrDefault(pet.getUniqueId(), 0.0) + GRAVITY_PXS2 * dt);
        int ny = cur.y + (int) Math.round(vy * dt);
        int facingLeft = isPetFacingLeft(pet) ? 1 : 0;
        if (ny >= floorY) {
            int stance = facingLeft | PET_STAND_RIGHT; // landed: stand on the floor below
            fallVy.remove(pet.getUniqueId());
            applyAndSend(chr, pet, index, new Point(cur.x, floorY), 0, 0, below.getId(), stance, config, observed);
        } else {
            int stance = facingLeft | PET_JUMP_RIGHT; // still falling (airborne pose)
            fallVy.put(pet.getUniqueId(), vy);
            applyAndSend(chr, pet, index, new Point(cur.x, ny), 0, (int) Math.round(vy), 0, stance, config, observed);
        }
        return true;
    }

    /**
     * Swim follow: glide the pet toward a point above the bot. Footholds are the
     * seabed (or absent) in water, so fh stays 0 and the pet floats, in the SWIM pose.
     */
    private static void followSwim(Character chr, Pet pet, int index, BotPetConfig config, boolean observed) {
        fallVy.remove(pet.getUniqueId());
        Point botPos = chr.getPosition();
        Point target = new Point(botPos.x + offsetFor(pet), botPos.y - config.swimOffset() * (index + 1));
        moveTowards(chr, pet, index, pet.getPos(), target,
                PET_SWIM_RIGHT, PET_SWIM_LEFT, PET_SWIM_RIGHT, PET_SWIM_LEFT, config, observed, true, 0);
    }

    /**
     * Shared movement core: glide the pet toward {@code target}, or re-home it when
     * hopelessly far (a warp slipped past the engine's own map-entry placement). The
     * {@code move*}/{@code idle*} pair is the context's own — land walks in MOVE and
     * rests in STAND, while water / air / rope keep a single pose. {@code noGravity}
     * keeps the pet at the target's own y (water / rope). {@code fh} is the ground to
     * stand on; 0 when floating.
     */
    /**
     * Shared movement core: CHASE the target at a fixed speed — both x and y move at
     * up to {@code followSpeed} toward the target over the tick, so a pet runs to
     * catch up rather than being snapped onto the target. That gap is what makes a
     * real pet look like it is chasing. When the pet is grounded the target's y is its
     * own floor (a level line), so the chase is horizontal; floating (water / rope /
     * airborne) it chases in 2D. Far away it is re-homed instead of walking the whole
     * distance.
     */
    private static void moveTowards(Character chr, Pet pet, int index, Point cur, Point target,
                                    int moveRight, int moveLeft, int idleRight, int idleLeft,
                                    BotPetConfig config, boolean observed, boolean noGravity, int fh) {
        double dx = target.x - cur.x;
        double dy = target.y - cur.y;
        double dist = Math.hypot(dx, dy);

        if (dist > config.teleportDistPx()) {
            // Re-home: a warp slipped the pet past the engine's map-entry placement.
            fallVy.remove(pet.getUniqueId());
            applyAndSend(chr, pet, index, target, 0, 0, fh,
                    isPetFacingLeft(pet) ? idleLeft : idleRight, config, observed);
            return;
        }
        if (dist <= config.epsPx()) {
            int idle = isPetFacingLeft(pet) ? idleLeft : idleRight;
            if (pet.getStance() != idle || pet.getFh() != fh) {
                applyAndSend(chr, pet, index, cur, 0, 0, fh, idle, config, observed);
            }
            return;
        }
        double dt = Math.max(0.05, config.followTickMs() / 1000.0);
        double maxStep = Math.max(1.0, config.followSpeed() * dt);
        double scale = Math.min(1.0, maxStep / dist);
        int nx = (int) Math.round(cur.x + dx * scale);
        int ny = (int) Math.round(cur.y + dy * scale);
        int stepX = nx - cur.x;
        int stance = stepX == 0
                ? (isPetFacingLeft(pet) ? idleLeft : idleRight)
                : (stepX > 0 ? moveRight : moveLeft);
        // Airborne (noGravity): fh must be 0 or the client anchors the pet to the floor
        // it should be floating above (its walk/hang/swim pose is unaffected).
        int wireFh = noGravity ? 0 : fh;
        applyAndSend(chr, pet, index, new Point(nx, ny),
                (int) Math.round(stepX / dt), (int) Math.round((ny - cur.y) / dt), wireFh, stance, config, observed);
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
