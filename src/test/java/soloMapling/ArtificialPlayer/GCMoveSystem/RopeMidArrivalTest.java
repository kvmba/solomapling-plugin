package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.config.GameConfig;
import org.gms.dao.entity.GameConfigDO;
import org.gms.manager.ServerManager;
import org.gms.property.ServiceProperty;
import org.gms.server.maps.Foothold;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Rope;
import org.gms.service.ConfigService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.awt.Point;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * A mid-rope move target must ARRIVE on the rope, not hang until the stall watchdog drops the bot.
 *
 * <p>920010300 (OPQ stage 2, 雅典娜禁地&lt;仓库&gt;) hangs a 3224px ladder at x=-44 whose whole span
 * has the room's floor far below it. A move whose goal is a point on that ladder (a followed
 * player who stopped climbing, a rope rest hang point) used to be unreachable by construction:
 * the rope-arrival branch in {@code clearReachedMoveTarget} also required
 * {@code findGroundFoothold(moveTarget) == null}, which is false on every rope that ends over
 * ground — 9991 of the game's 10032 ropes (99.6%). The goal therefore never cleared, the no-progress
 * watchdog measured no approach, and after 3s {@code giveUpStalledMove} dismounted the climber
 * into a straight drop: measured 2959px here, and the bot then re-climbs from the bottom.
 *
 * <p>The fix must keep the original guard's intent: a target that has a real landing between it
 * and the rope's bottom is NOT a mid-rope hang (there is a platform on that column), so it must
 * keep climbing to it instead of stopping in the air above it.
 */
public class RopeMidArrivalTest {

    private static final int MAP_ID = 920010300;
    private static final int LADDER_X = -44;

    @BeforeAll
    static void stubHostEnvironment() throws Exception {
        MovementClock.useVirtual(1_000_000L);
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
    static void restoreClock() {
        MovementClock.reset();
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

    private static final java.lang.reflect.Method TICK;

    static {
        try {
            TICK = GCMovementDriver.class.getDeclaredMethod("tick", BotMovementState.class);
            TICK.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private record Sim(BotMovementState st, AtomicReference<Point> pos, MapleMap map, Rope ladder) {
        Point p() {
            return pos.get();
        }
    }

    private static Sim simOnLadder(int y) {
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
        when(bot.getId()).thenReturn(777);
        when(bot.getHp()).thenReturn(5000);
        when(bot.getChair()).thenReturn(0);
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
        return new Sim(st, pos, map, ladder);
    }

    private static void moveTo(BotMovementState st, int x, int y) {
        st.following = false;
        st.farmAnchor = null;
        st.farmAnchorMapId = -1;
        st.moveTarget = new Point(x, y);
        st.moveTargetPrecise = true;
        st.moveTargetSource = "gcmove";
        st.moveBestDist = Integer.MAX_VALUE;
        st.moveProgressAtMs = MovementClock.nowMs();
    }

    private static int[] run(Sim sim, int ticks) {
        int dismountTick = -1;
        int dismountY = Integer.MIN_VALUE;
        for (int i = 0; i < ticks; i++) {
            if (i % 20 == 0) {
                ObserverTracker.markObservedNow(MAP_ID);
            }
            boolean before = sim.st().climbing;
            try {
                TICK.invoke(null, sim.st());
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e.getCause() != null ? e.getCause() : e);
            }
            MovementClock.advance(BotPhysicsEngine.cfg.TICK_MS);
            if (before && !sim.st().climbing && dismountTick < 0) {
                dismountTick = i;
                dismountY = sim.p().y;
            }
        }
        return new int[]{dismountTick, dismountY};
    }

    /** The bug: a mid-rope goal is reached, then the 3s watchdog drops the bot 2959px. */
    @Test
    void midRopeGoalArrivesWithoutFalling() {
        Sim sim = simOnLadder(-3200);
        moveTo(sim.st(), LADDER_X, -3200);

        int[] result = run(sim, 200);

        assertNull(sim.st().moveTarget,
                "the mid-rope goal must be cleared by the arrival rule, not left dangling");
        assertTrue(sim.st().climbPositionHeld,
                "arriving mid-rope must become an explicit hang (climbPositionHeld)");
        assertTrue(sim.st().climbing,
                "the bot must stay on the ladder: it let go at tick " + result[0]
                        + " (y=" + result[1] + ") and fell " + (sim.p().y - result[1]) + "px");
        assertEquals(-3200, sim.p().y, 8,
                "the bot must hold the y it arrived at, ended at " + sim.p());
    }

    /** Climb a long way first, then receive a mid-rope goal: still must not be dropped. */
    @Test
    void climbingThenMidRopeGoalDoesNotFall() {
        Sim sim = simOnLadder(-800);
        moveTo(sim.st(), LADDER_X, -2000);

        int[] result = run(sim, 600);

        assertTrue(sim.st().climbing,
                "the climber was dropped at tick " + result[0] + " (y=" + result[1] + "), fell "
                        + (sim.p().y - result[1]) + "px");
        // The climb grid is 5px/tick, so an 8px precise arrival may settle up to one step short
        // of the goal y - that is the same tolerance reachedMoveTarget uses.
        assertEquals(-2000, sim.p().y, 8,
                "the bot must stop and hang at its goal, ended at " + sim.p());
    }

    /**
     * The original guard's intent must survive: a rope whose column has a real landing between the
     * target and the rope bottom is NOT a mid-rope hang. The bot has to keep climbing (or step off
     * onto that landing), never stop hanging in the air above it.
     */
    @Test
    void targetAboveARealLandingIsNotAMidRopeHang() {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(MAP_ID);
        Rope ladder = null;
        for (Rope r : map.getRopes()) {
            if (r.x() == LADDER_X) {
                ladder = r;
            }
        }
        // the room floor at y=-241 spans the ladder column: a target between it and the rope
        // bottom has ground underneath, which is exactly what the original guard keyed on.
        Point target = new Point(LADDER_X, -400);
        Foothold below = BotPhysicsEngine.findGroundFoothold(map, target);
        assertNotNull(below, "this column has a landing below the target (the room floor)");
        assertFalse(BotPhysicsEngine.hasLandingBetween(map, target, ladder.bottomY()),
                "there is no landing BETWEEN the target and the rope bottom, so the target is a "
                        + "mid-rope hang and must be allowed to arrive");
    }
}
