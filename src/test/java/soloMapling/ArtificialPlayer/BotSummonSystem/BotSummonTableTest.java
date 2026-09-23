package soloMapling.ArtificialPlayer.BotSummonSystem;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the summon behaviour table: which job owns which summon, and - the rule that must never
 * regress - that the pirate turrets and the archer puppet are classified STATIONARY so the follower
 * never moves them.
 *
 * <p>Raw job ids are used (not {@code org.gms.client.Job}) so the test loads without a Spring
 * context; the ownership rule ({@link BotSummonTable#summonsForJobId}) mirrors the host's own
 * {@code Job.isA}.</p>
 */
class BotSummonTableTest {

    @Test
    void turretsAndPuppetsAreStationary() {
        // A real octopus / battleship turret is a placed cannon, not a pet: the follower must never
        // reposition it. Same for the archer puppet decoy.
        for (int skill : List.of(5211001 /* octopus */, 5220002 /* wrath of the octopi */,
                3111002 /* ranger puppet */, 3211002 /* sniper puppet */)) {
            BotSummonTable.Spec spec = BotSummonTable.forSkill(skill);
            assertNotNull(spec, "skill " + skill + " must be a registered summon");
            assertEquals(BotSummonTable.Move.STATIONARY, spec.move(),
                    "skill " + skill + " must be STATIONARY");
            assertTrue(spec.isStationary(), "spec.isStationary() must agree");
            assertFalse(spec.airborne(), "a stationary summon sits on the ground, never floats");
        }
    }

    @Test
    void turretsAttackButThePuppetDoesNot() {
        assertTrue(BotSummonTable.forSkill(5211001).attacks(), "the octopus fires");
        assertTrue(BotSummonTable.forSkill(5220002).attacks(), "the super octopus fires");
        assertFalse(BotSummonTable.forSkill(3111002).attacks(), "the puppet only draws aggro");
        assertFalse(BotSummonTable.forSkill(3211002).attacks(), "the puppet only draws aggro");
    }

    @Test
    void archerAndMageSummonsFollowAndAttack() {
        assertTrue(BotSummonTable.forSkill(3111005).attacks() // Silver Hawk
                && BotSummonTable.forSkill(3111005).move() == BotSummonTable.Move.CIRCLE);
        assertTrue(BotSummonTable.forSkill(2121005).attacks() // Elquines
                && BotSummonTable.forSkill(2121005).move() == BotSummonTable.Move.FOLLOW);
        assertTrue(BotSummonTable.forSkill(2321003).attacks() // Bahamut
                && BotSummonTable.forSkill(2321003).move() == BotSummonTable.Move.FOLLOW);
        assertTrue(BotSummonTable.forSkill(1321007).move() == BotSummonTable.Move.FOLLOW
                && !BotSummonTable.forSkill(1321007).attacks(), "the beholder supports, it does not attack");
    }

    @Test
    void lineageResolvesToTheAdvancedJobsSummon() {
        // A Bowmaster (312) inherits its branch: it must resolve to Phoenix, not the 2nd-job hawk.
        assertTrue(BotSummonTable.summonsForJobId(312).contains(3121006));
        // A Bishop (232) keeps both the priest dragon and its own bahamut.
        List<Integer> bishop = BotSummonTable.summonsForJobId(232);
        assertTrue(bishop.contains(2311006) && bishop.contains(2321003),
                "a Bishop owns both SUMMON_DRAGON and BAHAMUT, got " + bishop);
        // A plain Warrior (100) owns nothing.
        assertTrue(BotSummonTable.summonsForJobId(100).isEmpty());
        // A Corsair (522) inherits Outlaw's octopus and adds its own super turret.
        assertTrue(BotSummonTable.summonsForJobId(522).containsAll(List.of(5211001, 5220002)));
    }

    @Test
    void isAMirrorsTheHostRule() {
        assertTrue(BotSummonTable.isA(312, 300));   // Bowmaster is a Bowman
        assertTrue(BotSummonTable.isA(312, 312));
        assertFalse(BotSummonTable.isA(300, 312));  // ...but a Bowman is not a Bowmaster
        assertFalse(BotSummonTable.isA(522, 512));  // Corsair is not a Buccaneer (different branch)
    }

    @Test
    void unknownSkillIsNotASummon() {
        assertNull(BotSummonTable.forSkill(1000));
        assertFalse(BotSummonTable.isSummonSkill(1001005));
    }

    @Test
    void chooserPrefersTheAttackingSummonOverTheDecoy() {
        // A Ranger (311) owns Silver Hawk (attacking) AND Puppet (decoy): the hawk must win.
        assertEquals(3111005, BotSummonController.chooseSummon(311),
                "the hawk must be chosen over the non-attacking puppet");
        // A Corsair owns only the turret.
        assertEquals(5220002, BotSummonController.chooseSummon(522));
        // A class with no summon resolves to none.
        assertNull(BotSummonController.chooseSummon(100));
    }
}
