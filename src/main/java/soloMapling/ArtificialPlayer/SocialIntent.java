package soloMapling.ArtificialPlayer;

import java.util.Locale;

/**
 * What a player's chat line is DOING socially (praising, joking, thanking, insulting...), so a bot
 * can answer in kind - a laugh gets a laugh, an insult gets a retort. This is separate from the
 * functional menu intents (party / follow), which already have their own handling and always win.
 *
 * <p>Matching is substring-based, so every keyword is a whole word or at least two characters - a
 * bare "哈" / "菜" / "6" would swallow ordinary chat ("我在这干啥" / "菜市场" / "6点半"). Short
 * codes (6 / 88 / 666 / 233) match only as the whole trimmed line.
 *
 * <p>Declaration order is priority: the first intent that matches wins, so a line that both sneers
 * and laughs is treated as the sneer.
 */
public enum SocialIntent {

    // Order = priority (see docs/BOT_SOCIAL_KEYWORDS_PLAN.md §6).
    APOLOGY("Apology",
            new String[]{"sorry"},
            new String[]{"抱歉", "对不起", "不好意思", "打扰了", "我的错", "对不住", "见谅",
                    "我的我的"}),
    THANKS("Thanks",
            new String[]{"3q", "thx", "ty"},
            new String[]{"谢谢", "多谢", "感谢", "辛苦了", "麻烦你了", "三克油", "阿里嘎多",
                    "好人一生平安", "老板大气"}),
    PRAISE("Praise",
            new String[]{"6", "666", "yyds", "nb", "nbnb"},
            new String[]{"厉害", "牛逼", "牛批", "牛哇", "牛啊", "太牛", "真牛", "牛的一批",
                    "大佬", "大神", "高手", "卡密", "太秀", "天秀", "秀儿", "秀啊",
                    "太强", "好强", "真强", "很强", "强无敌", "无敌", "人形外挂", "战神",
                    "膜拜", "跪了"}),
    CHEER("Cheer",
            new String[]{"gkd"},
            new String[]{"加油", "冲冲冲", "冲鸭", "你可以的", "别放弃", "稳住", "干巴爹",
                    "上分", "拿捏", "练起来", "卷起来", "好好练", "有前途"}),
    PROVOKE("TeaseBack",
            new String[]{},
            new String[]{"就这", "你行你上", "别玩了", "别练了", "别丢人", "丢人",
                    "菜鸡", "太菜", "好菜", "菜鸟", "菜爆", "垃圾", "废物", "弱爆", "好弱",
                    "太弱", "很弱", "离谱", "绝了", "服了", "无语", "摆烂", "拉胯", "刮痧",
                    "呵呵", "急了", "破防", "栓q"}),
    WOW("Wow",
            new String[]{"awsl"},
            new String[]{"卧槽", "我擦", "我靠", "哇塞", "哇哦", "我的天", "天啊", "妈呀", "妈耶",
                    "好家伙", "我去", "芜湖", "爷青回", "泪目", "震撼", "起飞"}),
    LAUGH("Laugh",
            new String[]{"233", "2333", "hhh", "hhhh", "xswl"},
            new String[]{"哈哈", "笑死", "笑飞", "笑麻", "笑不活了", "乐死", "逗死", "嘿嘿",
                    "嘻嘻", "噗嗤", "绷不住", "蚌埠住了"}),
    AGREE("Agree",
            new String[]{"+1", "u1s1"},
            new String[]{"嗯嗯", "哦哦", "对对", "确实", "有道理", "说得对", "说得是", "没错",
                    "没毛病", "加一", "附议", "同感", "是这样的", "就是这样", "可不咋的",
                    "必须的", "赞同", "支持", "顶一个", "顶一下"});

    private final String node;
    private final String[] exact;
    private final String[] contains;

    SocialIntent(String node, String[] exact, String[] contains) {
        this.node = node;
        this.exact = exact;
        this.contains = contains;
    }

    /** The dialogue node a bot answers this intent with. */
    public String node() {
        return node;
    }

    /** The substring keywords this intent matches (introspection; also used by tests). */
    public String[] words() {
        return contains;
    }

    /** The whole-line codes this intent matches (introspection; also used by tests). */
    public String[] codes() {
        return exact;
    }

    /**
     * The dialogue node for what the player said, or null when the line is not a social intent
     * (ordinary chatter, a menu pick, an unknown line) - the caller then keeps its existing flow.
     */
    public static String classifyNode(String content) {
        SocialIntent intent = classify(content);
        return intent == null ? null : intent.node;
    }

    public static SocialIntent classify(String content) {
        if (content == null || content.isBlank() || !SocialPersonaConfig.intentEnabled()) {
            return null;
        }
        String c = content.trim().toLowerCase(Locale.ROOT);
        for (SocialIntent intent : values()) {
            if (intent.matches(c)) {
                return intent;
            }
        }
        return null;
    }

    private boolean matches(String lower) {
        for (String e : exact) {
            if (lower.equals(e)) {
                return true;
            }
        }
        for (String s : contains) {
            if (lower.contains(s)) {
                return true;
            }
        }
        return false;
    }
}
