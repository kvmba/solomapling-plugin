package soloMapling;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.DialoguePackPaths;
import soloMapling.Environment.SoloMaplingLanguageConfig;

import java.io.BufferedReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the scene split of {@code TownChatterDialogue.yaml}.
 *
 * <p>The file has two sections. {@code exchanges} is the town pool; {@code vehicle} is the aboard pool
 * BotChatter picks only while a participant is on a vehicle map (see {@code BotChatter.pickExchange}
 * + {@code GCTransit.isVehicleMap}). Both sections are drawn at random with no per-vehicle filter, so a
 * line must stay agnostic to the scene: a town line must not name a vehicle (or a town street shows
 * "you also waiting for this boat?"), and a vehicle line must not name a specific ride (or a boat line
 * plays in a subway). This test pins both directions, reading the sections directly.
 */
class TownChatterSceneTest {

    // Vehicle / crew / boarding nouns. Word-bounded so ordinary words don't trip it ("training").
    // "harbor" is deliberately absent — it names a place type that is legitimate town talk.
    private static final Pattern ASCII_NOUN = Pattern.compile(
            "\\b(boat|ship|ferry|sail|deck|cabin|dock|pier|"
                    + "train|railway|carriage|bus|subway|metro|platform|"
                    + "plane|flight|airplane|aircraft|takeoff|runway|"
                    + "cab|taxi|driver|elevator)s?\\b",
            Pattern.CASE_INSENSITIVE);

    // CJK has no word boundaries; unambiguous vehicle/crew/boarding nouns.
    private static final String[] CJK_NOUNS = {
            "船", "舟", "艇", "渡", "码头", "候船", "甲板", "船舱", "靠岸", "登船", "舷",
            "火车", "列车", "车厢", "铁轨", "站台", "地铁", "飞机", "航班", "登机", "电梯", "出租", "司机"};

    @AfterEach
    void resetLanguage() {
        SoloMaplingLanguageConfig.setLanguageTag(SoloMaplingLanguageConfig.DEFAULT);
    }

    @Test
    void townSectionNamesNoVehicle() throws Exception {
        for (String tag : new String[]{"en-US", "zh-CN"}) {
            SoloMaplingLanguageConfig.setLanguageTag(tag);
            for (String entry : sectionEntries("exchanges")) {
                assertNoVehicleNoun(tag, "town", entry);
            }
        }
    }

    @Test
    void vehicleSectionNamesNoVehicle() throws Exception {
        // The aboard pool serves every ride, so it must not name one either.
        for (String tag : new String[]{"en-US", "zh-CN"}) {
            SoloMaplingLanguageConfig.setLanguageTag(tag);
            for (String entry : sectionEntries("vehicle")) {
                assertNoVehicleNoun(tag, "vehicle", entry);
            }
        }
    }

    @Test
    void bothSectionsArePopulated() throws Exception {
        // A silently emptied section would fall back to the hardcoded English exchange (or, for the
        // vehicle pool, to the town pool), so pin that both are actually seeded in both languages.
        for (String tag : new String[]{"en-US", "zh-CN"}) {
            SoloMaplingLanguageConfig.setLanguageTag(tag);
            assertTrue(sectionEntries("exchanges").size() >= 100,
                    tag + " town section looks emptied");
            assertTrue(sectionEntries("vehicle").size() >= 10,
                    tag + " vehicle section looks emptied");
        }
    }

    private static void assertNoVehicleNoun(String tag, String section, String entry) {
        Matcher m = ASCII_NOUN.matcher(entry);
        boolean asciiHit = m.find();
        assertFalse(asciiHit,
                tag + " " + section + " chatter names a vehicle (" + (asciiHit ? m.group() : "") + "): " + entry);
        for (String noun : CJK_NOUNS) {
            assertFalse(entry.contains(noun),
                    tag + " " + section + " chatter names a vehicle (" + noun + "): " + entry);
        }
    }

    // The "- [..]" exchange entries under a top-level "<section>:" key (until the next top-level key).
    // Read directly rather than via TownChatterLines: its cache is language-independent, so it would
    // serve the first-loaded language for both tags.
    private static List<String> sectionEntries(String section) throws Exception {
        String key = section + ":";
        List<String> out = new ArrayList<>();
        boolean in = false;
        try (Reader r = DialoguePackPaths.openDialogueReader("TownChatterDialogue.yaml");
             BufferedReader br = new BufferedReader(r)) {
            String line;
            while ((line = br.readLine()) != null) {
                String s = line.trim();
                if (s.startsWith(key)) {
                    in = true;
                    continue;
                }
                if (in && !line.isEmpty() && !Character.isWhitespace(line.charAt(0))) {
                    in = false; // a new top-level key ends the section
                }
                if (in && s.startsWith("- [")) {
                    out.add(s);
                }
            }
        }
        return out;
    }
}
