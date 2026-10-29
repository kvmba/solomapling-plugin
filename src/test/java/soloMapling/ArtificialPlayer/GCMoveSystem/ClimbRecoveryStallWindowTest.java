package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.config.GameConfig;
import org.gms.dao.entity.GameConfigDO;
import org.gms.manager.ServerManager;
import org.gms.property.ServiceProperty;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Rope;
import org.gms.service.ConfigService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import soloMapling.ArtificialPlayer.BotGrindSystem.GrindBrain;

import java.awt.Point;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link ClimbRecovery}'s 1.2s stall window and 3s dismount grace, driven on the shared movement
 * clock so the window elapses with zero real waiting.
 *
 * <p>This is the coverage the predicate-level {@code ClimbHoldExemptionTest} cannot reach: that one
 * only asks {@code GCMovement.isHoldingClimb}, never the sampler. Before this refactor ClimbRecovery
 * read {@code System.currentTimeMillis()} directly, so no test could reach the 1.2s branch without
 * sleeping — which is exactly the hole that let a deliberate rope hold be dismounted for a year.
 */
public class ClimbRecoveryStallWindowTest {

    private static final int MAP_ID = 920010300;
    private static final int LADDER_X = -44;

    @BeforeAll
    static void stubHostEnvironment() throws Exception {
        MovementClock.reset(); // these tests own the clock
        ApplicationContext ctx = mock(ApplicationContext.class, RETURNS_DEEP_STUBS);
        ConfigService configService = mock(ConfigService.class);
        when(configService.loadGameConfigs()).thenReturn(List.<GameConfigDO>of());
        when(ctx.getBean(ConfigService.class)).thenReturn(configService);
        ServiceProperty props = mock(ServiceProperty.class);
        when(props.getLanguage()).thenReturn("zh-CN");
        when(ctx.getBean(ServiceProperty.class)).thenReturn(props);
        org.springframework.context.MessageSource ms = mock(org.springframework.context.MessageSource.class);
        when(ms.getMessage(any(), any(Object[].class), any(java.util.Locale.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(ctx.getBean(eq("messageSource"), eq(org.springframework.context.MessageSource.class))).thenReturn(ms);
        when(ctx.getBean(eq("logSource"), eq(org.springframework.context.MessageSource.class))).thenReturn(ms);
        when(ctx.getBean(eq("exceptionSource"), eq(org.springframework.context.MessageSource.class))).thenReturn(ms);
        var setter = ServerManager.class.getDeclaredMethod("setApplicationContext", ApplicationContext.class);
        setter.setAccessible(true);
        setter.invoke(new ServerManager(), ctx);
        GameConfig.add(config("server", "update_interval", "100"));
        org.gms.net.server.Server.getInstance();
    }

    @AfterAll
    static void restoreClock() throws Exception {
        MovementClock.reset();
        Field f = GCMovement.class.getDeclaredField("STATES");
        f.setAccessible(true);
        ((Map<?, ?>) f.get(null)).clear();
    }

    private static GameConfigDO config(String t, String k, String v) {
        GameConfigDO d = new GameConfigDO();
        d.setConfigType(t);
        d.setConfigSubType("0");
        d.setConfigCode(k);
        d.setConfigValue(v);
        d.setConfigClazz("java.lang.Long");
        return d;
    }

    private record Sim(BotMovementState st, AtomicReference<Point> pos, Rope ladder) {
        Point p() {
            return pos.get();
        }
    }

    /** A bot hanging still on the 3224px ladder, registered so GCMovement's state reads work. */
    private static Sim simOnLadder(int y, boolean grinding, boolean resting, boolean held) throws Exception {
        BotMovementProfile profile = new BotMovementProfile(105, 110);
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(MAP_ID);
        BotNavigationGraphProvider.getGraph(map, profile);
        Rope ladder = null;
        for (Rope r : map.getRopes()) {
            if (r.x() == LADDER_X) {
                ladder = r;
            }
        }
        assertNotNull(ladder, "the 3224px ladder at x=-44");
        AtomicReference<Point> pos = new AtomicReference<>(new Point(LADDER_X, y));
        org.gms.client.Character bot = mock(org.gms.client.Character.class, RETURNS_DEEP_STUBS);
        when(bot.getMap()).thenReturn(map);
        when(bot.getMapId()).thenReturn(MAP_ID);
        when(bot.getId()).thenReturn(700 + y + (grinding ? 1 : 0) + (resting ? 2 : 0) + (held ? 4 : 0));
        when(bot.getHp()).thenReturn(5000);
        when(bot.getChair()).thenReturn(0);
        when(bot.getName()).thenReturn("Climber");
        when(bot.getPosition()).thenAnswer(inv -> pos.get());
        doAnswer(inv -> {
            pos.set(new Point(inv.getArgument(0)));
            return null;
        }).when(bot).setPosition(any());
        BotMovementState st = new BotMovementState(bot, null);
        st.movementProfile = profile;
        st.lastProfileRefreshMs = Long.MAX_VALUE / 4;
        st.lastMap = map;
        st.fhIndex = BotMovementManager.buildFhIndex(map);
        BotPhysicsEngine.teleportTo(st, bot, new Point(LADDER_X, y));
        BotMovementManager.resetEntryStateAfterTeleport(st);
        BotPhysicsEngine.attachToRope(st, bot, ladder, y);
        st.grinding = grinding;
        st.resting = resting;
        st.climbPositionHeld = held;

        Field f = GCMovement.class.getDeclaredField("STATES");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Integer, BotMovementState> states = (Map<Integer, BotMovementState>) f.get(null);
        states.put(bot.getId(), st);
        return new Sim(st, pos, ladder);
    }

    /**
     * A grind bot parked on a rope with no goal: the sampler must still free it after 1.2s.
     * This is the net ClimbHoldExemptionTest documents as load-bearing; here it is actually
     * exercised, on the virtual clock.
     */
    @Test
    void anUnheldParkedClimberIsDismountedAfterTheStallWindow() throws Exception {
        MovementClock.useVirtual(1_000_000L);
        Sim sim = simOnLadder(-2000, true, false, false);
        GrindBrain brain = new GrindBrain(msg -> { });

        // The clock starts on the first sample (climbStallSinceMs == 0), so the window is
        // measured from there: 4 further samples = 1.0s, still inside 1.2s.
        ObserverTracker.markObservedNow(MAP_ID);
        brain.tick(sim.st().bot);              // t=0ms, arms the window
        for (int i = 0; i < 4; i++) {
            MovementClock.advance(250);
            ObserverTracker.markObservedNow(MAP_ID); // the force TTL is 2s on the same clock
            brain.tick(sim.st().bot);          // t=250..1000ms
        }
        assertTrue(sim.st().climbing,
                "1.0s of stillness must not dismount inside the 1.2s window");
        assertFalse(GCMovement.isHoldingClimb(sim.st().bot),
                "an unheld parked climber is not an explicit hold");

        // cross the window: 1.25s
        MovementClock.advance(250);
        ObserverTracker.markObservedNow(MAP_ID);
        brain.tick(sim.st().bot);
        assertFalse(sim.st().climbing,
                "past 1.2s the stall net must dismount the parked climber");
    }

    /** A grind break's rest hang is exempt: 10s of stillness must not touch it. */
    @Test
    void aRestHangSurvivesTheStallWindow() throws Exception {
        MovementClock.useVirtual(2_000_000L);
        Sim sim = simOnLadder(-2000, true, true, false);
        GrindBrain brain = new GrindBrain(msg -> { });
        for (int i = 0; i < 40; i++) { // 10s
            ObserverTracker.markObservedNow(MAP_ID);
            brain.tick(sim.st().bot);
            MovementClock.advance(250);
        }
        assertTrue(sim.st().climbing, "a rest hang is never a stall");
        assertTrue(GCMovement.isHoldingClimb(sim.st().bot),
                "resting=true must read as an explicit hold regardless of the grind flag");
    }

    /** Progress resets the window: a climber that keeps gaining height is never dismounted. */
    @Test
    void aProgressingClimbIsNeverDismounted() throws Exception {
        MovementClock.useVirtual(3_000_000L);
        Sim sim = simOnLadder(-3000, true, false, false);
        GrindBrain brain = new GrindBrain(msg -> { });
        for (int i = 0; i < 40; i++) {
            // one climb step per sample: >= CLIMB_PROGRESS_EPS over the window
            sim.pos().set(new Point(LADDER_X, sim.p().y - 10));
            ObserverTracker.markObservedNow(MAP_ID);
            brain.tick(sim.st().bot);
            MovementClock.advance(250);
            assertTrue(sim.st().climbing, "a genuine climb was cut short at sample " + i);
        }
    }
}
