package soloMapling.ArtificialPlayer.PartyQuest;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the contract every {@code workStage()} override answers: "is the whole run over?".
 *
 * <p>The base class acts on a true return by speaking the clear beat and warping the bot home
 * ({@code tickPartyQuest}). That makes a true return the single most destructive value in the
 * family - it ends the bot's participation - and it is easy to hand back accidentally, because
 * most of these methods only want to say "my part of this stage is finished" or "I got a spot".
 *
 * <p>Amoria's rope puzzles did exactly that: {@code return !plan.spots().isEmpty()} is true on
 * every tick the search has a candidate (which is always), so a bot reaching stage 2 walked out
 * of the instance immediately, every run. The bug was invisible to the pool-size tests because
 * nothing asserted the return value's meaning.
 *
 * <p>This test reads the overrides as source and requires every non-terminal one to return false
 * on the paths that mean "keep working". Assertions elsewhere in the family's history show how
 * narrow the exceptions are, so the allow-list below IS the review: a new quest may return true,
 * but only in a branch whose comment says the run is over.
 */
class PartyQuestWorkStageContractTest {

    /** Quest bots allowed to return true, and only from a branch that ends the run. */
    private static final List<String> MAY_END_THE_RUN = List.of(
            // The last stage's turn-in (all five stages cleared).
            "KerningPQBot.java",
            // The cleared exit after the boss falls.
            "PapulatusPQBot.java",
            // The expedition dissolved while the bot still stood in the queue hall.
            "AriantPQBot.java",
            // The last room's clear flag, read once the leader has left.
            "HenesysPQBot.java");

    @Test
    void onlyTheTerminalQuestsEverReportTheRunOver() throws Exception {
        List<String> offenders = new ArrayList<>();
        for (Path file : questBots()) {
            String body = methodBody(Files.readString(file), "protected boolean workStage()");
            if (body == null || !body.contains("return true")) {
                continue;
            }
            if (!MAY_END_THE_RUN.contains(file.getFileName().toString())) {
                offenders.add(file.getFileName() + " returns true from workStage() but is not one "
                        + "of the terminal quests - a true here warps the bot out of the run");
            }
        }
        assertTrue(offenders.isEmpty(), String.join("\n", offenders));
    }

    @Test
    void placementPuzzlesNeverReportTheRunOver() throws Exception {
        // The exact shape of the Amoria defect: a puzzle helper's boolean means "I have a spot",
        // and forwarding it answers the caller's "is the run over?" with a permanent yes. The
        // helper is a separate method, so the check has to follow the call, not just the switch.
        for (String name : List.of("AmoriaPQBot.java", "KerningPQBot.java", "LudiPQBot.java")) {
            String source = Files.readString(questBotPath(name));
            String workStage = methodBody(source, "protected boolean workStage()");
            assertTrue(workStage != null, name + " has no workStage()");
            for (String callee : calledPrivateMethods(workStage)) {
                String body = methodBody(source, "private boolean " + callee + "(");
                if (body == null) {
                    continue; // not a private boolean helper (e.g. a PqActions call)
                }
                assertTrue(!body.contains("return !plan.spots().isEmpty()"),
                        name + "/" + callee + " must not report the run over from a search");
            }
        }
    }

    /** The {@code name(} tokens inside a method body, as bare method names. */
    private static List<String> calledPrivateMethods(String body) {
        List<String> out = new ArrayList<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("([a-zA-Z_][a-zA-Z0-9_]*)\\(")
                .matcher(body);
        while (matcher.find()) {
            out.add(matcher.group(1));
        }
        return out;
    }

    /** Every {@code *Bot.java} under the quest family's type packages. */
    private static List<Path> questBots() throws Exception {
        Path root = Path.of("src/main/java/soloMapling/ArtificialPlayer/BotTypes");
        List<Path> out = new ArrayList<>();
        try (var stream = Files.walk(root)) {
            stream.filter(p -> p.getFileName().toString().endsWith("Bot.java"))
                    .filter(p -> p.getFileName().toString().contains("PQBot")
                            || p.getFileName().toString().matches("(Dojo|Pyramid|Carnival|Horntail)Bot.java"))
                    .forEach(out::add);
        }
        return out;
    }

    private static Path questBotPath(String fileName) {
        try (var stream = Files.walk(Path.of("src/main/java/soloMapling/ArtificialPlayer/BotTypes"))) {
            return stream.filter(p -> p.getFileName().toString().equals(fileName))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(fileName + " not found"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The brace-balanced body of a method, from its signature to its closing brace.
     *
     * <p>Source-level on purpose: the value under test is which branch returns what, and building
     * a live {@code PartyQuestBot} needs a WZ tree and a client. The same approach the catch-up
     * cadence test already takes.
     */
    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        if (start < 0) {
            return null;
        }
        int open = source.indexOf('{', start);
        if (open < 0) {
            return null;
        }
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(open, i + 1);
                }
            }
        }
        return null;
    }
}
