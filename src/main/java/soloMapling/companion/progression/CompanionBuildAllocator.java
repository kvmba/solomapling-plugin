package soloMapling.companion.progression;

import org.gms.client.Character;
import org.gms.client.Job;
import org.gms.client.Skill;
import org.gms.client.SkillFactory;
import org.gms.constants.game.GameConstants;

import java.util.Arrays;

/**
 * Deterministically consumes unspent AP and SP for Explorer companions.
 */
public final class CompanionBuildAllocator {

    private CompanionBuildAllocator() {
    }

    public static Allocation allocate(Character character) {
        if (character == null) {
            throw new NullPointerException("character");
        }
        int ap = allocateAp(character);
        int sp = allocateSp(character);
        if (ap > 0 || sp > 0) {
            character.equipChanged();
        }
        return new Allocation(ap, sp);
    }

    private static int allocateAp(Character character) {
        int available = character.getRemainingAp();
        if (available <= 0) {
            return 0;
        }
        boolean assigned = switch (primaryStatForJobId(character.getJob().getId())) {
            case STR -> character.assignStr(available);
            case DEX -> character.assignDex(available);
            case INT -> character.assignInt(available);
            case LUK -> character.assignLuk(available);
            case NONE -> false;
        };
        return assigned ? available : 0;
    }

    static PrimaryStat primaryStatForJobId(int jobId) {
        return switch ((jobId / 100) % 10) {
            case 1, 5 -> PrimaryStat.STR;
            case 2 -> PrimaryStat.INT;
            case 3 -> PrimaryStat.DEX;
            case 4 -> PrimaryStat.LUK;
            default -> PrimaryStat.NONE;
        };
    }

    private static int allocateSp(Character character) {
        Job currentJob = character.getJob();
        if (currentJob.getId() < 100 || currentJob.getId() >= 600) {
            return 0;
        }

        int spent = 0;
        Job[] lineage = Arrays.stream(Job.values())
                .filter(job -> job.getId() >= 100 && job.getId() < 600)
                .filter(currentJob::isA)
                .sorted((left, right) -> Integer.compare(left.getJobTier(), right.getJobTier()))
                .toArray(Job[]::new);
        for (Job lineageJob : lineage) {
            for (Skill skill : SkillFactory.getSkillsForJob(lineageJob.getId())) {
                int remaining = character.getRemainingSp();
                if (remaining <= 0) {
                    return spent;
                }
                int currentLevel = character.getSkillLevel(skill);
                int masterLevel = skill.isFourthJob()
                        ? character.getMasterLevel(skill)
                        : skill.getMaxLevel();
                int points = Math.min(remaining, Math.max(0, masterLevel - currentLevel));
                if (points <= 0) {
                    continue;
                }
                character.changeSkillLevel(
                        skill, (byte) (currentLevel + points), masterLevel, -1);
                character.gainSp(
                        -points, GameConstants.getSkillBook(lineageJob.getId()), false);
                spent += points;
            }
        }
        return spent;
    }

    public record Allocation(int apSpent, int spSpent) {
        public Allocation {
            if (apSpent < 0 || spSpent < 0) {
                throw new IllegalArgumentException("spent points must not be negative");
            }
        }
    }

    enum PrimaryStat {
        STR,
        DEX,
        INT,
        LUK,
        NONE
    }
}
