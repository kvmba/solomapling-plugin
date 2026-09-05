package soloMapling;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.Environment.BotMessages;
import soloMapling.Environment.SoloMaplingLanguageConfig;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The TrainingBot comment on its keyword lists is a real constraint, not decoration: a greedy
 * "how"/"train" used to let "how about joining my party" hijack the flavor-chat option instead of
 * rolling a party invite. Localizing the labels must not widen those keywords.
 */
class MenuKeywordRegressionTest {

    private static final String[] SOLO_SUFFIXES = {"training", "party", "goodbye"};
    private static final String[][] SOLO_KEYWORDS = {
            {"hows", "how is", "how goes"},
            {"party", "team", "join"},
            {"bye", "goodbye", "cya", "later"}
    };

    @AfterEach
    void reset() {
        SoloMaplingLanguageConfig.setLanguageTag(SoloMaplingLanguageConfig.DEFAULT);
    }

    @Test
    void partyIntentStillWinsOverFlavorChat() {
        for (String tag : new String[]{"en-US", "zh-CN"}) {
            SoloMaplingLanguageConfig.setLanguageTag(tag);
            List<List<String>> kw = BotMessages.keywords("menu.training_solo", SOLO_SUFFIXES, SOLO_KEYWORDS);

            // "how about joining my party" must not match option 0 (flavor chat).
            String typed = "how about joining my party";
            boolean flavorMatches = kw.get(0).stream().anyMatch(typed::contains);
            assertFalse(flavorMatches,
                    tag + ": flavor keywords must stay narrow, got " + kw.get(0));

            // ...and it must still match option 1 (party).
            boolean partyMatches = kw.get(1).stream().anyMatch(typed::contains);
            org.junit.jupiter.api.Assertions.assertTrue(partyMatches,
                    tag + ": party keywords must still match");
        }
    }

    @Test
    void chineseLabelDoesNotAccidentallyMatchPartyWording() {
        SoloMaplingLanguageConfig.setLanguageTag("zh-CN");
        List<List<String>> kw = BotMessages.keywords("menu.training_solo", SOLO_SUFFIXES, SOLO_KEYWORDS);
        // The localized flavor label "练得怎么样？" must not contain the party label "要组队吗？"
        // nor fire on party phrasing.
        boolean flavorMatchesOnParty = kw.get(0).stream().anyMatch("我要组队"::contains);
        assertFalse(flavorMatchesOnParty, "flavor keywords leaked party phrasing: " + kw.get(0));
    }

    // The party option's English list ("party"/"team"/"join") is not transliterated, so a player
    // reading the Chinese menu had to answer in English. BotMessages-zh-CN.yaml now ships the
    // phrasings a Chinese-speaking player actually types; this pins them to the party option
    // (index 1 - the middle of training/party/goodbye).
    @Test
    void chinesePartyPhrasingsReachThePartyOption() {
        SoloMaplingLanguageConfig.setLanguageTag("zh-CN");
        List<List<String>> kw = BotMessages.keywords("menu.training_solo", SOLO_SUFFIXES, SOLO_KEYWORDS);
        List<String> party = kw.get(1);

        for (String typed : new String[]{"组队", "组我", "带我", "一起", "上车", "刷怪"}) {
            assertTrue(party.stream().anyMatch(typed::contains),
                    "party option should match \"" + typed + "\", got " + party);
        }
        // English must survive: the YAML aliases extend the Java list, they do not replace it.
        assertTrue(party.contains("party"), "English keywords must survive, got " + party);
    }

    // Mirror of the party case for the FOLLOW option of the partied training menu ("跟我来！").
    // Its English list ("follow"/"come"/"lead") is not transliterated and the label alone only
    // matches that exact phrase, so "跟我走" - the wording a Chinese player reaches for first -
    // used to miss the menu entirely and look like a dead bot.
    @Test
    void chineseFollowPhrasingsReachTheFollowOption() {
        SoloMaplingLanguageConfig.setLanguageTag("zh-CN");
        String[] suffixes = {"training", "follow", "goodbye"};
        String[][] english = {
                {"hows", "how is", "how goes"},
                {"follow", "come", "lead"},
                {"bye", "goodbye", "cya", "later"}
        };
        List<List<String>> kw = BotMessages.keywords("menu.training_party", suffixes, english);
        List<String> follow = kw.get(1);

        for (String typed : new String[]{"跟我走", "跟着我", "跟我来", "跟我", "跟着", "跟上", "跟紧我",
                "走这边", "来这边", "过来", "过来这边", "带路", "一起走", "我带你"}) {
            assertTrue(follow.stream().anyMatch(typed::contains),
                    "follow option should match \"" + typed + "\", got " + follow);
        }
        assertTrue(follow.contains("follow"), "English keywords must survive, got " + follow);

        // The neighbouring options must not be hijacked: match() walks options IN ORDER with
        // contains(), so a follow alias that also reads as training/goodbye would win on ordering.
        for (String typed : new String[]{"再见", "练得怎么样", "走了", "溜了"}) {
            assertFalse(follow.stream().anyMatch(typed::contains),
                    "follow keywords hijacked \"" + typed + "\": " + follow);
        }
    }
}
