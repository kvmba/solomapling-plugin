package soloMapling.ArtificialPlayer.BotStatusSystem;

import org.gms.client.Disease;
import org.gms.server.life.MobSkillType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The engine mob-skill-type -> disease mapping the applier rolls against. Pure mapping, so it runs
 * without WZ data or a live mob.
 */
class BotDebuffApplierTest {

    @Test
    void botAffectingSkillsMapToTheirDisease() {
        assertEquals(Disease.STUN, BotDebuffApplier.diseaseFor(MobSkillType.STUN));
        assertEquals(Disease.SEDUCE, BotDebuffApplier.diseaseFor(MobSkillType.SEDUCE));
        assertEquals(Disease.SEAL, BotDebuffApplier.diseaseFor(MobSkillType.SEAL));
        assertEquals(Disease.SLOW, BotDebuffApplier.diseaseFor(MobSkillType.SLOW));
        assertEquals(Disease.WEAKEN, BotDebuffApplier.diseaseFor(MobSkillType.WEAKNESS));
        assertEquals(Disease.DARKNESS, BotDebuffApplier.diseaseFor(MobSkillType.DARKNESS));
        assertEquals(Disease.POISON, BotDebuffApplier.diseaseFor(MobSkillType.POISON));
    }

    @Test
    void nonDiseaseSkillsMapToNothing() {
        // Buffs / immunities / summons / reflect are not bot-affecting diseases here.
        assertNull(BotDebuffApplier.diseaseFor(MobSkillType.ATTACK_UP));
        assertNull(BotDebuffApplier.diseaseFor(MobSkillType.PHYSICAL_IMMUNE));
        assertNull(BotDebuffApplier.diseaseFor(MobSkillType.SUMMON));
        assertNull(BotDebuffApplier.diseaseFor(MobSkillType.PHYSICAL_COUNTER));
        assertNull(BotDebuffApplier.diseaseFor(MobSkillType.HEAL_M));
        assertNull(BotDebuffApplier.diseaseFor(null));
    }

    @Test
    void dispelIsNotModelledAsADisease() {
        // DISPEL strips buffs in the engine; it is not a stored disease a bot reacts to.
        assertNull(BotDebuffApplier.diseaseFor(MobSkillType.DISPEL));
    }
}
