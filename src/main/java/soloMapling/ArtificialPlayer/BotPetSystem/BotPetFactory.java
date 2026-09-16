package soloMapling.ArtificialPlayer.BotPetSystem;

import org.gms.client.Character;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.Item;
import org.gms.client.inventory.Pet;

import java.lang.reflect.Constructor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Creates the {@link Pet} object for a bot. Two paths:
 *
 * <ul>
 *   <li><b>In-memory (ambient template bots).</b> Reflects the private
 *       {@link Pet} constructor and fills the fields with the public setters,
 *       then attaches the pet to the character's in-memory pet array only. No
 *       {@code inventoryitems} row, no {@code pets} row, no cash-id bookkeeping
 *       — an ambient bot never calls {@code saveCharToDB}, so nothing about it
 *       should ever reach the database. The pet id is drawn from a high private
 *       range so it can never collide with a real (or companion) pet id.</li>
 *   <li><b>Persistent (companions).</b> Uses the host's own
 *       {@code Pet.createPet(...)} + a CASH inventory item bound to that id, so
 *       the pet is saved and reloaded with the companion like any player pet.</li>
 * </ul>
 */
public final class BotPetFactory {

    /**
     * In-memory pet ids start well above the {@code CashIdGenerator} range
     * (which wraps at 777,000,000) so an ambient pet id is never mistaken for a
     * persisted one.
     */
    private static final int IN_MEMORY_ID_BASE = 1_500_000_000;
    private static final AtomicInteger IN_MEMORY_ID = new AtomicInteger(IN_MEMORY_ID_BASE);

    private BotPetFactory() {
    }

    /**
     * Build a pet that lives only in memory. Returns {@code null} if the
     * reflective constructor is unavailable.
     */
    public static Pet createInMemory(PetSpec spec, String name) {
        try {
            Constructor<Pet> ctor = Pet.class.getDeclaredConstructor(int.class, short.class, int.class);
            ctor.setAccessible(true);
            int id = IN_MEMORY_ID.getAndIncrement();
            Pet pet = ctor.newInstance(spec.itemId(), (short) 0, id);
            pet.setSummoned(true);
            pet.setLevel((byte) spec.level());
            pet.setTameness(spec.tameness());
            // Fullness is never registered for hunger decay, so nothing drains it.
            pet.setFullness(spec.fullness());
            // The pet name is written into SPAWN_PET / SPAWN_PLAYER packets with no null
            // guard, so it must never be null — resolveName guarantees that.
            pet.setName(resolveName(spec.itemId(), name));
            return pet;
        } catch (ReflectiveOperationException e) {
            System.err.println("[BotPetFactory] in-memory Pet construction failed: " + e);
            return null;
        }
    }

    /**
     * A name that is never null/blank, so packet serialization can never NPE on it.
     * A named pet carries its nickname; an unnamed one keeps its OFFICIAL default name —
     * the item name from String.wz (localized: "褐色小猫"), exactly like a fresh real pet.
     * The item lookup is the shared, race-safe wrapper, and a null/blank result (a WZ gap)
     * falls back to the placeholder so a pet is never nameless on the wire.
     */
    private static String resolveName(int itemId, String name) {
        if (name != null && !name.isBlank()) {
            return name;
        }
        String official = null;
        try {
            official = soloMapling.ArtificialPlayer.BotHelpers.itemNameOrNull(itemId);
        } catch (RuntimeException | LinkageError e) {
            // The WZ provider is a boundary outside this feature: it is absent in unit
            // tests (class-init failure -> LinkageError). A host name lookup can also lose
            // a race on the host's non-concurrent name cache (-> RuntimeException, see
            // BotHelpers). Either way, fall through to the placeholder — a nameless wire
            // value would NPE the pet packet.
        }
        return official != null && !official.isBlank() ? official : "Pet";
    }

    /**
     * Build a pet through the host's native path: a real {@code pets} row and a
     * CASH item bound to it. Returns {@code null} on failure.
     */
    public static Pet createPersistent(Character bot, PetSpec spec, String name) {
        int petId = Pet.createPet(spec.itemId(), (byte) spec.level(), spec.tameness(), spec.fullness());
        if (petId <= 0) {
            return null;
        }
        Pet pet = Pet.loadFromDb(spec.itemId(), (short) 0, petId);
        if (pet == null) {
            // The row was just created; don't leave it orphaned if the reload failed.
            Pet.deleteFromDb(bot, petId);
            return null;
        }
        pet.setSummoned(true);
        pet.setTameness(spec.tameness());
        pet.setFullness(spec.fullness()); // see the in-memory note
        pet.setName(resolveName(spec.itemId(), name));
        // An Item bound to the pet id: its constructor loads the pet we just made.
        Item item = new Item(spec.itemId(), (short) 0, (short) 1, petId);
        item.setExpiration(Long.MAX_VALUE);
        item.setFlag((short) 0);
        short slot = bot.getInventory(InventoryType.CASH).addItem(item);
        if (slot == -1) {
            // CASH full: drop the pet we just created rather than leave an orphan row
            // with no backing inventory item.
            Pet.deleteFromDb(bot, petId);
            return null;
        }
        pet.saveToDb();
        return pet;
    }
}
