package soloMapling.ArtificialPlayer.BotTypes.OPQ;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OPQBot's engine session and orchestrator slot must span the FSM's RUNNING lifetime, not the
 * object's.
 *
 * <p>{@code OPQBot} extends {@code BotSM} directly (not {@code PartyQuestBot}), so it wires the
 * dynamic movement engine and its orchestrator registration itself. Both used to run in the
 * CONSTRUCTOR, and neither ends with the character's own removal: a bot wrapped but never started
 * (the GM {@code createAndSetBot} paths, e.g. {@code !bot opqbot <cid>} followed by
 * {@code !bot dcallmap}) therefore held a live 50ms driver thread, the shared movement lock, and a
 * slot in {@code registeredBots} - which also fed {@code allBotsLostParty()} and
 * {@code resolveLeader()}, so a ghost could reset a live run. {@code stopScheduledTask} is the only
 * release seam, and it never runs for a bot that was never started.
 *
 * <p>Both now live in {@code startScheduledTask}, the same register-when-active shape the base
 * class uses. Pinned at the source level, matching the sibling
 * {@code PartyQuestBotCombatSweepLifecycleTest}.
 */
class OPQBotLifecycleTest {

    private static final String MAIN = "src/main/java/soloMapling/ArtificialPlayer/BotTypes/OPQ/OPQBot.java";

    @Test
    void theConstructorHoldsNoEngineSessionOrOrchestratorSlot() throws Exception {
        String java = source(MAIN);
        int ctor = java.indexOf("public OPQBot(Character character)");
        assertTrue(ctor > 0, "the constructor must exist");
        int ctorEnd = java.indexOf("public synchronized void startScheduledTask", ctor);
        assertTrue(ctorEnd > ctor, "startScheduledTask must follow the constructor");
        String ctorBody = java.substring(ctor, ctorEnd);

        assertFalse(ctorBody.contains("GCMovement.enable("),
                "the constructor must not enable the movement session - a never-started bot would "
                        + "hold its driver thread and the shared movement lock forever");
        assertFalse(ctorBody.contains("orchestrator.registerBot("),
                "the constructor must not register with the orchestrator - a never-started bot "
                        + "would sit in registeredBots and could reset a live run's state");
    }

    @Test
    void startScheduledTaskArmsBothBeforeDelegating() throws Exception {
        String java = source(MAIN);
        int start = java.indexOf("public synchronized void startScheduledTask(long initialDelayMs)");
        assertTrue(start > 0, "startScheduledTask must exist");
        int enable = java.indexOf("GCMovement.enable(getChr())", start);
        int register = java.indexOf("orchestrator.registerBot(this)", start);
        int delegate = java.indexOf("super.startScheduledTask(initialDelayMs)", start);

        assertTrue(enable > 0 && enable < delegate,
                "the movement session must be enabled before the FSM runs its first tick");
        assertTrue(register > 0 && register < delegate,
                "the orchestrator registration must land before the FSM runs its first tick");
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
