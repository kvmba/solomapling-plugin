package soloMapling.ArtificialPlayer.BotAttackSystem;

/**
 * The advancement floors that decide whether a bot's level has unlocked a skill.
 *
 * <p>A bot's job is rolled from a spawn-time level band and can end up above its level (the
 * Ludibrium PQ lobby pinned a level-78 "Chief Bandit" to 38, where it still dropped 敛财术 money
 * bags). Rather than repair the job, every skill-use site asks this class first: a skill whose own
 * job tier the level has not reached is simply not usable.</p>
 *
 * <p>Derived from the job id alone - no engine lookup, no {@code org.gms.client.Job} reference - so
 * it loads in a plain unit test.</p>
 */
final class BotSkillLevelGate {

    /** 1st job for the magician line (2xx) - the one explorer exception to the level-10 floor. */
    private static final int MAGICIAN_FIRST_JOB = 8;
    private static final int FIRST_JOB = 10;
    private static final int SECOND_JOB = 30;
    private static final int THIRD_JOB = 70;
    private static final int FOURTH_JOB = 120;

    private BotSkillLevelGate() {}

    /**
     * The lowest character level {@code jobId} can be held at: 1st job 10 (magician 8), 2nd 30,
     * 3rd 70, 4th 120. Beginner (0, or an unlisted id) has no gate.
     */
    static int minLevelForJob(int jobId) {
        if (jobId <= 0) {
            return 0;
        }
        return switch (jobId % 100) {
            case 0 -> jobId / 100 == 2 ? MAGICIAN_FIRST_JOB : FIRST_JOB;
            case 10, 20, 30 -> SECOND_JOB;
            case 11, 21, 31 -> THIRD_JOB;
            case 12, 22, 32 -> FOURTH_JOB;
            default -> 0;
        };
    }

    /** The floor of the job a skill belongs to; {@code 0} for the skill-less basic swing. */
    static int minLevelForSkill(int skillId) {
        return skillId <= 0 ? 0 : minLevelForJob(skillId / 10000);
    }

    /**
     * The highest job tier {@code level} can have advanced to (0 beginner .. 4 fourth job) - the
     * inverse of {@link #minLevelForJob}, i.e. the best tier ANY job may hold at this level. The
     * first-job floor is the magician's level 8, not 10: a level-8/9 magician is a real 1st job,
     * and capping its damage to the beginner band would be wrong.
     */
    static int tierForLevel(int level) {
        if (level >= FOURTH_JOB) {
            return 4;
        }
        if (level >= THIRD_JOB) {
            return 3;
        }
        if (level >= SECOND_JOB) {
            return 2;
        }
        return level >= MAGICIAN_FIRST_JOB ? 1 : 0;
    }
}
