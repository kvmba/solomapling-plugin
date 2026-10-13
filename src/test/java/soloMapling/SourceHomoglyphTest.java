package soloMapling;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bans Cyrillic and Greek characters from the plugin's Java <em>code</em>.
 *
 * <p>A commit in the movement audit rewrote {@code BotFinalAttack.maybeTrigger}'s signature with
 * visually identical Cyrillic letters ({@code w}-U+0435-etc instead of ASCII "weapon"), so the
 * parameter name looked correct in every editor and review but no longer matched the ASCII word.
 * A mixed-script identifier is invisible at a glance and silently survives global find/replace,
 * {@code grep weapon} and rename refactors: the defect it invites is a future edit that misses
 * the one file, or a copied homoglyph spreading.
 *
 * <p>Comments and string literals are excluded on purpose - that is where legitimate non-ASCII
 * lives (CJK dialogue, and math symbols such as the delta in SpotFinder's "mean |Δx|" comment),
 * and a character there cannot be mistaken for part of an identifier. Only the code itself must
 * be free of the two Latin-look-alike blocks.
 */
class SourceHomoglyphTest {

    @Test
    void javaCodeCarriesNoCyrillicOrGreekHomoglyphs() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path root : sourceRoots()) {
            try (Stream<Path> stream = Files.walk(root)) {
                for (Path p : stream.filter(f -> f.toString().endsWith(".java")).toList()) {
                    String code = stripCommentsAndLiterals(Files.readString(p, StandardCharsets.UTF_8));
                    for (int i = 0; i < code.length(); i++) {
                        if (!isBanned(code.charAt(i))) {
                            continue;
                        }
                        int line = code.substring(0, i).split("\n", -1).length;
                        offenders.add(p + ":" + line + " carries U+"
                                + String.format("%04X", (int) code.charAt(i)));
                        break; // one report per file is enough to point at it
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "Cyrillic/Greek characters in code can hide inside Latin identifiers and defeat "
                        + "grep/rename; replace with the ASCII letter they imitate: " + offenders);
    }

    private static boolean isBanned(char c) {
        return (c >= 0x0400 && c <= 0x04FF) || (c >= 0x0370 && c <= 0x03FF);
    }

    /**
     * Removes comments and string/char literals, preserving line breaks so reported line numbers
     * stay accurate. Small state machine: no parser, and a mis-lexed edge case can only make the
     * scan more permissive (a literal kept as code) or stricter (real code treated as prose) -
     * both surface as a clear failure rather than a silent pass.
     */
    static String stripCommentsAndLiterals(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            char next = i + 1 < n ? source.charAt(i + 1) : '\0';
            if (c == '/' && next == '/') {
                while (i < n && source.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && next == '*') {
                i += 2;
                while (i + 1 < n && !(source.charAt(i) == '*' && source.charAt(i + 1) == '/')) {
                    if (source.charAt(i) == '\n') {
                        out.append('\n');
                    }
                    i++;
                }
                i = Math.min(n, i + 2);
            } else if (c == '"' || c == '\'') {
                char quote = c;
                i++;
                while (i < n) {
                    char d = source.charAt(i);
                    if (d == '\\') {
                        i += 2;
                        continue;
                    }
                    if (d == '\n') {
                        out.append('\n'); // unterminated literal: keep the line count honest
                    }
                    if (d == quote) {
                        i++;
                        break;
                    }
                    i++;
                }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** Both source trees, resolving the repo root whether the suite runs here or from the parent. */
    private static List<Path> sourceRoots() {
        for (String prefix : new String[]{"src", "../src"}) {
            Path main = Paths.get(prefix, "main", "java");
            Path test = Paths.get(prefix, "test", "java");
            if (Files.isDirectory(main) && Files.isDirectory(test)) {
                return List.of(main, test);
            }
        }
        throw new IllegalStateException("cannot locate src/main/java from " + Paths.get(".").toAbsolutePath());
    }
}
