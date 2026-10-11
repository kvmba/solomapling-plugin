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

import java.util.List;

import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Root-cause regression for the rope-head ballistic exit retention (fb4cb83 / 4c1b8fc / review F1).
 *
 * <p>The executor fires a ballistic straight-up arc for a launchStepX==0 CLIMB exit only when the
 * exit's landing sits ABOVE the rope-side anchor (an upward exit) and physics cannot own the
 * landing; the flight must then stay committed for the whole arc, or a mid-air replan steers the
 * bot off the edge's authored landing path.
 *
 * <p>Retention used to be decided by an edge-geometry classifier ({@code isBallisticClimbExit})
 * whose y-direction test was INVERTED: on the real 922010100 graph it claimed 83 edges of which
 * 0 were edges the fire branch actually fires (the fire set is the 42 upward same-x exits). The
 * review also found the classifier unpinned — flipping its sign kept the whole suite green.
 *
 * <p>The fix records the fired edge at the launch site ({@code BotMovementState.navFlightEdge})
 * and reads that single fact in the retention clause; the geometry classifier is gone. This test
 * pins the contract end to end on the real WZ graph: every edge the fire branch can launch is an
 * upward same-x exit, and each such edge, once recorded as the flight's edge, is retained by
 * {@link BotNavigationManager#reuseCommittedEdge} for the whole flight.
 */
public class RopeHeadBallisticRetentionTest {

    @BeforeAll
    static void stubHostEnvironment() throws Exception {
        ApplicationContext ctx = mock(ApplicationContext.class, RETURNS_DEEP_STUBS);
        ConfigService configService = mock(ConfigService.class);
        when(configService.loadGameConfigs()).thenReturn(List.<GameConfigDO>of());
        when(ctx.getBean(ConfigService.class)).thenReturn(configService);
        ServiceProperty props = mock(ServiceProperty.class);
        when(props.getLanguage()).thenReturn("zh-CN");
        when(ctx.getBean(ServiceProperty.class)).thenReturn(props);
        var setter = ServerManager.class.getDeclaredMethod("setApplicationContext", ApplicationContext.class);
        setter.setAccessible(true);
        setter.invoke(new ServerManager(), ctx);
        GameConfig.add(config("server", "update_interval", "100"));
        org.gms.net.server.Server.getInstance();
        org.junit.jupiter.api.Assumptions.assumeTrue(mapAvailable(), "Map.wz needed");
    }

    static boolean mapAvailable() {
        try {
            var s = org.gms.provider.DataProviderFactory.getDataProvider(org.gms.provider.wz.WZFiles.MAP);
            return s.getData("Map/Map9/922010100.img") != null;
        } catch (Throwable t) {
            return false;
        }
    }

    static GameConfigDO config(String type, String key, String value) {
        GameConfigDO d = new GameConfigDO();
        d.setConfigType(type);
        d.setConfigSubType("0");
        d.setConfigCode(key);
        d.setConfigValue(value);
        d.setConfigClazz("java.lang.Long");
        return d;
    }

    @Test
    void theFireBranchFiresUpwardExitsRegardlessOfLandingX() throws Exception {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(922010100);
        BotNavigationGraph g = BotNavigationGraphProvider.getGraph(map, BotMovementProfile.base());
        var f = BotNavigationGraph.class.getDeclaredField("outgoingByRegionId");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        var outgoing = (java.util.Map<Integer, List<BotNavigationGraph.Edge>>) f.get(g);
        int upward = 0;
        int downward = 0;
        int upwardOffX = 0;
        for (var e : outgoing.entrySet()) {
            var fromRegion = g.getRegion(e.getKey());
            if (fromRegion == null || !fromRegion.isRopeRegion) {
                continue; // the fire branch only runs for rope-side exits
            }
            for (BotNavigationGraph.Edge edge : e.getValue()) {
                if (edge.type != BotNavigationGraph.EdgeType.CLIMB || edge.launchStepX != 0) {
                    continue;
                }
                boolean up = edge.endPoint.y < edge.startPoint.y;
                if (up) {
                    upward++;
                    if (edge.startPoint.x != edge.endPoint.x) {
                        upwardOffX++;
                    }
                } else {
                    downward++;
                }
            }
        }
        org.junit.jupiter.api.Assertions.assertTrue(upward > 0,
                "the real tower must carry upward ballistic exits (the fb4cb83 shape)");
        org.junit.jupiter.api.Assertions.assertEquals(0, upwardOffX,
                "a straight-up rope-head exit keeps its column: an off-x landing belongs to the "
                        + "ground->rope entry class, never the exit class");
        // The fire branch keys on the DIRECTION alone (endPoint.y < startPoint.y). The landing x is
        // NOT part of the contract, and the fired shape cannot be re-derived safely from geometry:
        // the old classifier's x-equality clause only matched some of the exits and the y clause
        // matched none — which is why the launch-time fact replaced the classifier entirely.
        org.junit.jupiter.api.Assertions.assertTrue(downward > 0,
                "the real tower also carries downward exits (physics-owned climb-downs)");
    }

    @Test
    void aRecordedFlightEdgeIsRetainedThroughTheFlight() throws Exception {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(922010100);
        BotNavigationGraph g = BotNavigationGraphProvider.getGraph(map, BotMovementProfile.base());
        var f = BotNavigationGraph.class.getDeclaredField("outgoingByRegionId");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        var outgoing = (java.util.Map<Integer, List<BotNavigationGraph.Edge>>) f.get(g);

        BotNavigationGraph.Edge ballistic = null;
        for (var e : outgoing.entrySet()) {
            var fromRegion = g.getRegion(e.getKey());
            if (fromRegion == null || !fromRegion.isRopeRegion) continue;
            for (BotNavigationGraph.Edge edge : e.getValue()) {
                if (edge.type == BotNavigationGraph.EdgeType.CLIMB && edge.launchStepX == 0
                        && edge.endPoint.y < edge.startPoint.y
                        && edge.startPoint.x == edge.endPoint.x) {
                    ballistic = edge;
                    break;
                }
            }
            if (ballistic != null) break;
        }
        org.junit.jupiter.api.Assertions.assertNotNull(ballistic,
                "a ballistic rope-head exit must exist on this tower");

        // The state right after the launch: airborne, the flight's edge recorded at the launch
        // site (the new single source of truth).
        org.gms.client.Character bot = mock(org.gms.client.Character.class, RETURNS_DEEP_STUBS);
        when(bot.getMap()).thenReturn(map);
        when(bot.getMapId()).thenReturn(922010100);
        when(bot.getId()).thenReturn(9310);
        when(bot.getPosition()).thenReturn(new java.awt.Point(ballistic.startPoint.x, ballistic.startPoint.y - 20));
        BotMovementState st = new BotMovementState(bot, null);
        st.movementProfile = BotMovementProfile.base();
        st.lastMapId = 922010100;
        st.navEdge = ballistic;
        st.inAir = true;
        st.navFlightEdge = ballistic; // recorded by the launch site
        st.navTargetRegionId = ballistic.toRegionId;

        BotNavigationGraph.Edge kept = BotNavigationManager.reuseCommittedEdge(g, st, -1, ballistic.toRegionId);
        org.junit.jupiter.api.Assertions.assertSame(ballistic, kept,
                "the flight's own edge must stay committed through the whole arc");

        // The same edge with NO flight fact (e.g. a hop, or a stale edge that never fired) is not
        // exempt from replanning: the retention clause keys on the recorded fact, not on geometry.
        st.navFlightEdge = null;
        BotNavigationGraph.Edge dropped = BotNavigationManager.reuseCommittedEdge(g, st, -1, ballistic.toRegionId);
        org.junit.jupiter.api.Assertions.assertNull(dropped,
                "without a recorded flight the edge must be replannable (the old classifier would "
                        + "have kept the wrong (downward) edge here and dropped the real one)");
    }
}
