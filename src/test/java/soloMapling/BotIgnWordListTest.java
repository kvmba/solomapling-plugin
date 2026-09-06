package soloMapling;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.Environment.PluginResources;
import soloMapling.FreeMarket.FMShopDescGen;
import soloMapling.Environment.SoloMaplingLanguageConfig;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the localized bot-IGN word list.
 *
 * <p>This file used to be documented as "never translated, v83 names are latin-only". That is not
 * true for this server - {@code CompanionProvisioningInput} accepts CJK and
 * {@code FMShopDescGen.displayWidth()} already lays CJK out at two cells - so the zh-CN pack ships
 * Chinese names. The constraints below are the ones that actually matter, and they are easy to
 * break by hand-editing the list.
 *
 * <p>Each test spawns nothing and touches no pool: {@code FMShopDescGen} caches its name pool
 * statically, so per-process language switching cannot be asserted here. These checks run against
 * the file itself, which is where a bad entry would be introduced.
 */
class BotIgnWordListTest {

    private static final String LOCALIZED =
            "FreeMarket/FMNameDesc-zh-CN/randomRealMaplestoryIGNs.txt";

    @AfterEach
    void reset() {
        SoloMaplingLanguageConfig.setLanguageTag(SoloMaplingLanguageConfig.DEFAULT);
    }

    @Test
    void chinesePackExists() {
        assertTrue(PluginResources.exists(LOCALIZED),
                "zh-CN should ship a localized IGN list instead of falling back to English");
    }

    @Test
    void chinesePackIsPreferredWhenLanguageIsChinese() {
        SoloMaplingLanguageConfig.setLanguageTag("zh-CN");
        assertEquals(LOCALIZED, soloMapling.Environment.LocalizedResources.resolve(
                "FreeMarket/", "FMNameDesc", "randomRealMaplestoryIGNs.txt"));
    }

    @Test
    void englishStillResolvesEnglishList() {
        SoloMaplingLanguageConfig.setLanguageTag("en-US");
        assertEquals("FreeMarket/FMNameDesc/randomRealMaplestoryIGNs.txt",
                soloMapling.Environment.LocalizedResources.resolve(
                        "FreeMarket/", "FMNameDesc", "randomRealMaplestoryIGNs.txt"));
    }

