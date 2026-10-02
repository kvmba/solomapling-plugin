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
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

/**
 * The one-way-island contract for the LPQ stage-1 tower (922010100): every mob shelf a bot must
 * fight on is reachable from the row above (jump / down-jump / rope) AND escapable. A shelf with
 * no inbound edge reads as unpathable to the chase and wedges the party's stage-1 clear.
 */
public class LpqTopRowDownChainDiagTest {
    @BeforeAll
    static void stub() throws Exception {
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
        GameConfig.add(cfg("server", "update_interval", "100"));
        org.gms.net.server.Server.getInstance();
        org.junit.jupiter.api.Assumptions.assumeTrue(mapWzAvailable(), "Map.wz needed");
    }

    static boolean mapWzAvailable() {
        try {
            org.gms.provider.DataProvider s = org.gms.provider.DataProviderFactory.getDataProvider(org.gms.provider.wz.WZFiles.MAP);
            return s.getData("Map/Map9/922010100.img") != null;
        } catch (Throwable t) {
            t.printStackTrace(System.out);
            return false;
        }
    }

    static GameConfigDO cfg(String t, String k, String v) {
        GameConfigDO d = new GameConfigDO();
        d.setConfigType(t);
        d.setConfigSubType("0");
        d.setConfigCode(k);
        d.setConfigValue(v);
        d.setConfigClazz("java.lang.Long");
        return d;
    }

    @Test
    void trivialProbe() {
        System.out.println("TRIVIAL RUNS");
    }

    @Test
    void everyMobShelfIsReachableAndEscapable() {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(922010100);
        BotNavigationGraph g = BotNavigationGraphProvider.getGraph(map, BotMovementProfile.base());
        int top = g.findRegionId(map, new Point(70, -3488));

        // Outgoing-edge BFS from the top: the reachable set
        Map<Integer, String> came = new LinkedHashMap<>();
        ArrayDeque<Integer> q = new ArrayDeque<>();
        came.put(top, "start");
        q.add(top);
        while (!q.isEmpty()) {
            int cur = q.poll();
            for (BotNavigationGraph.Edge e : g.getOutgoing(cur)) {
                if (!came.containsKey(e.toRegionId)) {
                    came.put(e.toRegionId, cur + " via " + e.type);
                    q.add(e.toRegionId);
                }
            }
        }

        // The stage-1 spawn table's shelves (life entries 0..24): every one must be
        // (a) IN the reachable set and (b) holding at least one outgoing edge.
        int[][] mobSpots = {
                {88, -457}, {136, -581}, {120, -722}, {-92, -763}, {179, -977},
                {-81, -1159}, {0, -1409}, {139, -1935}, {-145, -1998}, {147, -2066},
                {206, -1510}, {-205, -1513}, {156, -1746}, {-87, -1933}, {-139, -2138},
                {117, -2311}, {124, -2581}, {6, -2572}, {-99, -2583}, {88, -2852},
                {-78, -2856}, {-162, -3009}, {149, -3038}, {70, -3501}, {-208, -3289}};
        List<String> failures = new ArrayList<>();
        for (int[] p : mobSpots) {
            Point ground = BotPhysicsEngine.findGroundPoint(map, new Point(p[0], p[1] - 1));
            int r = g.findRegionId(map, ground != null ? ground : new Point(p[0], p[1]));
            if (r < 0) {
                failures.add("(" + p[0] + "," + p[1] + ") no region");
                continue;
            }
            if (!came.containsKey(r)) {
                failures.add("(" + p[0] + "," + p[1] + ") region " + r + " UNREACHABLE from top row");
            } else if (g.getOutgoing(r).isEmpty()) {
                failures.add("(" + p[0] + "," + p[1] + ") region " + r + " reachable but NO EXIT");
            }
        }
        assertTrue(failures.isEmpty(), "one-way islands on the stage-1 tower: " + failures);
    }
}
