package soloMapling;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Job names spoken by bots must follow the server language.
 *
 * <p>{@code GameConstants.getJobName(id)} derives the name from the Java enum constant and
 * capitalises it, so it yields English ("Magician", "Bowman") on every server - which is how bots
 * came to say "玩 Magician 打成这样" inside otherwise Chinese lines. {@code Job.getName()} is the
 * localised name, loaded from the server's i18n message bundle ("英雄", "弓箭手").
 *
 * <p>Resolving a job needs a live Character, so this pins the contract against the source (and
 * against the host's own i18n bundle where reachable) rather than constructing one.
 */
class JobNameLocalizationTest {

    private static final Path RESOLVER = Paths.get(
            "src/main/java/soloMapling/ArtificialPlayer/DialogueContextResolver.java");

    @Test
    void jobNameUsesTheLocalizedJobEnum() throws IOException {
        String src = read(RESOLVER);
        assertTrue(src.contains("job.getName()"),
                "jobName() must read Job.getName() - the localised name");
        // Code only: the javadoc explains why GameConstants is NOT used, so it names it in prose.
        assertTrue(code(src).contains("GameConstants.getJobName") == false,
                "GameConstants.getJobName() always returns English; do not reintroduce the call");
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

    // If the host bundle has no Chinese for a job, the line is dropped rather than spoken with a
    // half-English word - so a missing entry degrades safely. This check just documents/asserts the
    // bundle is actually populated, since an empty bundle would silently drop every job line.
    @Test
    void hostChineseBundleHasJobNames() {
        Path bundle = Paths.get("../GMS083/gms-server/src/main/resources/i18n/message_zh_CN.properties");
        if (!Files.exists(bundle)) {
            return; // host checkout not adjacent; nothing to assert
        }
        String text = readQuietly(bundle);
        assertTrue(text.contains("job.name.0"), "expected job names in the zh-CN bundle");
        assertTrue(text.contains("英雄") || text.contains("弓箭手") || text.contains("魔法师"),
                "zh-CN job names should be Chinese, got: " + preview(text));
    }

    private static String read(Path p) throws IOException {
        assertTrue(Files.exists(p), "expected file at " + p);
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    private static String readQuietly(Path p) {
        try {
            return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static String preview(String text) {
        for (String line : text.split("\n")) {
            if (line.startsWith("job.name.")) {
                return line;
            }
        }
        return "(no job.name entries)";
    }
}
