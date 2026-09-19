package soloMapling.ArtificialPlayer.BotTownSystem;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ambient town bots (SocialBot's strolls, TownWandererBot's map family) gate their cross-map
 * destination on {@code MapMobIndex.isTown}: a map is a place they may stand/stroll into when it is
 * genuinely a town. That test must be "no mobs at all" OR "WZ town flag with nothing to grind" — NOT the
 * old "has any mob at all" rule, which walled off the Aquarium zoo (map {@code 230000003}, {@code town=1},
 * whose only spawns are caged boss-flagged display animals with no attack) and misread it as a level-58
 * field. The huntable guard on the flag clause keeps a real field that merely carries {@code town=1} out.
 *
 * <p>This pins the SELECTION contract against the source, as {@code MapMobIndex} itself cannot be
 * instantiated here ({@code LifeFactory} needs a running host) — the same approach as
 * {@code TrainingMapExhibitFilterTest}.
 */
class TownMapGateTest {

    private static final Path INDEX = Paths.get(
            "src/main/java/soloMapling/ArtificialPlayer/BotGrindSystem/MapMobIndex.java");
    private static final Path SOCIAL = Paths.get(
            "src/main/java/soloMapling/ArtificialPlayer/BotTypes/SocialBot.java");
    private static final Path WANDERER = Paths.get(
            "src/main/java/soloMapling/ArtificialPlayer/BotTypes/TownWandererBot.java");

    @Test
    void isTownIsMoblessOrTownFlaggedWithoutGrindables() throws IOException {
        String code = code(read(INDEX));
        assertTrue(code.contains("public static boolean isTown(int mapId)"),
                "MapMobIndex must expose isTown as the shared town test for the ambient town bots");
        assertTrue(code.contains("mobCount() == 0"),
                "isTown must keep the mobless-connector clause (subway entrance, elevators, shop floors)");
        assertTrue(code.contains(".town()"),
                "isTown must honor the WZ info/town flag (otherwise the zoo stays misread as a field)");
        assertTrue(code.contains("huntableCount() == 0"),
                "isTown must gate the town-flag clause on huntableCount()==0, so a real hunting field that "
                        + "merely carries town=1 (Herb Town's 251010000) is not opened to the town bots");
    }

    @Test
    void socialBotStrollsUseIsTownNotTheAllSpawnsLevel() throws IOException {
        String code = code(read(SOCIAL));
        assertTrue(code.contains("MapMobIndex.isTown(neighbor)"),
                "SocialBot.maybeStroll must gate its stroll destination on MapMobIndex.isTown");
        assertFalse(code.contains("MapMobIndex.level(neighbor)"),
                "SocialBot must not gate walks on the all-spawns level() that counts caged zoo animals");
    }

    @Test
    void wandererFamilyUsesIsTownNotTheAllSpawnsLevel() throws IOException {
        String code = code(read(WANDERER));
        assertTrue(code.contains("MapMobIndex.isTown(m)"),
                "TownWandererBot.discoverTownFamily must gate its map family on MapMobIndex.isTown");
        assertFalse(code.contains("MapMobIndex.level(m)"),
                "the wanderer must not gate its family on the all-spawns level()");
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
