package soloMapling.ArtificialPlayer.BotDecoratorSystem;

import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.PartyQuest.PqRecruitPoints;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the rule every recruit lobby depends on: a bot seated in a quest's level band must hold an
 * advancement that band can really reach.
 *
 * <p>This is how the 玩具塔101 boss ended up dying instantly. A bot is decorated at the spawner's
 * 10..80 default band; a quest asking for 35-50 used to pin the LEVEL only, so a level-38 Chief
 * Bandit (3rd job) stayed 3rd job - and its damage came from {@code Job.getJobTier()}, the 3rd-job
 * band (900..2300 per line instead of 240..620). {@link BotDecorate#jobIdForLevel} is the one
 * function that keeps the advancement and the level together, so the invariant is stated here.</p>
 */
class PqLobbyCareerBandTest {

    /** {@code minLevelForJob}: 1st job 10 (magician 8), 2nd 30, 3rd 70, 4th 120. */
    private static int floorFor(int jobId) {
        if (jobId <= 0) {
            return 0;
        }
        return switch (jobId % 100) {
            case 0 -> jobId / 100 == 2 ? 8 : 10;
            case 10, 20, 30 -> 30;
            case 11, 21, 31 -> 70;
            case 12, 22, 32 -> 120;
            default -> 0;
        };
    }

    /**
     * The contract: whatever {@link BotDecorate#jobIdForLevel} picks, the level can hold it. This
     * fails for every lobby whose band sits below the spawn-time reach if that function ever goes
     * back to keeping the old job.
     */
    @Test
    void everyLobbyBandGetsAnAdvancementItCanReach() {
        List<String> offenders = new ArrayList<>();
        for (PqRecruitPoints.Point point : allPoints()) {
            for (int level = Math.max(1, point.minLevel()); level <= point.maxLevel(); level++) {
                for (int family = 1; family <= 5; family++) {
                    int job = BotDecorate.jobIdForLevel(family, level);
                    if (floorFor(job) > level) {
                        offenders.add(point.name() + " lv" + level + " -> job " + job
                                + " needs lv" + floorFor(job));
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "an advancement its level cannot reach: " + offenders);
    }

    /** The class family must survive the re-derivation, or a warrior lobby becomes mixed. */
    @Test
    void theClassFamilyIsPreserved() {
        for (int family = 1; family <= 5; family++) {
            for (int level = 10; level <= 200; level++) {
                int job = BotDecorate.jobIdForLevel(family, level);
                assertEquals(family, job / 100,
                        "family " + family + " at level " + level + " became job " + job);
            }
        }
    }

    /**
     * The bounds {@code PqBotSpawner.levelRange} draws from: the band's low end plus at most a third
     * of it (min 9 levels), clamped to the quest's own maximum. A lobby must never seat a level its
     * own eligibility test would reject.
     */
    @Test
    void theDrawnSubBandStaysInsideTheQuestsOwnBand() {
        for (PqRecruitPoints.Point point : allPoints()) {
            int low = Math.max(1, point.minLevel());
            int high = Math.min(point.maxLevel(), low + Math.max(9, (point.maxLevel() - low) / 3));
            if (high < low) {
                high = low;
            }
            for (int level = low; level <= high; level++) {
                assertTrue(point.levelEligible(level),
                        point.name() + " would seat level " + level + ", outside its own band");
            }
        }
    }

    private static List<PqRecruitPoints.Point> allPoints() {
        List<PqRecruitPoints.Point> all = new ArrayList<>(PqRecruitPoints.ALL);
        all.add(PqRecruitPoints.ORBIS); // spawned by its own path, seated by the same rule
        return all;
    }
}
