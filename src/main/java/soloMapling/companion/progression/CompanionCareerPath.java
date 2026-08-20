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
    private static final int[] FIRST_JOBS = {100, 200, 300, 400, 500};
    private static final int[] WARRIOR_BRANCHES = {110, 120, 130};
    private static final int[] MAGICIAN_BRANCHES = {210, 220, 230};
    private static final int[] BOWMAN_BRANCHES = {310, 320};
    private static final int[] THIEF_BRANCHES = {410, 420};
    private static final int[] PIRATE_BRANCHES = {510, 520};

    private CompanionCareerPath() {
    }

    public static OptionalInt nextJobId(int currentJobId, int level, long personaSeed) {
        if (level < 1) {
            throw new IllegalArgumentException("level must be positive");
        }

        if (currentJobId == 0 && level >= 10) {
            return OptionalInt.of(select(FIRST_JOBS, personaSeed, 0x41C64E6DL));
        }
        if (level >= 30) {
            int[] branches = switch (currentJobId) {
                case 100 -> WARRIOR_BRANCHES;
                case 200 -> MAGICIAN_BRANCHES;
                case 300 -> BOWMAN_BRANCHES;
                case 400 -> THIEF_BRANCHES;
                case 500 -> PIRATE_BRANCHES;
                default -> null;
            };
            if (branches != null) {
                return OptionalInt.of(select(branches, personaSeed, currentJobId));
            }
        }
        if (level >= 70 && isSecondJob(currentJobId)) {
            return OptionalInt.of(currentJobId + 1);
        }
        if (level >= 120 && isThirdJob(currentJobId)) {
            return OptionalInt.of(currentJobId + 1);
        }
        return OptionalInt.empty();
    }

    private static boolean isSecondJob(int jobId) {
        int suffix = jobId % 100;
        return jobId >= 100 && jobId < 600
                && (suffix == 10 || suffix == 20 || suffix == 30);
    }

    private static boolean isThirdJob(int jobId) {
        int suffix = jobId % 100;
        return jobId >= 100 && jobId < 600
                && (suffix == 11 || suffix == 21 || suffix == 31);
    }

    private static int select(int[] choices, long seed, long salt) {
        long mixed = mix64(seed ^ salt);
        return choices[Math.floorMod(mixed, choices.length)];
    }

    private static long mix64(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }
}
