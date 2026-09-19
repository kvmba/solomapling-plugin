package soloMapling;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotOptionMenu;
import soloMapling.ArtificialPlayer.SocialIntent;
import soloMapling.Environment.BotMessages;
import soloMapling.Environment.SoloMaplingLanguageConfig;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The functional-menu keywords and the {@link SocialIntent} wordlist are two independent substring
 * matchers over the same player line, and nothing forbade them overlapping. "就这里" ("train right
 * here") CONTAINS PROVOKE's "就这" ("is that all?"), so a FollowerBot whose owner typed the
 * station-here command answered it as an insult and never ran the handoff.
 *
 * <p>The contract this pins: a bot's OWN option keyword is authoritative over the social reading of
 * the same words. These tests exercise the two halves - the menu still claims the functional line,
 * and the classifier still fires on genuine chatter - so a future edit cannot silently re-break the
 * priority by widening either wordlist.
 */
class FunctionalKeywordPriorityTest {

    // The follower menu, exactly as FollowerBot builds it (see FollowerBot MENU_SUFFIXES/KEYWORDS).
    private static final String[] FOLLOWER_SUFFIXES = {"train", "nevermind"};
    private static final String[][] FOLLOWER_KEYWORDS = {
            {"train", "grind", "station"},
            {"nevermind", "bye", "nah", "nope"}
    };

    @AfterEach
    void reset() {
        SoloMaplingLanguageConfig.setLanguageTag(SoloMaplingLanguageConfig.DEFAULT);
    }

    // Build the menu the way FollowerBot does, owner=null: matches() reads only the labels/keywords.
    private BotOptionMenu followerMenu() {
        List<String> labels = BotMessages.labels("menu.follower", FOLLOWER_SUFFIXES);
        List<List<String>> keywords =
                BotMessages.keywords("menu.follower", FOLLOWER_SUFFIXES, FOLLOWER_KEYWORDS);
        return new BotOptionMenu(null, labels, keywords, (idx, player) -> { });
    }

    /**
     * Every "train here" phrasing a Chinese player types must be claimed by the follower's menu -
     * INCLUDING the ones that also read as a social intent. This is the exact line the bug killed:
     * it matches the menu yet classifyNode() returns TeaseBack, so only the priority gate saves it.
     */
    @Test
    void stationHerePhrasingsAreClaimedByTheMenuEvenWhenTheyReadAsASocialIntent() {
        SoloMaplingLanguageConfig.setLanguageTag("zh-CN");
        BotOptionMenu menu = followerMenu();

        for (String typed : new String[]{"就这里", "就这儿", "就这", "就这练"}) {
            assertTrue(menu.matches(typed),
                    "\"" + typed + "\" must be claimed by the follower's train option");
            // These are precisely the lines the social reader would otherwise steal.
            assertNotNull(SocialIntent.classifyNode(typed),
                    "\"" + typed + "\" is expected to ALSO match a social intent - that is the collision");
        }
    }

    // The gate only applies to lines a bot actually claims: genuine chatter the menu does not carry
    // is still answered as a social gesture (the fix must not muzzle the bots).
    @Test
    void unclaimedChatterIsStillSocial() {
        SoloMaplingLanguageConfig.setLanguageTag("zh-CN");
        BotOptionMenu menu = followerMenu();

        for (String chatter : new String[]{"哈哈", "谢谢老哥", "大佬牛逼"}) {
            assertNotNull(SocialIntent.classifyNode(chatter), chatter + " should still be a social intent");
            assertFalse(menu.matches(chatter), chatter + " must NOT be claimed by the follower menu");
        }
    }

    // The number-pick path keeps working: the menu answers "1"/"2" as well as keywords.
    @Test
    void optionNumbersStillMatch() {
        SoloMaplingLanguageConfig.setLanguageTag("zh-CN");
        BotOptionMenu menu = followerMenu();
        assertTrue(menu.matches("1"));
        assertTrue(menu.matches("2"));
        assertFalse(menu.matches("3"));
    }
}
