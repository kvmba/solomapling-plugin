package soloMapling.ArtificialPlayer.PartyQuest;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the lock ORDER of the stage-tick nudge, the branch that resolves a walk arrival back into
 * stage logic.
 *
 * <p>{@code nudgeStageTick} is reachable from the movement driver's tick, which holds the bot's
 * movement monitor, and {@code BotSM.nudgeSoon} is {@code synchronized} on the macro monitor. If
 * the nudge runs INLINE there, the driver takes the macro monitor while holding the movement
 * monitor - and the stop path takes them in the opposite order
 * ({@code stopScheduledTask} holds the macro monitor and waits for the movement monitor via
 * {@code GCMovement.disable}). That is an AB-BA cycle: a stop racing a walk arrival deadlocks both
 * threads permanently, leaving the bot frozen and one driver thread parked. The codebase already
 * guards this exact cycle this way in {@code BotDeath}, {@code BotMapEntryResponder} and
 * {@code BotPlayerReaction}; this pins that the nudge keeps doing it too.
 *
 * <p>Shape-level on purpose: the cycle needs a live driver thread and a live macro tick to be
 * observed at runtime, and both halves would have to be driven from a test scheduler. What CAN be
 * checked statically is the property that prevents it - the macro-monitor call must never sit on
 * the callback's own stack - and that is what a regression would break.
 */
class PqStageNudgeLockOrderTest {

    private static String source(String rel) throws Exception {
        Path p = Paths.get(rel);
        if (!Files.isRegularFile(p)) {
            p = Paths.get("..", rel);
        }
        assertTrue(Files.isRegularFile(p), "source file missing: " + rel);
        return Files.readString(p);
    }

    @Test
    void theStageNudgeIsDispatchedNotInlined() throws Exception {
        String java = source("src/main/java/soloMapling/ArtificialPlayer/PartyQuest/PqActions.java");

        int decl = java.indexOf("private static void nudgeStageTick(");
        assertTrue(decl >= 0, "nudgeStageTick must exist (the walk-arrival bridge)");
        int bodyStart = java.indexOf('{', decl);
        int bodyEnd = java.indexOf("\n    }", bodyStart);
        assertTrue(bodyEnd > bodyStart, "nudgeStageTick body must be found");
        String body = java.substring(bodyStart, bodyEnd);

        assertTrue(body.contains("ExecutorServiceManager.runAsync("),
                "nudgeStageTick must dispatch the macro nudge - inlining it on the driver thread "
                        + "(which holds the movement monitor) closes an AB-BA cycle with the stop path");
        assertFalse(body.contains("nudgeSoon(") && !body.contains("runAsync("),
                "the synchronized nudgeSoon call must not sit on the caller's own stack");
    }

    /**
     * The two arrival callbacks that used to reach the macro monitor on the driver thread are
     * {@code walkTo}'s and {@code walkForPickup}'s. Pin that they still route through the
     * dispatched bridge rather than calling {@code BotSM} directly.
     */
    @Test
    void bothArrivalCallbacksRouteThroughTheDispatchedBridge() throws Exception {
        String java = source("src/main/java/soloMapling/ArtificialPlayer/PartyQuest/PqActions.java");

        // walkTo's arrival callback.
        int walkTo = java.indexOf("public static boolean walkTo(Character bot, Point target) {");
        assertTrue(walkTo >= 0, "walkTo must exist");
        int walkToEnd = java.indexOf("public static ", walkTo + 10);
        String walkToBody = java.substring(walkTo, walkToEnd > walkTo ? walkToEnd : java.length());
        assertTrue(walkToBody.contains("nudgeStageTick(bot)"),
                "walkTo's arrival must wake the FSM through the dispatched bridge");

        // walkForPickup: the bare driver move plus the same bridge.
        int pickup = java.indexOf("private static void walkForPickup(");
        assertTrue(pickup >= 0, "walkForPickup must exist");
        int pickupEnd = java.indexOf("\n    }", pickup);
        String pickupBody = java.substring(pickup, pickupEnd);
        assertTrue(pickupBody.contains("nudgeStageTick(bot)"),
                "the pickup walk must wake the FSM through the dispatched bridge");
    }

    /**
     * The other half of the cycle: the stop path really does hold the macro monitor while it
     * reaches for the movement monitor. If a future change makes that async, this pin can be
     * relaxed deliberately rather than by accident.
     */
    @Test
    void theStopPathStillHoldsTheMacroMonitorAcrossTheMovementTeardown() throws Exception {
        String sm = source("src/main/java/soloMapling/ArtificialPlayer/BotSM.java");
        assertTrue(sm.contains("public synchronized void stopScheduledTask()"),
                "BotSM.stopScheduledTask is synchronized - the macro-monitor half of the cycle");

        String pq = source("src/main/java/soloMapling/ArtificialPlayer/PartyQuest/PartyQuestBot.java");
        int stop = pq.indexOf("public synchronized void stopScheduledTask()");
        assertTrue(stop >= 0, "PartyQuestBot.stopScheduledTask must stay synchronized");
        int end = pq.indexOf("\n    }", stop);
        String body = pq.substring(stop, end);
        assertTrue(body.contains("GCMovement.disable("),
                "the stop path takes the movement monitor from under the macro monitor - the "
                        + "opposite order to the nudge, which is why the nudge must be dispatched");
    }
}
