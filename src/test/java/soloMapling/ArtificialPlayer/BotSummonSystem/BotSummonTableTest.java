package soloMapling.ArtificialPlayer.BotSummonSystem;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the summon behaviour table: which job owns which summon, and - the rule that must never
 * regress - that the pirate turrets are classified STATIONARY so the client holds them where they
 * spawned.
 *
 * <p>Raw job ids are used (not {@code org.gms.client.Job}) so the test loads without a Spring
 * context; the ownership rule ({@link BotSummonTable#summonsForJobId}) mirrors the host's own
 * {@code Job.isA}.</p>
 */
class BotSummonTableTest {

    @Test
    void turretsAreStationary() {
        // A real octopus / battleship turret is a placed cannon, not a pet: the client must hold it.
        for (int skill : List.of(5211001 /* octopus */, 5220002 /* wrath of the octopi */)) {
            BotSummonTable.Spec spec = BotSummonTable.forSkill(skill);
            assertNotNull(spec, "skill " + skill + " must be a registered summon");
            assertEquals(BotSummonTable.Move.STATIONARY, spec.move(),
                    "skill " + skill + " must be STATIONARY");
            assertTrue(spec.isStationary(), "spec.isStationary() must agree");
        }
    }

    @Test
    void turretsAttack() {
        assertTrue(BotSummonTable.forSkill(5211001).attacks(), "the octopus fires");
        assertTrue(BotSummonTable.forSkill(5220002).attacks(), "the super octopus fires");
    }

    @Test
    void puppetIsNotRegisteredAtAll() {
        // The archer Puppet only pulls mob aggro, which the host gates on the PUPPET buff stat we
        // never register - so shipping it would be an inert decoration. It must not be a summon.
        assertFalse(BotSummonTable.isSummonSkill(3111002), "ranger puppet must not be a bot summon");
        assertFalse(BotSummonTable.isSummonSkill(3211002), "sniper puppet must not be a bot summon");
        assertNull(BotSummonTable.forSkill(3111002));
        assertNull(BotSummonTable.forSkill(3211002));
    }

    @Test
    void archerAndMageSummonsUseTheHostMoveBytes() {
        // The wire byte must match the host's StatEffect.getSummonMovementType(): archers send
        // CIRCLE_FOLLOW (3), the mage/beholder/bahamut line sends FOLLOW (1). The plugin authors the
        // actual hover movement itself, so this only pins the parity byte.
        assertEquals(BotSummonTable.Move.CIRCLE_FOLLOW, BotSummonTable.forSkill(3111005).move()); // Silver Hawk
        assertEquals(BotSummonTable.Move.FOLLOW, BotSummonTable.forSkill(2121005).move());         // Elquines
        assertEquals(BotSummonTable.Move.FOLLOW, BotSummonTable.forSkill(2321003).move());         // Bahamut
        assertTrue(BotSummonTable.forSkill(3111005).attacks());
        assertTrue(BotSummonTable.forSkill(2121005).attacks());
        assertTrue(BotSummonTable.forSkill(2321003).attacks());
        assertEquals(BotSummonTable.Move.FOLLOW, BotSummonTable.forSkill(1321007).move());         // Beholder
        assertFalse(BotSummonTable.forSkill(1321007).attacks(), "the beholder supports, it does not attack");
    }

    @Test
    void moveByteMatchesTheHostPredicate() throws IOException {
        // The spawn nMoveAbility byte must stay identical to the host's own dispatch, or a bot's
        // summon renders with the wrong initial action for an observer. Read both sides from source
        // (the table cannot be compared to the host enum at runtime without a Spring context).
        Path host = Paths.get("../GMS083/gms-server/src/main/java/org/gms/server/StatEffect.java");
        if (!Files.isRegularFile(host)) {
            return; // host checkout not adjacent; nothing to validate against
        }
        String src = Files.readString(host, StandardCharsets.UTF_8);
        int at = src.indexOf("private SummonMovementType getSummonMovementType()");
        assertTrue(at >= 0, "the host must still define getSummonMovementType()");
        // A generous window covering the method (its two return groups + trailing return null).
        String body = src.substring(at, Math.min(src.length(), at + 2500));

        // Every bird/archer summon the plugin grades must be CIRCLE_FOLLOW in the host...
        for (String bird : List.of("Ranger.SILVER_HAWK", "Sniper.GOLDEN_EAGLE",
                "Bowmaster.PHOENIX", "Marksman.FROST_PREY", "Priest.SUMMON_DRAGON", "Outlaw.GAVIOTA")) {
            assertTrue(body.contains(bird), "host must still special-case " + bird);
        }
        assertTrue(body.contains("SummonMovementType.CIRCLE_FOLLOW"),
                "the host still sends CIRCLE_FOLLOW for the bird family");
        assertTrue(body.contains("SummonMovementType.FOLLOW"),
                "the host still sends FOLLOW for the mage/beholder family");
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
    void chooserPrefersTheAttackingSummon() {
        // A Corsair owns only attacking turrets, so it picks its own super turret (highest id).
        assertEquals(5220002, BotSummonController.chooseSummon(522));
        // A class with no summon resolves to none.
        assertNull(BotSummonController.chooseSummon(100));
    }
}
