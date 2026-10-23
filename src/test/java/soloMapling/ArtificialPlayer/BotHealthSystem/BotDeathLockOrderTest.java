package soloMapling.ArtificialPlayer.BotHealthSystem;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the lock ORDER between a death episode and the bot's movement state.
 *
 * <p>Two threads meet on a zero-HP bot:
 * <ul>
 *   <li>the movement driver calls {@link BotDeath#adoptIfZeroHp()} from its tick, holding the
 *       bot's movement monitor;</li>
 *   <li>the contact-damage / macro path calls {@link BotDeath#kill()} or
 *       {@link BotDeath#adoptIfZeroHp()} on its own thread.</li>
 * </ul>
 * If either arming method held {@code this} while running the corpse teardown, the teardown takes
 * the movement monitor ({@code GCMovement.stop}) in the opposite order to the driver's
 * (movement → death). That is an AB-BA cycle: the driver parks holding the movement monitor, the
 * other thread parks holding the death monitor, and the bot never moves or dies again.
 *
 * <p>Shape-level on purpose, exactly like {@code PqStageNudgeLockOrderTest}: the cycle needs a live
 * driver thread and a live zero-HP transition to observe at runtime. What can be checked statically
 * is the property that prevents it — the movement-monitor call must not sit on a stack that holds
 * the death monitor.
 */
class BotDeathLockOrderTest {

    private static String source(String rel) throws Exception {
        Path p = Paths.get(rel);
        if (!Files.isRegularFile(p)) {
            p = Paths.get("..", rel);
        }
        assertTrue(Files.isRegularFile(p), "source file missing: " + rel);
        return Files.readString(p);
    }

    private static String body(String java, String signature) {
        int decl = java.indexOf(signature);
        assertTrue(decl >= 0, "missing method: " + signature);
        int bodyStart = java.indexOf('{', decl);
        int bodyEnd = java.indexOf("\n    }", bodyStart);
        assertTrue(bodyEnd > bodyStart, "body not found for " + signature);
        return java.substring(bodyStart, bodyEnd);
    }

    @Test
    void theArmingMethodsDoNotHoldTheDeathMonitorAcrossTheMovementTeardown() throws Exception {
        String java = source("src/main/java/soloMapling/ArtificialPlayer/BotHealthSystem/BotDeath.java");

        for (String signature : new String[]{"void kill()", "boolean adoptIfZeroHp()"}) {
            assertFalse(java.contains("synchronized " + signature),
                    signature + " must not be a synchronized METHOD: the corpse teardown inside it "
                            + "takes the movement monitor, and the driver takes both in the "
                            + "opposite order");
            String body = body(java, signature);
            assertTrue(body.contains("synchronized (this)"),
                    signature + " should still guard its state transition with synchronized (this)");
            int unlockAt = body.indexOf("synchronized (this)");
            int teardownAt = body.indexOf("stillTheCorpse(");
            assertTrue(teardownAt > unlockAt,
                    signature + " must call stillTheCorpse AFTER the guarded block, never inside it");
        }

        // The dangerous call itself: it is what takes the movement monitor.
        String teardown = body(java, "void stillTheCorpse(Character chr)");
        assertTrue(teardown.contains("GCMovement.stop("),
                "stillTheCorpse is expected to be the place that stops the movement session");
    }

    @Test
    void theDriverStillPollsTheZeroHpGateUnderItsOwnMonitor() throws Exception {
        // The other half of the cycle: if this call ever moves OUT of the movement monitor the
        // ordering constraint above would relax, and this test should be revisited deliberately
        // rather than silently.
        String driver = source("src/main/java/soloMapling/ArtificialPlayer/GCMoveSystem/GCMovementDriver.java");
        assertTrue(driver.contains("death.adoptIfZeroHp()"),
                "the movement driver's zero-HP gate is the caller that holds the movement monitor");
        String tick = body(driver, "private static void tick(BotMovementState entry)");
        assertTrue(tick.contains("death.adoptIfZeroHp()"),
                "the zero-HP gate belongs to the movement tick");
    }
}
