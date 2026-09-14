package soloMapling.ArtificialPlayer.BotPetSystem;

import org.gms.client.Character;
import org.gms.client.inventory.Pet;
import org.gms.constants.game.CharacterStance;
import org.gms.net.packet.Packet;
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
 * <p>Land: a pet is parked just behind the bot and snapped to the foothold under
 * it. Water (swim maps): footholds are the seabed / can be missing, so the pet
 * floats (fh 0) and glides toward a point above the bot, rendered in the swim
 * stance (12/13) — the pet "swims" after its owner.</p>
 *
 * <p><b>Map changes / death carry-home need no code here.</b> Whenever a bot
 * enters a map the engine's own {@code MapleMap.addPlayer} already re-places its
 * pets on the ground and sends {@code showPet}, and every observer receives them
 * through {@code spawnPlayerMapObject} (they are part of {@code getPets()}). So a
 * pet follows its bot across portals, warps and the death carry-home with no
 * follower involvement — the engine does it, this only moves them once they are
 * on the map.</p>
 *
 * <p>Pets with the matching looting gear sweep nearby drops (their own / a
 * nearby player's free-for-all ones) through the engine's own pickup path.</p>
 */
public final class BotPetFollower {

    // Pet stances are NOT the character stances. The wire stance byte is
    // (actionIndex << 1) | facing (even = right, odd = left), with the OFFICIAL
    // action order STAND, MOVE, JUMP, ALERT, PRONE, FLY, HANG:
    //     STAND = 0/1     MOVE = 2/3      JUMP = 4/5    ALERT = 6/7
    //     PRONE = 8/9     FLY  = 10/11    HANG = 12/13
    // Two independent facts pin this down: the host summons a pet with
    // setStance(0) and the pet stands still, so 0 = STAND; and moving the pet
    // with 2/3 renders the walk, so 2/3 = MOVE. (The Journey/maplestory-wasm
    // client swaps the first two — MOVE=0/1, STAND=2/3 — so its table is NOT
    // used here; it disagrees with the official client.)
    private static final int PET_STAND_RIGHT = 0;
    private static final int PET_STAND_LEFT = 1;
    private static final int PET_MOVE_RIGHT = 2;
    private static final int PET_MOVE_LEFT = 3;
    private static final int PET_FLY_RIGHT = 10;
    private static final int PET_FLY_LEFT = 11;

    /** Bots that currently have pets — the only ones a tick visits. */
    private static final Set<Integer> TRACKED = ConcurrentHashMap.newKeySet();
    /** Per-pet next allowed speak time (epoch ms), keyed by pet unique id. */
    private static final Map<Integer, Long> nextSpeakAtMs = new ConcurrentHashMap<>();
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

        boolean swim = map.isSwim();
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

    /** Land follow: park the pet behind the bot, snapped to the ground. */
    private static void followLand(Character chr, Pet pet, int index, BotPetConfig config, boolean observed) {
        Point botPos = chr.getPosition();
        // The park position is behind the BOT, so it uses the bot's facing — a
        // Character, so the character stance encoding applies (only the pet's own
        // animation uses the pet stance values defined above).
        int facing = CharacterStance.isFacingLeft(chr.getStance()) ? -1 : 1;
        int behind = Math.abs(config.baseOffset()) + config.stepOffset() * index; // 40, 80, 120 px behind
        Point target = new Point(botPos.x - facing * behind, botPos.y);

        Point cur = pet.getPos();
        boolean withinDeadZone = Math.abs(cur.x - target.x) <= config.epsPx()
                && Math.abs(cur.y - target.y) <= config.epsPx();
        if (withinDeadZone) {
            // Arrived. If the pet is still rendered mid-walk, settle it to a stand
            // pose, or the client keeps looping the last move animation while the pet
            // sits in place ("marching in place"). Pet stances are NOT the character
            // stances: 0 = stand (the host summons pets with setStance(0)), and the
            // low bit is the facing. Sent once (the stance check guards a repeat).
            int stand = isPetFacingLeft(pet) ? PET_STAND_LEFT : PET_STAND_RIGHT;
            if (pet.getStance() != stand) {
                pet.setStance(stand);
                if (observed) {
                    broadcastMove(chr, pet, index, cur, 0, 0, pet.getFh(), stand, config);
                }
            }
            return;
        }
        int deltaX = target.x - cur.x;
        int deltaY = target.y - cur.y;
        double dt = Math.max(0.05, config.followTickMs() / 1000.0);
        int vx = deltaX == 0 ? 0 : (int) Math.round(deltaX / dt);
        int stance = deltaX == 0
                ? (isPetFacingLeft(pet) ? PET_STAND_LEFT : PET_STAND_RIGHT)
                : (deltaX > 0 ? PET_MOVE_RIGHT : PET_MOVE_LEFT);
        pet.setPos(target);
        pet.setStance(stance);
        int fh = BotPetController.footholdId(chr.getMap(), target);
        pet.setFh(fh);
        if (observed) {
            broadcastMove(chr, pet, index, target, vx, (int) Math.round(deltaY / dt), fh, stance, config);
        }
    }

    /**
     * Swim follow: glide the pet toward a point above the bot at a fixed speed,
     * rendered in the pet's FLY animation. Footholds are the seabed (or absent) in
     * water, so fh stays 0 and the client floats the pet.
     */
    private static void followSwim(Character chr, Pet pet, int index, BotPetConfig config, boolean observed) {
        Point botPos = chr.getPosition();
        Point target = new Point(botPos.x, botPos.y - config.swimOffset() * (index + 1));

        Point cur = pet.getPos();
        double offsetX = target.x - cur.x;
        double offsetY = target.y - cur.y;
        double dist = Math.hypot(offsetX, offsetY);
        if (dist <= config.swimDeadZonePx()) {
            return; // floating in place is the natural water idle — nothing to settle
        }
        double dt = Math.max(0.05, config.followTickMs() / 1000.0);
        double maxStep = Math.max(1.0, config.swimFollowSpeed() * dt);
        double scale = Math.min(1.0, maxStep / dist);
        double stepX = offsetX * scale;
        double stepY = offsetY * scale;

        int newX = (int) Math.round(cur.x + stepX);
        int newY = (int) Math.round(cur.y + stepY);
        int stance = stepX >= 0 ? PET_FLY_RIGHT : PET_FLY_LEFT;
        pet.setPos(new Point(newX, newY));
        pet.setStance(stance);
        pet.setFh(0);
        if (observed) {
            broadcastMove(chr, pet, index, new Point(newX, newY),
                    (int) Math.round(stepX / dt), (int) Math.round(stepY / dt), 0, stance, config);
        }
    }

    /**
     * Facing of a pet from its stance byte: the low bit is the facing (even =
     * right, odd = left), so STAND 0/1 and MOVE 2/3 mirror each other.
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
     * Pets with looting gear sweep nearby drops. A pet loots only the drops it
     * is entitled to (its owner's, or a free-for-all drop — including a nearby
     * player's once its owner-protection has lapsed), so a looting pet "loots
     * for" whoever dropped them without ever stealing a protected drop.
     */
    private static void loot(Character chr, MapleMap map, BotPetConfig config) {
        Pet[] pets = chr.getPets();
        for (int idx = 0; idx < pets.length; idx++) {
            Pet pet = pets[idx];
            if (pet == null) {
                continue;
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
        }
    }
}
