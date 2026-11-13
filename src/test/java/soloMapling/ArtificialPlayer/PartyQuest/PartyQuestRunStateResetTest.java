package soloMapling.ArtificialPlayer.PartyQuest;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Per-run latches must be cleared at the run's end, not only when the bot object is thrown away.
 *
 * <p>A quest bot is a long-lived object: the same instance is recruited again for the next party,
 * so any field that records "where I am in this run" survives into the next one. The base class
 * already owns the seam for that - {@link PartyQuestBot#releaseRoomState()} runs on every run end
 * (and on stop/retype) - and Ludi, Ariant and Papulatus reset their own state there.
 *
 * <p>Three quests did not, and each one degrades silently on the second run:
 * <ul>
 *   <li>Henesys' {@code guarding} stayed true, so the next run skipped the planting half and stood
 *       in an empty field while Moon Bunny never spawned;</li>
 *   <li>Ellin's {@code mazeExitAsked} still named the maze map, so the bot skipped the one talk
 *       that opens the way out and the party stayed stuck in the maze;</li>
 *   <li>Kerning's and Magatia's "already announced" latches suppressed the plan line the player
 *       needs to solve the stage.</li>
 * </ul>
 *
 * <p>Pinned at the source level: a run-scoped field and its reset live in the same seam, and a
 * unit test cannot build a live bot without a WZ tree. The assertion is deliberately about the
 * shape - "the class overrides releaseRoomState and the field is reset in it" - because that is
 * exactly what a future edit would forget.
 */
class PartyQuestRunStateResetTest {

    /** Quest bot file -> the per-run fields it must reset at the run's end. */
    private static final List<String[]> RESETS = List.of(
            new String[]{"Henesys/HenesysPQBot.java", "guarding"},
            new String[]{"Ellin/EllinPQBot.java", "mazeExitAsked"},
            new String[]{"Kerning/KerningPQBot.java", "announcedStage"},
            new String[]{"Magatia/MagatiaPQBot.java", "announcedCombination"},
            // These two already reset; kept so the list stays the whole register of run latches.
            new String[]{"Ludi/LudiPQBot.java", "announcedCrateAttempt"});

    @Test
    void everyRunScopedLatchIsClearedAtTheRunsEnd() throws Exception {
        List<String> offenders = new ArrayList<>();
        for (String[] entry : RESETS) {
            Path file = Path.of("src/main/java/soloMapling/ArtificialPlayer/BotTypes", entry[0]);
            String source = Files.readString(file);
            String field = entry[1];

            assertTrue(source.contains(field + " ="),
                    entry[0] + " must still declare the latch " + field);

            String release = methodBody(source, "protected void releaseRoomState()");
            if (release == null) {
                offenders.add(entry[0] + " declares " + field + " but never overrides "
                        + "releaseRoomState(), so it survives into the next run");
                continue;
            }
            if (!release.contains("super.releaseRoomState()")) {
                offenders.add(entry[0] + ".releaseRoomState() must call super - the base class "
                        + "releases the room's placement ledger there");
            }
            if (!release.contains(field + " =")) {
                offenders.add(entry[0] + ".releaseRoomState() does not reset " + field);
            }
        }
        assertTrue(offenders.isEmpty(), String.join("\n", offenders));
    }

    /**
     * The brace-balanced body of a method, from its signature to its closing brace.
     *
     * <p>The same source-level shape the catch-up cadence test uses, and for the same reason: the
     * value under test is a lifecycle wiring decision, and a live bot needs a WZ tree.
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
