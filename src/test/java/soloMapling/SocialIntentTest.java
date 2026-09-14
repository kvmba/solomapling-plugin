package soloMapling;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotDialogueHandler;
import soloMapling.ArtificialPlayer.SocialIntent;
import soloMapling.ArtificialPlayer.SocialPersonaConfig;
import soloMapling.Environment.SoloMaplingLanguageConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the social-intent classifier: it recognises the common gestures, refuses to fire on ordinary
 * chat (the substring trap), and every node it can return actually exists in both language packs.
 */
class SocialIntentTest {

    @AfterEach
    void reset() {
        SoloMaplingLanguageConfig.setLanguageTag(SoloMaplingLanguageConfig.DEFAULT);
        SocialPersonaConfig.configure(null);
    }

    @Test
    void recognisesCommonGestures() {
        assertEquals("Praise", SocialIntent.classifyNode("大佬牛逼啊"));
        assertEquals("Praise", SocialIntent.classifyNode("太强了"));
        assertEquals("Praise", SocialIntent.classifyNode("666"));
        assertEquals("Praise", SocialIntent.classifyNode("yyds"));
        assertEquals("Praise", SocialIntent.classifyNode("nbnb"));
        assertEquals("TeaseBack", SocialIntent.classifyNode("就这？"));
        assertEquals("TeaseBack", SocialIntent.classifyNode("你太菜了"));
        assertEquals("TeaseBack", SocialIntent.classifyNode("破防了吧"));
        assertEquals("Laugh", SocialIntent.classifyNode("哈哈哈哈哈"));
        assertEquals("Laugh", SocialIntent.classifyNode("笑死我了"));
        assertEquals("Laugh", SocialIntent.classifyNode("233"));
        assertEquals("Wow", SocialIntent.classifyNode("卧槽"));
        assertEquals("Wow", SocialIntent.classifyNode("爷青回"));
        assertEquals("Agree", SocialIntent.classifyNode("确实"));
        assertEquals("Agree", SocialIntent.classifyNode("顶一下"));
        assertEquals("Thanks", SocialIntent.classifyNode("谢谢老哥"));
        assertEquals("Thanks", SocialIntent.classifyNode("老板大气"));
        assertEquals("Apology", SocialIntent.classifyNode("抱歉抱歉"));
        assertEquals("Cheer", SocialIntent.classifyNode("加油啊"));
    }

    @Test
    void noKeywordCollidesAcrossIntentsAndNoneIsASingleChar() {
        for (SocialIntent a : SocialIntent.values()) {
            for (SocialIntent b : SocialIntent.values()) {
                if (a == b) {
                    continue;
                }
                for (String kw : a.words()) {
                    assertFalse(java.util.Arrays.asList(b.words()).contains(kw),
                            "'" + kw + "' appears in both " + a + " and " + b + " (shadowed by order)");
                    assertFalse(java.util.Arrays.asList(b.codes()).contains(kw),
                            "'" + kw + "' is a keyword of " + a + " and a code of " + b);
                }
            }
            for (String kw : a.words()) {
                assertTrue(kw.length() >= 2, a + ": single-char contains keyword '" + kw + "' swallows chat");
            }
        }
    }

    @Test
    void greetingIsTheLowestPriorityIntent() {
        assertEquals("Greeting", SocialIntent.classifyNode("你好"));
        assertEquals("Greeting", SocialIntent.classifyNode("哈喽"));
        assertEquals("Greeting", SocialIntent.classifyNode("hi"));
        assertEquals("Greeting", SocialIntent.classifyNode("早上好"));
        // A sharper intent on the same line still wins over the plain hello.
        assertEquals("Praise", SocialIntent.classifyNode("你好厉害"));
    }

    @Test
    void ordinaryChatIsNotASocialIntent() {
        assertNull(SocialIntent.classifyNode("我在这干啥呢"));
        assertNull(SocialIntent.classifyNode("去菜市场买菜"));
        assertNull(SocialIntent.classifyNode("我 666 血够吗"));
        assertNull(SocialIntent.classifyNode("1888 金币"));
        assertNull(SocialIntent.classifyNode("这地图怪好多"));
        assertNull(SocialIntent.classifyNode(""));
        assertNull(SocialIntent.classifyNode(null));
    }

    @Test
    void priorityPutsTheSharperIntentFirst() {
        // A sneer that also carries a laugh is a sneer.
        assertEquals("TeaseBack", SocialIntent.classifyNode("就这？哈哈"));
    }

    @Test
    void shortCodesMatchOnlyAsTheWholeLine() {
        assertEquals("Praise", SocialIntent.classifyNode("6"));
        assertNull(SocialIntent.classifyNode("6点半了"));
        assertEquals("Thanks", SocialIntent.classifyNode("3Q"));
    }

    @Test
    void offPersonaDisablesIntentRecognition() {
        SocialPersonaConfig.configure(null);
        assertNotNull(SocialIntent.classifyNode("哈哈")); // default: on
    }

    @Test
    void everyIntentNodeExistsInBothPacks() {
        for (String tag : new String[]{"zh-CN", "en-US"}) {
            SoloMaplingLanguageConfig.setLanguageTag(tag);
            for (SocialIntent intent : SocialIntent.values()) {
                var con = BotDialogueHandler.getDialogueCon(
                        "SocialBotDialogue.yaml", "SocialBot", intent.node());
                assertNotNull(con, tag + ": missing node " + intent.node());
                assertTrue(con.getDialogue().size() >= 8,
                        tag + ": node " + intent.node() + " too small: " + con.getDialogue().size());
            }
        }
    }
}
