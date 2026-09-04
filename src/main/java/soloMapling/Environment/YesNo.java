package soloMapling.Environment;

import java.util.List;

/**
 * Yes/no option menu, shared by the bots that ask a straight yes/no question (tutorial bot,
 * game-zone host). Both the displayed labels and the accepted answers are localized.
 *
 * <p>Answer matching runs once per player reply, not per tick, so it resolves keywords on each
 * call rather than caching them: a static snapshot would keep serving the previous language after
 * a switch. The cost is a couple of map lookups against an already-loaded pack.
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
        if (content == null || content.isEmpty()) {
            return false;
        }
        List<String> keywords = BotMessages.keywords(PREFIX, SUFFIXES, KEYWORDS).get(index);
        // Try the raw text first: toLowerCase() allocates a second string, and most chat is not a
        // yes/no answer, so the common path should not pay for it.
        if (containsAny(content, keywords)) {
            return true;
        }
        String lower = content.toLowerCase();
        return !lower.equals(content) && containsAny(lower, keywords);
    }

    private static boolean containsAny(String content, List<String> keywords) {
        for (String keyword : keywords) {
            if (content.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
