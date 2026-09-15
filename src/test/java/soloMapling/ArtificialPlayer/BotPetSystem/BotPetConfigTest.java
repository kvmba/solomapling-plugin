package soloMapling.ArtificialPlayer.BotPetSystem;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the packaged YAML against key drift: if a nested key stops matching the
 * parser, the value silently falls back to its default and this fails. Runs from
 * the project root, where {@code src/main/java/soloMapling/...} resolves.
 */
class BotPetConfigTest {

    @Test
    void packagedConfigParsesEveryNestedBlock() {
        BotPetConfig c = BotPetConfig.load();

        // curve
        assertEquals(0.30, c.pMax(), 1e-9);
        assertEquals(120, c.levelCap());
        assertEquals(1.4, c.exponent(), 1e-9);
        assertEquals(0.25, c.tierWeight(), 1e-9);
        assertEquals(10, c.minLevel());

        // counts: four bands, ascending sMax, last catches all
        assertEquals(4, c.countBands().size());
        assertEquals(0.25, c.countBands().get(0).sMax(), 1e-9);
        assertEquals(100, c.countBands().get(0).weightFor(1));
        assertEquals(18, c.countBands().get(3).weightFor(3));

        // pet_level
        assertEquals(1, c.petLevelBase());
        assertEquals(4, c.petLevelPerStrength());
        assertEquals(5, c.petLevelMax());

        // naming
        assertEquals(0.25, c.namingChance(), 1e-9);

        // gear
        assertEquals(1812001, c.itemPouchId());
        assertEquals(0.60, c.itemPouchChance(), 1e-9);
        assertEquals(1812000, c.mesoMagnetId());
        assertEquals(0.30, c.mesoMagnetChance(), 1e-9);

        // follow
        assertEquals(200L, c.followTickMs());
        assertEquals(14, c.swimOffset());

        // pickup
        assertEquals(1, c.pickupMaxPerTick());
        assertEquals(120, c.pickupRange());
        assertEquals(1000L, c.pickupCooldownMs());

        // speak
        assertEquals(0.25, c.speakChance(), 1e-9);
        assertEquals(8000L, c.speakMinIntervalMs());
        assertEquals(25000L, c.speakMaxIntervalMs());

        // switches
        assertTrue(c.persistCompanions());
        assertTrue(c.enabled());
    }

    @Test
    void packagedPetPoolLoadsValidatedIds() {
        BotPetPool.forceReload();
        assertTrue(BotPetPool.isLoaded(), "packaged pool should load");
        assertTrue(BotPetPool.size() >= 10, "pool should have a healthy set of pets");
        assertTrue(BotPetPool.all().stream().allMatch(id -> id >= 5000000 && id < 5010000),
                "every pooled id is a pet id");
    }

    @Test
    void packagedPetNamesLoadAndAreUsable() {
        // Guards the nickname pool: a bad encoding, a wrong key, or an over-long entry
        // would silently empty or shrink it — and a named pet would then fall back to
        // its default name instead of the intended nickname.
        BotPetNames.load();
        String name = BotPetNames.random();
        assertTrue(name != null && !name.isBlank(), "a nickname must be drawable");
        assertTrue(name.length() <= 7, "nickname must fit the client's name limit: " + name);
        // The pool is Chinese; a mojibake read would produce replacement/question marks.
        assertTrue(name.chars().anyMatch(c -> c >= 0x4E00 && c <= 0x9FFF),
                "nickname should contain a CJK character, got: " + name);
    }
}
