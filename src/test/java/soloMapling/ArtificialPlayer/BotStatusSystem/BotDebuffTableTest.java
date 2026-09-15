package soloMapling.ArtificialPlayer.BotStatusSystem;

import org.gms.client.Disease;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotDebuffTableTest {

    @Test
    void freezeIsStunAndSeduce() {
        assertTrue(BotDebuffTable.freezes(Disease.STUN));
        assertTrue(BotDebuffTable.freezes(Disease.SEDUCE));
        assertFalse(BotDebuffTable.freezes(Disease.SEAL));
        assertFalse(BotDebuffTable.freezes(Disease.SLOW));
    }

    @Test
    void sealDisarmsWithoutFreezing() {
        assertTrue(BotDebuffTable.seals(Disease.SEAL));
        assertTrue(BotDebuffTable.blocksAttack(Disease.SEAL));
        assertTrue(BotDebuffTable.blocksAttack(Disease.STUN));   // frozen also disarms
        assertFalse(BotDebuffTable.blocksAttack(Disease.SLOW));  // slowed can still swing
        assertFalse(BotDebuffTable.blocksAttack(Disease.DARKNESS));
    }

    @Test
    void slowWeakenBlindPoisonAreDistinct() {
        assertTrue(BotDebuffTable.slows(Disease.SLOW));
        assertTrue(BotDebuffTable.weakens(Disease.WEAKEN));
        assertTrue(BotDebuffTable.blinds(Disease.DARKNESS));
        assertTrue(BotDebuffTable.poisons(Disease.POISON));

        assertFalse(BotDebuffTable.slows(Disease.WEAKEN));
        assertFalse(BotDebuffTable.weakens(Disease.SLOW));
        assertFalse(BotDebuffTable.blinds(Disease.POISON));
    }

    @Test
    void unrelatedDiseasesGateNothing() {
        assertFalse(BotDebuffTable.blocksAttack(Disease.CURSE));
        assertFalse(BotDebuffTable.freezes(Disease.CURSE));
        assertFalse(BotDebuffTable.slows(Disease.CURSE));
        assertFalse(BotDebuffTable.isDebuff(Disease.NULL));
        assertTrue(BotDebuffTable.isDebuff(Disease.CURSE)); // still a real disease (shown, just inert)
    }
}
