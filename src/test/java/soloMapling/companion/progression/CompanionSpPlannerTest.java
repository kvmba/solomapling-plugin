package soloMapling.companion.progression;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionSpPlannerTest {

    @Test
    void everyExplorerBranchHasACompleteVersionedProfile() {
        for (CompanionCareerBuild career : CompanionCareerBuild.values()) {
            CompanionSkillBuild build = CompanionSkillBuilds.forCareer(career);
            assertEquals(career, build.career());
            assertEquals(CompanionCareerBuild.RULESET_VERSION, build.rulesetVersion());
            assertTrue(build.milestones().stream()
                    .anyMatch(milestone -> milestone.jobId() == career.firstJobId()));
            assertTrue(build.milestones().stream()
                    .anyMatch(milestone -> milestone.jobId() == career.secondJobId()));
            assertTrue(build.milestones().stream()
                    .anyMatch(milestone -> milestone.jobId() == career.thirdJobId()));
            assertTrue(build.milestones().stream()
                    .anyMatch(milestone -> milestone.jobId() == career.fourthJobId()));
        }
    }

    @Test
    void archerFirstJobConsumesItsExactClassicBudget() {
        CompanionSpPlanner.Plan plan = CompanionSpPlanner.plan(
                CompanionSkillBuilds.forCareer(CompanionCareerBuild.BOWMASTER),
                300,
                30,
                61,
                FakeSkillState.allAvailable(CompanionCareerBuild.BOWMASTER));

        assertEquals(61, plan.spent());
        assertEquals(0, plan.unspent());
        assertTrue(plan.blockedReason().isEmpty());
    }

    @Test
    void hunterSecondJobConsumesItsExactClassicBudget() {
        CompanionSkillBuild build =
                CompanionSkillBuilds.forCareer(CompanionCareerBuild.BOWMASTER);
        FakeSkillState state = FakeSkillState.allAvailable(CompanionCareerBuild.BOWMASTER);
        state.completeJob(build, 300);

        CompanionSpPlanner.Plan plan = CompanionSpPlanner.plan(
                build, 310, 70, 121, state);

        assertEquals(121, plan.spent());
        assertEquals(0, plan.unspent());
        assertTrue(plan.blockedReason().isEmpty());
    }

    @Test
    void everyBranchFitsTheClassicFirstThreeJobBudgets() {
        for (CompanionCareerBuild career : CompanionCareerBuild.values()) {
            CompanionSkillBuild build = CompanionSkillBuilds.forCareer(career);
            FakeSkillState state = FakeSkillState.allAvailable(career);
            int firstBudget = career.firstJobId() == 200 ? 67 : 61;

            CompanionSpPlanner.Plan first = CompanionSpPlanner.plan(
                    build, career.firstJobId(), 30, firstBudget, state);
            assertEquals(firstBudget, first.spent(), career.id() + " first job");
            state.apply(first);

            CompanionSpPlanner.Plan second = CompanionSpPlanner.plan(
                    build, career.secondJobId(), 70, 121, state);
            assertEquals(121, second.spent(), career.id() + " second job");
            state.apply(second);

            CompanionSpPlanner.Plan third = CompanionSpPlanner.plan(
                    build, career.thirdJobId(), 120, 151, state);
            assertEquals(151, third.spent(), career.id() + " third job");
        }
    }

    @Test
    void fourthJobSkipsLockedOptionalSkillsAndReportsTheMasteryBlock() {
        CompanionSkillBuild build =
                CompanionSkillBuilds.forCareer(CompanionCareerBuild.BOWMASTER);
        FakeSkillState state = FakeSkillState.allAvailable(CompanionCareerBuild.BOWMASTER);
        state.completeJob(build, 300);
        state.completeJob(build, 310);
        state.completeJob(build, 311);
        state.maximums.replaceAll((skillId, ignored) ->
                skillId / 10_000 == 312 ? 0 : 30);
        state.maximums.put(org.gms.constants.skills.Bowmaster.SHARP_EYES, 10);
        state.maximums.put(org.gms.constants.skills.Bowmaster.MAPLE_WARRIOR, 10);

        CompanionSpPlanner.Plan plan =
                CompanionSpPlanner.plan(build, 312, 120, 30, state);

        assertEquals(20, plan.spent());
        assertEquals(10, plan.unspent());
        assertFalse(plan.blockedReason().isEmpty());
        assertTrue(plan.blockedReason().startsWith("mastery-cap:"));
    }

    private static final class FakeSkillState implements CompanionSpPlanner.SkillState {
        private final Map<Integer, Integer> levels = new HashMap<>();
        private final Map<Integer, Integer> maximums = new HashMap<>();

        static FakeSkillState allAvailable(CompanionCareerBuild career) {
            FakeSkillState state = new FakeSkillState();
            for (CompanionSkillBuild.SkillMilestone milestone :
                    CompanionSkillBuilds.forCareer(career).milestones()) {
                int maximum = milestone.targetLevel() == CompanionSkillBuild.MAX
                        ? 30
                        : Math.max(30, milestone.targetLevel());
                state.maximums.merge(milestone.skillId(), maximum, Math::max);
            }
            return state;
        }

        void completeJob(CompanionSkillBuild build, int jobId) {
            for (CompanionSkillBuild.SkillMilestone milestone : build.milestones()) {
                if (milestone.jobId() == jobId) {
                    int maximum = maximums.getOrDefault(milestone.skillId(), 30);
                    int target = milestone.targetLevel() == CompanionSkillBuild.MAX
                            ? maximum
                            : Math.min(maximum, milestone.targetLevel());
                    levels.merge(milestone.skillId(), target, Math::max);
                }
            }
        }

        void apply(CompanionSpPlanner.Plan plan) {
            for (CompanionSpPlanner.SkillAllocation allocation : plan.allocations()) {
                levels.put(allocation.skillId(), allocation.resultingLevel());
            }
        }

        @Override
        public boolean exists(int skillId) {
            return maximums.containsKey(skillId);
        }

        @Override
        public int currentLevel(int skillId) {
            return levels.getOrDefault(skillId, 0);
        }

        @Override
        public int maximumLevel(int skillId) {
            return maximums.getOrDefault(skillId, 0);
        }
    }
}
