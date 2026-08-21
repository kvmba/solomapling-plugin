package soloMapling.companion.progression;

import java.util.OptionalInt;

/**
 * Deterministic Explorer career selection for persistent companions.
 *
 * <p>A persona seed chooses the first job and any second-job branch. Once a
 * companion has entered a branch, later advancements always preserve it. This
 * also respects a GM-selected first or second job.</p>
 */
public final class CompanionCareerPath {
    private CompanionCareerPath() {
    }

    public static OptionalInt nextJobId(int currentJobId, int level, long personaSeed) {
        if (level < 1) {
            throw new IllegalArgumentException("level must be positive");
        }

        if (currentJobId == 0) {
            CompanionCareerBuild selected = CompanionCareerBuild.fromSeed(personaSeed);
            return nextJobId(currentJobId, level, selected);
        }
        if (isFirstJob(currentJobId)) {
            return nextJobId(
                    currentJobId,
                    level,
                    CompanionCareerBuild.forFirstJobAndSeed(currentJobId, personaSeed));
        }
        OptionalInt existing = CompanionCareerBuild.forJob(currentJobId)
                .map(build -> nextJobId(currentJobId, level, build))
                .orElseGet(OptionalInt::empty);
        if (existing.isPresent()) {
            return existing;
        }
        return OptionalInt.empty();
    }

    public static OptionalInt nextJobId(
            int currentJobId,
            int level,
            CompanionCareerBuild build
    ) {
        if (level < 1) {
            throw new IllegalArgumentException("level must be positive");
        }
        if (build == null) {
            throw new NullPointerException("build");
        }
        if (currentJobId == 0) {
            int firstJobLevel = build.firstJobId() == 200 ? 8 : 10;
            return level >= firstJobLevel
                    ? OptionalInt.of(build.firstJobId())
                    : OptionalInt.empty();
        }
        if (!build.containsJob(currentJobId)) {
            return OptionalInt.empty();
        }
        if (currentJobId == build.firstJobId() && level >= 30) {
            return OptionalInt.of(build.secondJobId());
        }
        if (currentJobId == build.secondJobId() && level >= 70) {
            return OptionalInt.of(build.thirdJobId());
        }
        if (currentJobId == build.thirdJobId() && level >= 120) {
            return OptionalInt.of(build.fourthJobId());
        }
        return OptionalInt.empty();
    }

    private static boolean isFirstJob(int jobId) {
        return jobId == 100 || jobId == 200 || jobId == 300
                || jobId == 400 || jobId == 500;
    }

}
