package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.manager.ServerManager;
import org.gms.property.ServiceProperty;
import org.gms.server.maps.MapleMap;
import org.gms.service.ConfigService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.awt.Point;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the crouch beat every straight down-jump pays before takeoff.
 *
 * <p>Background: {@code queueDownJump} used to arm the pose and the very next grounded tick took off,
 * so the whole crouch was a single 50ms frame — a twitch, not the deliberate Down-then-Alt a player
 * performs. The beat was then counted in TICKS, which was the next defect: an unobserved map's LOD
 * driver self-reschedules at 1000ms per tick, so 16 tick-counted beats stretched into ~16 seconds of
 * a bot crouched on a launch row.
 *
 * <p>The beat is now an ABSOLUTE WALL-CLOCK DEADLINE ({@link BotMovementState#downJumpCrouchUntilMs},
 * armed by {@code BotPhysicsEngine.queueDownJump} at {@code now + DOWN_JUMP_CROUCH_MS}): the hold ends
 * when the clock reaches the deadline, however many ticks — fast or slow — it takes to get there.
 * Every down-jump entry point (the CROUCH ground action, the walked-in tryExecuteDrop and the warmup
 * fallback) pays it through the single two-argument {@code queueDownJump}; the old crouched/uncrouched
 * split is gone.
 *
 * <p>These tests drive the PRODUCTION driver tick on a virtual clock (the
 * {@code TowerMazeExecutionSimTest} shape) over the real LPQ-tower WZ geometry, whose stacked rows
 * guarantee a legal down-jump landing below the spawn.
 *
 * @see BotPhysicsEngine.Config#DOWN_JUMP_CROUCH_MS
 * @see BotMovementState#downJumpCrouchUntilMs
 */
class DownJumpCrouchBeatTest {

    // The LPQ tower's stacked box rows: from (-50,-2241) a straight down-jump lands on (-50,-2132),
    // so beginDownJump's void guard passes and the launch really happens.
    private static final int MAP_ID = 922010501;
    private static MapleMap map;

    @BeforeAll
    static void boot() {
        ApplicationContext ctx = mock(ApplicationContext.class, RETURNS_DEEP_STUBS);
        ConfigService configService = mock(ConfigService.class);
        when(configService.loadGameConfigs()).thenReturn(List.<org.gms.dao.entity.GameConfigDO>of());
        when(ctx.getBean(ConfigService.class)).thenReturn(configService);
        ServiceProperty props = mock(ServiceProperty.class);
        when(props.getLanguage()).thenReturn("zh-CN");
        when(ctx.getBean(ServiceProperty.class)).thenReturn(props);
        org.springframework.context.MessageSource messageSource =
                mock(org.springframework.context.MessageSource.class);
        when(messageSource.getMessage(anyString(), any(Object[].class), any(java.util.Locale.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(ctx.getBean(eq("messageSource"), eq(org.springframework.context.MessageSource.class)))
                .thenReturn(messageSource);
        when(ctx.getBean(eq("logSource"), eq(org.springframework.context.MessageSource.class)))
                .thenReturn(messageSource);
        when(ctx.getBean(eq("exceptionSource"), eq(org.springframework.context.MessageSource.class)))
                .thenReturn(messageSource);
        try {
            var setter = ServerManager.class.getDeclaredMethod("setApplicationContext", ApplicationContext.class);
            setter.setAccessible(true);
            setter.invoke(new ServerManager(), ctx);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        map = BotNavigationMapLoader.loadMapGeometry(MAP_ID);
        BotNavigationGraphProvider.getGraph(map, BotMovementProfile.base());
        MovementClock.useVirtual(1_000_000L);
    }

    @AfterAll
    static void restoreClock() {
        MovementClock.reset();
    }

    private record Sim(BotMovementState st, AtomicReference<Point> pos) {
        Point p() {
            return pos.get();
        }
    }

    private static Sim sim(int spawnX, int spawnY) {
        AtomicReference<Point> pos = new AtomicReference<>(new Point(spawnX, spawnY));
        org.gms.client.Character bot = mock(org.gms.client.Character.class, RETURNS_DEEP_STUBS);
        when(bot.getMap()).thenReturn(map);
        when(bot.getMapId()).thenReturn(MAP_ID);
        when(bot.getId()).thenReturn(424242);
        when(bot.getHp()).thenReturn(5000);
        when(bot.getChair()).thenReturn(0);
        when(bot.getPosition()).thenAnswer(inv -> pos.get());
        doAnswer(inv -> {
            pos.set(new Point(inv.getArgument(0)));
            return null;
        }).when(bot).setPosition(any());

        BotMovementState st = new BotMovementState(bot, null);
        st.movementProfile = BotMovementProfile.base();
        st.lastProfileRefreshMs = Long.MAX_VALUE / 4;
        st.lastMap = map;
        st.fhIndex = BotMovementManager.buildFhIndex(map);
        BotPhysicsEngine.teleportTo(st, bot, new Point(spawnX, spawnY));
        BotMovementManager.resetEntryStateAfterTeleport(st);
        return new Sim(st, pos);
    }

    private static final java.lang.reflect.Method TICK;

    static {
        try {
            TICK = GCMovementDriver.class.getDeclaredMethod("tick", BotMovementState.class);
            TICK.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** One production driver tick, then advance the virtual clock by the given wall-clock span. */
    private static void tick(Sim sim, long clockAdvanceMs) {
        try {
            TICK.invoke(null, sim.st());
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e.getCause() != null ? e.getCause() : e);
        }
        MovementClock.advance(clockAdvanceMs);
    }

    /** Arms the crouch the way every down-jump entry point does (the single two-argument form). */
    private static void queueDownJump(Sim sim) {
        BotPhysicsEngine.queueDownJump(sim.st(), sim.st().bot);
    }

    @Test
    @DisplayName("queueDownJump arms a wall-clock deadline, the crouch pose and the pending drop")
    void theArmedBeatIsTheConfiguredWallClockWindow() {
        Sim sim = sim(-50, -2241);
        long before = MovementClock.nowMs();
        queueDownJump(sim);

        BotMovementState st = sim.st();
        assertTrue(st.downJumpPending, "the drop itself must be armed");
        assertTrue(st.crouching, "the prone pose must be up for the whole hold");
        assertEquals(before + BotPhysicsEngine.cfg.DOWN_JUMP_CROUCH_MS, st.downJumpCrouchUntilMs,
                "the hold must be an absolute wall-clock deadline, not a tick count");
    }

    @Test
    @DisplayName("The crouch holds the prone pose exactly until the deadline, then the drop launches")
    void theCrouchHoldsUntilTheDeadlineThenLaunches() {
        Sim sim = sim(-50, -2241);
        long armedAt = MovementClock.nowMs();
        queueDownJump(sim);
        long deadline = sim.st().downJumpCrouchUntilMs;
        int holdTicks = 0;

        // Advance in uniform 50ms ticks: every tick still inside the deadline must hold the pose,
        // keep the pending drop and stand down the stuck watchdog (launchReadyAwaiting).
        while (MovementClock.nowMs() < deadline) {
            tick(sim, BotPhysicsEngine.cfg.TICK_MS);
            holdTicks++;
            assertTrue(sim.st().crouching,
                    "tick " + holdTicks + ": the pose must stay prone while the deadline has not passed");
            assertTrue(sim.st().downJumpPending,
                    "tick " + holdTicks + ": the drop must stay armed while crouching");
            assertFalse(sim.st().inAir,
                    "tick " + holdTicks + ": the bot must not take off before the deadline");
            assertTrue(sim.st().launchReadyAwaiting,
                    "tick " + holdTicks + ": the deliberate hold must exempt the bot from the stuck watchdog");
        }
        assertTrue(holdTicks >= BotPhysicsEngine.cfg.DOWN_JUMP_CROUCH_MS / BotPhysicsEngine.cfg.TICK_MS - 1,
                "the crouch must be visible for at least the configured window at the production cadence, "
                        + "not the single-frame twitch the beat exists to replace (held " + holdTicks + " ticks)");

        // The first tick at-or-after the deadline takes off.
        int guard = 0;
        while (!sim.st().inAir && guard++ < 5) {
            tick(sim, BotPhysicsEngine.cfg.TICK_MS);
        }
        assertTrue(sim.st().inAir, "the first grounded tick past the deadline must launch the drop");
        assertFalse(sim.st().crouching, "takeoff ends the prone pose");
        assertEquals(0L, sim.st().downJumpCrouchUntilMs, "launch consumes the deadline");
        long launchAt = MovementClock.nowMs();
        assertTrue(launchAt - armedAt >= BotPhysicsEngine.cfg.DOWN_JUMP_CROUCH_MS - BotPhysicsEngine.cfg.TICK_MS,
                "the crouch must never be shorter than the configured window");
    }

    @Test
    @DisplayName("LOD scheduling cannot stretch the crouch: slow ticks elapse it by wall clock")
    void lodSchedulingCannotStretchTheCrouch() {
        // The defect the wall-clock rewrite exists for: an unobserved map's driver self-reschedules
        // at 1000ms per tick, and the old 16-tick beat turned into ~16s of crouch. Drive SLOW
        // 300ms-apart production ticks — the deadline must elapse by CLOCK, not by tick count
        // (three ticks cover 900ms of virtual clock; a tick-counted hold would still be crouching).
        Sim sim = sim(-50, -2241);
        queueDownJump(sim);
        long deadline = sim.st().downJumpCrouchUntilMs;

        tick(sim, 300);
        assertTrue(sim.st().crouching && sim.st().downJumpPending,
                "300ms of clock: still inside the 800ms window, the crouch holds");
        tick(sim, 300);
        assertTrue(sim.st().crouching && sim.st().downJumpPending,
                "600ms of clock: still inside the window, the crouch holds");
        while (MovementClock.nowMs() < deadline) {
            tick(sim, 300);
        }
        // The crouch gate is read at each tick's START (the clock advances after the tick), so the
        // tick that crosses the deadline still held the pose; the NEXT tick takes off.
        tick(sim, 300);
        assertTrue(sim.st().inAir,
                "past 800ms of wall clock - reached in FOUR SLOW ticks instead of sixteen: the "
                        + "deadline must have elapsed by clock. A tick-counted hold would still be "
                        + "crouching here and the bot would sit out one full second of LOD cadence "
                        + "per tick until the 16th tick.");
        assertTrue(MovementClock.nowMs() >= deadline,
                "the launch must not happen before the configured crouch window has elapsed");
    }

    @Test
    @DisplayName("Clearing the move intent cancels the pending drop, the deadline and the pose")
    void clearingTheMoveIntentCancelsThePendingDropAndThePose() throws Exception {
        Sim sim = sim(-50, -2241);
        queueDownJump(sim);
        assertTrue(sim.st().downJumpPending, "precondition: the drop is armed");

        register(sim.st());
        try {
            GCMovement.clearMoveIntent(sim.st().bot);
            assertFalse(sim.st().downJumpPending, "a cancelled move must not leave a drop queued");
            assertEquals(0L, sim.st().downJumpCrouchUntilMs,
                    "the crouch deadline must not outlive the drop it belongs to");
            assertFalse(sim.st().crouching, "the prone pose must end with the cancelled drop");
        } finally {
            unregister(sim.st());
        }
    }

    @Test
    @DisplayName("The configured beat reads as a deliberate crouch without nearing the stall nets")
    void theConfiguredBeatIsDeliberate() {
        int ms = BotPhysicsEngine.cfg.DOWN_JUMP_CROUCH_MS;
        assertTrue(ms >= 500,
                "a crouch must be visible for at least half a second; GCFidget's own DUCK_MIN_MS is 900");
        assertTrue(ms < 5_000,
                "the beat must stay far below the 8s no-progress give-up, or a stalled drop is abandoned");
    }

    @Test
    @DisplayName("A deliberate hold is exempt from the stuck watchdog, so it is never rescue-hopped")
    void aDeliberateHoldIsExemptFromTheStuckWatchdog() {
        // The hold itself raises the flag; this pins the watchdog's half of the contract, so a future
        // edit to isStuckCheckExempt cannot silently re-open the "hop, re-land, hop" loop the
        // drop-cadence hold already hit (BotNavigationManager.tryExecuteDrop).
        // The comparison case must carry a goal, otherwise the (navEdge == null && moveTarget == null)
        // clause exempts it anyway and the flag's own contribution would be invisible.
        BotMovementState holding = new BotMovementState(null, null);
        holding.launchReadyAwaiting = true;
        holding.moveTarget = new Point(10, 10);
        assertTrue(BotMovementManager.isStuckCheckExempt(holding),
                "a bot deliberately holding its launch must not be read as wedged");

        BotMovementState waiting = new BotMovementState(null, null);
        waiting.moveTarget = new Point(10, 10);
        assertFalse(BotMovementManager.isStuckCheckExempt(waiting),
                "without the hold flag the same goal-carrying bot is NOT exempt - the flag is what "
                        + "protects the beat, and dropping it re-opens the rescue-hop loop");
    }

    private static void register(BotMovementState st) throws Exception {
        states().put(st.bot.getId(), st);
    }

    private static void unregister(BotMovementState st) throws Exception {
        states().remove(st.bot.getId());
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<Integer, BotMovementState> states() throws Exception {
        var f = GCMovement.class.getDeclaredField("STATES");
        f.setAccessible(true);
        return (java.util.Map<Integer, BotMovementState>) f.get(null);
    }
}
