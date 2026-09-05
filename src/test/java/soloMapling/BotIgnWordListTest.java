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

    // Characters the v83-era client font can actually draw. A name is rendered with the game's
    // own font, so anything outside this set shows up as '?' or a tofu box - which is exactly
    // how "?等等等等" reached players: the pool had picked up U+10E6 GEORGIAN (ღ), modern-looking
    // dingbats (✨ ❥ ❦ ❧) and spare Misc Symbols (☀ ☁ ☂ ☃ ◈ ◉) that no 2008-2010 client can draw.
    //
    // Deliberately conservative: an unusual glyph renders as garbage, whereas a plain name is
    // always fine. When adding a symbol, check it against the fonts an old MapleStory client
    // uses (宋体 / MS Gothic), not against a modern system font.
    private static final Set<Integer> ALLOWED_SYMBOLS = Set.of(
            0x4E36,  // 丶
            0x7070,  // 灬
            0x4E28,  // 丨
            0x306E,  // の
            0x309B,  // ゛
            0x309C,  // ゜
            0x301C,  // 〜
            0xFF5E,  // ～
            0x00B0,  // °
            0x266A,  // ♪
            0x2605,  // ★
            0x2606,  // ☆
            0x2661,  // ♡
            0x2665,  // ♥
            0x273F,  // ✿
            0x2740,  // ❀
            0x2741,  // ❁
            0x273E,  // ✾
            0x25C6,  // ◆
            0x25C7,  // ◇
            0x25CB,  // ○
            0x25CF   // ●
    );

    private static void assertRenderable(String name) {
        for (int i = 0; i < name.length(); i++) {
            int cp = name.codePointAt(i);
            Character.UnicodeBlock block = Character.UnicodeBlock.of(cp);
            if (block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS) {
                continue;
            }
            if (block == Character.UnicodeBlock.BASIC_LATIN) {
                boolean alnum = (cp >= 'a' && cp <= 'z') || (cp >= 'A' && cp <= 'Z')
                        || (cp >= '0' && cp <= '9');
                assertTrue(alnum,
                        "name '" + name + "' has ASCII punctuation '" + (char) cp
                                + "' - names may not carry . / \\ $ ? ~ * ( ) [ ] or similar");
                continue;
            }
            assertTrue(ALLOWED_SYMBOLS.contains(cp),
                    String.format("name '%s' has U+%04X (%s), which the v83 client font cannot "
                            + "render - it would show as '?' in game", name, cp, block));
        }
    }

    // Guards against the specific regression: no name may display a '?' or tofu box.
    @Test
    void noNameContainsUnrenderableGlyphs() throws IOException {
        for (String name : read(LOCALIZED)) {
            assertRenderable(name);
        }
    }

    @Test
    void namesAreUnique() throws IOException {
        List<String> names = read(LOCALIZED);
        Set<String> unique = new HashSet<>(names);
        assertEquals(names.size(), unique.size(),
                "duplicated names shrink the pool and make bots share identities");
    }

    @Test
    void someNamesCarryDecorationButMostDoNot() throws IOException {
        List<String> names = read(LOCALIZED);
        long decorated = names.stream().filter(BotIgnWordListTest::isDecorated).count();
        double pct = decorated * 100.0 / names.size();
        assertTrue(decorated > 0, "some names should carry a symbol, as real players do");
        // A list where every name is decorated reads as machine-generated spam.
        assertTrue(pct < 40.0,
                "too many decorated names: " + String.format("%.1f", pct) + "%");
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

    /** True when the name carries a symbol outside ASCII and CJK (note, star, kaomoji, ...). */
    private static boolean isDecorated(String s) {
        return s.codePoints().anyMatch(c -> {
            Character.UnicodeBlock b = Character.UnicodeBlock.of(c);
            return b != Character.UnicodeBlock.BASIC_LATIN
                    && b != Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS;
        });
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
