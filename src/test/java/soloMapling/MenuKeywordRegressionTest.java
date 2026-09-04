package soloMapling;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.Environment.BotMessages;
import soloMapling.Environment.SoloMaplingLanguageConfig;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;

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
}
