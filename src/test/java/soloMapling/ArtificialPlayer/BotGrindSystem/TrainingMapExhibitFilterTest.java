package soloMapling.ArtificialPlayer.BotGrindSystem;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Training-map discovery must not treat a map of CAGED EXHIBIT ANIMALS as a hunting ground.
 *
 * <p>Aquarium's zoo (map {@code 230000003}) holds six boss-flagged display mobs (9500200-9500204, exp
 * 0-10) placed purely for show. {@code MapMobIndex} used to lump every WZ {@code life} spawn into one
 * {@code mobCount}/{@code medianLevel}, so the zoo read as a normal level-58 map and lv46-80 training
 * bots picked it and swung at the animals — the exact defect this guards.
 *
 * <p>The fix splits the index into an all-spawns view ({@code medianLevel}/{@code mobCount}, still used
 * by town-presence and death/field consumers so a mob-bearing map is still "a field") and a HUNTABLE
 * view ({@code huntableMedianLevel}/{@code huntableCount}, grindable mobs only) that discovery keys off.
 * {@code MapMobIndex} cannot be instantiated here — {@code LifeFactory} needs a running host — so, as in
 * {@code JobNameLocalizationTest}, the contract is pinned against the source.
 */
class TrainingMapExhibitFilterTest {

    private static final Path FINDER = Paths.get(
            "src/main/java/soloMapling/ArtificialPlayer/BotGrindSystem/TrainingMapFinder.java");
    private static final Path INDEX = Paths.get(
            "src/main/java/soloMapling/ArtificialPlayer/BotGrindSystem/MapMobIndex.java");

    @Test
    void finderSelectsByTheHuntableViewNotTheAllSpawnsView() throws IOException {
        String code = code(read(FINDER));
        assertTrue(code.contains("huntableMedianLevel()"),
                "TrainingMapFinder must band maps by huntableMedianLevel() (grindable mobs), not the "
                        + "all-spawns medianLevel() that counts caged zoo animals");
        assertTrue(code.contains("huntableCount()"),
                "TrainingMapFinder must gate on huntableCount(), so an all-exhibit map drops out");
        // The old, defect-prone reads must be gone from the SELECTION gate. (medianLevel/mobCount may
        // still be documented in prose, hence the code-only check.)
        assertFalse(code.contains("info.medianLevel()"),
                "discovery must not band by the all-spawns medianLevel()");
        assertFalse(code.contains("info.mobCount()"),
                "discovery must not gate on the all-spawns mobCount()");
    }

    @Test
    void huntableExcludesBossAndExpLessMobs() throws IOException {
        String code = code(read(INDEX));
        // The classifier itself: a grindable mob is neither boss-flagged nor exp-less. Boss flags the
        // caged display animals; exp>0 drops exp-less script props that sit on otherwise-mobless maps.
        assertTrue(code.contains("!m.isBoss() && m.getExp() > 0"),
                "isHuntable must require !isBoss() && exp > 0 or the zoo's display animals count as prey");
        assertTrue(code.contains("huntableMedianLevel"),
                "MapMobInfo must carry a huntable median level");
        assertTrue(code.contains("huntableCount"),
                "MapMobInfo must carry a huntable count");
    }

    /** Strips comments so assertions match real calls, not prose about them. */
    private static String code(String src) {
        StringBuilder out = new StringBuilder();
        for (String line : src.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
                continue;
            }
            out.append(line).append('\n');
        }
        return out.toString();
    }

    private static String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
