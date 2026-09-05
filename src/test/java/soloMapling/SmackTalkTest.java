package soloMapling;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotDialogueHandler;
import soloMapling.ArtificialPlayer.DialogueContextResolver;
import soloMapling.Environment.SoloMaplingLanguageConfig;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the SmackTalk pool: the "server rat" lines a bot uses on a player it clearly out-levels.
 *
 * <p>The important behaviours are the two that would otherwise silently degrade:
 * <ul>
 *   <li>A line is addressed AT the player. Without 你/您 (or a vocative) it reads as the bot
 *       muttering to itself - which is how this pool first shipped.</li>
 *   <li>A line is a TEMPLATE. {@code DialogueContextResolver} drops the whole line when any
 *       {TOKEN} cannot be resolved, so a bot never speaks a half-filled sentence.</li>
 * </ul>
 */
class SmackTalkTest {

    private static final String PATH = "SocialBotDialogue.yaml";
    private static final String TYPE = "SocialBot";
    private static final String NODE = "SmackTalk";

    private static final Set<String> KNOWN_TOKENS = Set.of(
            "PLAYER_NAME", "PLAYER_LEVEL", "PLAYER_JOB", "PLAYER_FAME",
            "PLAYER_WEAPON", "PLAYER_GEAR", "PLAYER_PET", "PLAYER_GUILD", "PLAYER_NX",
            "MAP", "REGION", "JOB", "LEVEL", "WEAPON", "MOB", "DROP");

    @AfterEach
    void reset() {
        SoloMaplingLanguageConfig.setLanguageTag(SoloMaplingLanguageConfig.DEFAULT);
    }

    private static List<String> lines() {
        SoloMaplingLanguageConfig.setLanguageTag("zh-CN");
        var con = BotDialogueHandler.getDialogueCon(PATH, TYPE, NODE);
        assertNotNull(con, "SmackTalk node should exist");
        return con.getDialogue();
    }

    @Test
    void poolIsLargeEnoughToNotRepeatConstantly() {
        assertTrue(lines().size() >= 500,
                "expected a large smack-talk pool, got " + lines().size());
    }

    @Test
    void everyLineAddressesThePlayer() {
        for (String line : lines()) {
            String bare = line.replaceAll("\\{[A-Z_]+\\}", "X");
            boolean addressed = bare.contains("你") || bare.contains("您")
                    || bare.startsWith("兄弟") || bare.startsWith("哥们")
                    || bare.startsWith("老兄") || bare.startsWith("小老弟");
            assertTrue(addressed,
                    "line reads as talking to nobody: " + line);
        }
    }

    @Test
    void everyTokenIsKnown() {
        Pattern token = Pattern.compile("\\{([A-Z_]+)\\}");
        Set<String> unknown = new TreeSet<>();
        for (String line : lines()) {
            Matcher m = token.matcher(line);
            while (m.find()) {
                if (!KNOWN_TOKENS.contains(m.group(1))) {
                    unknown.add(m.group(1));
                }
            }
        }
        assertEquals(Set.of(), unknown, "unknown {TOKEN}s would resolve to nothing");
    }

    // The resolver's contract: any unresolvable token drops the entire line. Pin it against this
    // pool, because a template that silently loses its token is exactly the bug that would make a
    // bot say "你 {PLAYER_LEVEL} 级" out loud.
    @Test
    void linesWithPlayerTokensAreDroppedWithoutAPlayer() {
        List<String> all = lines();
        long withTokens = all.stream().filter(s -> s.contains("{")).count();
        assertTrue(withTokens > 0, "some lines should carry {PLAYER_*} tokens");

        long dropped = all.stream()
                .filter(s -> s.contains("{"))
                .filter(s -> DialogueContextResolver.fill(s, null, null).isEmpty())
                .count();
        assertEquals(withTokens, dropped,
                "every tokenised line must be dropped when the player context is missing");
    }

    @Test
    void plainLinesSurviveWithoutAPlayer() {
        long dropped = lines().stream()
                .filter(s -> !s.contains("{"))
                .filter(s -> DialogueContextResolver.fill(s, null, null).isEmpty())
                .count();
        assertEquals(0, dropped, "token-free lines must always be speakable");
    }

    // Expanded tokens are much longer than the raw template, so a template that looks short can
    // still overflow the chat box once {PLAYER_GEAR} becomes a real item name.
    @Test
    void noLineOverflowsOnceTokensExpand() {
        for (String line : lines()) {
            assertTrue(estimatedWidth(line) <= 60,
                    "line would overflow once tokens expand: " + line);
        }
    }

    @Test
    void englishPackHasNoSmackTalk() {
        SoloMaplingLanguageConfig.setLanguageTag("en-US");
        var con = BotDialogueHandler.getDialogueCon(PATH, TYPE, NODE);
        assertTrue(con == null || con.getDialogue().isEmpty(),
                "English pack has no SmackTalk; a missing node must stay empty, not throw");
    }

    /** Display width with each token budgeted as a real item/level name. */
    private static int estimatedWidth(String s) {
        final int TOKEN_BUDGET = 8;
        Pattern token = Pattern.compile("\\{[A-Z_]+\\}");
        Matcher m = token.matcher(s);
        int width = 0;
        int last = 0;
        while (m.find()) {
            width += rawWidth(s.substring(last, m.start()));
            width += TOKEN_BUDGET;
            last = m.end();
        }
        width += rawWidth(s.substring(last));
        return width;
    }

    private static int rawWidth(String s) {
        int width = 0;
        for (int i = 0; i < s.length(); i++) {
            width += Character.UnicodeBlock.of(s.charAt(i))
                    == Character.UnicodeBlock.BASIC_LATIN ? 1 : 2;
        }
        return width;
    }
}
