package soloMapling.companion.progression;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable ordered SP milestones for one persisted career build. */
public record CompanionSkillBuild(
        CompanionCareerBuild career,
        String rulesetVersion,
        List<SkillMilestone> milestones
) {
    public static final int MAX = Integer.MAX_VALUE;

    public CompanionSkillBuild {
        career = Objects.requireNonNull(career, "career");
        rulesetVersion = requireText(rulesetVersion, "rulesetVersion");
        milestones = List.copyOf(Objects.requireNonNull(milestones, "milestones"));
        if (milestones.isEmpty()) {
            throw new IllegalArgumentException("milestones must not be empty");
        }
        Map<Integer, Integer> previousTargets = new HashMap<>();
        for (SkillMilestone milestone : milestones) {
            if (!career.containsJob(milestone.jobId())) {
                throw new IllegalArgumentException(
                        "Skill " + milestone.skillId() + " is outside " + career.id());
            }
            int previous = previousTargets.getOrDefault(milestone.skillId(), 0);
            if (milestone.targetLevel() < previous) {
                throw new IllegalArgumentException(
                        "Skill targets must not decrease: " + milestone.skillId());
            }
            previousTargets.put(milestone.skillId(), milestone.targetLevel());
        }
    }

    public List<SkillMilestone> eligibleMilestones(int currentJobId, int level) {
        if (!career.containsJob(currentJobId)) {
            return List.of();
        }
        List<SkillMilestone> eligible = new ArrayList<>();
        for (SkillMilestone milestone : milestones) {
            if (milestone.minimumCharacterLevel() <= level
                    && isInCurrentLineage(milestone.jobId(), currentJobId)) {
                eligible.add(milestone);
            }
        }
        return List.copyOf(eligible);
    }

    private boolean isInCurrentLineage(int milestoneJobId, int currentJobId) {
        for (int tier = 1; tier <= 4; tier++) {
            if (career.jobForTier(tier) == milestoneJobId) {
                return currentJobId == career.jobForTier(tier)
                        || (tier < 4 && isLaterJob(currentJobId, tier));
            }
        }
        return false;
    }

    private boolean isLaterJob(int currentJobId, int milestoneTier) {
        for (int tier = milestoneTier + 1; tier <= 4; tier++) {
            if (career.jobForTier(tier) == currentJobId) {
                return true;
            }
        }
        return false;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    public record SkillMilestone(
            int skillId,
            int targetLevel,
            int minimumCharacterLevel,
            BlockedPolicy blockedPolicy,
            List<SkillPrerequisite> prerequisites
    ) {
        public SkillMilestone {
            if (skillId <= 0) {
                throw new IllegalArgumentException("skillId must be positive");
            }
            if (targetLevel <= 0) {
                throw new IllegalArgumentException("targetLevel must be positive");
            }
            if (minimumCharacterLevel < 1) {
                throw new IllegalArgumentException("minimumCharacterLevel must be positive");
            }
            blockedPolicy = Objects.requireNonNull(blockedPolicy, "blockedPolicy");
            prerequisites = List.copyOf(
                    Objects.requireNonNull(prerequisites, "prerequisites"));
        }

        public int jobId() {
            return skillId / 10_000;
        }
    }

    public record SkillPrerequisite(int skillId, int minimumLevel) {
        public SkillPrerequisite {
            if (skillId <= 0 || minimumLevel <= 0) {
                throw new IllegalArgumentException(
                        "skill prerequisite values must be positive");
            }
        }
    }

    public enum BlockedPolicy {
        /** Preserve remaining SP until this core milestone can be advanced. */
        RESERVE,
        /** Skip an unavailable optional milestone and continue the profile. */
        SKIP
    }
}
