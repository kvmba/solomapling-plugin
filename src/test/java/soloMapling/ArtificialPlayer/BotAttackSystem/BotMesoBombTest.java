package soloMapling.ArtificialPlayer.BotAttackSystem;

import org.gms.constants.skills.Bandit;
import org.gms.constants.skills.ChiefBandit;
import org.gms.constants.skills.Rogue;
import org.gms.constants.skills.Shadower;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the bot's own logic in the 攒袋-引爆 (Pickpocket -&gt; Money Explosion) loop.
 *
 * <p>The detonation frame itself needs no test seam: {@code tryDetonate} broadcasts through the
 * host's own {@code PacketCreator.closeRangeAttack}, whose {@code addAttackBody} special-cases
 * skill 4211006 with the per-entry bag-count byte - the exact bytes a real player's explosion
 * sends, so observer clients parse it by construction. (A Character cannot even be constructed in
 * unit tests: its static init requires the host's Spring context.)</p>
 */
class BotMesoBombTest {

    @Test
    void thePoseIsTheClientActionEnumProne2() {
        // The Skill.wz action node names "prone2"; on the client's hardcoded action enum that is
        // 58 - pinned by the two live-validated codes the plugin already ships, avenger=56 and
        // assassination=59, which walk assaulter/prone2/assassination consecutively.
        assertEquals(58, BotAttackData.actionFor(ChiefBandit.MESO_EXPLOSION, null));
    }

    @Test
    void onlyPickpocketTriggerSkillsScatterBags() {
        // The host's AbstractDealDamageHandler gate, verbatim: the plain swing, Double Stab,
        // Savage Blow, Assaulter, Band of Thieves, Assassinate, Taunt and Boomerang Step.
        assertTrue(BotMesoBomb.isTriggerSkill(0));
        assertTrue(BotMesoBomb.isTriggerSkill(Rogue.DOUBLE_STAB));
        assertTrue(BotMesoBomb.isTriggerSkill(Bandit.SAVAGE_BLOW));
        assertTrue(BotMesoBomb.isTriggerSkill(ChiefBandit.ASSAULTER));
        assertTrue(BotMesoBomb.isTriggerSkill(ChiefBandit.BAND_OF_THIEVES));
        assertTrue(BotMesoBomb.isTriggerSkill(Shadower.ASSASSINATE));
        assertTrue(BotMesoBomb.isTriggerSkill(Shadower.TAUNT));
        assertTrue(BotMesoBomb.isTriggerSkill(Shadower.BOOMERANG_STEP));
    }

    @Test
    void nonTriggerSkillsScatterNothing() {
        assertFalse(BotMesoBomb.isTriggerSkill(ChiefBandit.MESO_EXPLOSION)); // the bomb itself
        assertFalse(BotMesoBomb.isTriggerSkill(1111008));                    // Shout, unrelated
    }
}
