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

import java.awt.Point;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Documents what the arrival rule deliberately does NOT do, so nobody "fixes" it by accident.
 *
 * <p>A rope whose own column carries a ledge inside its span (82 ropes across the game, e.g.
 * 701010310 x=165 with a ledge at y=349) does NOT treat a goal between its top and that ledge as a
 * mid-rope hang. The bot keeps climbing instead of stopping in the air above the ledge — that is
 * the rule's original intent and it stays. What happens next when the goal can never be reached
 * (it sits above a ledge, in the rope's top band) is the pre-existing behaviour of the dismount
 * guard: the bot steps onto the neighbouring walkable surface with the goal still set.
 *
 * <p>Pinning the NEGATIVE half matters: {@link RopeMidArrivalTest} covers the open-shaft shape
 * that this rule now fixes, and a future reader must not widen the rule to cover this shape too
 * without also proving the ledge case still climbs.
 */
public class RopeArrivalRuleScopeTest {

    private static final int MAP_ID = 701010310;
    private static final int ROPE_X = 165;

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

    private record Sim(BotMovementState st, AtomicReference<Point> pos, Rope rope) {
        Point p() {
            return pos.get();
        }
    }

    private static Sim simOnRope() {
        BotMovementProfile profile = new BotMovementProfile(105, 110);
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(MAP_ID);
        BotNavigationGraphProvider.getGraph(map, profile);
        Rope rope = null;
        for (Rope r : map.getRopes()) {
            if (r.x() == ROPE_X) {
                rope = r;
            }
        }
        assertNotNull(rope, "the rope at x=165 on 701010310");
        AtomicReference<Point> pos = new AtomicReference<>(new Point(ROPE_X, 400));
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
        BotPhysicsEngine.teleportTo(st, bot, new Point(ROPE_X, 400));
        BotMovementManager.resetEntryStateAfterTeleport(st);
        BotPhysicsEngine.attachToRope(st, bot, rope, 400);
        return new Sim(st, pos, rope);
    }

    /** A goal well clear of the in-span ledge is an ordinary mid-rope hang: it arrives and holds. */
    @Test
    void aGoalBelowTheInSpanLedgeIsAMidRopeHang() {
        Sim sim = simOnRope();
        BotMovementState st = sim.st();
        st.following = false;
        st.moveTarget = new Point(ROPE_X, 500);
        st.moveTargetPrecise = true;
        st.moveTargetSource = "gcmove";
        st.moveBestDist = Integer.MAX_VALUE;
        st.moveProgressAtMs = MovementClock.nowMs();

        for (int i = 0; i < 400 && st.climbing; i++) {
            if (i % 20 == 0) {
                ObserverTracker.markObservedNow(MAP_ID);
            }
            try {
                TICK.invoke(null, st);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e.getCause() != null ? e.getCause() : e);
            }
            MovementClock.advance(BotPhysicsEngine.cfg.TICK_MS);
        }
        assertTrue(st.climbing, "a goal below the in-span ledge must hold on the rope");
        assertTrue(st.climbPositionHeld, "and must become an explicit hold");
        assertFalse(BotPhysicsEngine.hasLandingBetween(sim.st().bot.getMap(),
                new Point(ROPE_X, 500), sim.rope().bottomY()),
                "nothing lies between that goal and the rope bottom");
    }

    /**
     * A goal in the band above the in-span ledge keeps the original behaviour: the arrival rule
     * refuses on the rope (a ledge lies further down the column). What happens instead is the
     * graph's own climb+jump-off edge onto that ledge, so the bot still gets there — measured on
     * 701010310 (rope 336..780, ledge 349) as 0.45s off the rope and 1.30s standing at (159,349).
     * This is NOT a hang, and the rule must not be widened to swallow the band.
     */
    @Test
    void aGoalAboveTheInSpanLedgeKeepsTheOriginalRefusalAndStillReachesTheLedge() {
        Sim sim = simOnRope();
        BotMovementState st = sim.st();
        // the goal must be described precisely: the rope's own top band (rope 336..780)
        Point goal = new Point(ROPE_X, 346);
        assertTrue(BotPhysicsEngine.hasLandingBetween(st.bot.getMap(), goal, sim.rope().bottomY()),
                "the ledge at y=349 lies between a goal at y=346 and the rope bottom, so the "
                        + "arrival rule must refuse - that refusal is the rule's original intent");

        st.following = false;
        st.moveTarget = new Point(goal);
        st.moveTargetPrecise = true;
        st.moveTargetSource = "gcmove";
        st.moveBestDist = Integer.MAX_VALUE;
        st.moveProgressAtMs = MovementClock.nowMs();

        boolean landed = false;
        for (int i = 0; i < 600; i++) {
            if (i % 20 == 0) {
                ObserverTracker.markObservedNow(MAP_ID);
            }
            try {
                TICK.invoke(null, st);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e.getCause() != null ? e.getCause() : e);
            }
            MovementClock.advance(BotPhysicsEngine.cfg.TICK_MS);
            if (!st.climbing && !st.inAir
                    && BotPhysicsEngine.findGroundFoothold(st.bot.getMap(), sim.p()) != null) {
                landed = true;
                break;
            }
        }
        assertTrue(landed, "the bot must end up standing on a foothold, not fall or hang; ended at "
                + sim.p());
        assertTrue(sim.p().y >= 340 && sim.p().y <= 360,
                "and it must be on the in-span ledge (y=349), ended at " + sim.p());
    }
}
