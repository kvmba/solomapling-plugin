package soloMapling;

import org.junit.jupiter.api.Test;
import soloMapling.FreeMarket.BotNamePool;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The classifier that lets a drawn name match a bot's job. Its word table is the single source of
 * truth for "which names assert which class", so the boundaries are pinned here rather than only
 * through the pool.
 */
class BotNamePoolTest {

    @Test
    void classWordsMapToTheirCategory() {
        assertEquals(BotNamePool.WARRIOR, BotNamePool.categoryOf("圣骑士肝帝"));
        assertEquals(BotNamePool.WARRIOR, BotNamePool.categoryOf("狂战士摸鱼"));
        assertEquals(BotNamePool.MAGICIAN, BotNamePool.categoryOf("大主教躺赢"));
        assertEquals(BotNamePool.MAGICIAN, BotNamePool.categoryOf("冰雷在线"));
        assertEquals(BotNamePool.BOWMAN, BotNamePool.categoryOf("弓箭手搬砖"));
        assertEquals(BotNamePool.THIEF, BotNamePool.categoryOf("暗影者当年"));
        assertEquals(BotNamePool.PIRATE, BotNamePool.categoryOf("机械师干饭"));
        assertEquals(BotNamePool.PIRATE, BotNamePool.categoryOf("海盗王小姐"));
    }

    // Longer words win, so 圣骑士 is a warrior even though it contains 骑士, and 大主教 is a
    // magician even though it contains 主教.
    @Test
    void longestWordWins() {
        assertEquals(BotNamePool.WARRIOR, BotNamePool.categoryOf("圣骑士"));
        assertEquals(BotNamePool.WARRIOR, BotNamePool.categoryOf("骑士"));
        assertEquals(BotNamePool.MAGICIAN, BotNamePool.categoryOf("大主教"));
        assertEquals(BotNamePool.MAGICIAN, BotNamePool.categoryOf("主教"));
    }

    // 龙神 / 恶魔 / 天使 read as flavour, not as a v83 job (there is no such class), so they stay
    // neutral rather than forcing a class the pool cannot match.
    @Test
    void flavourWordsStayNeutral() {
        assertEquals(BotNamePool.NEUTRAL, BotNamePool.categoryOf("龙神干饭"));
        assertEquals(BotNamePool.NEUTRAL, BotNamePool.categoryOf("传说中的恶魔"));
        assertEquals(BotNamePool.NEUTRAL, BotNamePool.categoryOf("迷糊的天使"));
    }

    // 勇士 is a warrior word, but 勇士部落 (Perion) is a town and must be subtracted explicitly.
    @Test
    void placeNameIsSubtracted() {
        assertEquals(BotNamePool.NEUTRAL, BotNamePool.categoryOf("勇士部落"));
        assertEquals(BotNamePool.NEUTRAL, BotNamePool.categoryOf("Noob勇士部落"));
        assertEquals(BotNamePool.WARRIOR, BotNamePool.categoryOf("勇士"));
    }

    @Test
    void baseClassAndJobIdMapToCategory() {
        assertEquals(BotNamePool.WARRIOR, BotNamePool.categoryOfBaseClass(1));
        assertEquals(BotNamePool.PIRATE, BotNamePool.categoryOfBaseClass(5));
        assertEquals(BotNamePool.NEUTRAL, BotNamePool.categoryOfBaseClass(0));
        assertEquals(BotNamePool.NEUTRAL, BotNamePool.categoryOfBaseClass(6));

        assertEquals(BotNamePool.WARRIOR, BotNamePool.categoryOfJobId(100));
        assertEquals(BotNamePool.WARRIOR, BotNamePool.categoryOfJobId(112));
        assertEquals(BotNamePool.PIRATE, BotNamePool.categoryOfJobId(522));
        assertEquals(BotNamePool.NEUTRAL, BotNamePool.categoryOfJobId(0));   // beginner
        assertEquals(BotNamePool.NEUTRAL, BotNamePool.categoryOfJobId(-1));
    }

