package soloMapling.ArtificialPlayer.BotTypes.Ariant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks Ariant Coliseum's catch threshold, which is the boundary a bot has to respect to score
 * at all.
 *
 * <p>This quest is scored on Spirit Jewels in the inventory, and a jewel only comes from
 * catching a scorpion - which the server only offers below forty percent health. A bot that
 * fights the way it does everywhere else kills the scorpions it was supposed to catch and ends
 * the round with nothing, so this boundary is the whole difference between playing the content
 * and defeating it.
 */
class AriantCatchRangeTest {

    @Test
    void aHealthyScorpionCannotBeCaught() {
        assertFalse(AriantPqData.catchableAt(1000, 1000));
        assertFalse(AriantPqData.catchableAt(500, 1000));
    }

    @Test
    void fortyPercentHealthIsNotYetLowEnough() {
        // The handler's test is hp < (maxHp/10)*4, a strict inequality, so exactly forty
        // percent fails. Getting this backwards would have the bot stop swinging one hit early.
        assertFalse(AriantPqData.catchableAt(400, 1000));
    }

    @Test
    void justBelowFortyPercentIsCatchable() {
        assertTrue(AriantPqData.catchableAt(399, 1000));
        assertTrue(AriantPqData.catchableAt(1, 1000));
    }

    @Test
    void theThresholdUsesIntegerDivisionLikeTheServer() {
        // maxHp 1005 divides to 100, times four is 400, so 399 is catchable and 400 is not.
        // A tidy 0.4 would put the boundary three health lower and have the bot wait for a
        // catch the server would already have accepted.
        assertFalse(AriantPqData.catchableAt(400, 1005));
        assertTrue(AriantPqData.catchableAt(399, 1005));
    }

    @Test
    void aDeadOrMalformedMonsterIsNotCatchable() {
        assertFalse(AriantPqData.catchableAt(0, 0));
        assertFalse(AriantPqData.catchableAt(-10, 1000));
    }

    @Test
    void theLevelBandIsNarrowerThanMostQuestsHere() {
        // 20 to 30 - far tighter than the others, so the same bot cannot be reused across
        // quests without regard to level.
        assertTrue(AriantPqData.levelEligible(20));
        assertTrue(AriantPqData.levelEligible(30));
        assertFalse(AriantPqData.levelEligible(19));
        assertFalse(AriantPqData.levelEligible(31));
    }
}
