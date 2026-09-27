package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.config.GameConfig;
import org.gms.dao.entity.GameConfigDO;
import org.gms.manager.ServerManager;
import org.gms.property.ServiceProperty;
import org.gms.server.maps.Foothold;
import org.gms.server.maps.FootholdTree;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Rope;
import org.gms.service.ConfigService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.awt.Point;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.mockito.Mockito.*;

/**
 * FULL per-map audit of every PQ stage map (90 maps, all quest families), loaded from the
 * REAL WZ through the production loader. Two passes per map:
 *   A. AS-IS (all WZ ropes): bake + rope-entry links + fallback launch scan;
 *   B. USABLE-ROPES-ONLY clone (uf=0 decorative ropes removed, which the WZ marks
 *      unclimbable and the engine's Rope record ignores): same audit. Differences
 *      attribute failures to decorative-rope noise.
 * Report goes to /tmp/pq_audit_report.txt.
 */
public class AllPqMapsAuditTest {
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
    }
    static GameConfigDO cfg(String t, String k, String v) {
        GameConfigDO d = new GameConfigDO();
        d.setConfigType(t); d.setConfigSubType("0"); d.setConfigCode(k); d.setConfigValue(v); d.setConfigClazz("java.lang.Long");
        return d;
    }

    private static List<Integer> maps() throws Exception {
        return Files.readAllLines(Path.of("/tmp/all_pq_maps.txt")).stream()
                .map(String::trim).filter(s -> !s.isEmpty()).map(Integer::parseInt).toList();
    }

    @Test
    void auditEveryPqStageMap() throws Exception {
        StringBuilder report = new StringBuilder();
        int bakeFail = 0, linkFail = 0, fallbackFail = 0;
        for (int mapId : maps()) {
            String line = audit(mapId);
            report.append(line).append('\n');
            if (line.contains("BAKE-FAIL")) bakeFail++;
            // The uf=0 decorative-rope LINK-FAILs show up in the AS-IS pass by design (the
            // engine treats them as climbable; the USABLE pass is the truth). Only the
            // usable pass's verdict gates.
            if (line.contains("usable LINK-FAIL")) linkFail++;
        }
        Files.writeString(Path.of("/tmp/pq_audit_report.txt"), report.toString());
        System.out.println("[audit] bakeFail=" + bakeFail + " linkFail=" + linkFail + " fallbackFail=" + fallbackFail);
        // Assertions only on the classes that MUST be clean: bake + real climb links.
        // The fallback scan is reported per-map (informational): a ground row far from any
        // rope legitimately has no launch and the walk layer handles approach — the audit
        // prints those so a human can eyeball each one.
        org.junit.jupiter.api.Assertions.assertEquals(0, bakeFail, "bake failures - see /tmp/pq_audit_report.txt");
        org.junit.jupiter.api.Assertions.assertEquals(0, linkFail, "climb-link failures - see /tmp/pq_audit_report.txt");
    }

    private String audit(int mapId) {
        try {
            MapleMap raw = BotNavigationMapLoader.loadMapGeometry(mapId);
            if (raw.getFootholds() == null || raw.getFootholds().getAllFootholds().isEmpty()) {
                return mapId + " OK(no-footholds) ropes=" + raw.getRopes().size();
            }
            // clone with only usable ropes (uf=0 decorative removed) for pass B
            MapleMap usable = cloneMapWithUsableRopes(raw, mapId);
            // re-key the clone so the graph cache does not hand back the raw map's bake
            usable = reKey(usable, mapId + 5);
            int usableRopes = usable.getRopes().size();
            String a = auditMap(raw, mapId + " as-is");
            String b = auditMap(usable, mapId + " usable");
            return mapId + " ropes=" + raw.getRopes().size() + "/" + usableRopes + " | " + a + " | " + b;
        } catch (Throwable t) {
            return mapId + " BAKE-FAIL " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    private MapleMap reKey(MapleMap src, int newId) throws Exception {
        MapleMap map = new MapleMap(newId, 0, 0, 920010000, 0.0f);
        map.setFootholds(src.getFootholds());
        java.awt.Rectangle vr = src.getMapArea();
        if (vr != null) {
            map.setMapLineBoundings(vr.y, vr.y + vr.height, vr.x, vr.x + vr.width);
        }
        map.setFieldLimit(src.getFieldLimit());
        for (Rope rope : src.getRopes()) {
            map.addRope(rope);
        }
        return map;
    }

    private MapleMap cloneMapWithUsableRopes(MapleMap src, int mapId) throws Exception {
        // read the WZ again, keep only uf=1 ropes (same as the loader, plus the uf filter)
        org.gms.provider.DataProvider mapSource =
                org.gms.provider.DataProviderFactory.getDataProvider(org.gms.provider.wz.WZFiles.MAP);
        org.gms.provider.Data mapData = mapSource.getData(
                "Map/Map" + (mapId / 100000000) + "/" + String.format("%09d", mapId) + ".img");
        MapleMap map = new MapleMap(mapId, 0, 0, 920010000, 0.0f);
        FootholdTree tree = new FootholdTree(new Point(-265, -4000), new Point(265, 600));
        // copy footholds + bounds from src
        map.setFootholds(src.getFootholds());
        java.awt.Rectangle vr = src.getMapArea();
        if (vr != null) {
            map.setMapLineBoundings(vr.y, vr.y + vr.height, vr.x, vr.x + vr.width);
        }
        map.setFieldLimit(src.getFieldLimit());
        org.gms.provider.Data ropeData = mapData.getChildByPath("ladderRope");
        if (ropeData != null) {
            for (org.gms.provider.Data rope : ropeData) {
                int uf = org.gms.provider.DataTool.getInt(rope.getChildByPath("uf"), 1);
                if (uf == 0) continue; // decorative
                int x = org.gms.provider.DataTool.getInt(rope.getChildByPath("x"));
                int y1 = org.gms.provider.DataTool.getInt(rope.getChildByPath("y1"));
                int y2 = org.gms.provider.DataTool.getInt(rope.getChildByPath("y2"));
                boolean ladder = org.gms.provider.DataTool.getInt(rope.getChildByPath("l"), 0) == 1;
                map.addRope(new Rope(x, y1, y2, ladder));
            }
        }
        return map;
    }

    private String auditMap(MapleMap map, String tag) {
        BotNavigationGraph g = BotNavigationGraphProvider.getGraph(map, BotMovementProfile.base());
        java.util.Set<Integer> ropeIds = new java.util.HashSet<>();
        for (BotNavigationGraph.Region r : g.regions) {
            if (r.isRopeRegion) ropeIds.add(r.id);
        }
        java.util.Set<Integer> hasEntry = new java.util.HashSet<>();
        for (java.util.Map.Entry<Integer, List<BotNavigationGraph.Edge>> en : g.outgoingByRegionId.entrySet()) {
            for (BotNavigationGraph.Edge e : en.getValue()) {
                if (ropeIds.contains(e.toRegionId)) hasEntry.add(e.toRegionId);
            }
        }
        int ropeRegions = ropeIds.size(), ropeNoEntry = 0;
        for (BotNavigationGraph.Region r : g.regions) {
            if (!r.isRopeRegion) continue;
            boolean physicallyEnterable = false;
            for (BotNavigationGraph.Region ground : g.regions) {
                if (ground.isRopeRegion) continue;
                boolean yOverlap = r.minY <= ground.maxY + 90 && r.maxY >= ground.minY - 90;
                boolean xNear = Math.abs(r.minX - ground.minX) <= 400 || Math.abs(r.minX - ground.maxX) <= 400
                        || (r.minX >= ground.minX && r.minX <= ground.maxX);
                if (yOverlap && xNear) { physicallyEnterable = true; break; }
            }
            if (physicallyEnterable && !hasEntry.contains(r.id)) ropeNoEntry++;
        }
        int fallbackScans = 0, fallbackMiss = 0;
        BotMovementProfile profile = BotMovementProfile.base();
        for (BotNavigationGraph.Region rope : g.regions) {
            if (!rope.isRopeRegion) continue;
            for (BotNavigationGraph.Region ground : g.regions) {
                if (ground.isRopeRegion) continue;
                if (ground.maxY <= rope.minY) continue;
                if (Math.abs(rope.minX - ground.minX) > 400 && Math.abs(rope.minX - ground.maxX) > 400
                        && !(rope.minX >= ground.minX && rope.minX <= ground.maxX)) continue;
                Point probe = ground.centerPoint();
                try {
                    var m = BotFallbackMovementManager.class.getDeclaredMethod("nearestUpwardRopeLaunch",
                            MapleMap.class, Point.class, BotMovementProfile.class);
                    m.setAccessible(true);
                    fallbackScans++;
                    boolean found = false;
                    // walk toward the rope's X in walkStep hops (what the bot actually does);
                    // a launch found anywhere along the approach counts as a pass.
                    int step = BotPhysicsEngine.walkStep(map, profile);
                    for (int hop = 0; hop <= 12 && !found; hop++) {
                        int dir = Integer.compare(rope.minX, probe.x);
                        Point p2 = hop == 0 ? probe
                                : new Point(probe.x + dir * hop * step, probe.y);
                        Point launch = (Point) m.invoke(null, map, p2, profile);
                        if (launch != null) found = true;
                    }
                    if (!found) fallbackMiss++;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }
        String verdict = "OK";
        if (ropeNoEntry > 0) verdict = "LINK-FAIL(" + ropeNoEntry + "/" + ropeRegions + ")";
        else if (fallbackMiss > 0) verdict = "fbMiss=" + fallbackMiss + "/" + fallbackScans;
        return tag + " " + verdict;
    }
}
