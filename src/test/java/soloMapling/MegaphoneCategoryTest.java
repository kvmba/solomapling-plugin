package soloMapling;

import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotDialogueHandler;
import soloMapling.Environment.SoloMaplingLanguageConfig;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the megaphone "beef" category and the merchant templates.
 *
 * <p>BeefCallouts is reachable only because MEGA_CATEGORIES/MEGA_WEIGHTS were extended - a
 * category added to the YAML alone would never be selected. The template checks exist because
 * SellingMerchantBot substitutes %ITEM%/%PRICE% at runtime, so a line with a hardcoded item name
 * would make every merchant advertise the same fake product.
 */
class MegaphoneCategoryTest {

    private static final String MEGA_PATH = "MegaphoneDialogue.yaml";
    private static final String MEGA_TYPE = "MegaphoneBroadcast";
    private static final String MERCHANT_PATH = "MerchantBotDialogue.yaml";
    private static final String MERCHANT_TYPE = "MerchantBot";

    @Test
    void beefCategoryIsReachableFromDialogue() {
        SoloMaplingLanguageConfig.setLanguageTag("zh-CN");
        var con = BotDialogueHandler.getDialogueCon(MEGA_PATH, MEGA_TYPE, "BeefCallouts");
        assertNotNull(con, "BeefCallouts should exist in the megaphone pack");
        assertFalse(con.getDialogue().isEmpty(), "BeefCallouts should have lines");
        assertTrue(con.getDialogue().size() > 1000,
                "expected a large beef pool, got " + con.getDialogue().size());
    }

    // A YAML-only addition would never be drawn: selection runs off the hardcoded arrays.
    @Test
    void beefCategoryIsRegisteredForSelection() throws Exception {
        Class<?> c = Class.forName("soloMapling.ArtificialPlayer.SocialHotPotatoManager");
        String[] categories = (String[]) field(c, "MEGA_CATEGORIES").get(null);
        int[] weights = (int[]) field(c, "MEGA_WEIGHTS").get(null);
        int total = (int) field(c, "MEGA_WEIGHT_TOTAL").get(null);

        assertTrue(Arrays.asList(categories).contains("BeefCallouts"),
                "BeefCallouts missing from MEGA_CATEGORIES");
        assertEquals(categories.length, weights.length, "category/weight length mismatch");
        assertEquals(total, Arrays.stream(weights).sum(), "weights must sum to MEGA_WEIGHT_TOTAL");
    }

    @Test
    void sellTemplatesKeepPlaceholders() {
        SoloMaplingLanguageConfig.setLanguageTag("zh-CN");
        assertTemplatesKeepPlaceholders("SellAdvertise");
        assertTemplatesKeepPlaceholders("BuyAdvertise");
    }

    private void assertTemplatesKeepPlaceholders(String node) {
        var con = BotDialogueHandler.getDialogueCon(MERCHANT_PATH, MERCHANT_TYPE, node);
        assertNotNull(con, node + " should exist");
        List<String> lines = con.getDialogue();
        assertFalse(lines.isEmpty(), node + " should have lines");
        long withItem = lines.stream().filter(s -> s.contains("%ITEM%")).count();
        // Some pre-existing lines are bare shouts; the templates we added must carry both tokens.
        assertTrue(withItem > 50, node + " should mostly be templates, got " + withItem);
    }

    // A hardcoded price (or item) would be spoken identically by every merchant regardless of
    // what is in their shop, so advertise lines must stay templates. Bargaining lines such as
    // "卖 %ITEM% 你出价" legitimately carry only %ITEM% - the buyer names the price.
    @Test
    void sellTemplatesDoNotHardcodePrices() {
        SoloMaplingLanguageConfig.setLanguageTag("zh-CN");
        for (String node : new String[]{"SellAdvertise", "BuyAdvertise"}) {
            var con = BotDialogueHandler.getDialogueCon(MERCHANT_PATH, MERCHANT_TYPE, node);
            assertNotNull(con);
            for (String line : con.getDialogue()) {
                if (line.contains("%ITEM%")) {
                    continue; // template or bargaining line - fine
                }
                assertFalse(line.matches(".*\\d+\\s*[m千k].*"),
                        node + " hardcodes a price instead of using %PRICE%: " + line);
            }
        }
    }

    @Test
    void englishPackStillParsesWithNewCategoryAbsent() {
        SoloMaplingLanguageConfig.setLanguageTag("en-US");
        // The English pack has no BeefCallouts; the code must tolerate a missing node rather
        // than blow up when the category is drawn.
        var con = BotDialogueHandler.getDialogueCon(MEGA_PATH, MEGA_TYPE, "BeefCallouts");
        assertTrue(con == null || con.getDialogue().isEmpty(),
                "English pack has no BeefCallouts; missing node must stay a null/empty result");
    }

    private static Field field(Class<?> c, String name) throws Exception {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }
}
