package soloMapling.ArtificialPlayer;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source guards for the cross-thread collection rule that the performance pass fixed.
 *
 * <p>{@code MapleMap.getCharacters()} returns the map's LIVE {@code LinkedHashSet} view under a
 * read lock, then releases that lock before the caller iterates. Players join and leave the map on
 * other threads under the write lock, so iterating the live view off-lock can throw
 * {@code ConcurrentModificationException} or observe a half-updated set - and the plugin's
 * call sites are all either bot ticks, the chat dispatcher pool, or command threads.
 * {@code MapleMap.getAllPlayers()} is the host's snapshot API (a copy taken under that lock) and is
 * the only safe form. A regression here is silent (the exception is usually caught) but loses
 * players from scans, so it is pinned at the source level: the plugin classes cannot be
 * instantiated without a running host.
 */
class CrossThreadCollectionRuleTest {

    /** Files whose getCharacters() use was deliberately reviewed and converted. */
    private static final List<String> CONVERTED = List.of(
            "src/main/java/soloMapling/ArtificialPlayer/BotSM.java",
            "src/main/java/soloMapling/ArtificialPlayer/BotMessagingSystem/Dispatcher.java",
            "src/main/java/soloMapling/ArtificialPlayer/BotBuffRequestSystem/BotBuffRequestHandler.java",
            "src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotBuffEffects.java",
            "src/main/java/soloMapling/ArtificialPlayer/BotTypes/CompanionBot.java",
            "src/main/java/soloMapling/command/ArtificialPlayerCommand.java");

    @Test
    void noPluginCodeIteratesTheLiveMapCharacterView() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(Paths.get("src/main/java"))) {
            for (Path p : stream.filter(f -> f.toString().endsWith(".java")).toList()) {
                String code = stripCommentsAndLiterals(Files.readString(p, StandardCharsets.UTF_8));
                if (code.contains("getCharacters()")) {
                    offenders.add(p.toString());
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "getCharacters() hands back the map's live set after releasing its lock; iterate "
                        + "the snapshot getAllPlayers() instead. Offenders: " + offenders);
    }

    @Test
    void theConvertedCallSitesStillUseTheSnapshotApi() throws IOException {
        for (String file : CONVERTED) {
            String code = stripCommentsAndLiterals(
                    Files.readString(Paths.get(file), StandardCharsets.UTF_8));
            assertTrue(code.contains("getAllPlayers()"),
                    file + " must iterate the map snapshot (getAllPlayers) for its player scan");
        }
    }

    @Test
    void spotClaimsNeverReintroduceAProcessWideMonitor() throws IOException {
        String code = stripCommentsAndLiterals(Files.readString(
                Paths.get("src/main/java/soloMapling/ArtificialPlayer/BotSpotClaims.java"),
                StandardCharsets.UTF_8));
        // The claim registry is read/poked per candidate spot on every combat tick; a single
        // process-wide monitor serialized the whole world's grind ticks (see the class javadoc).
        assertFalse(code.contains("static synchronized"),
                "BotSpotClaims must stay per-map locked, not process-wide synchronized");
    }

    // Same stripping rule as SourceHomoglyphTest: only code is inspected, comments and string
    // literals may legitimately mention the old method name.
    private static String stripCommentsAndLiterals(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            char next = i + 1 < n ? source.charAt(i + 1) : '\0';
            if (c == '/' && next == '/') {
                while (i < n && source.charAt(i) != '\n') i++;
            } else if (c == '/' && next == '*') {
                i += 2;
                while (i + 1 < n && !(source.charAt(i) == '*' && source.charAt(i + 1) == '/')) i++;
                i = Math.min(n, i + 2);
            } else if (c == '"' || c == '\'') {
                char quote = c;
                out.append(' ');
                i++;
                while (i < n) {
                    char d = source.charAt(i);
                    if (d == '\\') {
                        i += 2;
                        continue;
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
}
