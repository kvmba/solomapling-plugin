package soloMapling.ArtificialPlayer.BotPetSystem;

import org.gms.client.Character;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.Pet;
import org.gms.constants.inventory.ItemConstants;
import org.gms.constants.inventory.PetEquipSlot;
import org.gms.server.maps.Foothold;
import org.gms.server.maps.MapleMap;
import org.gms.util.PacketCreator;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
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

    /** Horizontal spread between a bot's pets so they do not stack on one pixel. */
    private static final int SPAWN_X_SPREAD = 22;
    /** Pet STAND-right stance (see BotPetFollower); 0 is the pet's MOVE pose, not stand. */
    private static final int PET_STAND_RIGHT = 4;

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
        boolean lookChanged = false;
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
            lookChanged |= equipPetGear(bot, slot, spec, config);
            if (persistent) {
                pet.saveToDb();
            }
            broadcastShow(bot, pet);
            granted = true;
        }
        if (granted) {
            BotPetFollower.track(bot.getId());
        }
        if (lookChanged) {
            // Pet gear rides in the character LOOK (body parts 14/21/22/23), which
            // observers only receive on an equipChanged broadcast — the host's own
            // equip path ends the same way. Once per grant, not per pet.
            refreshLook(bot);
        }
    }

    /**
     * Refresh the character look after a gear change. {@code equipChanged} is not
     * null-map safe (it broadcasts to {@code getMap()}), and a character starts
     * with a null map until it is placed — so a gear change on a map-less bot
     * must skip the broadcast rather than NPE.
     */
    private static void refreshLook(Character bot) {
        if (bot.getMap() != null) {
            bot.equipChanged();
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
        boolean gearCleared = false;
        for (int i = 0; i < pets.length; i++) {
            gearCleared |= clearPetGear(bot, i);
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
        if (gearCleared) {
            // Gear rides in the character look; refresh it so observers stop
            // rendering the name tag / pouches (host's own unequip does the same).
            refreshLook(bot);
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
     * Place a pet near the bot. On land it stands on the floor under its own x; in
     * a swim map footholds are the seabed / can be missing, so it floats (fh=0)
     * and the follower glides it instead. Pets are nudged apart by index so a
     * multi-pet bot's pets do not stack on one pixel.
     */
    static void placeAtBot(Character bot, Pet pet, int index) {
        MapleMap map = bot.getMap();
        Point pos = bot.getPosition();
        int x = pos.x + (index + 1) * SPAWN_X_SPREAD;
        Point p = new Point(x, map != null && map.isSwim() ? pos.y : groundY(map, x, pos.y));
        pet.setPos(p);
        pet.setStance(PET_STAND_RIGHT); // 4; 0 is the pet's MOVE pose, not stand
        pet.setFh(map != null && map.isSwim() ? 0 : footholdId(map, p));
    }

    static int footholdId(MapleMap map, Point p) {
        if (map == null) {
            return 0;
        }
        Foothold fh = GCMovement.footholdBelow(map, p.x, p.y);
        return fh == null ? 0 : fh.getId();
    }

    /** The y of the floor under x, falling back to {@code fallbackY} when there is none. */
    private static int groundY(MapleMap map, int x, int fallbackY) {
        if (map == null) {
            return fallbackY;
        }
        Foothold fh = GCMovement.footholdBelow(map, x, fallbackY);
        return fh == null ? fallbackY : fh.calculateFooting(x);
    }

    /** @return true when any gear was actually written (caller must then refresh the look) */
    private static boolean equipPetGear(Character bot, int petIndex, PetSpec spec, BotPetConfig config) {
        if (petIndex < 0 || petIndex >= ItemConstants.PET_EQUIP_SLOTS.size()) {
            return false;
        }
        boolean changed = false;
        if (spec.pickupItem()) {
            changed |= BotPetGear.equipItemPouch(bot, petIndex, config.itemPouchId());
        }
        if (spec.pickupMeso()) {
            changed |= BotPetGear.equipMesoMagnet(bot, petIndex, config.mesoMagnetId());
        }
        if (spec.named()) {
            changed |= BotPetGear.equipNameTag(bot, petIndex);
        }
        return changed;
    }

    /** @return true when any gear slot actually held an item (caller must then refresh the look) */
    private static boolean clearPetGear(Character bot, int petIndex) {
        if (bot == null || petIndex < 0 || petIndex >= ItemConstants.PET_EQUIP_SLOTS.size()) {
            return false;
        }
        var inv = bot.getInventory(InventoryType.EQUIPPED);
        if (inv == null) {
            return false;
        }
        PetEquipSlot slots = ItemConstants.PET_EQUIP_SLOTS.get(petIndex);
        boolean cleared = false;
        cleared |= inv.getItem(slots.itemPouch()) != null;
        cleared |= inv.getItem(slots.mesoMagnet()) != null;
        cleared |= inv.getItem(slots.nameTag()) != null;
        cleared |= inv.getItem(slots.itemIgnore()) != null;
        cleared |= inv.getItem(slots.chatBalloon()) != null;
        cleared |= inv.getItem(slots.equip()) != null;
        inv.removeSlot(slots.itemPouch());
        inv.removeSlot(slots.mesoMagnet());
        inv.removeSlot(slots.nameTag());
        inv.removeSlot(slots.itemIgnore());
        inv.removeSlot(slots.chatBalloon());
        inv.removeSlot(slots.equip());
        return cleared;
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
