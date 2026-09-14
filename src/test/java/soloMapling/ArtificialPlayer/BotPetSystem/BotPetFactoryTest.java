package soloMapling.ArtificialPlayer.BotPetSystem;

import org.gms.client.inventory.Pet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the in-memory pet path works without any database. The host has no
 * factory for a non-persisted pet — {@code createPet} and {@code loadFromDb}
 * both hit the DB, and the constructor is private — so the reflective path is
 * the only way to make a pet that never touches {@code pets}/{@code inventoryitems}.
 * If this test runs at all, that path did not touch the DB.
 */
class BotPetFactoryTest {

    @Test
    void inMemoryPetIsBuiltWithoutDb() {
        PetSpec spec = new PetSpec(5000001, 3, true, false, false);
        Pet pet = BotPetFactory.createInMemory(spec, "Mochi");

        assertNotNull(pet, "reflective construction should succeed");
        assertEquals(5000001, pet.getItemId());
        assertEquals(3, pet.getLevel());
        assertEquals("Mochi", pet.getName());
        assertTrue(pet.isSummoned());
        assertEquals(100, pet.getFullness());
        assertTrue(pet.getUniqueId() > 0, "pet needs a unique id for MOVE_PET");
    }

    @Test
    void inMemoryPetsGetDistinctIds() {
        PetSpec spec = new PetSpec(5000002, 1, false, false, false);
        Pet a = BotPetFactory.createInMemory(spec, null);
        Pet b = BotPetFactory.createInMemory(spec, null);
        assertNotNull(a);
        assertNotNull(b);
        assertNotEquals(a.getUniqueId(), b.getUniqueId(), "ids must be unique");
    }

    @Test
    void inMemoryIdsAvoidTheCashIdRange() {
        // CashIdGenerator wraps below 777,000,000; in-memory ids must stay clear of it.
        Pet pet = BotPetFactory.createInMemory(new PetSpec(5000004, 1, false, false, false), null);
        assertNotNull(pet);
        assertTrue(pet.getUniqueId() > 777_000_000,
                "in-memory pet id must not collide with real pet ids");
    }
}
