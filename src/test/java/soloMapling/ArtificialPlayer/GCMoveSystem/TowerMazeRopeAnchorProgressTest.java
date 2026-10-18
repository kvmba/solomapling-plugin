package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.config.GameConfig;
import org.gms.dao.entity.GameConfigDO;
import org.gms.manager.ServerManager;
import org.gms.property.ServiceProperty;
import org.gms.server.maps.MapleMap;
import org.gms.service.ConfigService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.awt.Point;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * REGRESSION for the "Tower's Maze bots only let go and drop straight down" report
 * (LPQ stage-5 rooms 922010501-506), pinned on the REAL map geometry and the PRODUCTION
 * driver tick.
 *
 * <p>The rooms' side ledges are reached by a rope: the bot climbs to an authored jump-off
 * anchor and then leaps sideways onto the ledge that holds the pass box. The macro layer issues
 * ONE move toward the box's FLOOR and re-issues it only after the driver drops it - exactly
 * {@code PqActions.approachTo}'s contract. Climbing to the anchor moves the bot AWAY from that
 * floor, so the driver's no-progress watchdog read a healthy climb as a wedge, abandoned the
 * move and dismounted the climber with {@code jumpOffRope(dx=0)}; beside the long rope there is
 * no landable column, so that dismount is a pure vertical drop, the bot fell back to a lower
 * row, re-planned, climbed again and repeated forever.
 *
 * <p>The fix measures progress toward the committed nav edge's own waypoint while an edge is
 * committed, restarting the measurement per edge. These legs must therefore reach the box
 * floors, and no rope exit on the way may be a dx=0 vertical drop.
 */
public class TowerMazeRopeAnchorProgressTest {

