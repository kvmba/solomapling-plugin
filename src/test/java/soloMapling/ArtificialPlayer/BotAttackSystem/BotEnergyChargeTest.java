package soloMapling.ArtificialPlayer.BotAttackSystem;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The charge model behind 能量获得 (Energy Charge): the bar math and the job gate. Pure functions, so
 * these run without WZ data, a map, or a live character.
 *
 * <p>The numbers pinned here are the host's own (Character.handleEnergyChargeGain): +102 a hit, armed
 * and flipped to 15000 in the same step, and every charged-state test in the host compares against
 * exactly 15000.
 */
class BotEnergyChargeTest {

    @Test
    void aHitAddsTheHostsFlatCharge() {
        assertEquals(102, BotEnergyCharge.gain(0, 1).energy());
        assertEquals(204, BotEnergyCharge.gain(102, 1).energy());
    }

    @Test
    void anAoeSwingChargesOncePerMobHit() {
        // The host loops handleEnergyChargeGain once per mob the swing hit.
        assertEquals(306, BotEnergyCharge.gain(0, 3).energy());
        assertEquals(BotEnergyCharge.FULL_ENERGY, BotEnergyCharge.gain(9_900, 1).energy());
    }

    @Test
    void theArmStepFillsTheBarInTheSameCall() {
        // 10000 arms and 15000 fills in one host call, so a bot never rests in between.
        BotEnergyCharge.Gain gain = BotEnergyCharge.gain(9_950, 1);

        assertEquals(BotEnergyCharge.FULL_ENERGY, gain.energy());
        assertTrue(gain.becameFull());
        assertTrue(BotEnergyCharge.isCharged(gain.energy()));
    }

    @Test
    void chargingClampsAtTheArmValueUntilTheStepFlipsIt() {
        // A big AoE cannot over-charge past the 10000 arm point for one mob and land at, say, 10200.
        assertEquals(BotEnergyCharge.FULL_ENERGY, BotEnergyCharge.gain(9_999, 6).energy());
    }

    @Test
    void aChargedBarDoesNotRecharge() {
        // The caller short-circuits on a charged bar; the model itself is also a no-op on it.
        BotEnergyCharge.Gain gain = BotEnergyCharge.gain(BotEnergyCharge.FULL_ENERGY, 3);

        assertEquals(BotEnergyCharge.FULL_ENERGY, gain.energy());
        assertFalse(gain.becameFull());
    }

    @Test
    void onlyBrawlerLinePiratesHoldACharge() {
        assertTrue(BotEnergyCharge.isEnergyChargeJob(511)); // Marauder
        assertTrue(BotEnergyCharge.isEnergyChargeJob(512)); // Buccaneer
        assertFalse(BotEnergyCharge.isEnergyChargeJob(510));   // Brawler - the skill comes at 3rd job
        assertFalse(BotEnergyCharge.isEnergyChargeJob(521));   // Outlaw - gun line
        assertFalse(BotEnergyCharge.isEnergyChargeJob(522));   // Corsair - gun line
        assertFalse(BotEnergyCharge.isEnergyChargeJob(411));   // Hermit
        assertFalse(BotEnergyCharge.isEnergyChargeJob(1511));  // Thunder Breaker - its own Cygnus skill
        assertFalse(BotEnergyCharge.isEnergyChargeJob(0));     // beginner
    }

    @Test
    void theChargedValueIsTheHostsOwn() {
        // Every host check tests the bar against exactly this value (Character.reapplyLocalStats,
        // AbstractDealDamageHandler, TouchMonsterDamageHandler), so it is a contract, not a tunable.
        assertEquals(15_000, BotEnergyCharge.FULL_ENERGY);
    }

    @Test
    void aBotBelowTheThirdJobHasNoEnergyCharge() {
        // Energy Charge is a Marauder (511) skill: the third job is taken at level 70, so anything
        // below that legitimately holds nothing to grant.
        assertEquals(0, BotEnergyCharge.skillLevelForBot(0, 40));
        assertEquals(0, BotEnergyCharge.skillLevelForBot(69, 40));
    }

    @Test
    void theGrantedLevelFillsInAtTheHostsOwnSpRate() {
        // A 3rd job advance hands out 1 SP (Character.changeJob: 511 % 10 != 2), then the server
        // grants level_up_sp_gain = 3 a level at 1 point per skill level - the same model
        // CompanionSkillBuilds uses for a brawler build, which puts Energy Charge first and takes it
        // straight to 40.
        assertEquals(1, BotEnergyCharge.skillLevelForBot(70, 40));   // the day of advancement
        assertEquals(4, BotEnergyCharge.skillLevelForBot(71, 40));   // + 3
        assertEquals(7, BotEnergyCharge.skillLevelForBot(72, 40));
    }

    @Test
    void aMaxedSkillStaysInsideTheWzLevelTable() {
        // 40 is the real WZ ceiling (Skill.wz/511.img.xml: level nodes 1..40), so the granted level
        // can never index past the effect list.
        assertEquals(40, BotEnergyCharge.skillLevelForBot(83, 40));
        assertEquals(40, BotEnergyCharge.skillLevelForBot(120, 40));
        assertEquals(40, BotEnergyCharge.skillLevelForBot(200, 40));
        // A maxLevel below the pacing's reach still caps rather than overshoots.
        assertEquals(20, BotEnergyCharge.skillLevelForBot(120, 20));
    }
}
