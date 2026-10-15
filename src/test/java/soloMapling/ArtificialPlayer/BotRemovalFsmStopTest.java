package soloMapling.ArtificialPlayer;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A bot removal must end the bot's scheduled work, not merely detach the character. The BotSM
 * owns the tick-wheel entry, the shared 250ms combat-sweep registration and the dynamic movement
 * session, and {@code MapleMap.removePlayer} leaves the character's map and running flag intact -
 * so a removal that skipped the stop left the bot ticking as a ghost (the {@code !bot dcallmap}
 * path, which had no stop, unlike the single-bot {@code !bot remove}).
 *
 * <p>{@code removeBotFromServer} now stops a still-running FSM itself; the guard is what keeps a
 * caller that already stopped the bot (the single-bot remove, the OPQ lobby rotation, the
 * companion teardown) from paying a second stop. The predicate is pinned directly, and the call
 * site's ORDER (stop before any state is detached) is pinned at the source level - the same
 * shape-level pin the summon regrant isolation test uses for the same reason.</p>
 */
class BotRemovalFsmStopTest {

    @Test
    void onlyARunningFsmMustBeStopped() {
        BotSM running = mock(BotSM.class);
        when(running.getRunning()).thenReturn(true);
        assertTrue(BotGeneration.removalMustStopFsm(running));

        BotSM stopped = mock(BotSM.class);
        when(stopped.getRunning()).thenReturn(false);
        assertFalse(BotGeneration.removalMustStopFsm(stopped),
                "every stopper pairs setRunning(false) with stopScheduledTask - a second stop is noise");

        assertFalse(BotGeneration.removalMustStopFsm(null),
                "an FSM-less cast (the skill-show bots) has nothing to stop");
    }

    @Test
    void theRemovalPathStopsTheFsmBeforeDetachingAnything() throws Exception {
        String java = source("src/main/java/soloMapling/ArtificialPlayer/BotGeneration.java");
        int method = java.indexOf("public static void removeBotFromServer(");
        assertTrue(method > 0, "removeBotFromServer must exist");
        int stop = java.indexOf("removalMustStopFsm(CharacterStorage.getBotById(", method);
        int pets = java.indexOf("BotPetSystem.remove(fakechar)", method);
        assertTrue(stop > 0 && pets > stop,
                "removeBotFromServer must stop the FSM before it detaches pets/summons/map state");
    }

    private static String source(String rel) throws Exception {
        Path p = Paths.get(rel);
        if (!Files.isRegularFile(p)) {
            p = Paths.get("..", rel);
        }
        assertTrue(Files.isRegularFile(p), "source file missing: " + rel);
        return Files.readString(p);
    }
}
