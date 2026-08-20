package soloMapling.companion.progression;

import org.gms.client.Character;
import org.gms.client.Job;
import org.gms.client.Skill;
import org.gms.client.SkillFactory;
import org.gms.config.GameConfig;
import org.gms.constants.game.GameConstants;

/**
 * Deterministically consumes unspent AP and SP for Explorer companions.
 */
public final class CompanionBuildAllocator {

    private CompanionBuildAllocator() {
    }

    public static Allocation allocate(
            Character character,
            CompanionCareerBuild careerBuild
    ) {
        if (character == null) {
            throw new NullPointerException("character");
        }
        if (careerBuild == null) {
            throw new NullPointerException("careerBuild");
        }
        CompanionApPlanner.Plan ap = planAp(character, careerBuild);
        applyAp(character, ap);
        CompanionSpPlanner.Plan sp = planSp(character, careerBuild);
        applySp(character, sp);
        if (ap.spent() > 0 || sp.spent() > 0) {
            character.equipChanged();
        }
        return new Allocation(ap.spent(), sp.spent(), ap, sp);
    }

    public static Diagnostics preview(
            Character character,
            CompanionCareerBuild careerBuild
    ) {
        if (character == null || careerBuild == null) {
            throw new NullPointerException("character and careerBuild are required");
        }
        return new Diagnostics(
                planAp(character, careerBuild),
                planSp(character, careerBuild));
    }

    private static CompanionApPlanner.Plan planAp(
            Character character,
            CompanionCareerBuild careerBuild
    ) {
        int available = character.getRemainingAp();
        CompanionApPlanner.Plan plan = CompanionApPlanner.plan(
                careerBuild,
                character.getLevel(),
                new CompanionApPlanner.Stats(
                        character.getStr(),
                        character.getDex(),
                        character.getInt(),
                        character.getLuk()),
                available,
                GameConfig.getServerInt("max_ap"));
        return plan;
    }

    private static void applyAp(
            Character character,
            CompanionApPlanner.Plan plan
    ) {
        if (plan.spent() > 0 && !character.assignStrDexIntLuk(
                plan.str(), plan.dex(), plan.intStat(), plan.luk())) {
            throw new IllegalStateException(
                    "Host rejected validated companion AP allocation: " + plan.summary());
        }
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

    private static CompanionSpPlanner.Plan planSp(
            Character character,
            CompanionCareerBuild careerBuild
    ) {
        Job currentJob = character.getJob();
        if (currentJob.getId() < 100 || currentJob.getId() >= 600) {
            return new CompanionSpPlanner.Plan(
                    java.util.List.of(), 0, character.getRemainingSp(),
                    "awaiting-first-job", "");
        }
        if (!careerBuild.containsJob(currentJob.getId())) {
            return new CompanionSpPlanner.Plan(
                    java.util.List.of(), 0, character.getRemainingSp(), "",
                    "career-mismatch:" + currentJob.getId() + "!=" + careerBuild.id());
        }

        return CompanionSpPlanner.plan(
                CompanionSkillBuilds.forCareer(careerBuild),
                currentJob.getId(),
                character.getLevel(),
                character.getRemainingSp(),
                new CompanionSpPlanner.SkillState() {
                    @Override
                    public boolean exists(int skillId) {
                        return SkillFactory.getSkill(skillId) != null;
                    }

                    @Override
                    public int currentLevel(int skillId) {
                        Skill skill = SkillFactory.getSkill(skillId);
                        return skill == null ? 0 : character.getSkillLevel(skill);
                    }

                    @Override
                    public int maximumLevel(int skillId) {
                        Skill skill = SkillFactory.getSkill(skillId);
                        if (skill == null) {
                            return 0;
                        }
                        return skill.isFourthJob()
                                ? character.getMasterLevel(skill)
                                : skill.getMaxLevel();
                    }
                });
    }

    private static void applySp(
            Character character,
            CompanionSpPlanner.Plan plan
    ) {
        for (CompanionSpPlanner.SkillAllocation allocation : plan.allocations()) {
            Skill skill = SkillFactory.getSkill(allocation.skillId());
            int masterLevel = skill.isFourthJob()
                    ? character.getMasterLevel(skill)
                    : skill.getMaxLevel();
            character.changeSkillLevel(
                    skill, (byte) allocation.resultingLevel(), masterLevel, -1);
            character.gainSp(
                    -allocation.points(),
                    GameConstants.getSkillBook(allocation.jobId()),
                    false);
        }
    }

    public record Allocation(
            int apSpent,
            int spSpent,
            CompanionApPlanner.Plan apPlan,
            CompanionSpPlanner.Plan spPlan
    ) {
        public Allocation {
            if (apSpent < 0 || spSpent < 0) {
                throw new IllegalArgumentException("spent points must not be negative");
            }
            if (apPlan == null || spPlan == null) {
                throw new NullPointerException("allocation plans");
            }
        }

        public String summary() {
            return apPlan.summary() + ";" + spPlan.summary();
        }
    }

    public record Diagnostics(
            CompanionApPlanner.Plan apPlan,
            CompanionSpPlanner.Plan spPlan
    ) {
        public Diagnostics {
            if (apPlan == null || spPlan == null) {
                throw new NullPointerException("diagnostic plans");
            }
        }

        public String summary() {
            return apPlan.summary() + ";" + spPlan.summary();
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
