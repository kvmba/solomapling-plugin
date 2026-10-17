package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.config.GameConfig;
import org.gms.dao.entity.GameConfigDO;
import org.gms.manager.ServerManager;
import org.gms.property.ServiceProperty;
import org.gms.server.maps.MapleMap;
import org.gms.service.ConfigService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

/**
 * Pins the path-API's UNREACHABLE / SAME-REGION distinction.
 *
 * <p>A* used to answer both "the target is on your own ledge, walk straight there" and "there is
 * no way to reach it at all" with an EMPTY path list. Callers read that one value as the first
 * meaning, so a genuinely unreachable target (different nav region, no edge chain) degraded into
 * "same region, walk directly at it" - the driver then pulsed a walk toward a goal thousands of
 * pixels away with no steering that could ever get there.
 *
 * <p>The observed case: LPQ stage 5, the map's TOP door (in01 of 922010500) sits on a ledge 100px
 * above the row beneath it. A plain 35-50 bot has 110 jump = 93px of rise, so it cannot make the
 * step; the graph correctly has no edge, and the old empty-path answer made the bot stand at the
 * row and hop in place for the whole stage.
 *
 * <p>Both meanings must therefore be distinguishable at the API boundary:
 * <ul>
 *   <li>{@code null} = unreachable (no goal state reached);</li>
 *   <li>{@code empty list} = same region, direct walk (no edges needed).</li>
 * </ul>
 */
class PathUnreachableContractTest {

    private static final int MAP = 922010500;

    @BeforeAll
    static void stub() throws Exception {
        ApplicationContext ctx = mock(ApplicationContext.class, RETURNS_DEEP_STUBS);
        ConfigService configService = mock(ConfigService.class);
        when(configService.loadGameConfigs()).thenReturn(List.<GameConfigDO>of());
        when(ctx.getBean(ConfigService.class)).thenReturn(configService);
        ServiceProperty props = mock(ServiceProperty.class);
        when(props.getLanguage()).thenReturn("zh-CN");
        when(ctx.getBean(ServiceProperty.class)).thenReturn(props);
        org.springframework.context.MessageSource ms = mock(org.springframework.context.MessageSource.class);
        when(ms.getMessage(anyString(), any(Object[].class), any(java.util.Locale.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(ctx.getBean(eq("messageSource"), eq(org.springframework.context.MessageSource.class))).thenReturn(ms);
        when(ctx.getBean(eq("logSource"), eq(org.springframework.context.MessageSource.class))).thenReturn(ms);
        when(ctx.getBean(eq("exceptionSource"), eq(org.springframework.context.MessageSource.class))).thenReturn(ms);
        var setter = ServerManager.class.getDeclaredMethod("setApplicationContext", ApplicationContext.class);
        setter.setAccessible(true);
        setter.invoke(new ServerManager(), ctx);
        GameConfig.add(cfg("server", "update_interval", "100"));
        org.gms.net.server.Server.getInstance();
        org.junit.jupiter.api.Assumptions.assumeTrue(mapAvailable(),
                "Map.wz not resolvable (this test needs the real stage-5 geometry)");
    }

    static boolean mapAvailable() {
        try {
            return org.gms.provider.DataProviderFactory.getDataProvider(org.gms.provider.wz.WZFiles.MAP)
                    .getData("Map/Map9/922010500.img") != null;
        } catch (Throwable t) {
            return false;
        }
    }

    static GameConfigDO cfg(String t, String k, String v) {
        GameConfigDO d = new GameConfigDO();
        d.setConfigType(t); d.setConfigSubType("0"); d.setConfigCode(k); d.setConfigValue(v);
        d.setConfigClazz("java.lang.Long");
        return d;
    }

    @Test
    void anUnreachableTargetIsNullNotEmpty() {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(MAP);
        Point from = BotPhysicsEngine.findGroundPoint(map, map.getPortal("st00").getPosition());
        // The top door's ledge, entered with the 35-50 jump band: the 100px step has no edge.
        Point door = BotPhysicsEngine.findGroundPoint(map, new Point(-145, -1292));
        BotMovementProfile band = new BotMovementProfile(100, 110);
        BotNavigationGraph g = BotNavigationGraphProvider.getGraph(map, band);
        int sr = g.findRegionId(map, from);
        int dr = g.findRegionId(map, door);
        assertTrue(sr != dr, "the test needs two DIFFERENT regions to be meaningful");

        List<BotNavigationGraph.Edge> path = BotNavigationManager.findPath(g, map, from, sr, dr, door);
        assertNull(path, "an unreachable cross-region target must answer null, not an empty list");
    }

    @Test
    void aSameRegionTargetStillAnswersEmpty() {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(MAP);
        Point from = BotPhysicsEngine.findGroundPoint(map, map.getPortal("st00").getPosition());
        // next00 sits on the same y=-215 row as the spawn floor.
        Point same = BotPhysicsEngine.findGroundPoint(map, map.getPortal("next00").getPosition());
        BotNavigationGraph g = BotNavigationGraphProvider.getGraph(map, new BotMovementProfile(100, 110));
        int sr = g.findRegionId(map, from);
        int tr = g.findRegionId(map, same);
        assertEquals(sr, tr, "next00 must sit on the spawn row");

        List<BotNavigationGraph.Edge> path = BotNavigationManager.findPath(g, map, from, sr, tr, same);
        assertNotNull(path, "a same-region target must answer a list, not null");
        assertTrue(path.isEmpty(), "a same-region target needs no edges: direct walk");
    }

    @Test
    void aHastedBodyFindsTheRealChain() {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(MAP);
        Point from = BotPhysicsEngine.findGroundPoint(map, map.getPortal("st00").getPosition());
        Point door = BotPhysicsEngine.findGroundPoint(map, new Point(-145, -1292));
        // 123 jump (a thief past the Haste cap) clears the step, so the SAME query has a route.
        BotNavigationGraph g = BotNavigationGraphProvider.getGraph(map, new BotMovementProfile(100, 123));
        int sr = g.findRegionId(map, from);
        int dr = g.findRegionId(map, door);
        List<BotNavigationGraph.Edge> path = BotNavigationManager.findPath(g, map, from, sr, dr, door);
        assertNotNull(path, "a body that can make the step must get a route, not null");
        assertTrue(!path.isEmpty(), "the route is a real edge chain");
    }
}
