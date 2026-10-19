package soloMapling.ArtificialPlayer.BotAttackSystem;

import org.gms.manager.ServerManager;
import org.gms.property.ServiceProperty;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.context.MessageSource;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The attack-ownership gate is the one thing that makes a bot's swing atomic across the thread
 * families that reach {@link BotAttackDriver#botAttack} for the same bot through different monitors
 * (the PQ combat beat on the GrindTickRegistry sweep, the stage macro tick under the SeekState
 * monitor, the movement tick's contact retaliation). These pin the properties the
 * {@link BotAttackAction#tryRun} gate relies on, without needing a live map:
 *
 * <ol>
 *   <li>one owner per bot, and a second thread's attempt is REFUSED while the first holds —
 *       never parked behind it (the old ReentrantLock's tryLock semantics, kept);</li>
 *   <li>different bots never contend with each other (one bot's swing must not serialise the
 *       world);</li>
 *   <li>the ownership slot is released the instant the swing returns, so a reused character id
 *       can swing again — the registry never survives past the in-flight attack;</li>
 *   <li>clearBot stays safe while a swing is in flight (the cooldown maps it releases are
 *       per-id, so a despawn mid-swing cannot corrupt the live owner);</li>
 *   <li>a null bot is refused before the gate claims anything.</li>
 * </ol>
 *
 * <p>The old test reflected over {@code BotAttackDriver.SWING_LOCK}, the per-bot ReentrantLock the
 * ownership was unified away from: the lock map kept every lock forever (a bounded leak), and the
 * movement layer's own admission rules (climbing, the attack-action lock) were re-checked a second
 * time inside {@code attack()}. {@code tryRun} is the single owner now — the admission lives in
 * {@code GCMovement.runAttackAction} under the same state monitor the physics tick holds.
 */
class BotAttackActionOwnershipTest {

    private static final long QUIET_MS = 1_000L;

    /**
     * Character's static initializers reach into the host's Spring context - the same stub
     * BotPartyQueueTest / DownJumpCrouchBeatTest boot before any mock is built.
     */
    @BeforeAll
    static void stubHostEnvironment() throws Exception {
        ApplicationContext ctx = mock(ApplicationContext.class, RETURNS_DEEP_STUBS);
        org.gms.service.ConfigService configService = mock(org.gms.service.ConfigService.class);
        when(configService.loadGameConfigs()).thenReturn(java.util.List.of());
        when(ctx.getBean(org.gms.service.ConfigService.class)).thenReturn(configService);
        ServiceProperty props = mock(ServiceProperty.class);
        when(props.getLanguage()).thenReturn("zh-CN");
        when(ctx.getBean(ServiceProperty.class)).thenReturn(props);
        MessageSource messageSource = mock(MessageSource.class);
        when(messageSource.getMessage(anyString(), any(Object[].class), any(java.util.Locale.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(ctx.getBean(eq("messageSource"), eq(MessageSource.class))).thenReturn(messageSource);
        when(ctx.getBean(eq("logSource"), eq(MessageSource.class))).thenReturn(messageSource);
        when(ctx.getBean(eq("exceptionSource"), eq(MessageSource.class))).thenReturn(messageSource);
        var setter = ServerManager.class.getDeclaredMethod("setApplicationContext", ApplicationContext.class);
        setter.setAccessible(true);
        setter.invoke(new ServerManager(), ctx);
        // Pay the inline-mock agent's first-install cost here on the MAIN thread: the first
        // Character retransform measured ~1s, and whichever test method runs first would otherwise
        // pay it inside its worker thread and blow the 1s ownership latch.
        mock(ApplicationContext.class, RETURNS_DEEP_STUBS).hashCode();
        org.gms.client.Character warm = mock(org.gms.client.Character.class, RETURNS_DEEP_STUBS);
        when(warm.getId()).thenReturn(0);
        warm.getId();
    }

    /** A swing supplier that parks until released, so the test can hold the ownership mid-swing. */
    private static final class BlockingSwing implements AutoCloseable {
        final AtomicBoolean inside = new AtomicBoolean(false);
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(1);

        String run(int botId) {
            return BotAttackAction.tryRun(bot(botId), false, "refused", () -> {
                inside.set(true);
                entered.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "swung";
            });
        }

        @Override
        public void close() {
            release.countDown();
        }
    }

    /**
     * A minimal bot: a stub map keeps BotAttackAction's own outer gate open, while the bot staying
     * unregistered from GCMovement.STATES makes runAttackAction hand straight to the supplier - the
     * gate is what is under test, not the movement layer.
     */
    private static org.gms.client.Character bot(int id) {
        org.gms.client.Character bot = org.mockito.Mockito.mock(org.gms.client.Character.class,
                org.mockito.Mockito.RETURNS_DEEP_STUBS);
        org.mockito.Mockito.when(bot.getId()).thenReturn(id);
        org.mockito.Mockito.when(bot.getMap())
                .thenReturn(org.mockito.Mockito.mock(org.gms.server.maps.MapleMap.class));
        return bot;
    }

    @Test
    @DisplayName("One owner per bot: a second thread is refused while the first swings, never parked")
    void aSecondThreadCannotSwingWhileTheFirstIsSwinging() throws Exception {
        BlockingSwing swing = new BlockingSwing();
        Thread owner = new Thread(() -> swing.run(600), "first-swing");
        owner.start();
        try {
            assertTrue(swing.entered.await(QUIET_MS, TimeUnit.MILLISECONDS),
                    "precondition: the first swing took the ownership");

            AtomicBoolean secondAcquired = new AtomicBoolean(false);
            CountDownLatch secondDone = new CountDownLatch(1);
            Thread second = new Thread(() -> {
                secondAcquired.set(BotAttackAction.tryRun(bot(600), false, "refused",
                        () -> "swung").equals("swung"));
                secondDone.countDown();
            }, "second-swing");
            second.start();
            assertTrue(secondDone.await(QUIET_MS, TimeUnit.MILLISECONDS),
                    "the second attempt must be refused immediately, not wait out the first swing");
            assertFalse(secondAcquired.get(),
                    "a second thread must be refused while a swing is in flight - that is the double-swing fix");
        } finally {
            swing.close();
            owner.join(QUIET_MS);
        }
    }

    @Test
    @DisplayName("Different bots never share an ownership slot")
    void differentBotsDoNotContend() throws Exception {
        BlockingSwing swing = new BlockingSwing();
        Thread owner = new Thread(() -> swing.run(700), "bot-700");
        owner.start();
        try {
            assertTrue(swing.entered.await(QUIET_MS, TimeUnit.MILLISECONDS),
                    "precondition: bot 700 is mid-swing");

            String other = BotAttackAction.tryRun(bot(701), false, "refused", () -> "swung");
            assertEquals("swung", other,
                    "a different bot's swing must not wait behind (or be refused by) another bot's swing");
        } finally {
            swing.close();
            owner.join(QUIET_MS);
        }
    }

    @Test
    @DisplayName("The ownership slot dies with the swing: a released bot id can swing again")
    void theOwnershipSlotIsReleasedWhenTheSwingReturns() {
        String first = BotAttackAction.tryRun(bot(800), false, "refused", () -> "swung");
        assertEquals("swung", first, "an idle bot's first swing must be admitted");
        String second = BotAttackAction.tryRun(bot(800), false, "refused", () -> "swung");
        assertEquals("swung", second,
                "no lock registry may survive the in-flight attack, or a reused character id "
                        + "could never swing again");
    }

    @Test
    @DisplayName("clearBot is safe while a swing is in flight and cannot evict the live owner")
    void clearBotDuringAnInFlightSwingLeavesTheOwnerAlive() throws Exception {
        BlockingSwing swing = new BlockingSwing();
        final String[] result = {null};
        final Throwable[] workerFailure = {null};
        Thread owner = new Thread(() -> {
            try {
                result[0] = swing.run(900);
            } catch (Throwable t) {
                workerFailure[0] = t;
            }
        }, "bot-900");
        owner.setUncaughtExceptionHandler((t, e) -> workerFailure[0] = e);
        owner.start();
        try {
            boolean entered = swing.entered.await(QUIET_MS, TimeUnit.MILLISECONDS);
            assertTrue(entered,
                    "precondition: bot 900 is mid-swing"
                            + (workerFailure[0] != null ? " - worker failed: " + workerFailure[0] : ""));
            BotAttackDriver.clearBot(900); // the despawn racing the live swing
            swing.close();
            owner.join(QUIET_MS);
            assertEquals("swung", result[0],
                    "the in-flight swing must complete under its own ownership"
                            + (workerFailure[0] != null ? " - worker failed: " + workerFailure[0] : ""));


            // And the id, now reusable, is not locked out by anything clearBot did.
            assertEquals("swung", BotAttackAction.tryRun(bot(900), false, "refused", () -> "swung"),
                    "a reused character id must be able to acquire the ownership again");
        } finally {
            swing.close();
        }
    }

    @Test
    @DisplayName("A null bot is refused without touching the registry")
    void aNullBotIsRefusedWithoutClaimingAnything() {
        String r = BotAttackAction.tryRun(null, false, "refused", () -> "swung");
        assertEquals("refused", r,
                "the gate must reject a null body before it could claim an ownership slot");
    }
}
