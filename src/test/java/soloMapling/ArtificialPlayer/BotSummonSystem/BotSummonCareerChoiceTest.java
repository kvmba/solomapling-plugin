package soloMapling.ArtificialPlayer.BotSummonSystem;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pins which summon a career shows, because a PQ lobby re-derives a bot's advancement from the
 * lobby's level band AFTER the spawn-time summon was granted.
 *
 * <p>Two shapes matter, and they are different bugs:
 * <ul>
 *   <li><b>band drops a tier</b> (the LPQ 35-50 case): the spawn-time 3rd-job bird is owned by a
 *       career that has no summon at all. "Does the new career own any summon?" catches it.</li>
 *   <li><b>band keeps the tier, class family re-rolls the branch</b> (a 70-119 lobby turning a
 *       Ranger into a Sniper): both careers own a bird, but a DIFFERENT one. Only "would it choose
 *       the same skill?" catches that, and getting it wrong leaves a crossbowman flying the bowman's
 *       hawk.</li>
 * </ul>
 */
class BotSummonCareerChoiceTest {

    @Test
    void theSameTierCanStillSwapTheBird() {
        // Ranger and Sniper are both 3rd-job archers, so a 70-119 lobby may re-roll between them.
        assertEquals(Integer.valueOf(3111005), BotSummonController.chooseSummon(311), "Ranger: silver hawk");
        assertEquals(Integer.valueOf(3211005), BotSummonController.chooseSummon(321), "Sniper: golden eagle");
        // Their 4th-job successors swap too.
        assertEquals(Integer.valueOf(3121006), BotSummonController.chooseSummon(312), "Bowmaster: phoenix");
        assertEquals(Integer.valueOf(3221005), BotSummonController.chooseSummon(322), "Marksman: frost prey");
    }

    @Test
    void anAdvancedJobKeepsTheHighestSummon() {
        assertEquals(Integer.valueOf(2321003), BotSummonController.chooseSummon(232), "Bishop: bahamut");
        assertEquals(Integer.valueOf(2311006), BotSummonController.chooseSummon(231), "Priest: dragon");
        assertEquals(Integer.valueOf(2221005), BotSummonController.chooseSummon(222), "I/L archmage: ifrit");
        assertEquals(Integer.valueOf(2121005), BotSummonController.chooseSummon(212), "F/P archmage: elquines");
    }

    @Test
    void aCareerThatOwnsNoSummonChoosesNull() {
        // The 35-50 lobby band re-derives every family into a 2nd-job id, and every summon in the
        // table is a 3rd/4th-job skill - so the spawn-time grant must be dropped.
        for (int job : new int[]{100, 110, 120, 130, 200, 210, 220, 230,
                300, 310, 320, 400, 410, 420, 500, 510, 520}) {
            assertNull(BotSummonController.chooseSummon(job),
                    "job " + job + " owns no summon and must not keep the spawn-time one");
        }
    }

    @Test
    void thePirateTurretsAreDistinguishedByLine() {
        assertEquals(Integer.valueOf(5211001), BotSummonController.chooseSummon(521), "Outlaw: octopus");
        assertEquals(Integer.valueOf(5220002), BotSummonController.chooseSummon(522), "Corsair: wrath");
        // Brawler line owns none at all - a brawler must never inherit the gunner's turret.
        assertNull(BotSummonController.chooseSummon(511), "Marauder owns no summon");
        assertNull(BotSummonController.chooseSummon(512), "Buccaneer owns no summon");
    }
}
