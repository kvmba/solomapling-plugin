package soloMapling.ArtificialPlayer.PartyQuest;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Per-run claims and latches must be cleared before recruiting the same bot for another run.
 *
 * <p>A quest bot is a long-lived object: the same instance is recruited again for the next party,
 * so any field that records "where I am in this run" survives into the next one. The base class
 * already owns the seam for that - {@link PartyQuestBot#releaseRoomState()} runs on every run end
 * and on stop/retype. Check that quest-specific cleanup stays wired to that seam.
 */
class PartyQuestRunStateResetTest {

    /** Quest bot file -> the per-run state it must release or reset. */
    private static final List<String[]> RESETS = List.of(
            new String[]{"Henesys/HenesysPQBot.java", "HenesysStages.releaseFlowerClaim(getChr())"},
            new String[]{"Magatia/MagatiaPQBot.java", "announcedCombination = null"},
            new String[]{"Ludi/LudiPQBot.java", "announcedStage = -1"},
            new String[]{"Ludi/LudiPQBot.java", "climbFloorY = Integer.MIN_VALUE"});

    @Test
    void runScopedStateIsReleasedAtTheEnd() throws Exception {
        List<String> offenders = new ArrayList<>();
        for (String[] entry : RESETS) {
            Path file = Path.of("src/main/java/soloMapling/ArtificialPlayer/BotTypes", entry[0]);
            String source = Files.readString(file);
            String reset = entry[1];

            String release = methodBody(source, "protected void releaseRoomState()");
            if (release == null) {
                offenders.add(entry[0] + " never overrides releaseRoomState(): " + reset);
                continue;
            }
            if (!release.contains("super.releaseRoomState()")) {
                offenders.add(entry[0] + ".releaseRoomState() must call super - the base class "
                        + "resets its run state and releases the bot's wait spot there");
            }
            if (!release.contains(reset)) {
                offenders.add(entry[0] + ".releaseRoomState() is missing " + reset);
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
