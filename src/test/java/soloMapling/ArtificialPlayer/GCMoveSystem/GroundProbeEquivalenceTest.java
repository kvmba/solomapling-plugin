package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.config.GameConfig;
import org.gms.dao.entity.GameConfigDO;
import org.gms.manager.ServerManager;
import org.gms.property.ServiceProperty;
import org.gms.server.maps.Foothold;
import org.gms.server.maps.MapleMap;
import org.gms.service.ConfigService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Differential test on REAL map geometry: the allocation-free ground probes added by the
 * performance pass must return exactly what the pre-change code returned, for every probe point.
 *
 * <p>The changed math is small but load-bearing: {@code findGroundFoothold} / {@code findGroundPoint}
 * used to re-resolve the same column through {@code pointBelowIndexed} to get the surface Y for the
 * closer-of-two comparison; the pass reads that Y straight off the already-resolved foothold. A
 * single pixel of drift there silently moves every bot's feet, so the old implementation is
 * reproduced verbatim below and compared point by point over several real maps.
 *
 * <p>This is the class of check the earlier review rounds lacked: reading the diff proved the math
 * looked identical, but only executing both versions on real foothold trees proves it.</p>
 */
class GroundProbeEquivalenceTest {

    private static final int[] MAPS = {
            100000000, // Henesys
            101000000, // Ellinia
            104000000, // Lith Harbor
            200000000, // Orbis
            220000000, // Ludibrium
            922010300, // LPQ stage 3 (the pit map)
    };

    @BeforeAll
    static void stub() throws Exception {
        ApplicationContext ctx = mock(ApplicationContext.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
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

    static GameConfigDO cfg(String type, String key, String value) {
        GameConfigDO d = new GameConfigDO();
        d.setConfigType(type);
        d.setConfigSubType("0");
        d.setConfigCode(key);
        d.setConfigValue(value);
        d.setConfigClazz("java.lang.Long");
        return d;
    }

    // ── the pre-change implementations, verbatim ─────────────────────────────────

    private static Foothold legacyFindGroundFoothold(MapleMap map, Point position) {
        if (map == null || map.getFootholds() == null || position == null) {
            return null;
        }
        Foothold exact = BotPhysicsEngine.findBelowIndexed(map, position);
        Foothold offset = BotPhysicsEngine.findBelowIndexed(map,
                new Point(position.x, position.y - BotPhysicsEngine.cfg.MAX_SLOPE_UP));
        if (exact == null) return offset;
        if (offset == null) return exact;
        Point exactGround = BotPhysicsEngine.pointBelowIndexed(map, position);
        Point offsetGround = BotPhysicsEngine.pointBelowIndexed(map,
                new Point(position.x, position.y - BotPhysicsEngine.cfg.MAX_SLOPE_UP));
        if (exactGround == null) return offset;
        if (offsetGround == null) return exact;
        return Math.abs(offsetGround.y - position.y) < Math.abs(exactGround.y - position.y) ? offset : exact;
    }

    private static Point legacyFindGroundPoint(MapleMap map, Point position) {
        if (map == null || position == null) {
            return null;
        }
        Point exactGround = BotPhysicsEngine.pointBelowIndexed(map, position);
        Point offsetGround = BotPhysicsEngine.pointBelowIndexed(map,
                new Point(position.x, position.y - BotPhysicsEngine.cfg.MAX_SLOPE_UP));
        if (exactGround == null) {
            return offsetGround;
        }
        if (offsetGround == null) {
            return exactGround;
        }
        int exactDistance = Math.abs(exactGround.y - position.y);
        int offsetDistance = Math.abs(offsetGround.y - position.y);
        return offsetDistance < exactDistance ? offsetGround : exactGround;
    }

    private static List<MapleMap> loadedMaps() {
        List<MapleMap> maps = new ArrayList<>();
        for (int id : MAPS) {
            try {
                // The production geometry loader: same WZ path the nav-graph bake uses, without
                // needing a live channel (which is what getMapleMapById would require).
                MapleMap map = BotNavigationMapLoader.loadMapGeometry(id);
                if (map != null && map.getFootholds() != null) {
                    maps.add(map);
                }
            } catch (Throwable ignored) {
                // map absent from this WZ set - just skip it
            }
        }
        return maps;
    }

    @Test
    void groundFootholdAndPointMatchThePreChangeMathOnRealMaps() {
        List<MapleMap> maps = loadedMaps();
        org.junit.jupiter.api.Assumptions.assumeTrue(!maps.isEmpty(),
                "no real Map.wz available (see PqGraphChainAuditTest's probe)");

        int probes = 0;
        for (MapleMap map : maps) {
            int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
            int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
            for (Foothold fh : map.getFootholds().getAllFootholds()) {
                minX = Math.min(minX, Math.min(fh.getX1(), fh.getX2()));
                maxX = Math.max(maxX, Math.max(fh.getX1(), fh.getX2()));
                minY = Math.min(minY, Math.min(fh.getY1(), fh.getY2()));
                maxY = Math.max(maxY, Math.max(fh.getY1(), fh.getY2()));
            }
            // Dense sweep: every 7px horizontally, a handful of Y offsets per column, so both the
            // "exact hit", "only the slope-offset hit", "two candidates, pick nearer" and "no
            // ground" branches are all exercised.
            for (int x = minX - 20; x <= maxX + 20; x += 7) {
                for (int y = minY - 40; y <= maxY + 40; y += 23) {
                    Point p = new Point(x, y);
                    Foothold expectedFh = legacyFindGroundFoothold(map, p);
                    Foothold actualFh = BotPhysicsEngine.findGroundFoothold(map, p);
                    assertEquals(describe(expectedFh), describe(actualFh),
                            "findGroundFoothold diverged at " + p + " on map " + map.getId());

                    Point expectedPoint = legacyFindGroundPoint(map, p);
                    Point actualPoint = BotPhysicsEngine.findGroundPoint(map, p);
                    assertEquals(expectedPoint, actualPoint,
                            "findGroundPoint diverged at " + p + " on map " + map.getId());
                    probes++;
                }
            }
        }
        assertNotNull(maps);
        org.junit.jupiter.api.Assertions.assertTrue(probes > 100_000,
                "expected a dense sweep, only ran " + probes + " probes");
    }

    /** Identity by foothold id + geometry: the same tree yields one instance per foothold. */
    private static String describe(Foothold fh) {
        return fh == null ? "null"
                : fh.getId() + ":" + fh.getX1() + "," + fh.getY1() + "->" + fh.getX2() + "," + fh.getY2();
    }
}
