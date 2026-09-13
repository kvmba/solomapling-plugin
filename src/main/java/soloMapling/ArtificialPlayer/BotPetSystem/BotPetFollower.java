package soloMapling.ArtificialPlayer.BotPetSystem;

import org.gms.client.Character;
import org.gms.client.inventory.Pet;
import org.gms.constants.game.CharacterStance;
import org.gms.constants.inventory.ItemConstants;
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
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

import java.awt.Point;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
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
 * <p>When the bot changes map (including a death carry-home, which is just a
 * {@code changeMap}), the pets are re-placed on the new map and re-broadcast.
 * Pets with the matching looting gear sweep nearby drops (their own / a nearby
 * player's free-for-all ones) through the engine's own pickup path.</p>
 */
public final class BotPetFollower {

    private static final Map<Integer, Integer> LAST_MAP = new ConcurrentHashMap<>();
    private static ScheduledFuture<?> task;

    private BotPetFollower() {
    }

    public static synchronized void start(BotPetConfig config) {
        if (task != null) {
            return;
        }
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
        LAST_MAP.clear();
    }

    /** Forget a bot's map state (called when it leaves the world). */
    public static void forget(int botId) {
        LAST_MAP.remove(botId);
    }

    private static void tick(BotPetConfig config) {
        try {
            for (BotSM bot : CharacterStorage.getAllBots().values()) {
                Character chr = bot == null ? null : bot.getChr();
                if (chr == null || chr.getMap() == null) {
                    continue;
                }
                if (chr.getNoPets() == 0) {
                    LAST_MAP.remove(chr.getId());
                    continue;
                }
                MapleMap map = chr.getMap();
                int mapId = chr.getMapId();

                Integer last = LAST_MAP.put(chr.getId(), mapId);
                if (last == null || last != mapId) {
                    // Fresh pets, or a map change (portal, warp, or death carry-home):
                    // re-place on the new map and let observers see them there.
                    BotPetController.relocateForMap(chr);
                    continue;
                }

                if (!GCMovement.isMapObserved(mapId)) {
                    continue; // LOD: nobody can see it, so neither the motion nor the packet is worth it
                }

                boolean swim = map.isSwim();
                int idx = 0;
                for (Pet pet : chr.getPets()) {
                    if (pet == null) {
                        continue;
                    }
                    if (swim) {
                        followSwim(chr, pet, idx, config);
                    } else {
                        followLand(chr, pet, idx, config);
                    }
                    idx++;
                }
                if (chr.getHp() > 0) {
                    loot(chr, map, config);
                }
            }
        } catch (Throwable t) {
            System.err.println("[BotPetFollower] tick error: " + t);
        }
    }

    private static void followLand(Character chr, Pet pet, int index, BotPetConfig config) {
        Point botPos = chr.getPosition();
        int facing = CharacterStance.isFacingLeft(chr.getStance()) ? -1 : 1;
        int behind = Math.abs(config.baseOffset()) + config.stepOffset() * index; // 40, 80, 120 px behind
        Point target = new Point(botPos.x - facing * behind, botPos.y);

        Point cur = pet.getPos();
        if (Math.abs(cur.x - target.x) <= config.epsPx() && Math.abs(cur.y - target.y) <= config.epsPx()) {
            return;
        }
        int deltaX = target.x - cur.x;
        int deltaY = target.y - cur.y;
        double dt = Math.max(0.05, config.followTickMs() / 1000.0);
        int vx = deltaX == 0 ? 0 : (int) Math.round(deltaX / dt);
        int stance = deltaX == 0
                ? CharacterStance.STAND_RIGHT_STANCE
                : (deltaX > 0 ? CharacterStance.WALK_RIGHT_STANCE : CharacterStance.WALK_LEFT_STANCE);
        pet.setPos(target);
        pet.setStance(stance);
        int fh = BotPetController.footholdId(chr.getMap(), target);
        pet.setFh(fh);
        broadcastMove(chr, pet, index, target, vx, (int) Math.round(deltaY / dt), fh, stance, config);
    }

    private static void followSwim(Character chr, Pet pet, int index, BotPetConfig config) {
        Point botPos = chr.getPosition();
        Point target = new Point(botPos.x, botPos.y - config.swimOffset() * (index + 1));

        Point cur = pet.getPos();
        double offsetX = target.x - cur.x;
        double offsetY = target.y - cur.y;
        double dist = Math.hypot(offsetX, offsetY);
        if (dist <= config.swimDeadZonePx()) {
            return;
        }
        double dt = Math.max(0.05, config.followTickMs() / 1000.0);
        double maxStep = Math.max(1.0, config.swimFollowSpeed() * dt);
        double scale = Math.min(1.0, maxStep / dist);
        double stepX = offsetX * scale;
        double stepY = offsetY * scale;

        int newX = (int) Math.round(cur.x + stepX);
        int newY = (int) Math.round(cur.y + stepY);
        int stance = stepX >= 0 ? CharacterStance.SWIM_RIGHT_STANCE : CharacterStance.SWIM_LEFT_STANCE;
        pet.setPos(new Point(newX, newY));
        pet.setStance(stance);
        pet.setFh(0);
        broadcastMove(chr, pet, index, new Point(newX, newY),
                (int) Math.round(stepX / dt), (int) Math.round(stepY / dt), 0, stance, config);
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
