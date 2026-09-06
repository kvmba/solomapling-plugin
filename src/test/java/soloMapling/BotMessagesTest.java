package soloMapling;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotTypes.Blackjack.BlackjackRules;
import soloMapling.Environment.BotMessages;
import soloMapling.Environment.SoloMaplingLanguageConfig;
import soloMapling.Environment.YesNo;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Player-visible UI strings that live in Java code must follow the configured language, and must
 * survive a language switch (the cache is per-language, so a stale cache would leak the previous
 * language's text into chat).
 */
class BotMessagesTest {

    @AfterEach
    void reset() {
        SoloMaplingLanguageConfig.setLanguageTag(SoloMaplingLanguageConfig.DEFAULT);
    }

    @Test
    void englishResolvesEnglishText() {
        SoloMaplingLanguageConfig.setLanguageTag("en-US");
        assertEquals("Thumper has declined your party request.",
                BotMessages.get("party.declined", "Thumper"));
    }

    @Test
    void chineseResolvesLocalizedText() {
        SoloMaplingLanguageConfig.setLanguageTag("zh-CN");
        assertEquals("Thumper 拒绝了你的组队邀请。",
                BotMessages.get("party.declined", "Thumper"));
    }

    @Test
    void englishStillWorksAfterSwitchingBackFromChinese() {
        SoloMaplingLanguageConfig.setLanguageTag("zh-CN");
        BotMessages.get("party.declined", "Thumper");
        SoloMaplingLanguageConfig.setLanguageTag("en-US");
        assertEquals("Thumper has declined your party request.",
                BotMessages.get("party.declined", "Thumper"));
    }

    @Test
    void untranslatedKeyFallsBackToEnglish() {
        SoloMaplingLanguageConfig.setLanguageTag("zh-CN");
        // test.english_only exists only in BotMessages.yaml, on purpose.
        assertEquals("english only", BotMessages.get("test.english_only"));
    }

    @Test
    void unknownKeyReturnsTheKeyItself() {
        SoloMaplingLanguageConfig.setLanguageTag("en-US");
        assertEquals("no.such.key", BotMessages.get("no.such.key"));
    }

    @Test
    void missingArgumentLeavesPlaceholderVisibleRatherThanBlank() {
        SoloMaplingLanguageConfig.setLanguageTag("en-US");
        // Two placeholders, one argument: the second stays literal so the gap is obvious in-game
        // instead of silently producing a half-sentence.
        assertEquals("Dropping 3 cloud{1}!", BotMessages.get("opq.dropping_clouds", 3));
    }

    @Test
    void chineseOrdinalsAreNotEnglishWords() {
        SoloMaplingLanguageConfig.setLanguageTag("zh-CN");
        for (int i = 1; i <= 7; i++) {
            String ordinal = BotMessages.get("opq.box_ordinal." + i);
            assertFalse(ordinal.matches(".*[a-zA-Z].*"),
                    "box ordinal " + i + " should be localized, got: " + ordinal);
        }
    }

    @Test
    void chineseMenusAreLocalized() {
        SoloMaplingLanguageConfig.setLanguageTag("zh-CN");
        List<String> labels = BotMessages.labels("menu.social",
                "whatsup", "interesting", "rumors", "teamup", "goodbye");
        assertEquals(List.of("最近咋样？", "有啥新鲜事？", "有啥传闻？", "要组队吗？", "再见"), labels);
    }

    // A localized menu that only matched English keywords would leave a player staring at a
    // Chinese menu with no way to answer it. The label text must be typeable.
    @Test
    void localizedMenuLabelsAreTypeableAsKeywords() {
        SoloMaplingLanguageConfig.setLanguageTag("zh-CN");
        List<List<String>> keywords = BotMessages.keywords("menu.social",
                new String[]{"whatsup", "interesting", "rumors", "teamup", "goodbye"},
                new String[][]{{"what's up"}, {"interesting"}, {"rumor"}, {"team"}, {"bye"}});

        assertTrue(keywords.get(0).contains("最近咋样"), "label text should be a keyword");
        assertTrue(keywords.get(3).contains("组队"), "YAML aliases should be keywords");
        assertTrue(keywords.get(0).contains("what's up"), "English keywords must survive");
    }

    @Test
    void englishKeywordsAreKeptWhenLocalized() {
        SoloMaplingLanguageConfig.setLanguageTag("zh-CN");
        List<List<String>> keywords = BotMessages.keywords("menu.training_solo",
                new String[]{"training", "party", "goodbye"},
                new String[][]{{"hows"}, {"party"}, {"bye"}});
        // English clients and English-typing players must still match.
        assertTrue(keywords.get(0).contains("hows"));
        assertTrue(keywords.get(1).contains("party"));
    }

    @Test
    void yesNoIsAnswerableInBothLanguages() {
        SoloMaplingLanguageConfig.setLanguageTag("zh-CN");
        assertTrue(YesNo.isYes("是"));
        assertTrue(YesNo.isNo("不"));
        // English still accepted under a Chinese client.
        assertTrue(YesNo.isYes("yes"));
        assertTrue(YesNo.isNo("no"));
        assertFalse(YesNo.isYes("不"));
        assertFalse(YesNo.isNo("是"));
    }

    @Test
    void yesNoLabelsFollowTheLanguage() {
        SoloMaplingLanguageConfig.setLanguageTag("zh-CN");
        assertEquals(List.of("是", "不"), YesNo.labels());
        SoloMaplingLanguageConfig.setLanguageTag("en-US");
        assertEquals(List.of("Yes", "No"), YesNo.labels());
    }

    // Guards the specific regression this work set out to fix: no player-visible string should
    // still be hardcoded English while the server runs in Chinese. Whole English WORDS are the
    // signal - proper nouns stay untranslated by design (Han/Cho are the dice bet names, and the
    // client shows them that way), so they are excluded rather than treated as leaks.
    @Test
    void noPlayerVisibleStringLeaksEnglishUnderChinese() {
        SoloMaplingLanguageConfig.setLanguageTag("zh-CN");
        String[] keys = {
                "party.declined", "party.join_failed", "social.busy",
                "trade.declined", "trade.why_decline", "trade.thank_you",
                "trade.nothing_to_sell", "trade.nothing_for_sale", "trade.wants_prefix",
                "tutorial.talk_again", "tutorial.gifts_later", "tutorial.type_name_correctly",
                "gamezone.talk_again", "gamezone.no_such_drink",
                "blackjack.table_full", "blackjack.already_at_table", "blackjack.again",
                "blackjack.waiting_for_players", "blackjack.place_bets", "blackjack.bust",
                // The dealer's spoken patter, hardcoded in BlackjackDealerBot until this fix.
                "blackjack.your_turn", "blackjack.too_many", "blackjack.twenty_one",
                "blackjack.dealer_points", "blackjack.dealer_twenty_one",
                // Per-result lines assembled by BlackjackRules.formatOutcomeMessage().
                "blackjack.outcome.bust", "blackjack.outcome.lose_dealer_blackjack",
                "blackjack.outcome.lose", "blackjack.outcome.win_dealer_bust",
                "blackjack.outcome.win", "blackjack.outcome.blackjack",
                "blackjack.outcome.push", "blackjack.outcome.unexpected",
                "dice.select_han_cho", "dice.no_bet", "dice.casino_defame", "dice.roll",
                "dropgame.rules", "dropgame.too_slow", "dropgame.invite_timeout",
                "dropgame.trade_cancelled", "dropgame.wrong_amount",
                "gacha.congrats", "gacha.lucky", "gacha.unlucky",
                "opq.got_record", "opq.dropping_record", "opq.dropping_clouds",
                "training.break_sign"
        };
        Set<String> properNouns = Set.of("han", "cho");
        for (String key : keys) {
            String value = BotMessages.get(key);
            for (String word : value.toLowerCase().split("[^a-zA-Z]+")) {
                if (word.length() >= 3 && !properNouns.contains(word)) {
                    fail(key + " should be localized but resolved to: " + value);
                }
            }
        }
    }

    // The reported bug: the dealer asked "What will you do?" in English on a Chinese server.
    @Test
    void dealerTurnPromptIsLocalized() {
        SoloMaplingLanguageConfig.setLanguageTag("zh-CN");
        assertEquals("小明：17。你要怎么做？",
                BotMessages.get("blackjack.your_turn", "小明", 17));
        SoloMaplingLanguageConfig.setLanguageTag("en-US");
        assertEquals("Thumper: 17. What will you do?",
                BotMessages.get("blackjack.your_turn", "Thumper", 17));
    }

    // Result lines are assembled per player by BlackjackRules.formatOutcomeMessage(), so they
    // must be read from the message pack rather than built with English literals.
    @Test
    void outcomeLinesAreLocalized() {
        SoloMaplingLanguageConfig.setLanguageTag("zh-CN");
        assertEquals("小明：二十一点！",
                BlackjackRules.formatOutcomeMessage("小明",
                        BlackjackRules.Outcome.BLACKJACK_WIN, List.of("AH", "KD"), List.of("2H", "3D")));
        assertEquals("小明：平局。",
                BlackjackRules.formatOutcomeMessage("小明",
                        BlackjackRules.Outcome.PUSH, List.of("AH", "9D"), List.of("2H", "8D")));
        SoloMaplingLanguageConfig.setLanguageTag("en-US");
        assertEquals("Thumper: Blackjack!",
                BlackjackRules.formatOutcomeMessage("Thumper",
                        BlackjackRules.Outcome.BLACKJACK_WIN, List.of("AH", "KD"), List.of("2H", "3D")));
    }
}
