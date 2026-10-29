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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * A rope whose bottom hangs far above its landing is a LEGAL route, not a defect.
 *
 * <p>910020300 ("도움닫기 발판으로 가는 점프대", hidden street) is built entirely out of these: a row
 * of 76-435px grab ropes hangs at y~-4013 over floors 900-2800px below. The intended play is to
 * jump diagonally onto a rope, then let go and drop straight down. 204 ropes in 77 maps have a
 * bottom-to-landing gap over 600px, 146 of them on ropes shorter than 300px.
 *
 * <p>This test exists to veto a class of "fix" that looks reasonable and is not: adding a
 * maximum-dismount-drop guard so a bot never lets go into a deep fall. That guard would strand
 * every bot on these ropes (nothing to sidestep onto, and the rope is high above the map floor)
 * and leave it hanging until a rescue teleport. The deep drop must stay allowed; only the
 * <em>reason</em> for letting go is allowed to change.
 */
public class DeepDropRopeLegalityTest {

    private static final int MAP_ID = 910020300;

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

    /**
     * Grab the high rope at x=129 (span -4013..-3878) whose floor sits 2792px below, aim at that
     * floor, and require the bot to arrive on it. Any "you may not drop that far" guard turns
     * this into a permanent rope hang.
     */
    @Test
    void ropeBottomHangingFarAboveItsFloorStillDrops() {
        BotMovementProfile profile = new BotMovementProfile(105, 110);
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(MAP_ID);
        BotNavigationGraphProvider.getGraph(map, profile);

        Rope rope = null;
        for (Rope r : map.getRopes()) {
            if (r.x() == 129) {
                rope = r;
            }
        }
        assertNotNull(rope, "the high grab rope at x=129 is the case this test pins");
        Point floor = BotPhysicsEngine.findGroundPoint(map, new Point(129, rope.bottomY() + 1));
        assertNotNull(floor, "the rope must have a real landing below it");
        assertTrue(floor.y - rope.bottomY() > 600,
                "this rope is the deep-drop case (gap " + (floor.y - rope.bottomY()) + "px)");

        AtomicReference<Point> pos = new AtomicReference<>(new Point(129, rope.bottomY()));
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
        BotPhysicsEngine.teleportTo(st, bot, new Point(129, rope.bottomY()));
        BotMovementManager.resetEntryStateAfterTeleport(st);
        BotPhysicsEngine.attachToRope(st, bot, rope, rope.bottomY());

        st.following = false;
        st.farmAnchor = null;
        st.farmAnchorMapId = -1;
        st.moveTarget = new Point(129, floor.y);
        st.moveTargetPrecise = true;
        st.moveTargetSource = "gcmove";
        st.moveBestDist = Integer.MAX_VALUE;
        st.moveProgressAtMs = MovementClock.nowMs();

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
            if (!st.climbing && !st.inAir) {
                break;
            }
        }
        Point end = pos.get();
        Foothold fh = BotPhysicsEngine.findGroundFoothold(map, end);
        assertNotNull(fh, "the bot must land, not hang: ended at " + end);
        assertTrue(end.y > rope.bottomY() + 600,
                "the bot must actually complete the deep drop, ended at " + end
                        + " (rope bottom " + rope.bottomY() + ")");
    }
}
