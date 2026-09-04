package soloMapling.Environment;

import java.util.List;

/**
 * Yes/no option menu, shared by the bots that ask a straight yes/no question (tutorial bot,
 * game-zone host). Both the displayed labels and the accepted answers are localized.
 */
public final class YesNo {

    private YesNo() {
    }

    private static final String PREFIX = "menu.yesno";
    private static final String[] SUFFIXES = {"yes", "no"};
    private static final String[][] KEYWORDS = {{"yes"}, {"no"}};

    /** Localized labels in display order: yes, then no. */
    public static List<String> labels() {
        return BotMessages.labels(PREFIX, SUFFIXES);
    }

    /**
     * Whether the typed text is a yes (or no) answer. Accepts the English word and the localized
     * label, so a menu showing 是/不 is answerable either way.
     */
    public static boolean isYes(String content) {
        return matches(content, 0);
    }

    public static boolean isNo(String content) {
        return matches(content, 1);
    }

    private static boolean matches(String content, int index) {
        if (content == null) {
            return false;
        }
        String lower = content.toLowerCase();
        for (String keyword : BotMessages.keywords(PREFIX, SUFFIXES, KEYWORDS).get(index)) {
            if (lower.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
