package soloMapling;

import com.esotericsoftware.yamlbeans.YamlReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.DialoguePackPaths;
import soloMapling.Environment.SoloMaplingLanguageConfig;

import java.io.Reader;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The merchant bots build their FM shouts by substituting into these nodes, so a pack that drops
 * a node or a placeholder would silently mute the bots (or leak a raw {@code %ITEM%}) at runtime.
 */
class MerchantDialogueNodesTest {

    @AfterEach
    void reset() {
        SoloMaplingLanguageConfig.setLanguageTag(SoloMaplingLanguageConfig.DEFAULT);
    }

    @SuppressWarnings("unchecked")
    private static List<String> readNodeText(String file, String botType, String node) throws Exception {
        try (Reader reader = DialoguePackPaths.openDialogueReader(file)) {
            Map<String, Object> root = (Map<String, Object>) new YamlReader(reader).read();
            Map<String, Object> types = (Map<String, Object>) root.get(botType);
            assertNotNull(types, botType + " missing from " + file);
            Map<String, Object> nodeMap = (Map<String, Object>) types.get(node);
            assertNotNull(nodeMap, node + " missing from " + file);
            return (List<String>) nodeMap.get("text");
        }
    }

    private static void assertEveryLineHas(List<String> lines, String placeholder, String label) {
        assertNotNull(lines, label + " has no text entries");
        assertFalse(lines.isEmpty(), label + " has no text entries");
        for (String line : lines) {
            assertTrue(line.contains(placeholder), label + " line missing " + placeholder + ": " + line);
        }
    }

    private void assertMerchantAdvertisePlaceholders(String languageTag) throws Exception {
        SoloMaplingLanguageConfig.setLanguageTag(languageTag);
        String file = "MerchantBotDialogue.yaml";

        assertEveryLineHas(readNodeText(file, "MerchantBot", "SellAdvertise"),
                "%ITEM%", languageTag + " SellAdvertise");

        List<String> buyLines = readNodeText(file, "MerchantBot", "BuyAdvertise");
        assertEveryLineHas(buyLines, "%ITEM%", languageTag + " BuyAdvertise");
        assertEveryLineHas(buyLines, "%PRICE%", languageTag + " BuyAdvertise");
    }

    @Test
    void englishPackKeepsAdvertisePlaceholders() throws Exception {
        assertMerchantAdvertisePlaceholders("en-US");
    }

    @Test
    void chinesePackKeepsAdvertisePlaceholders() throws Exception {
        assertMerchantAdvertisePlaceholders("zh-CN");
    }

    @Test
    void chinesePackDefinesNxHandoffNodes() throws Exception {
        SoloMaplingLanguageConfig.setLanguageTag("zh-CN");
        for (String node : List.of("NXAdvertise", "NXContactNotice", "NXCodeHandoff",
                "NXCodeThanks", "NXInviteIgnored")) {
            List<String> lines = readNodeText("MerchantBotDialogue.yaml", "MerchantBot", node);
            assertNotNull(lines, node + " has no text entries");
            assertFalse(lines.isEmpty(), node + " has no text entries");
        }
    }

    @Test
    void chinesePackDefinesShopOfferNodes() throws Exception {
        SoloMaplingLanguageConfig.setLanguageTag("zh-CN");
        assertEveryLineHas(readNodeText("ShopOfferDialogue.yaml", "ShopOffer", "AFKPriceUpdate"),
                "{price}", "zh-CN AFKPriceUpdate");

        List<String> hint = readNodeText("ShopOfferDialogue.yaml", "ShopOffer", "OfferHint");
        assertNotNull(hint);
        assertFalse(hint.isEmpty());
    }
}
