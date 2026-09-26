package soloMapling.ArtificialPlayer.BotAttackSystem;

import org.gms.constants.skills.Crusader;
import org.gms.constants.skills.DawnWarrior;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The finisher classification behind the 斗气集中 (Combo) orb consumption. Pure functions, so these
 * run without WZ data, a map, or a live character.
 *
 * <p>Pinned to the host's own set (GameConstants.isFinisherSkill): the adventurer Panic / Coma pair
 * (1111003-1111006) plus the Dawn Warrior's two (11111002/11111003). Shout (1111008) sits inside the
 * id range but is NOT a finisher - it must not consume the ring.
 */
class BotComboOrbTest {

    @Test
    void theHostsFinisherSetIsExactlyThePanicComaPairAndTheDawnWarriorPair() {
        assertTrue(BotComboOrb.isFinisher(Crusader.SWORD_PANIC)); // 1111003
        assertTrue(BotComboOrb.isFinisher(Crusader.AXE_PANIC));   // 1111004
        assertTrue(BotComboOrb.isFinisher(Crusader.SWORD_COMA));  // 1111005
        assertTrue(BotComboOrb.isFinisher(Crusader.AXE_COMA));    // 1111006
        assertTrue(BotComboOrb.isFinisher(DawnWarrior.PANIC));    // 11111002
        assertTrue(BotComboOrb.isFinisher(DawnWarrior.COMA));     // 11111003
    }

    @Test
    void shoutIsNotAFinisher() {
        // The host's set stops at 1111006; Shout (1111008) is a plain attack that keeps the orbs.
        assertFalse(BotComboOrb.isFinisher(Crusader.SHOUT));
    }

    @Test
    void theComboSkillAndPlainAttacksAreNotFinishers() {
        assertFalse(BotComboOrb.isFinisher(Crusader.COMBO));
        assertFalse(BotComboOrb.isFinisher(0));
    }

    @Test
    void theFinisherCadenceIsSixtySeconds() {
        // The bank-and-spend rhythm: a finisher consumes the whole ring, so the plugin spaces them
        // a minute apart - wide enough for a comboed bot to refill the ring several times over.
        assertEquals(60_000L, BotComboOrb.FINISHER_COOLDOWN_MS);
    }
}
