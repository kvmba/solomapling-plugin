package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.client.Character;
import org.gms.config.GameConfig;
import org.gms.dao.entity.GameConfigDO;
import org.gms.manager.ServerManager;
import org.gms.property.ServiceProperty;
import org.gms.service.ConfigService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.context.MessageSource;

import java.util.List;

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
 * Pins the crouch beat a CROUCH-sourced straight down-jump pays before takeoff.
 *
 * <p>Background: {@code queueDownJump} used to arm the pose and the very next grounded tick took off,
 * so the whole crouch was a single 50ms frame — a twitch, not the deliberate Down-then-Alt a player
 * performs. The beat is counted in TICKS (decremented by {@code tickMotionTimers} alongside
 * {@code downJumpGracePeriodMS}) rather than in wall-clock milliseconds, because a wall-clock hold
 * stalls a bot whose ticks run faster than real time: an execution sim driving the production tick
 * directly burned 30 ticks in a few milliseconds and never reached an 800ms deadline, which pinned the
 * bot on its launch row until the 8s no-progress give-up abandoned the whole descent.
 *
 * <p>The beat is opt-in per entry point: {@code queueDownJump} is the single arming gate, and only
 * the CROUCH ground action arms ticks ({@code tryExecuteDrop} and the warmup fallback pass
 * {@code crouched = false} and launch next tick).
 *
 * @see BotPhysicsEngine.Config#DOWN_JUMP_CROUCH_TICKS
 * @see BotMovementState#downJumpCrouchTicks
 */
class DownJumpCrouchBeatTest {

    /**
     * queueDownJump settles the avatar (idleOnGround reads the position; syncCharacterState writes
     * the stance), and Character's static initializers reach into the host's Spring context - the
     * same stub BotPartyQueueTest / BotMovementCapabilitiesTest boot before any mock is built.
     */
    @BeforeAll
    static void stubHostEnvironment() throws Exception {
        ApplicationContext ctx = mock(ApplicationContext.class, RETURNS_DEEP_STUBS);
        ConfigService configService = mock(ConfigService.class);
        when(configService.loadGameConfigs()).thenReturn(List.<GameConfigDO>of());
        when(ctx.getBean(ConfigService.class)).thenReturn(configService);
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
    }

    private static BotMovementState state() {
        return new BotMovementState(null, null);
    }

    @Test
    @DisplayName("The default beat is zero, so a drop that never showed a crouch launches next tick")
    void theDefaultBeatIsZero() {
        assertEquals(0, state().downJumpCrouchTicks,
                "a fresh state must not hold a crouch - only the CROUCH ground action pays the beat");
    }

    @Test
    @DisplayName("One tickMotionTimers call spends exactly one beat tick")
    void oneTickSpendsOneBeatTick() {
        BotMovementState st = state();
        st.downJumpCrouchTicks = 4;
        BotPhysicsEngine.tickMotionTimers(st);
        assertEquals(3, st.downJumpCrouchTicks);
        BotPhysicsEngine.tickMotionTimers(st);
        BotPhysicsEngine.tickMotionTimers(st);
        BotPhysicsEngine.tickMotionTimers(st);
        assertEquals(0, st.downJumpCrouchTicks, "the beat must run out exactly, never go negative");
    }

    @Test
    @DisplayName("An exhausted beat never decrements below zero")
    void anExhaustedBeatNeverGoesNegative() {
        BotMovementState st = state();
        BotPhysicsEngine.tickMotionTimers(st);
        assertEquals(0, st.downJumpCrouchTicks);
        BotPhysicsEngine.tickMotionTimers(st);
        assertEquals(0, st.downJumpCrouchTicks,
                "ticking an exhausted beat must stay at zero so it can never read as 'still holding'");
    }

    @Test
    @DisplayName("The configured beat is long enough to read as a deliberate crouch")
    void theConfiguredBeatIsDeliberate() {
        int ticks = BotPhysicsEngine.cfg.DOWN_JUMP_CROUCH_TICKS;
        long ms = (long) ticks * BotPhysicsEngine.cfg.TICK_MS;
        assertTrue(ticks > 1,
                "a one-frame crouch is the twitch this beat exists to replace");
        assertTrue(ms >= 500,
                "a crouch must be visible for at least half a second; GCFidget's own DUCK_MIN_MS is 900");
        assertTrue(ms < 5_000,
                "the beat must stay far below the 8s no-progress give-up, or a stalled drop is abandoned");
    }

    @Test
    @DisplayName("Only the CROUCH ground action pays the beat; walked-into edges launch next tick")
    void onlyTheCrouchActionPaysTheBeat() {
        // Pins WHICH entries pay: the walked-into down-jumps (tryExecuteDrop, the warmup fallback)
        // must launch on the very next grounded tick - a uniform beat taxed every graph DROP edge
        // 16 driver ticks (16s on an unobserved map's 1s cadence) and armed launchReadyAwaiting
        // for the whole hold. Only the CROUCH ground action reads as a deliberate Down-then-Alt.
        // queueDownJump settles the avatar (idleOnGround), so the Character mock needs the host
        // Spring stubs (same shape BotPartyQueueTest boots) plus a position; syncCharacterState
        // only calls setStance, which a mock absorbs.
        Character bot = mock(Character.class, RETURNS_DEEP_STUBS);
        when(bot.getPosition()).thenReturn(new java.awt.Point(0, -100));

        BotMovementState walked = state();
        BotPhysicsEngine.queueDownJump(walked, bot, false);
        assertEquals(0, walked.downJumpCrouchTicks,
                "a walked-into drop must launch on the next grounded tick - the walk IS the intent");
        assertTrue(walked.downJumpPending, "the drop itself is still armed");

        BotMovementState crouched = state();
        BotPhysicsEngine.queueDownJump(crouched, bot, true);
        assertEquals(BotPhysicsEngine.cfg.DOWN_JUMP_CROUCH_TICKS, crouched.downJumpCrouchTicks,
                "the CROUCH ground action pays the full beat - the crouch is the visible intent");
        assertTrue(crouched.downJumpPending);
    }

    @Test
    @DisplayName("A deliberate hold is exempt from the stuck watchdog, so it is never rescue-hopped")
    void aDeliberateHoldIsExemptFromTheStuckWatchdog() {
        // The hold itself raises the flag; this pins the watchdog's half of the contract, so a future
        // edit to isStuckCheckExempt cannot silently re-open the "hop, re-land, hop" loop the
        // drop-cadence hold already hit (BotNavigationManager.tryExecuteDrop).
        // The comparison case must carry a goal, otherwise the (navEdge == null && moveTarget == null)
        // clause exempts it anyway and the flag's own contribution would be invisible.
        BotMovementState holding = state();
        holding.launchReadyAwaiting = true;
        holding.moveTarget = new java.awt.Point(10, 10);
        assertTrue(BotMovementManager.isStuckCheckExempt(holding),
                "a bot deliberately holding its launch must not be read as wedged");

        BotMovementState waiting = state();
        waiting.moveTarget = new java.awt.Point(10, 10);
        assertFalse(BotMovementManager.isStuckCheckExempt(waiting),
                "without the hold flag the same goal-carrying bot is NOT exempt - the flag is what "
                        + "protects the beat, and dropping it re-opens the rescue-hop loop");
    }
}
