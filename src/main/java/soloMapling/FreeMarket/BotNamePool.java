package soloMapling.FreeMarket;

/**
 * Classifies a bot IGN by the v83 job category its role word names, so a name can be
 * drawn consistent with the job the character actually ends up with.
 *
 * <p>A name like {@code 圣骑士肝帝} on a thief reads wrong; the pool carries thousands
 * of names whose role word asserts a class (圣骑士, 大主教, 魔法师, 弓箭手, ...). The
 * name and the job used to be two independent rolls, so a third of bots shipped a name
 * that contradicted their class. {@link FMShopDescGen} now draws a name that is either
 * neutral or asserts the bot's own category, and this class is the single place that
 * decides which words belong to which category.</p>
 *
 * <p>Categories use the v83 base-class numbering (1..5) so they line up with
 * {@code BotDecorate.rollBaseClass()} and {@code Job} ids without a translation table.</p>
 *
 * <p>Not every role-flavoured word is a class claim. {@code 龙神}, {@code 恶魔} and
 * {@code 天使} read as flavour, not as a v83 job (there is no such class), so they stay
 * neutral. {@code 勇士} is a class word but {@code 勇士部落} is a town (Perion) and is
 * subtracted explicitly.</p>
 */
public final class BotNamePool {

    public static final int NEUTRAL = 0;
    public static final int WARRIOR = 1;
    public static final int MAGICIAN = 2;
    public static final int BOWMAN = 3;
    public static final int THIEF = 4;
    public static final int PIRATE = 5;

    private static final String[] WARRIOR_WORDS =
            {"圣骑士", "龙骑士", "黑骑士", "骑士", "狂战士", "剑客", "英雄", "战士", "勇士", "战神", "龙骑"};
    private static final String[] MAGICIAN_WORDS =
            {"大主教", "主教", "魔法师", "法师", "牧师", "祭司", "冰雷", "火毒"};
    private static final String[] BOWMAN_WORDS =
            {"弓箭手", "神射手", "弓手", "射手", "游侠", "狙击手"};
    private static final String[] THIEF_WORDS =
            {"暗影者", "刺客", "忍者", "飞侠", "标飞", "刀飞"};
    private static final String[] PIRATE_WORDS =
            {"海盗王", "海盗", "船长", "拳霸", "拳手", "机械师", "准将"};

    // Words a shorter category token would otherwise swallow: 勇士部落 (Perion) contains 勇士.
    private static final String[] NEUTRAL_EXCEPTIONS = {"勇士部落"};

    private record Word(String text, int category) {
    }

    // Longest word first, so 圣骑士 wins over 骑士 and 勇士部落 over 勇士. No two words of
    // equal length contain each other, so the tie order does not matter.
    private static final Word[] ORDERED = buildOrdered();

    private BotNamePool() {
    }

    /** The job category a name's role word asserts, or {@link #NEUTRAL} when it asserts none. */
    public static int categoryOf(String name) {
        if (name == null) {
            return NEUTRAL;
        }
        for (Word word : ORDERED) {
            if (name.contains(word.text())) {
                return word.category();
            }
        }
        return NEUTRAL;
    }

    /** Category for a v83 base-class number (1=Warrior … 5=Pirate); anything else is neutral. */
    public static int categoryOfBaseClass(int baseClass) {
        return (baseClass >= 1 && baseClass <= 5) ? baseClass : NEUTRAL;
    }

    /** Category for a v83 job id (100/110/…=Warrior … 500/…=Pirate); beginner/unknown is neutral. */
    public static int categoryOfJobId(int jobId) {
        return jobId <= 0 ? NEUTRAL : categoryOfBaseClass(jobId / 100);
    }

    /**
     * The category a spawn's name should be drawn for: a forced job's exact class, else the base
     * class - but {@link #NEUTRAL} when the level band can produce a beginner.
     *
     * <p>{@code BotDecorate.selectJobForClass} returns the beginner job (0) for any level below 10,
     * whatever the base class, so a band whose minimum dips under 10 can hand out a classless
     * character. Asserting a class then would be the very mismatch this pool exists to avoid, so the
     * name is drawn neutral instead.</p>
     *
     * @param baseClass  the resolved base class (1..5), or 0 when unknown
     * @param minLevel   the lowest level the spawn band can produce (the default band is 10..80)
     * @param forcedJobId a pinned exact job id, or 0 for a random job of the class
     */
    public static int nameCategoryFor(int baseClass, int minLevel, int forcedJobId) {
        if (forcedJobId > 0) {
            // A forced job owns the character whatever its level, so the name follows it.
            return categoryOfJobId(forcedJobId);
        }
        if (minLevel < 10) {
            return NEUTRAL;
        }
        return categoryOfBaseClass(baseClass);
    }

    private static Word[] buildOrdered() {
        java.util.List<Word> words = new java.util.ArrayList<>();
        add(words, WARRIOR, WARRIOR_WORDS);
        add(words, MAGICIAN, MAGICIAN_WORDS);
        add(words, BOWMAN, BOWMAN_WORDS);
        add(words, THIEF, THIEF_WORDS);
        add(words, PIRATE, PIRATE_WORDS);
        add(words, NEUTRAL, NEUTRAL_EXCEPTIONS);
        words.sort((a, b) -> Integer.compare(b.text().length(), a.text().length()));
        return words.toArray(new Word[0]);
    }

    private static void add(java.util.List<Word> words, int category, String[] tokens) {
        for (String token : tokens) {
            words.add(new Word(token, category));
        }
    }
}
