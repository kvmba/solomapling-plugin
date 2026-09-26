package soloMapling.ArtificialPlayer.BotAttackSystem;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the rarity grading of the throwing-star roll: a star is only reachable once the bot's level
 * can afford it, and low levels can never roll the rare tiers.
 *
 * <p>{@link ThrowingStarSelector#rollForLevel} is public for this test; the production path is
 * {@link ThrowingStarSelector#selectFor}, which needs a decorated Character.
 */
class ThrowingStarSelectorTest {

    private static final int SUBI = 2070000;         // 海星镖
    private static final int WOLBI = 2070001;        // 回旋镖
    private static final int MOKBI = 2070002;        // 黑色利刃
    private static final int KUMBI = 2070003;        // 雪花镖
    private static final int TOBI = 2070004;         // 黑色刺
    private static final int STEELY = 2070005;       // 金钱镖
    private static final int ILBI = 2070006;         // 齿轮镖
    private static final int CRYSTAL_ILBI = 2070016; // 水晶飞镖

    @Test
    void lowLevelsAreConfinedToTheCheapStars() {
        // A level that cannot afford a tier must never throw it - this is the whole point of the
        // rarity grading (a flat pick over the level's band had every level-200 throwing crystal ilbi).
        for (int lv = 1; lv < 90; lv++) {
            assertTrue(reachable(lv).stream().noneMatch(s -> s == ILBI || s == CRYSTAL_ILBI),
                    "level " + lv + " can roll a rare star");
        }
        for (int lv = 1; lv < 120; lv++) {
            assertTrue(reachable(lv).stream().noneMatch(s -> s == CRYSTAL_ILBI),
                    "level " + lv + " can roll crystal ilbi");
        }
        for (int lv = 1; lv < 45; lv++) {
            assertTrue(reachable(lv).stream().noneMatch(s -> s == TOBI || s == STEELY),
                    "level " + lv + " can roll a star above kumbi");
        }
        for (int lv = 1; lv < 70; lv++) {
            assertTrue(reachable(lv).stream().noneMatch(s -> s == STEELY),
                    "level " + lv + " can roll steely");
        }
    }

    @Test
    void rollableStarsNeverGetCheaperAsTheBotLevels() {
        // The reachable set may only move up the ladder.
        Set<Integer> prev = reachable(1);
        for (int lv = 2; lv <= 200; lv++) {
            Set<Integer> cur = reachable(lv);
            assertTrue(cur.stream().max(Integer::compareTo).orElse(0)
                            >= prev.stream().max(Integer::compareTo).orElse(0),
                    "top star regressed at level " + lv);
            prev = cur;
        }
    }

    @Test
    void rungAdvancesOnTheClawThiefAdvancements() {
        // The rung is the best star a level can afford: flat inside a band, +1 at each floor. The
        // floors sit on the job line - 10 飞侠 / 30 刺客 / 70 无影人 / 120 隐士 - plus a mid-point
        // between each, so the star reads as the gear the bot's job would carry.
        assertEquals(0, ThrowingStarSelector.rungForLevel(1));
        assertEquals(0, ThrowingStarSelector.rungForLevel(9));
        assertEquals(1, ThrowingStarSelector.rungForLevel(10));   // 一转 飞侠
        assertEquals(1, ThrowingStarSelector.rungForLevel(19));
        assertEquals(2, ThrowingStarSelector.rungForLevel(20));
        assertEquals(2, ThrowingStarSelector.rungForLevel(29));
        assertEquals(3, ThrowingStarSelector.rungForLevel(30));   // 二转 刺客
        assertEquals(3, ThrowingStarSelector.rungForLevel(44));
        assertEquals(4, ThrowingStarSelector.rungForLevel(45));
        assertEquals(4, ThrowingStarSelector.rungForLevel(69));
        assertEquals(5, ThrowingStarSelector.rungForLevel(70));   // 三转 无影人
        assertEquals(5, ThrowingStarSelector.rungForLevel(89));
        assertEquals(6, ThrowingStarSelector.rungForLevel(90));
        assertEquals(6, ThrowingStarSelector.rungForLevel(119));
        assertEquals(7, ThrowingStarSelector.rungForLevel(120));  // 四转 隐士
        assertEquals(7, ThrowingStarSelector.rungForLevel(200));
    }

    @Test
    void cheapStarsDominateAndRareOnesStayRare() {
        // The point of the grading: even a bot that can afford a rare star usually throws the cheap
        // one. Without this the roll degenerated into "every high level throws crystal ilbi", which
        // made the rarest star in the game the most common thing in the world.
        assertTrue(shareOf(CRYSTAL_ILBI, 200) < shareOf(ILBI, 200),
                "crystal ilbi out-threw ilbi at level 200");
        assertTrue(shareOf(CRYSTAL_ILBI, 200) < 0.20,
                "crystal ilbi too common at level 200: " + shareOf(CRYSTAL_ILBI, 200));
        assertTrue(shareOf(ILBI, 100) < shareOf(STEELY, 100),
                "ilbi out-threw steely at level 100");
        assertTrue(shareOf(TOBI, 50) < shareOf(MOKBI, 50),
                "tobi out-threw mokbi at level 50");
    }

    // How often a level rolls one particular star.
    private static double shareOf(int star, int level) {
        int hits = 0;
        int samples = 20000;
        for (int i = 0; i < samples; i++) {
            if (ThrowingStarSelector.rollForLevel(level) == star) {
                hits++;
            }
        }
        return (double) hits / samples;
    }

    @Test
    void everyLevelRollsAStar() {
        // No gap in the ladder leaves a level with nothing to throw.
        for (int lv = 1; lv <= 200; lv++) {
            int star = ThrowingStarSelector.rollForLevel(lv);
            assertTrue(star == SUBI || star == WOLBI || star == MOKBI || star == KUMBI
                            || star == TOBI || star == STEELY || star == ILBI || star == CRYSTAL_ILBI,
                    "level " + lv + " rolled unknown star " + star);
        }
    }

    // The set of stars a level can roll, sampled generously - the roll is random, so a small sample
    // would miss the rare tiers and pass by accident.
    private static Set<Integer> reachable(int level) {
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < 20000; i++) {
            seen.add(ThrowingStarSelector.rollForLevel(level));
        }
        return seen;
    }
}
