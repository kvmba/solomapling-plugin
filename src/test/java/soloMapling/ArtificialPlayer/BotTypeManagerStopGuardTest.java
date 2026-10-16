package soloMapling.ArtificialPlayer;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code manuallyStopBot} must guard an unregistered character the way its two siblings do.
 *
 * <p>A character the bot registry never saw reaches it - {@code !bot spawn} without
 * {@code autoAttack} only creates the Character (BotGeneration.createBot never calls
 * addActiveBot), and {@code !bot create} / {@code !bot disconnect|remove} take an arbitrary cid -
 * so the missing null check threw from inside the executor's pool task, where nothing surfaces it:
 * the GM just saw a command with no reply.
 *
 * <p>Pinned at the source level rather than behaviourally, for the same reason
 * {@code BotRemovalFsmStopTest} does: the interesting property is which of the three sibling
 * functions lacks the guard, and the class needs a live world (a Character cannot be mocked here -
 * Mockito refuses the final host type). A behavioural test would only prove the happy path.
 */
class BotTypeManagerStopGuardTest {

    @Test
    void manuallyStopBotChecksForAMissingBotLikeItsSiblings() throws Exception {
        String java = source("src/main/java/soloMapling/ArtificialPlayer/BotTypeManager.java");

        String stop = body(java, "public static void manuallyStopBot(Character fakechar)");
        String start = body(java, "public static void manuallyStartBot(Character fakechar, long initialDelayMs)");
        String attack = body(java, "public static void startAttackTestBot(Character fakechar)");

        assertTrue(stop.contains("getBotById(fakechar.getId())"), "stop must resolve the BotSM");
        assertTrue(stop.contains("bot == null"), "stop must guard an unregistered bot");
        assertTrue(start.contains("bot == null"), "start keeps its guard");
        assertTrue(attack.contains("bot == null"), "attack keeps its guard");

        // The guard must come before the dereference, not merely exist somewhere in the method.
        int guard = stop.indexOf("bot == null");
        int use = stop.indexOf("bot.setRunning(false)");
        assertTrue(guard >= 0 && use > guard,
                "the null check must precede the first dereference");
    }

    private static String body(String java, String signature) {
        int start = java.indexOf(signature);
        assertTrue(start > 0, "missing method: " + signature);
        int end = java.indexOf("\n    }", start);
        assertTrue(end > start, "unterminated method: " + signature);
        return java.substring(start, end);
    }

    private static String source(String rel) throws Exception {
        Path path = Paths.get(rel);
        if (!Files.isRegularFile(path)) {
            path = Paths.get("..", rel);
        }
        assertTrue(Files.isRegularFile(path), "source file missing: " + rel);
        return Files.readString(path);
    }
}
