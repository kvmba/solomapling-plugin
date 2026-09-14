package soloMapling.ArtificialPlayer.BotPetSystem;

import org.gms.client.Character;
import org.gms.client.inventory.Equip;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.Item;
import org.gms.constants.inventory.ItemConstants;
import org.gms.server.ItemInformationProvider;

/**
 * Puts pet looting gear into a pet's equip slots. A real client decides whether
 * a pet can loot by looking at exactly these slots (host {@code PetLootHandler}:
 * {@code isEquippedItemPouch} / {@code isEquippedMesoMagnet}), so a pet only
 * loots when we put the matching gear on it — "loot ability comes from the gear".
 *
 * <p>Pet gear CANNOT go through {@link soloMapling.ArtificialPlayer.BotCustomization}
 * (its slot resolver does not know the 1812/1822 prefixes and would mis-slot the
 * item). We write straight to the per-pet equip slot from
 * {@link ItemConstants#PET_EQUIP_SLOTS}.</p>
 */
public final class BotPetGear {

    private BotPetGear() {
    }

    /** The name label ring: equipping it gives the pet a name tag. (v83 has one.) */
    private static final int NAME_TAG_RING_ID = 1822000;

    public static boolean equipItemPouch(Character bot, int petIndex, int itemId) {
        return equip(bot, petIndex, itemId, ItemConstants.PET_EQUIP_SLOTS.get(petIndex).itemPouch());
    }

    public static boolean equipMesoMagnet(Character bot, int petIndex, int itemId) {
        return equip(bot, petIndex, itemId, ItemConstants.PET_EQUIP_SLOTS.get(petIndex).mesoMagnet());
    }

    public static boolean equipNameTag(Character bot, int petIndex) {
        // A name tag only shows if the pet has a real name; harmless otherwise.
        return equip(bot, petIndex, NAME_TAG_RING_ID, ItemConstants.PET_EQUIP_SLOTS.get(petIndex).nameTag());
    }

    /** @return true when an item was actually written to the slot */
    private static boolean equip(Character bot, int petIndex, int itemId, short slot) {
        if (bot == null || bot.getInventory(InventoryType.EQUIPPED) == null) {
            return false;
        }
        Equip source = equipOf(itemId);
        if (source == null) {
            return false;
        }
        var inv = bot.getInventory(InventoryType.EQUIPPED);
        if (inv.getItem(slot) != null) {
            inv.removeSlot(slot);
        }
        source.setPosition(slot);
        inv.addItemFromDB(source);
        return true;
    }

    /** Pet gear are Equip items; {@code getEquipById} returns an {@link Equip}. */
    private static Equip equipOf(int itemId) {
        Item item = ItemInformationProvider.getInstance().getEquipById(itemId);
        return (item instanceof Equip equip) ? equip : null;
    }
}
