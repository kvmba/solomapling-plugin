package soloMapling.ArtificialPlayer;

import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotDialogueHandler.DialogueConstructor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the death grumbling pools.
 *
 * <p>The two pools are what a player actually sees of the death state, and the brief was
 * explicit: at least a hundred lines each, in both languages. A future edit that quietly
 * prunes a pool, or adds a line in one language only, would show up as bots repeating
 * themselves — so both are checked here rather than left to review.
 */
class BotDeathDialogueTest {

    private static final String PACK = "BotDeathDialogue.yaml";
    private static final String TYPE = "BotDeath";
    private static final int MIN_LINES = 100;
    // A chat line wraps past the bubble well before this; anything longer gets clipped.
    private static final int MAX_CHARS = 80;

    @Test
    void fieldDeathHasEnoughLinesInBothLanguages() {
        assertPool("FieldDeath", true);
        assertPool("FieldDeath", false);
    }

    @Test
    void townDeathHasEnoughLinesInBothLanguages() {
        assertPool("TownDeath", true);
        assertPool("TownDeath", false);
    }

    private void assertPool(String node, boolean chinese) {
        String previous = soloMapling.Environment.SoloMaplingLanguageConfig.languageTag();
        try {
            soloMapling.Environment.SoloMaplingLanguageConfig.setLanguageTag(
                    chinese ? "zh-CN" : soloMapling.Environment.SoloMaplingLanguageConfig.DEFAULT);
            BotDialogueHandler.invalidateDialogueCache();
            List<String> lines = lines(node);
            String where = (chinese ? "zh-CN" : "en") + "/" + node;
            assertFalse(lines.isEmpty(), "missing pool: " + where);
            assertTrue(lines.size() >= MIN_LINES,
                    where + " has only " + lines.size() + " lines, needs " + MIN_LINES);

            Set<String> seen = new HashSet<>();
            for (String line : lines) {
                assertTrue(seen.add(line), where + " repeats: " + line);
                assertFalse(line.isBlank(), where + " has a blank line");
                assertTrue(resolvedTokensOnly(line),
                        where + " has an unresolvable placeholder: " + line);
                assertTrue(line.length() <= MAX_CHARS,
                        where + " line too long (" + line.length() + "): " + line);
            }
        } finally {
            soloMapling.Environment.SoloMaplingLanguageConfig.setLanguageTag(previous);
            BotDialogueHandler.invalidateDialogueCache();
        }
    }

    /**
     * Braces are allowed, but only as tokens the resolver knows: DialogueContextResolver
     * re-rolls to a plain line when a token cannot be filled, so an unrecognised one would
     * drop every line carrying it and leave the bot silent.
     */
    private static boolean resolvedTokensOnly(String line) {
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) != '{') {
                continue;
            }
            int close = line.indexOf('}', i + 1);
            if (close < 0) {
                return false;
            }
            String token = line.substring(i + 1, close);
            if (!RESOLVABLE.contains(token)) {
                return false;
            }
            i = close;
        }
        return true;
    }

    /** Tokens DialogueContextResolver fills from the speaking bot's own context. */
    private static final Set<String> RESOLVABLE =
            Set.of("MOB", "MAP", "REGION", "DROP", "JOB", "LEVEL", "WEAPON");

    private List<String> lines(String node) {
        DialogueConstructor con = BotDialogueHandler.getDialogueCon(PACK, TYPE, node);
        assertNotNull(con, "no such node: " + node);
        return new ArrayList<>(con.getDialogue());
    }
}
