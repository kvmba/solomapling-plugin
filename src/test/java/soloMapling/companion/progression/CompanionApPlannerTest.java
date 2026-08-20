package soloMapling.companion.progression;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CompanionApPlannerTest {

    @Test
    void bowAndCrossbowUseDifferentClassicStrengthTargets() {
        CompanionApPlanner.Stats stats = new CompanionApPlanner.Stats(4, 25, 4, 4);

        CompanionApPlanner.Plan bow = CompanionApPlanner.plan(
                CompanionCareerBuild.BOWMASTER, 70, stats, 100, 999);
        CompanionApPlanner.Plan crossbow = CompanionApPlanner.plan(
                CompanionCareerBuild.MARKSMAN, 70, stats, 100, 999);

        assertEquals(75, bow.secondaryTarget());
        assertEquals(71, bow.str());
        assertEquals(70, crossbow.secondaryTarget());
        assertEquals(66, crossbow.str());
        assertEquals(100, bow.spent());
        assertEquals(100, crossbow.spent());
    }

    @Test
    void magicianAndThiefMeetSecondaryThenSpendOnPrimary() {
        CompanionApPlanner.Plan magician = CompanionApPlanner.plan(
                CompanionCareerBuild.BISHOP,
                30,
                new CompanionApPlanner.Stats(4, 4, 20, 4),
                121,
                999);
        CompanionApPlanner.Plan thief = CompanionApPlanner.plan(
                CompanionCareerBuild.NIGHT_LORD,
                30,
                new CompanionApPlanner.Stats(4, 25, 4, 4),
                121,
                999);

        assertEquals(29, magician.luk());
        assertEquals(92, magician.intStat());
        assertEquals(35, thief.dex());
        assertEquals(86, thief.luk());
    }

    @Test
    void firstJobMinimumIsAppliedBeforeRegularTargets() {
        CompanionApPlanner.Plan warrior = CompanionApPlanner.plan(
                CompanionCareerBuild.HERO_SWORD,
                10,
                new CompanionApPlanner.Stats(12, 4, 4, 4),
                40,
                999);
        CompanionApPlanner.Plan pirate = CompanionApPlanner.plan(
                CompanionCareerBuild.CORSAIR,
                10,
                new CompanionApPlanner.Stats(4, 4, 4, 4),
                40,
                999);

        assertEquals(24, warrior.str());
        assertEquals(16, warrior.dex());
        assertEquals(10, pirate.secondaryTarget());
        assertEquals(34, pirate.dex());
        assertEquals(6, pirate.str());
    }

    @Test
    void alreadySatisfiedTargetMakesPlanningIdempotent() {
        CompanionApPlanner.Plan plan = CompanionApPlanner.plan(
                CompanionCareerBuild.BOWMASTER,
                70,
                new CompanionApPlanner.Stats(75, 300, 4, 4),
                0,
                999);

        assertEquals(0, plan.spent());
        assertEquals(0, plan.unspent());
    }

    @Test
    void allTwelveBranchesUseTheirExpectedLevelSeventyFormula() {
        for (CompanionCareerBuild build : CompanionCareerBuild.values()) {
            CompanionApPlanner.Plan plan = CompanionApPlanner.plan(
                    build,
                    70,
                    new CompanionApPlanner.Stats(4, 4, 4, 4),
                    500,
                    999);
            int expectedTarget = switch (build) {
                case HERO_SWORD, PALADIN_SWORD, DARK_KNIGHT_SPEAR, BUCCANEER,
                        NIGHT_LORD, SHADOWER -> 110;
                case FIRE_POISON_ARCHMAGE, ICE_LIGHTNING_ARCHMAGE, BISHOP -> 73;
                case BOWMASTER -> 75;
                case MARKSMAN, CORSAIR -> 70;
            };
            assertEquals(expectedTarget, plan.secondaryTarget(), build::id);
            assertEquals(500, plan.spent(), build::id);
        }
    }
}
