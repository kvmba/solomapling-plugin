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
 * <p>Land: the pet glides at a fixed speed toward a point fanned out beside the
 * owner (a fixed per-pet spread, so several pets stay apart and none sits on the
 * owner), staying on the floor under its own x and settling to a STAND at rest.
 * It follows the owner's position, not its facing, so a turn never flings the pet
 * across. When the owner is airborne (a jump or a long fall) the pet goes airborne
 * with it, holding the owner's height in the JUMP pose. Water (swim maps): the pet
 * floats (fh 0) and glides toward a point above the bot, rendered in the SWIM
 * stance (12/13). A rope/ladder owner makes the pet hang (HANG, 30/31). Whenever
 * the pet floats (water / air / rope) its foothold is cleared to 0, or a stale
 * land foothold would anchor it to the seabed and render it walking.</p>
 *
 * <p><b>Map changes / death carry-home need no code here.</b> Whenever a bot
 * enters a map the engine's own {@code MapleMap.addPlayer} already re-places its
 * pets on the ground and sends {@code showPet}, and every observer receives them
 * through {@code spawnPlayerMapObject} (they are part of {@code getPets()}). So a
 * pet follows its bot across portals, warps and the death carry-home with no
 * follower involvement — the engine does it, this only moves them once they are
 * on the map.</p>
 *
 * <p>Pets with the matching looting gear pick up nearby MONSTER drops (its owner's
 * or a free-for-all one), paced to one item a second, through the engine's own
 * pickup path; a player-thrown item is left alone.</p>
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

    /** Half the distance (px) between the owner and each pet / between pets. */
    private static final int FOLLOW_GAP_PX = 45;
    /** Probe this far above a point when searching for the ground below it. */
    private static final int GROUND_PROBE_UP = 8;

    /** Bots that currently have pets — the only ones a tick visits. */
    private static final Set<Integer> TRACKED = ConcurrentHashMap.newKeySet();
    /** Per-pet next allowed speak time (epoch ms), keyed by pet unique id. */
    private static final Map<Integer, Long> nextSpeakAtMs = new ConcurrentHashMap<>();
    /** Per-pet next allowed pickup time (epoch ms), keyed by pet unique id. */
    private static final Map<Integer, Long> nextPickupAtMs = new ConcurrentHashMap<>();
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
     * Land follow, modelled on the real client's CPet: the pet keeps its deadband
     * around the owner, gliding toward the owner's x at a fixed speed (never
     * snapping). It holds to the side it is already on, so an owner turning around
     * does NOT fling the pet to the other side — the pet just keeps following. It
     * snaps to the floor beneath its own x, so it never floats over a ledge, and at
     * rest it settles to STAND so it never keeps walking on the spot.
     */
    private static void followLand(Character chr, Pet pet, int index, BotPetConfig config, boolean observed) {
        Point botPos = chr.getPosition();

        // On a rope/ladder the real pet hangs on the owner's back (HANG pose).
        if (CharacterStance.isClimbing(chr.getStance())) {
            moveTowards(chr, pet, index, pet.getPos(), botPos,
                    PET_HANG_RIGHT, PET_HANG_LEFT, PET_HANG_RIGHT, PET_HANG_LEFT, config, observed, true);
            return;
        }

        // Move toward a per-pet point beside the owner (a fixed spread so a
        // multi-pet bot's pets fan out instead of stacking on the owner). Following
        // the owner's position rather than its facing is what keeps a turn from
        // flinging the pet to the other side.
        Point target = new Point(botPos.x + spreadFor(index), botPos.y);

        // Airborne owner (jump or a long fall): the pet goes airborne too and holds
        // the owner's height, rendered in the JUMP pose. Otherwise it walks the
        // ground under its own x, so it never floats over a ledge.
        if (CharacterStance.isJumping(chr.getStance())) {
            moveTowards(chr, pet, index, pet.getPos(), target,
                    PET_JUMP_RIGHT, PET_JUMP_LEFT, PET_JUMP_RIGHT, PET_JUMP_LEFT, config, observed, true);
        } else {
            moveTowards(chr, pet, index, pet.getPos(), groundSnap(chr.getMap(), target),
                    PET_MOVE_RIGHT, PET_MOVE_LEFT, PET_STAND_RIGHT, PET_STAND_LEFT, config, observed, false);
        }
    }

    /**
     * Swim follow: glide the pet toward a point above the bot. Footholds are the
     * seabed (or absent) in water, so fh stays 0 and the pet floats, in the SWIM pose.
     */
    private static void followSwim(Character chr, Pet pet, int index, BotPetConfig config, boolean observed) {
        Point botPos = chr.getPosition();
        Point target = new Point(botPos.x + spreadFor(index), botPos.y - config.swimOffset() * (index + 1));
        moveTowards(chr, pet, index, pet.getPos(), target,
                PET_SWIM_RIGHT, PET_SWIM_LEFT, PET_SWIM_RIGHT, PET_SWIM_LEFT, config, observed, true);
    }

    /**
     * Shared movement core: glide the pet toward {@code target} at a fixed speed, or
     * teleport when hopelessly far (a warp). {@code move*}/{@code idle*} are the
     * context's own stance pair — land walks in MOVE and rests in STAND, while water
     * / air / rope keep their single pose (SWIM / JUMP / HANG). Within the deadband
     * it settles to the idle pose, sent once. {@code noGravity} keeps the pet at the
     * target's own y (water / airborne / hanging on the owner's back).
     */
    private static void moveTowards(Character chr, Pet pet, int index, Point cur, Point target,
                                    int moveRight, int moveLeft, int idleRight, int idleLeft,
                                    BotPetConfig config, boolean observed, boolean noGravity) {
        double dx = target.x - cur.x;
        double dy = target.y - cur.y;
        double dist = Math.hypot(dx, dy);

        if (dist > config.teleportDistPx()) {
            // Hopelessly far (a warp slipped past the engine's own re-placement):
            // snap, with the foothold recomputed at the target (the old fh is stale).
            int fh = noGravity ? 0 : BotPetController.footholdId(chr.getMap(), target);
            applyAndSend(chr, pet, index, target, 0, 0, fh,
                    isPetFacingLeft(pet) ? idleLeft : idleRight, config, observed);
            return;
        }
        if (dist <= config.epsPx()) {
            int idle = isPetFacingLeft(pet) ? idleLeft : idleRight;
            if (pet.getStance() != idle) {
                // Clear the foothold when floating (water / air / rope): a stale land
                // fh here would anchor the pet to the seabed and render it walking
                // instead of swimming, even though the stance byte is correct.
                int fh = noGravity ? 0 : pet.getFh();
                applyAndSend(chr, pet, index, cur, 0, 0, fh, idle, config, observed);
            }
            return;
        }
        double dt = Math.max(0.05, config.followTickMs() / 1000.0);
        double maxStep = Math.max(1.0, config.followSpeed() * dt);
        double scale = Math.min(1.0, maxStep / dist);
        int nx = (int) Math.round(cur.x + dx * scale);
        int ny = (int) Math.round(cur.y + dy * scale);
        if (!noGravity) {
            // Stay on the floor under the new x, so the pet walks along the ground
            // instead of gliding through the air toward a foothold below.
            ny = groundSnap(chr.getMap(), new Point(nx, ny)).y;
        }
        int stepX = nx - cur.x;
        int stance = stepX == 0
                ? (isPetFacingLeft(pet) ? idleLeft : idleRight)
                : (stepX > 0 ? moveRight : moveLeft);
        int fh = noGravity ? 0 : BotPetController.footholdId(chr.getMap(), new Point(nx, ny));
        applyAndSend(chr, pet, index, new Point(nx, ny),
                (int) Math.round(stepX / dt), (int) Math.round((ny - cur.y) / dt), fh, stance, config, observed);
    }

    /**
     * Signed x-offset for the pet at {@code index}, giving a symmetric fan around
     * the owner (0 -> +gap, 1 -> -gap, 2 -> +2*gap) so pets never sit on the owner
     * and stay apart from each other.
     */
    private static int spreadFor(int index) {
        int step = FOLLOW_GAP_PX * (index / 2 + 1);
        return (index % 2 == 0) ? step : -step;
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
     * The ground point under {@code p} (its own x), or {@code p} itself when there is
     * none. Probes a little above {@code p} so an upward slope is found too (mirrors
     * the host's own pet placement, which probes a few px up before findBelow).
     */
    private static Point groundSnap(MapleMap map, Point p) {
        if (map == null) {
            return p;
        }
        Foothold fh = map.getFootholds().findBelow(new Point(p.x, p.y - GROUND_PROBE_UP));
        return fh == null ? p : new Point(p.x, fh.calculateFooting(p.x));
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
