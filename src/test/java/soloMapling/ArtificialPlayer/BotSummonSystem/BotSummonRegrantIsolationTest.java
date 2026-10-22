package soloMapling.ArtificialPlayer.BotSummonSystem;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the recast sweep's fault isolation. {@code regrantIfDue} is invoked from two
 * {@code scheduleAtFixedRate} tasks, whose JDK contract is that an escaping throwable CANCELS the
 * task permanently - so a single bot's failing grant would silently end summon recasts for the
 * whole world (and, because the move tick calls it first, the summon tick too).
 *
 * <p>The behaviour cannot be driven directly here: the sweep reaches into host player storage and
 * a live {@code MapleMap}. The contract is therefore pinned at the shape level - the per-bot
 * work must be a separately-callable step ({@code regrantFor}) invoked from inside the sweep's
 * loop - plus the sweep must be reachable as the periodic entry point. Both halves go red if the
 * isolation is removed (the per-bot body folded back inline into the loop).
 */
class BotSummonRegrantIsolationTest {

    private static String source(String rel) throws Exception {
        Path p = Paths.get(rel);
        if (!Files.isRegularFile(p)) {
            p = Paths.get("..", rel);
        }
        assertTrue(Files.isRegularFile(p), "source file missing: " + rel);
        return Files.readString(p);
    }

    @Test
    void perBotRecastWorkIsASeparateMethodCalledFromTheSweep() throws Exception {
        String java = source("src/main/java/soloMapling/ArtificialPlayer/BotSummonSystem/BotSummonFollower.java");

        Method step = BotSummonFollower.class.getDeclaredMethod("regrantFor",
                Integer.class, BotSummonConfig.class, long.class, long.class);
        assertTrue(Modifier.isStatic(step.getModifiers()),
                "regrantFor is the per-bot recast step the sweep isolates");

        // The sweep must CALL the isolated step (folding the body back into the loop would drop
        // this call and leave the scheduler unprotected again).
        assertTrue(java.contains("regrantFor(botId, cfg, nowMs, token)"),
                "regrantIfDue must delegate each bot to the isolated regrantFor step");
    }

    @Test
    void theSweepBodyIsWrappedInAPerBotCatch() throws Exception {
        String java = source("src/main/java/soloMapling/ArtificialPlayer/BotSummonSystem/BotSummonFollower.java");

        // Find regrantIfDue and require a catch between its declaration and the next declaration,
        // i.e. inside the loop that walks the leases.
        int sweep = java.indexOf("private static void regrantIfDue(");
        assertTrue(sweep >= 0, "regrantIfDue must exist");
        int end = java.indexOf("private static void regrantFor(", sweep);
        assertTrue(end > sweep, "regrantFor must follow the sweep");
        String body = java.substring(sweep, end);
        assertTrue(body.contains("try {") && body.contains("catch (Throwable"),
                "a throw must not escape regrantIfDue: the periodic task would be cancelled");
    }

    @Test
    void bothPeriodicEntriesStillRouteThroughTheGuardedSweep() throws Exception {
        String java = source("src/main/java/soloMapling/ArtificialPlayer/BotSummonSystem/BotSummonFollower.java");
        // The move tick calls the sweep at its head; the dedicated task calls it directly. Both
        // must keep going through the guarded method rather than re-inlining per-bot work.
        int tick = java.indexOf("private static void tick(long token) {");
        assertTrue(tick >= 0);
        assertTrue(java.substring(tick, tick + 200).contains("regrantIfDue(cfg, token)"),
                "the move tick still runs the recast beat through the guarded sweep");
        assertTrue(java.contains("scheduleAtFixedRate(() -> regrantIfDue(config, token)"),
                "the dedicated regrant task still runs the guarded sweep");
    }
}
