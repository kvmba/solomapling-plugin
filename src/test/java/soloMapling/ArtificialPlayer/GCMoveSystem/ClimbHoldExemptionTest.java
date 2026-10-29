package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link GCMovement#isHoldingClimb} is the one predicate the grind layer's stall net reads. It has
 * to split two states that look identical on the rope but need opposite treatment:
 *
 * <ul>
 *   <li>an explicit REST hang — must be exempt, or the 1.2s sampler dismounts a bot off the rope it
 *       walked to for a break (measured 1.25s before the exemption);</li>
 *   <li>a GRINDER that merely arrived mid-rope ({@code climbPositionHeld}) — must NOT be exempt.
 *       GrindBrain early-returns while {@code isClimbing}, so nothing would ever issue it a new
 *       goal, and both the driver's and the grind layer's stall nets standing down leaves the bot
 *       hanging until the 60s macro bail.</li>
 * </ul>
 *
 * Non-grinders with an explicit hold (a GM {@code !gcmove stop}, a travel/PQ arrival) stay exempt.
 */
public class ClimbHoldExemptionTest {

    @BeforeAll
    static void stub() throws Exception {
        MovementClock.useVirtual(1_000_000L);
        org.springframework.context.ApplicationContext ctx =
                mock(org.springframework.context.ApplicationContext.class, RETURNS_DEEP_STUBS);
        org.gms.service.ConfigService configService = mock(org.gms.service.ConfigService.class);
        when(configService.loadGameConfigs())
                .thenReturn(java.util.List.<org.gms.dao.entity.GameConfigDO>of());
        when(ctx.getBean(org.gms.service.ConfigService.class)).thenReturn(configService);
        org.gms.property.ServiceProperty props = mock(org.gms.property.ServiceProperty.class);
        when(props.getLanguage()).thenReturn("zh-CN");
        when(ctx.getBean(org.gms.property.ServiceProperty.class)).thenReturn(props);
        org.springframework.context.MessageSource ms =
                mock(org.springframework.context.MessageSource.class);
        when(ms.getMessage(any(), any(Object[].class), any(java.util.Locale.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(ctx.getBean(eq("messageSource"), eq(org.springframework.context.MessageSource.class)))
                .thenReturn(ms);
        when(ctx.getBean(eq("logSource"), eq(org.springframework.context.MessageSource.class)))
                .thenReturn(ms);
        when(ctx.getBean(eq("exceptionSource"), eq(org.springframework.context.MessageSource.class)))
                .thenReturn(ms);
        var setter = org.gms.manager.ServerManager.class
                .getDeclaredMethod("setApplicationContext", org.springframework.context.ApplicationContext.class);
        setter.setAccessible(true);
        setter.invoke(new org.gms.manager.ServerManager(), ctx);
    }

    @AfterAll
    static void restore() throws Exception {
        MovementClock.reset();
        Field f = GCMovement.class.getDeclaredField("STATES");
        f.setAccessible(true);
        ((Map<?, ?>) f.get(null)).clear();
    }

    private static BotMovementState stateFor(int botId, boolean climbing, boolean held, boolean resting, boolean grinding)
            throws Exception {
        org.gms.client.Character bot = mock(org.gms.client.Character.class, RETURNS_DEEP_STUBS);
        when(bot.getId()).thenReturn(botId);
        when(bot.getPosition()).thenAnswer(inv -> new java.awt.Point(0, 0));
        BotMovementState st = new BotMovementState(bot, null);
        st.climbing = climbing;
        st.climbPositionHeld = held;
        st.resting = resting;
        st.grinding = grinding;
        Field f = GCMovement.class.getDeclaredField("STATES");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Integer, BotMovementState> states = (Map<Integer, BotMovementState>) f.get(null);
        states.put(botId, st);
        return st;
    }

    @Test
    void aGrindBreakRestHangIsExempt() throws Exception {
        BotMovementState st = stateFor(1, true, false, true, true);
        assertTrue(GCMovement.isHoldingClimb(st.bot),
                "a grind break's rope nap must not be interrupted by the stall sampler");
    }

    @Test
    void aGrinderThatMerelyArrivedMidRopeIsNotExempt() throws Exception {
        BotMovementState st = stateFor(2, true, true, false, true);
        assertFalse(GCMovement.isHoldingClimb(st.bot),
                "a grinding bot must keep the stall net armed: GrindBrain returns early while "
                        + "isClimbing and RoamStrategy issues no move while the mob's x stays put, "
                        + "so this 1.2s net is the only thing that frees a goal-less grinder parked "
                        + "on a rope (measured: dismount at tick 6, ~1.5s)");
    }

    @Test
    void aNonGrinderExplicitHoldIsExempt() throws Exception {
        BotMovementState st = stateFor(3, true, true, false, false);
        assertTrue(GCMovement.isHoldingClimb(st.bot),
                "GM !gcmove stop / travel and PQ arrivals keep their explicit rope hang");
    }

    @Test
    void aPlainClimberIsNotExempt() throws Exception {
        BotMovementState st = stateFor(4, true, false, false, false);
        assertFalse(GCMovement.isHoldingClimb(st.bot),
                "an ordinary climb in progress is not a hold");
    }

    @Test
    void aGroundedBotIsNotExempt() throws Exception {
        BotMovementState st = stateFor(5, false, true, true, false);
        assertFalse(GCMovement.isHoldingClimb(st.bot),
                "the predicate is about a climber; a grounded bot holding stale flags is not one");
    }
}