    // The pool caches its language (see FMShopDescGen.namePoolLanguage), so this is safe within a
    // single process: a switch rebuilds the pool instead of silently reusing the old one.
    @Test
    void chinesePackSuppliesChineseNames() {
        SoloMaplingLanguageConfig.setLanguageTag("zh-CN");
        List<String> drawn = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            drawn.add(FMShopDescGen.getRandomCharacterIGN());
        }
        long cjk = drawn.stream().filter(BotIgnWordListTest::containsCjk).count();
        assertEquals(drawn.size(), cjk,
                "zh-CN should draw Chinese names, got: " + drawn);
    }

    @Test
    void englishPackStillSuppliesEnglishNames() {
        SoloMaplingLanguageConfig.setLanguageTag("en-US");
        List<String> drawn = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            drawn.add(FMShopDescGen.getRandomCharacterIGN());
        }
        long cjk = drawn.stream().filter(BotIgnWordListTest::containsCjk).count();
        assertEquals(0, cjk, "en-US should not draw Chinese names, got: " + drawn);
    }

    @Test
    void everyNameIsUsable() throws IOException {
        List<String> names = read(LOCALIZED);
        assertFalse(names.isEmpty(), "localized IGN list should not be empty");
        for (String name : names) {
            int width = displayWidth(name);
            assertTrue(width >= 8 && width <= 12,
                    "name '" + name + "' has display width " + width + ", expected 8-12");
            // FMShopDescGen.loadAndShuffleNames() drops lines longer than 12 chars, so a name
            // that trips this would silently never be drawn.
            assertTrue(name.length() <= 12,
                    "name '" + name + "' exceeds the 12-char loader limit (" + name.length() + ")");
            assertFalse(name.contains(" ") || name.contains("\t"),
                    "name '" + name + "' contains whitespace");
            assertTrue(containsCjk(name), "name '" + name + "' has no Chinese characters");
            assertRenderable(name);
        }
    }

    // Mirrors CompanionProvisioningInput.STROKE_DECORATION. These sit inside the CJK block, so a
    // "is it a Chinese character?" check lets them through - they have to be named one by one.
    // Keep the two lists in step: a stroke character allowed here is a name the provisioner
    // would reject, and the pool and the validator would disagree about what is legal.
    private static final Set<Character> STROKE_DECORATION = Set.of(
            '\u4e36',  // 丶
            '\u4e28',  // 丨
            '\u706c',  // 灬
            '\u4e3f',  // 丿
            '\u4e40',  // 乀
            '\u4e85',  // 亅
            '\u5f61',  // 彡
            '\u4e42'   // 乂
    );

    // Allowed characters, full stop: ASCII letters, ASCII digits, and simplified-Chinese
    // ideographs (CJK Unified Ideographs, U+4E00-U+9FA5), minus the stroke decoration above.
    // Nothing else is a legal name.
    //
    // This used to whitelist a set of "decoration" symbols (♪ ★ ♡ の ゛ ゜ 〜 ° 丶 灬 丨 ...)
    // because real players decorated names that way. They are gone now: any of them can come
    // out of the v83 client font as '?' or a tofu box, and a name that renders as garbage is
    // worse than a plain one. A plain name is always fine.
    //
    // The whitelist is deliberately closed rather than "reject known-bad": an unrecognised
    // glyph must fail the build, not ship.
    private static boolean isAllowed(char ch) {
        if (STROKE_DECORATION.contains(ch)) {
            return false;
        }
        return (ch >= 'a' && ch <= 'z')
                || (ch >= 'A' && ch <= 'Z')
                || (ch >= '0' && ch <= '9')
                || (ch >= '\u4e00' && ch <= '\u9fa5');
    }

    private static void assertRenderable(String name) {
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            assertTrue(isAllowed(ch),
                    String.format("name '%s' has U+%04X (%s) - only A-Z, a-z, 0-9 and simplified "
                            + "Chinese ideographs are allowed", name, (int) ch,
                            Character.UnicodeBlock.of(ch)));
        }
    }

    // Guards against the specific regression: no name may display a '?' or tofu box.
    @Test
    void noNameContainsUnrenderableGlyphs() throws IOException {
        for (String name : read(LOCALIZED)) {
            assertRenderable(name);
        }
    }

    // The rule is a whitelist, so invisible ASCII is rejected with everything else - but it is
    // the failure nobody notices by eye (a name looks fine in an editor and breaks in game),
    // so assert it directly rather than trusting the whitelist to cover it.
    @Test
    void noNameContainsInvisibleOrControlCharacters() throws IOException {
        for (String name : read(LOCALIZED)) {
            for (int i = 0; i < name.length(); i++) {
                char ch = name.charAt(i);
                boolean invisible = ch < 0x20 || ch == 0x7F
                        || Character.isWhitespace(ch) || Character.isSpaceChar(ch);
                assertFalse(invisible,
                        String.format("name '%s' has an invisible/control character U+%04X at "
                                + "index %d", name, (int) ch, i));
            }
        }
    }

    @Test
    void namesAreUnique() throws IOException {
        List<String> names = read(LOCALIZED);
        Set<String> unique = new HashSet<>(names);
        assertEquals(names.size(), unique.size(),
                "duplicated names shrink the pool and make bots share identities");
    }

    // Mirrors FMShopDescGen.displayWidth(): CJK counts as two cells.
    private static int displayWidth(String s) {
        int width = 0;
        for (int i = 0; i < s.length(); i++) {
            width += Character.UnicodeBlock.of(s.charAt(i))
                    == Character.UnicodeBlock.BASIC_LATIN ? 1 : 2;
        }
        return width;
    }

    private static boolean containsCjk(String s) {
        return s.codePoints().anyMatch(c -> Character.UnicodeBlock.of(c)
                == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS);
    }

    private static List<String> read(String path) throws IOException {
        List<String> out = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(PluginResources.openReader(path))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isEmpty()) {
                    out.add(line);
                }
            }
        }
        return out;
    }
}