    @BeforeAll
    static void stubHostEnvironment() throws Exception {
        ApplicationContext ctx = mock(ApplicationContext.class, RETURNS_DEEP_STUBS);
        ConfigService configService = mock(ConfigService.class);
        when(configService.loadGameConfigs()).thenReturn(List.<GameConfigDO>of());
        when(ctx.getBean(ConfigService.class)).thenReturn(configService);
        ServiceProperty props = mock(ServiceProperty.class);
        when(props.getLanguage()).thenReturn("zh-CN");
        when(ctx.getBean(ServiceProperty.class)).thenReturn(props);
        org.springframework.context.MessageSource messageSource =
                mock(org.springframework.context.MessageSource.class);
        when(messageSource.getMessage(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(Object[].class),
                org.mockito.ArgumentMatchers.any(java.util.Locale.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        for (String name : new String[]{"messageSource", "logSource", "exceptionSource"}) {
            when(ctx.getBean(org.mockito.ArgumentMatchers.eq(name),
                    org.mockito.ArgumentMatchers.eq(org.springframework.context.MessageSource.class)))
                    .thenReturn(messageSource);
        }
        var setter = ServerManager.class.getDeclaredMethod("setApplicationContext", ApplicationContext.class);
        setter.setAccessible(true);
        setter.invoke(new ServerManager(), ctx);
        GameConfig.add(config("server", "update_interval", "100"));
        org.gms.net.server.Server.getInstance();
        MovementClock.useVirtual(1_000_000L);
        Assumptions.assumeTrue(mapWzAvailable(),
                "Map.wz not resolvable from this working directory (sim needs the real WZ)");
    }

    @AfterAll
    static void restoreClock() {
        MovementClock.reset();
    }

    static boolean mapWzAvailable() {
        try {
            return org.gms.provider.DataProviderFactory.getDataProvider(org.gms.provider.wz.WZFiles.MAP)
                    .getData("Map/Map9/922010502.img") != null;
        } catch (Throwable unavailable) {
            return false;
        }
    }

    private static GameConfigDO config(String type, String key, String value) {
        GameConfigDO d = new GameConfigDO();
        d.setConfigType(type);
        d.setConfigSubType("0");
        d.setConfigCode(key);
        d.setConfigValue(value);
        d.setConfigClazz("java.lang.Long");
        return d;
    }

    private record Sim(BotMovementState st, AtomicReference<Point> pos) {
        Point p() {
            return pos.get();
        }
    }

    private static Sim sim(int mapId, int sx, int sy, int botId, BotMovementProfile profile) {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(mapId);
        BotNavigationGraphProvider.getGraph(map, profile);
        AtomicReference<Point> pos = new AtomicReference<>(new Point(sx, sy));
        org.gms.client.Character bot = mock(org.gms.client.Character.class, RETURNS_DEEP_STUBS);
        when(bot.getMap()).thenReturn(map);
        when(bot.getMapId()).thenReturn(mapId);
        when(bot.getId()).thenReturn(botId);
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
        BotPhysicsEngine.teleportTo(st, bot, new Point(sx, sy));
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

    private static void tick(Sim sim, int mapId) {
        ObserverTracker.markObservedNow(mapId);
        try {
            TICK.invoke(null, sim.st());
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e.getCause() != null ? e.getCause() : e);
        }
        MovementClock.advance(BotPhysicsEngine.cfg.TICK_MS);
    }

    /** Exactly GCMovement.move(): a fresh goal resets the stall bookkeeping. */
    private static void issueMove(BotMovementState st, int x, int y) {
        st.following = false;
        st.farmAnchor = null;
        st.farmAnchorMapId = -1;
        st.moveTarget = new Point(x, y);
        st.moveTargetPrecise = true;
        st.moveTargetSource = "gcmove";
        st.moveBestDist = Integer.MAX_VALUE;
        st.moveProgressAtMs = MovementClock.nowMs();
        st.moveProgressGoal = null;
    }

    /**
     * Walk to a box floor the way the macro layer does: one issue, re-issued only after the
     * driver dropped it. Returns whether the box's floor was reached, and counts every rope
     * exit that produced NO horizontal travel (the "let go and fall straight down" shape).
     */
    private static int[] approach(int mapId, Sim sim, int tx, int ty, int maxBeats) {
        int verticalDrops = 0;
        for (int beat = 0; beat < maxBeats; beat++) {
            if (sim.st().moveTarget == null) {
                issueMove(sim.st(), tx, ty);
            }
            for (int t = 0; t < 30; t++) {
                boolean wasClimbing = sim.st().climbing;
                Point before = sim.p();
                tick(sim, mapId);
                Point after = sim.p();
                if (wasClimbing && sim.st().inAir) {
                    int exitX = after.x;
                    int maxDx = 0;
                    for (int k = 0; k < 40 && sim.st().inAir; k++) {
                        tick(sim, mapId);
                        maxDx = Math.max(maxDx, Math.abs(sim.p().x - exitX));
                    }
                    if (maxDx <= 2) {
                        verticalDrops++;
                    }
                }
                if (Math.abs(after.x - tx) <= 90 && Math.abs(after.y - ty) <= 70) {
                    return new int[]{0, verticalDrops};
                }
            }
        }
        Point end = sim.p();
        boolean reached = Math.abs(end.x - tx) <= 90 && Math.abs(end.y - ty) <= 70;
        return new int[]{reached ? 0 : 1, verticalDrops};
    }

    private static void assertLeg(String label, int mapId, int sx, int sy, int tx, int ty,
                                  int maxBeats, BotMovementProfile profile) {
        Sim sim = sim(mapId, sx, sy, 6000 + Math.abs(label.hashCode() % 500), profile);
        int[] result = approach(mapId, sim, tx, ty, maxBeats);
        org.junit.jupiter.api.Assertions.assertEquals(0, result[0],
                label + ": the bot never reached the box floor (" + sim.p() + ")");
        org.junit.jupiter.api.Assertions.assertEquals(0, result[1],
                label + ": a rope exit dropped with no horizontal travel - the reported "
                        + "'lets go and falls straight down' (end " + sim.p() + ")");
    }

    /** 502: side ledges at -1458/-1414 above the rope, and -3013/-3009 below it. */
    @Test
    void room502SideLedges() {
        BotMovementProfile profile = new BotMovementProfile(105, 110);
        assertLeg("502 right -1414", 922010502, 179, -223, 208, -1414, 40, profile);
        assertLeg("502 left -1458", 922010502, 179, -223, -199, -1458, 40, profile);
        assertLeg("502 left -3013", 922010502, 179, -223, -204, -3013, 40, profile);
        assertLeg("502 right -3009", 922010502, 179, -223, 221, -3009, 40, profile);
    }

    /** A slow (no Haste) body must reach the same ledges - the jump is profile-validated. */
    @Test
    void room502SideLedgesAtBaseProfile() {
        BotMovementProfile profile = new BotMovementProfile(100, 100);
        assertLeg("502 right -1414 (base)", 922010502, 179, -223, 208, -1414, 40, profile);
        assertLeg("502 left -3013 (base)", 922010502, 179, -223, -204, -3013, 40, profile);
    }

    /** 503/504/505 share the two-rope shape; 506 is the guarded ladder room. */
    @Test
    void otherMazeRooms() {
        BotMovementProfile profile = new BotMovementProfile(105, 110);
        assertLeg("503 right -921", 922010503, 179, -223, 226, -921, 40, profile);
        assertLeg("504 right -1299", 922010504, 179, -223, 148, -1299, 40, profile);
        assertLeg("505 right -2014", 922010505, 179, -223, 227, -2014, 40, profile);
        assertLeg("506 left -630", 922010506, 179, -223, -83, -630, 40, profile);
    }
}
