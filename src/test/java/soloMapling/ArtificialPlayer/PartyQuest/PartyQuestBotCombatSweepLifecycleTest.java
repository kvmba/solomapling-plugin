package soloMapling.ArtificialPlayer.PartyQuest;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The 250ms combat-sweep registration must span the FSM's RUNNING lifetime, not the object's.
 *
 * <p>The constructor ran {@code GrindTickRegistry.register} once, so a bot wrapped but never
 * started (the GM {@code createAndSetBot} paths) sat in the registry as a ghost participant -
 * and {@code removalMustStopFsm} skips a {@code running == false} bot, so that ghost was never
 * unregistered. The same shape also meant a stopped-then-restarted bot never re-registered: the
 * constructor runs once, the stop ran unregister, and {@code !bot manualstart} after
 * {@code !bot manualstop} left the PQ bot alive but swinging nothing (seekAndAttack is only
 * reachable from the sweep's grindTick).
 *
 * <p>Registration now lives in {@link PartyQuestBot#startScheduledTask} - the same
 * register-when-active shape TrainingBot's grind sessions use - with the unregister staying at
 * stop. Pinned at the source level (the same shape-level pin the removal FSM-stop test uses):
 * the constructor must not register, and startScheduledTask must register before it delegates.
 *
 * <p>The GCMovement session rides the same lifetime rule, for the same reason and now at the same
 * seam: {@code enable} starts a driver thread AND takes the shared movement lock, and
 * {@code stopScheduledTask} is the only release. Armed from the constructor it therefore leaked a
 * live driver on a bot that was wrapped but never started - exactly the ghost shape the sweep had.
 * The tests below pin both halves (constructor must not enable; start must enable).
 */
class PartyQuestBotCombatSweepLifecycleTest {

    @Test
    void theConstructorMustNotRegisterTheCombatSweep() throws Exception {
        String java = source("src/main/java/soloMapling/ArtificialPlayer/PartyQuest/PartyQuestBot.java");
        int ctor = java.indexOf("protected PartyQuestBot(Character character)");
        assertTrue(ctor > 0, "the constructor must exist");
        int ctorEnd = java.indexOf("public synchronized void startScheduledTask", ctor);
        assertTrue(ctorEnd > ctor, "startScheduledTask must follow the constructor");
        String ctorBody = java.substring(ctor, ctorEnd);
        assertFalse(ctorBody.contains("GrindTickRegistry.getInstance().register"),
                "the constructor must not register the combat sweep - a never-started bot would "
                        + "ghost in the registry and a restarted bot would never re-register");
    }

    /**
     * The same lifetime rule for the movement session. {@code GCMovement.enable} starts the bot's
     * driver task and acquires the shared movement lock; {@code stopScheduledTask} (which never runs
     * for a wrapped-but-never-started bot) is the only release. Armed from the constructor, a GM
     * {@code !bot lpqbot <cid>} + {@code !bot dcallmap} left a driver thread and a permanently held
     * movement lock behind - the busy-bot ghost, invisible to every guard.
     */
    @Test
    void theMovementSessionAlsoSpansTheRunningLifetime() throws Exception {
        String java = source("src/main/java/soloMapling/ArtificialPlayer/PartyQuest/PartyQuestBot.java");
        int ctor = java.indexOf("protected PartyQuestBot(Character character)");
        int ctorEnd = java.indexOf("public synchronized void startScheduledTask", ctor);
        String ctorBody = java.substring(ctor, ctorEnd);
        assertFalse(ctorBody.contains("GCMovement.enable("),
                "the constructor must not enable the movement session - its driver thread and the "
                        + "shared movement lock would outlive a never-started bot forever");

        int start = java.indexOf("public synchronized void startScheduledTask(long initialDelayMs)");
        assertTrue(start > 0, "startScheduledTask must exist");
        int enable = java.indexOf("GCMovement.enable(getChr())", start);
        int delegate = java.indexOf("super.startScheduledTask(initialDelayMs)", start);
        assertTrue(enable > 0 && delegate > enable,
                "startScheduledTask must enable the movement session BEFORE it delegates - the walk "
                        + "helpers and the map-entry choreography both read isEnabled()");
    }

    @Test
    void startScheduledTaskRegistersBeforeDelegating() throws Exception {
        String java = source("src/main/java/soloMapling/ArtificialPlayer/PartyQuest/PartyQuestBot.java");
        int start = java.indexOf("public synchronized void startScheduledTask(long initialDelayMs)");
        assertTrue(start > 0, "startScheduledTask must exist");
        int register = java.indexOf("GrindTickRegistry.getInstance().register(this)", start);
        int delegate = java.indexOf("super.startScheduledTask(initialDelayMs)", start);
        assertTrue(register > 0 && delegate > register,
                "startScheduledTask must register the combat sweep BEFORE it delegates - the first "
                        + "wheel tick may otherwise fire before the participant is in the registry");
    }

    @Test
    void stopScheduledTaskStillUnregisters() throws Exception {
        String java = source("src/main/java/soloMapling/ArtificialPlayer/PartyQuest/PartyQuestBot.java");
        int stop = java.indexOf("public synchronized void stopScheduledTask()");
        assertTrue(stop > 0, "stopScheduledTask must exist");
        int unregister = java.indexOf("GrindTickRegistry.getInstance().unregister(this)", stop);
        assertTrue(unregister > stop,
                "the stop path must remain the one unregister seam");
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
