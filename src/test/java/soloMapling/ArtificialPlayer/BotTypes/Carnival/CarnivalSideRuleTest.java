package soloMapling.ArtificialPlayer.BotTypes.Carnival;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the side rule that decides which monsters a carnival bot may fight.
 *
 * <p>This is the easiest way for a CPQ bot to cheat without anyone noticing. The arena holds both
 * parties' summons in one map object list, and the engine's kill path awards CP for <em>any</em>
 * monster that carries CP - there is no team check in {@code MapleMap.killMonster}. A bot that
 * hunts the nearest mob therefore farms its own side's summons, and a bot opponent that farms its
 * own monsters is not an opponent.
 *
 * <p>The rule is checked at both ends of the swing path: the chase filter in {@code PqActions}
 * and the target selection in {@code BotAttackDriver}. Both must agree, so the rule is pinned
 * here as the single truth they share.
 */
class CarnivalSideRuleTest {

    /**
     * The production rule, transcribed as a pure function so it can be exercised without a live
     * {@code Character}. {@code mine} is the bot's team; {@code theirs} the monster's.
     */
    private static boolean mayHit(int mine, int theirs) {
        return theirs < 0 || theirs != mine;
    }

    @Test
    void aRedBotHitsOnlyBlueMonsters() {
        assertTrue(mayHit(0, 1), "red may kill blue's summons");
        assertFalse(mayHit(0, 0), "red must not kill red's own summons");
    }

    @Test
    void aBlueBotHitsOnlyRedMonsters() {
        assertTrue(mayHit(1, 0), "blue may kill red's summons");
        assertFalse(mayHit(1, 1), "blue must not kill blue's own summons");
    }

    @Test
    void anUnattributedMonsterIsAlwaysFair() {
        // -1 is what a monster carries before the arena places it, and what a plain map mob
        // carries. Neither can be "your own side", so both stay huntable - a filter that excluded
        // them would make the bot ignore a monster that is genuinely on the field.
        assertTrue(mayHit(0, -1));
        assertTrue(mayHit(1, -1));
        assertTrue(mayHit(-1, 0), "no team of its own: everything is someone else's");
        assertTrue(mayHit(-1, -1));
    }

    @Test
    void theProductionRuleMatchesTheTranscription() {
        // Pin the shape the production rule must keep, including the side being read once at
        // install time rather than per monster.
        for (int mine : new int[]{0, 1, -1}) {
            for (int theirs : new int[]{0, 1, -1}) {
                assertEquals(mayHit(mine, theirs), CarnivalPQBot.sideRule(mine, theirs),
                        "team " + mine + " vs " + theirs);
            }
        }
    }
}