    @Test
    void nullAndPlainNamesAreNeutral() {
        assertEquals(BotNamePool.NEUTRAL, BotNamePool.categoryOf(null));
        assertEquals(BotNamePool.NEUTRAL, BotNamePool.categoryOf("酸辣粉119"));
        assertEquals(BotNamePool.NEUTRAL, BotNamePool.categoryOf("橘子汽水"));
    }

    // selectJobForClass returns the classless beginner job for any level < 10, whatever the base
    // class, so a band that can dip under 10 must draw a neutral name rather than assert a class.
    @Test
    void levelBandThatCanBeBeginnerDrawsNeutral() {
        assertEquals(BotNamePool.NEUTRAL, BotNamePool.nameCategoryFor(BotNamePool.WARRIOR, 1, 0));
        assertEquals(BotNamePool.NEUTRAL, BotNamePool.nameCategoryFor(BotNamePool.THIEF, 9, 0));
        assertEquals(BotNamePool.THIEF, BotNamePool.nameCategoryFor(BotNamePool.THIEF, 10, 0));
        assertEquals(BotNamePool.THIEF, BotNamePool.nameCategoryFor(BotNamePool.THIEF, 30, 0));
    }

    // A forced job owns the character whatever the level, so its category wins even on a low band.
    @Test
    void forcedJobPinsTheCategory() {
        assertEquals(BotNamePool.THIEF, BotNamePool.nameCategoryFor(BotNamePool.WARRIOR, 1, 412));
        assertEquals(BotNamePool.MAGICIAN, BotNamePool.nameCategoryFor(0, 1, 232));
    }

    @Test
    void unknownBaseClassIsNeutral() {
        assertEquals(BotNamePool.NEUTRAL, BotNamePool.nameCategoryFor(0, 20, 0));
        assertEquals(BotNamePool.NEUTRAL, BotNamePool.nameCategoryFor(6, 20, 0));
    }

    // The English pool is the default language, so it needs the same coverage. Its words match on
    // token boundaries (see BotNamePool), because a substring match would be wrong for ASCII.
    @Test
    void englishClassWordsMapToTheirCategory() {
        assertEquals(BotNamePool.THIEF, BotNamePool.categoryOf("HermitBoyo"));
        assertEquals(BotNamePool.MAGICIAN, BotNamePool.categoryOf("BishopGod"));
        assertEquals(BotNamePool.WARRIOR, BotNamePool.categoryOf("DashWarrior"));
        assertEquals(BotNamePool.BOWMAN, BotNamePool.categoryOf("Pr0Archer"));
        assertEquals(BotNamePool.PIRATE, BotNamePool.categoryOf("CorsairLyfe"));
        assertEquals(BotNamePool.THIEF, BotNamePool.categoryOf("xSneakyThief"));
    }

    // Multi-word classes are recognised from an adjacent token pair.
    @Test
    void englishMultiWordClassesMatchAcrossTokens() {
        assertEquals(BotNamePool.BOWMAN, BotNamePool.categoryOf("xBowMaster07"));
        assertEquals(BotNamePool.THIEF, BotNamePool.categoryOf("xNightLord"));
        assertEquals(BotNamePool.THIEF, BotNamePool.categoryOf("xChiefBandit"));
        assertEquals(BotNamePool.WARRIOR, BotNamePool.categoryOf("xDarkKnight"));
        assertEquals(BotNamePool.WARRIOR, BotNamePool.categoryOf("xPaladin07"));
    }

    // A substring match would misread ordinary words: SIN inside SINCE, MAGE inside IMAGE, HERO
    // inside ZEROHERO. Token matching keeps them neutral.
    @Test
    void englishSubstringsDoNotFalsePositive() {
        assertEquals(BotNamePool.NEUTRAL, BotNamePool.categoryOf("Since2005"));
        assertEquals(BotNamePool.NEUTRAL, BotNamePool.categoryOf("zeroherozx"));
        assertEquals(BotNamePool.NEUTRAL, BotNamePool.categoryOf("RiceBowl"));
        assertEquals(BotNamePool.NEUTRAL, BotNamePool.categoryOf("GuildMaster"));
        assertEquals(BotNamePool.NEUTRAL, BotNamePool.categoryOf("Evilcookie33"));
    }
}
