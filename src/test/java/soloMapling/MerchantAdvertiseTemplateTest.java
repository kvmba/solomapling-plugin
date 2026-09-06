package soloMapling;

import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotDialogueHandler;
import soloMapling.Environment.SoloMaplingLanguageConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Merchant advertise templates must never reach chat un-substituted.
 *
 * <p>Merchants shout from {@code SellAdvertise}/{@code BuyAdvertise} templates where
 * {@code %ITEM%} and {@code %PRICE%} are filled at speak time. When the substitution is missing on
 * any one path the bot shouts a literal "%PRICE%" - which is exactly what shipped: SellingMerchantBot
 * replaced {@code %ITEM%} but not {@code %PRICE%}, so every added template that quoted a price
 * leaked.
 *
 * <p>These merchants need a live Character, so they cannot be constructed in a unit test. Instead
 * this pins the contract from both ends: the templates are placeholders, and each consuming class
 * substitutes both of them.
 */
class MerchantAdvertiseTemplateTest {

    private static final String MERCHANT_PATH = "MerchantBotDialogue.yaml";
    private static final String MERCHANT_TYPE = "MerchantBot";

    private static final Path SELLING_SRC = Paths.get(
            "src/main/java/soloMapling/ArtificialPlayer/BotTypes/SellingMerchantBot.java");
    private static final Path BUYING_SRC = Paths.get(
            "src/main/java/soloMapling/ArtificialPlayer/BotTypes/BuyingMerchantBot.java");

    @Test
    void sellAdvertiseIsTemplateDriven() {
        List<String> lines = node("SellAdvertise");
        assertFalse(lines.isEmpty());
        long withPlaceholders = lines.stream()
                .filter(s -> s.contains("%ITEM%") || s.contains("%PRICE%")).count();
        assertTrue(withPlaceholders > 0, "SellAdvertise should be templates, not finished lines");
    }

    @Test
    void buyAdvertiseIsTemplateDriven() {
        List<String> lines = node("BuyAdvertise");
        assertFalse(lines.isEmpty());
        long withPlaceholders = lines.stream()
                .filter(s -> s.contains("%ITEM%") || s.contains("%PRICE%")).count();
        assertTrue(withPlaceholders > 0, "BuyAdvertise should be templates, not finished lines");
    }

    // The regression: SellingMerchantBot built the shout from a template without ever replacing
    // %PRICE%, so a merchant advertising a priced item read "出 白卷 %PRICE%".
    @Test
    void sellingMerchantSubstitutesThePrice() throws IOException {
        String src = read(SELLING_SRC);
        assertTrue(src.contains("replace(\"%PRICE%\""),
                "SellingMerchantBot must substitute %PRICE% - otherwise the placeholder is shouted");
        assertTrue(src.contains("replace(\"%ITEM%\""),
                "SellingMerchantBot must substitute %ITEM%");
    }

    @Test
    void buyingMerchantSubstitutesBoth() throws IOException {
        String src = read(BUYING_SRC);
        assertTrue(src.contains("replace(\"%PRICE%\""), "BuyingMerchantBot must substitute %PRICE%");
        assertTrue(src.contains("replace(\"%ITEM%\""), "BuyingMerchantBot must substitute %ITEM%");
    }

    // A template whose placeholder no code path fills would leak verbatim, so every advertised
    // placeholder must be one of the two the merchants handle.
    @Test
    void templatesOnlyUseKnownPlaceholders() {
        for (String node : new String[]{"SellAdvertise", "BuyAdvertise"}) {
            for (String line : node(node)) {
                String stripped = line.replace("%ITEM%", "").replace("%PRICE%", "");
                assertFalse(stripped.contains("%"),
                        node + " has an unknown placeholder that nothing substitutes: " + line);
            }
        }
    }

    private static List<String> node(String name) {
        SoloMaplingLanguageConfig.setLanguageTag("zh-CN");
        var con = BotDialogueHandler.getDialogueCon(MERCHANT_PATH, MERCHANT_TYPE, name);
        assertNotNull(con, name + " node should exist");
        return con.getDialogue();
    }

    private static String read(Path p) throws IOException {
        assertTrue(Files.exists(p), "expected source file at " + p);
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }
}
