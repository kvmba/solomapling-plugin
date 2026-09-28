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

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

/** The climb-chain audit for every PQ map whose bot work needs a high target:
 * does the BAKED graph plan spawn-ground -> the work target? */
public class PqGraphChainAuditTest {
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
        // The plan() cases load PQ stage maps through the production WZ loader, which resolves
        // the server's wz directory relative to the working directory. On a machine without it
        // every case would just error "Map data not found", so probe the four maps this class
        // uses and skip instead of erroring.
        org.junit.jupiter.api.Assumptions.assumeTrue(mapWzAvailable(),
                "Map.wz not resolvable from this working directory (audit needs the real WZ)");
    }

    static boolean mapWzAvailable() {
        try {
            org.gms.provider.DataProvider mapSource =
                    org.gms.provider.DataProviderFactory.getDataProvider(org.gms.provider.wz.WZFiles.MAP);
            for (int mapId : new int[]{910010000, 920010100, 922010200, 922010700}) {
                if (mapSource.getData("Map/Map" + (mapId / 100000000) + "/"
                        + String.format("%09d", mapId) + ".img") == null) {
                    return false;
                }
            }
            return true;
        } catch (Throwable unavailable) {
            return false;
        }
    }
    static GameConfigDO cfg(String t, String k, String v) {
        GameConfigDO d = new GameConfigDO();
        d.setConfigType(t); d.setConfigSubType("0"); d.setConfigCode(k); d.setConfigValue(v); d.setConfigClazz("java.lang.Long");
        return d;
    }

    private void plan(String label, int mapId, Point from, Point to) {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(mapId);
        BotNavigationGraph g = BotNavigationGraphProvider.getGraph(map, BotMovementProfile.base());
        Point fromGround = BotPhysicsEngine.findGroundPoint(map, from);
        if (fromGround == null) fromGround = from;
        Point toGround = BotPhysicsEngine.findGroundPoint(map, to);
        int fromR = g.findRegionId(map, fromGround);
        int toR = g.findRegionId(map, toGround != null ? toGround : to);
        List<BotNavigationGraph.Edge> path = (fromR == toR)
                ? List.of()
                : BotNavigationManager.findPath(g, map, fromGround, fromR, toR, to);
        System.out.println("[chain] " + label + " map " + mapId + " " + from.x + "," + from.y
                + " -> " + to.x + "," + to.y
                + " fromR=" + fromR + " toR=" + toR
                + (path != null ? " path=" + path.size() + " edges" : " path=NULL"));
        assertTrue(path != null, label + ": no baked plan");
        assertTrue(!path.isEmpty() || fromR == toR,
                label + ": fromR=" + fromR + " toR=" + toR + " — NO EDGE CHAIN");
    }

    @Test
    void ludiStage1Tower() {
        // 922010100: spawn floor (y=130, forbidFallDown) -> rope ladder x=-117 -> exit-platform
        // row (y=-180) -> rope x=164 -> first Ratz platform (y=-450).
        plan("LPQ st1 first ratz", 922010100, new Point(0, 130), new Point(88, -450));
        // Above the first Ratz: one-way hop steps y=-506/-539/-572, then a rope grab at x=165
        // (bottom y=-622) up to the y=-713 mob platform.
        plan("LPQ st1 hop steps", 922010100, new Point(88, -450), new Point(136, -572));
        plan("LPQ st1 rope3 mob row", 922010100, new Point(88, -450), new Point(120, -713));
        plan("LPQ st1 rope4 row", 922010100, new Point(120, -713), new Point(179, -968));
        plan("LPQ st1 mid tower", 922010100, new Point(179, -968), new Point(0, -1399));
        plan("LPQ st1 high mob", 922010100, new Point(0, -1399), new Point(139, -1935));
        plan("LPQ st1 top row", 922010100, new Point(0, -1399), new Point(70, -3488));
        // And the way back down to the exit portal row (y=-180, next00 at -38,-180).
        plan("LPQ st1 back to exit", 922010100, new Point(70, -3488), new Point(-38, -180));
    }

    @Test
    void ludiStage2TowerBoxes() {
        plan("LPQ st2 box", 922010200, new Point(-177, -2635), new Point(-149, -1425));
        plan("LPQ st2 box", 922010200, new Point(-177, -2635), new Point(194, -591));
    }

    @Test
    void ludiStage7Passes() {
        plan("LPQ st7 mob", 922010700, new Point(-53, -895), new Point(-171, -347));
    }

    @Test
    void opqHubRoomDoors() {
        // The hub's room portals the bot actually walks to (OrbisPqData.towerSpotFor)
        plan("OPQ walkway door", 920010100, new Point(-168, -85), new Point(-250, -1631));
        plan("OPQ storage door", 920010100, new Point(-168, -85), new Point(157, -1385));
        plan("OPQ sealed door", 920010100, new Point(-168, -85), new Point(-259, -1267));
        plan("OPQ stage-up door", 920010100, new Point(-168, -85), new Point(-42, -1905));
        plan("OPQ lounge door", 920010100, new Point(-168, -85), new Point(-38, -1454));
        plan("OPQ papa door", 920010100, new Point(-168, -85), new Point(180, -316));
        plan("OPQ music door", 920010100, new Point(-168, -85), new Point(155, -31));
    }

    @Test
    void henesysFlowerSpots() {
        plan("HPQ flower1", 910010000, new Point(339, 36), new Point(4, -690));
        plan("HPQ flower2", 910010000, new Point(339, 36), new Point(182, -452));
        plan("HPQ flower5", 910010000, new Point(339, 36), new Point(-535, -449));
    }
}
