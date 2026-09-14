package soloMapling.ArtificialPlayer.BotPetSystem;

import org.gms.client.Character;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.Pet;
import org.gms.constants.inventory.ItemConstants;
import org.gms.constants.inventory.PetEquipSlot;
import org.gms.server.maps.Foothold;
import org.gms.server.maps.MapleMap;
import org.gms.util.PacketCreator;
import soloMapling.companion.CompanionRoster;

import java.awt.Point;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Grants, summons, relocates and removes a bot's pets. This is the engine-facing
 * half of the feature; the decision half is {@link BotPetAssigner}.
 *
 * <p>Ambient (template) bots get in-memory pets that never touch the database;
 * persistent companions get real pets through the host path. See
 * {@link BotPetFactory}.</p>
 */
public final class BotPetController {

    private BotPetController() {
    }

    /** Y offset above the character at which a pet is placed when it appears. */
    private static final int SPAWN_Y_OFFSET = 12;

    /**
     * Give {@code bot} its pets, if the policy says so. Idempotent: a bot that
     * already has pets is left alone (this is what makes companion reloads safe).
     */
    public static void grantForBot(Character bot, BotPetConfig config) {
        if (bot == null || bot.getMap() == null) {
            return; // mapless bots (e.g. the console bot) can't show a pet either
        }
        if (hasAnyPet(bot)) {
            // Idempotent: a companion that reloaded its saved pets keeps them.
            // Still start following them — a reloaded companion is granted before
            // any follow tracking existed.
            BotPetFollower.track(bot.getId());
            return;
        }

        boolean persistent = config.persistCompanions() && CompanionRoster.isCompanion(bot.getId());
        List<Integer> pool = BotPetPool.all();
        List<PetSpec> specs = BotPetAssigner.assign(
                bot.getLevel(), bot.getTier(), pool, config, ThreadLocalRandom.current());
        if (specs.isEmpty()) {
            return;
        }

        boolean granted = false;
        for (PetSpec spec : specs) {
            String name = spec.named() ? BotPetNames.random() : null;
            Pet pet = persistent
                    ? BotPetFactory.createPersistent(bot, spec, name)
                    : BotPetFactory.createInMemory(spec, name);
            if (pet == null) {
                continue;
            }
            bot.addPet(pet);
            // Read the slot the engine actually gave the pet (host convention, cf.
            // SpawnPetProcessor) instead of assuming a counter: the slot is what
            // MOVE_PET / gear slots / name-tag slots are keyed on.
            int slot = bot.getPetIndex(pet.getUniqueId());
            if (slot < 0) {
                bot.removePet(pet, true); // cannot happen off an empty array; never mis-slot anyway
                continue;
            }
            placeAtBot(bot, pet, slot);
            // Gear first, so the spawn broadcast already reflects the name tag
            // and looting pouches (hasPetNameTag / hasPetChatballoon read slots).
            equipPetGear(bot, slot, spec, config);
            if (persistent) {
                pet.saveToDb();
            }
            broadcastShow(bot, pet);
            granted = true;
        }
        if (granted) {
            BotPetFollower.track(bot.getId());
        }
    }

    /**
     * Detach every pet from {@code bot}: remove the looting/name gear, drop the
     * pet from the in-memory array, and tell observers. Used when an ambient bot
     * becomes an FM shop keeper or leaves the world.
     *
     * <p>Persistent companions are <b>not</b> touched here: their pets are real
     * saved state and must survive a logout. {@link #grantForBot} is already
     * idempotent, so a companion re-loading keeps the pets it had.</p>
     */
    public static void removePets(Character bot) {
        if (bot == null || CompanionRoster.isCompanion(bot.getId())) {
            return;
        }
        if (bot.getNoPets() == 0) {
            BotPetFollower.forget(bot.getId());
            return; // nothing to detach (the common case)
        }
        Pet[] pets = bot.getPets();
        for (int i = 0; i < pets.length; i++) {
            clearPetGear(bot, i);
        }
        if (bot.getMap() != null) {
            for (Pet pet : pets) {
                if (pet != null) {
                    broadcastRemove(bot, pet);
                }
            }
        }
        boolean had = false;
        for (Pet pet : pets) {
            if (pet == null) {
                continue;
            }
            had = true;
            pet.setSummoned(false);
            bot.removePet(pet, true);
            // In-memory pets were never written to the DB, so there is no row to
            // delete — that is the whole point of the in-memory path.
        }
        if (had) {
            bot.sendPacket(PacketCreator.petStatUpdate(bot));
        }
        BotPetFollower.forget(bot.getId());
    }

    private static boolean hasAnyPet(Character bot) {
        if (bot == null) {
            return false;
        }
        for (Pet pet : bot.getPets()) {
            if (pet != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Place a pet just above the bot. On land it snaps to the foothold below; in
     * a swim map footholds are the seabed / can be missing, so it floats (fh=0)
     * and the follower glides it instead.
     */
    static void placeAtBot(Character bot, Pet pet, int index) {
        MapleMap map = bot.getMap();
        Point pos = bot.getPosition();
        Point p = new Point(pos.x, pos.y - SPAWN_Y_OFFSET * (index + 1));
        pet.setPos(p);
        pet.setStance(0);
        if (map != null && map.isSwim()) {
            pet.setFh(0);
        } else {
            pet.setFh(footholdId(map, p));
        }
    }

    static int footholdId(MapleMap map, Point p) {
        if (map == null) {
            return 0;
        }
        Foothold fh = map.getFootholds().findBelow(p);
        return fh == null ? 0 : fh.getId();
    }

    private static void equipPetGear(Character bot, int petIndex, PetSpec spec, BotPetConfig config) {
        if (petIndex < 0 || petIndex >= ItemConstants.PET_EQUIP_SLOTS.size()) {
            return;
        }
        if (spec.pickupItem()) {
            BotPetGear.equipItemPouch(bot, petIndex, config.itemPouchId());
        }
        if (spec.pickupMeso()) {
            BotPetGear.equipMesoMagnet(bot, petIndex, config.mesoMagnetId());
        }
        if (spec.named()) {
            BotPetGear.equipNameTag(bot, petIndex);
        }
    }

    private static void clearPetGear(Character bot, int petIndex) {
        if (bot == null || petIndex < 0 || petIndex >= ItemConstants.PET_EQUIP_SLOTS.size()) {
            return;
        }
        var inv = bot.getInventory(InventoryType.EQUIPPED);
        if (inv == null) {
            return;
        }
        PetEquipSlot slots = ItemConstants.PET_EQUIP_SLOTS.get(petIndex);
        inv.removeSlot(slots.itemPouch());
        inv.removeSlot(slots.mesoMagnet());
        inv.removeSlot(slots.nameTag());
        inv.removeSlot(slots.itemIgnore());
        inv.removeSlot(slots.chatBalloon());
        inv.removeSlot(slots.equip());
    }

    private static void broadcastShow(Character bot, Pet pet) {
        if (bot.getMap() != null) {
            bot.getMap().broadcastMessage(bot, PacketCreator.showPet(bot, pet, false, false), true);
        }
    }

    private static void broadcastRemove(Character bot, Pet pet) {
        if (bot.getMap() != null) {
            bot.getMap().broadcastMessage(bot, PacketCreator.showPet(bot, pet, true, false), true);
        }
    }
}
