package soloMapling.companion.progression;

import soloMapling.companion.progression.CompanionSkillBuild.BlockedPolicy;
import soloMapling.companion.progression.CompanionSkillBuild.SkillMilestone;
import soloMapling.companion.progression.CompanionSkillBuild.SkillPrerequisite;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Pure ordered SP planner with explicit prerequisite and mastery handling. */
public final class CompanionSpPlanner {
    private CompanionSpPlanner() {
    }

    public static Plan plan(
            CompanionSkillBuild build,
            int currentJobId,
            int level,
            int availableSp,
            SkillState state
    ) {
        Objects.requireNonNull(build, "build");
        Objects.requireNonNull(state, "state");
        if (level < 1) {
            throw new IllegalArgumentException("level must be positive");
        }
        if (availableSp < 0) {
            throw new IllegalArgumentException("availableSp must not be negative");
        }

        int remaining = availableSp;
        List<SkillAllocation> allocations = new ArrayList<>();
        Map<Integer, Integer> projected = new HashMap<>();
        String blocked = "";
        String next = "";

        for (SkillMilestone milestone : build.eligibleMilestones(currentJobId, level)) {
            int current = projected.computeIfAbsent(
                    milestone.skillId(), state::currentLevel);
            int maximum = state.maximumLevel(milestone.skillId());
            int configuredTarget = milestone.targetLevel();
            int target = configuredTarget == CompanionSkillBuild.MAX
                    ? maximum
                    : Math.min(configuredTarget, maximum);

            String unavailable = unavailableReason(
                    milestone, current, maximum, configuredTarget, projected, state);
            if (!unavailable.isEmpty()) {
                blocked = unavailable;
                if (milestone.blockedPolicy() == BlockedPolicy.RESERVE) {
                    next = describe(milestone, current);
                    break;
                }
                continue;
            }
            if (current >= target) {
                continue;
            }
            next = describe(milestone, current);
            if (remaining <= 0) {
                break;
            }
            int points = Math.min(remaining, target - current);
            allocations.add(new SkillAllocation(
                    milestone.skillId(), points, current + points,
                    milestone.jobId()));
            projected.put(milestone.skillId(), current + points);
            remaining -= points;
            if (current + points < target) {
                break;
            }
            next = "";
        }

        return new Plan(
                List.copyOf(allocations),
                availableSp - remaining,
                remaining,
                next,
                blocked);
    }

    private static String unavailableReason(
            SkillMilestone milestone,
            int current,
            int maximum,
            int configuredTarget,
            Map<Integer, Integer> projected,
            SkillState state
    ) {
        if (!state.exists(milestone.skillId())) {
            return "missing-skill:" + milestone.skillId();
        }
        for (SkillPrerequisite prerequisite : milestone.prerequisites()) {
            int level = projected.getOrDefault(
                    prerequisite.skillId(), state.currentLevel(prerequisite.skillId()));
            if (level < prerequisite.minimumLevel()) {
                return "prerequisite:" + milestone.skillId() + "<-"
                        + prerequisite.skillId() + ":" + prerequisite.minimumLevel();
            }
        }
        if (maximum <= current
                && (configuredTarget == CompanionSkillBuild.MAX
                || current < configuredTarget)) {
            return "mastery-cap:" + milestone.skillId() + ":" + maximum;
        }
        return "";
    }

    private static String describe(SkillMilestone milestone, int current) {
        String target = milestone.targetLevel() == CompanionSkillBuild.MAX
                ? "MAX"
                : Integer.toString(milestone.targetLevel());
        return milestone.skillId() + ":" + current + "->" + target;
    }

    public interface SkillState {
        boolean exists(int skillId);

        int currentLevel(int skillId);

        int maximumLevel(int skillId);
    }

    public record SkillAllocation(
            int skillId,
            int points,
            int resultingLevel,
            int jobId
    ) {
        public SkillAllocation {
            if (skillId <= 0 || points <= 0 || resultingLevel <= 0 || jobId <= 0) {
                throw new IllegalArgumentException("invalid skill allocation");
            }
        }
    }

    public record Plan(
            List<SkillAllocation> allocations,
            int spent,
            int unspent,
            String nextMilestone,
            String blockedReason
    ) {
        public Plan {
            allocations = List.copyOf(Objects.requireNonNull(allocations, "allocations"));
            if (spent < 0 || unspent < 0) {
                throw new IllegalArgumentException("SP totals must not be negative");
            }
            nextMilestone = Objects.requireNonNullElse(nextMilestone, "");
            blockedReason = Objects.requireNonNullElse(blockedReason, "");
        }

        public String summary() {
            return "spSpent=" + spent
                    + ";spUnspent=" + unspent
                    + ";nextSkill=" + (nextMilestone.isEmpty() ? "none" : nextMilestone)
                    + ";spBlocked=" + (blockedReason.isEmpty() ? "none" : blockedReason);
        }
    }
}
