package soloMapling.ArtificialPlayer.BotAttackSystem;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source-level guard for the "never swing at a friendly monster" rule.
 *
 * <p>{@code Monster.getStats().isFriendly()} is the WZ {@code damagedByMob=1} flag. The engine
 * paints such mobs like any other monster and lets damage land on them - there is no friendly
 * clause anywhere in {@code Monster.damage}. What makes them special is the quest scripts: HPQ's
 * {@code friendlyKilled} ends the whole instance five seconds after the Moon Bunny dies, and the
 * same hook is the FAILURE path in every other quest that spawns an escort. So a bot that swings
 * at one can lose the run for its party, which is exactly the failure this pins.</p>
 *
 * <p>{@code Character} needs a Spring context, so {@link BotAttackDriver} cannot be exercised in
 * a plain unit test (the same reason {@code BotBuffConfigSourceTest} and
 * {@code HideCancelAuditTest} read source). The check is structural: {@code seek(...)} is the ONE
 * candidate scan every swing path draws from - the PQ combat sweep, the grind brain and the GM
 * attack commands all reach it through {@code attack(...)} - so the friendly filter has to live
 * there, and it has to be applied before a candidate is added to the list.</p>
 */
class BotAttackFriendlyFilterTest {

    private static final Path DRIVER = Paths.get(
            "src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotAttackDriver.java");

    @Test
    void theSeekScanDropsFriendlyMonsters() throws IOException {
        String src = read(DRIVER);
        String seek = methodBody(src, "private static SeekScan seek(");
        assertTrue(seek.contains("isFriendly(m)"),
                "seek() must reject friendly monsters before they enter the candidate list");
        assertTrue(seek.contains("candidates.add(m)") && seek.indexOf("isFriendly(m)") < seek.indexOf("candidates.add(m)"),
                "the friendly gate has to run BEFORE the candidate is added, not after");
    }

    @Test
    void friendlyMeansTheWzDamagedByMobFlag() throws IOException {
        String src = read(DRIVER);
        String helper = methodBody(src, "private static boolean isFriendly(");
        assertTrue(helper.contains("getStats()") && helper.contains("isFriendly()"),
                "the rule is the engine's own stats flag, not a hard-coded mob id list");
    }

    /** No swing site may re-add a raw map scan that bypasses {@code seek(...)}. */
    @Test
    void noOtherScanFeedsSwingsWithoutTheFilter() throws IOException {
        String src = read(DRIVER);
        List<Integer> scans = new ArrayList<>();
        Matcher m = Pattern.compile("getMapObjectsInRange\\(").matcher(src);
        while (m.find()) {
            scans.add(m.start());
        }
        int seekAt = src.indexOf("private static SeekScan seek(");
        assertTrue(seekAt > 0, "seek() is the single candidate scan");
        for (int at : scans) {
            // The only allowed scan is the one inside seek(), after its friendly gate.
            assertTrue(at > seekAt,
                    "a second map scan outside seek() would bypass the friendly filter: offset " + at);
        }
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /** The source from {@code signature} to its closing brace at the same indent. */
    private static String methodBody(String src, String signature) {
        int start = src.indexOf(signature);
        assertFalse(start < 0, "method not found: " + signature);
        int depth = 0;
        int i = src.indexOf('{', start);
        for (; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return src.substring(start, i + 1);
            }
        }
        return src.substring(start);
    }
}
