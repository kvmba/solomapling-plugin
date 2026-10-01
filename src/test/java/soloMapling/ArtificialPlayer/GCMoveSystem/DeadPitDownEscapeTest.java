package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.config.GameConfig;
import org.gms.dao.entity.GameConfigDO;
import org.gms.manager.ServerManager;
import org.gms.property.ServiceProperty;
import org.gms.server.maps.Foothold;
import org.gms.server.maps.FootholdTree;
import org.gms.server.maps.MapleMap;
import org.gms.service.ConfigService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The downward-half of livability (our fix): a surface a bot leaves by walking off,
 * down-jumping, or riding a rope DOWN is not a dead pit - the guard previously only
 * counted escapes that go UP (portal / rope above / jump chain), which called LPQ
 * stage-1's spawn row (one walk-off to the door row) and every plain drop-through
 * ledge dead. Geometry mirrors that shape at test scale: a high row whose only way
 * down is a fall to a lower row far beyond jump reach, plus the guard cases that
 * must STAY dead (no landing below at all) or turn live via a rope that bottoms there.
 */
public class DeadPitDownEscapeTest {

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

    /** A high row over a lower floor 392px below (beyond any jump reach) — the LPQ
     *  stage-1 spawn-row shape: the only way off is DOWN, and down works. */
    private static MapleMap highRowOverFloor(int mapId) {
        MapleMap map = new MapleMap(mapId, 0, 0, 922010000, 0.0f);
        map.setMapLineBoundings(-1000, 600, -265, 265);
        FootholdTree tree = new FootholdTree(new Point(-265, -100), new Point(265, 600));
        tree.insert(new Foothold(new Point(-265, 0), new Point(265, 0), 1));     // high row
        tree.insert(new Foothold(new Point(-265, 392), new Point(265, 392), 2)); // lower floor
        map.setFootholds(tree);
        return map;
    }

    @Test
    void aRowWithARealWalkOffBelowIsLivable() {
        MapleMap map = highRowOverFloor(922011911);
        Foothold highRow = map.getFootholds().getAllFootholds().get(0);
        assertTrue(DeadPitGuard.isLivableSurface(map, highRow, BotMovementProfile.base()),
                "a walk-off with a real landing 392px below is an exit (LPQ stage-1 spawn row)");
    }

    @Test
    void aRowOverBareVoidStaysDead() {
        // Same shape but the lower floor only spans x[-100..100]: the high row's midpoint
        // column (x=0) still lands, so this stays LIVE — the quarter-point probe pins that
        // a partial floor keeps the surface alive when any probed column lands.
        MapleMap map = new MapleMap(922011912, 0, 0, 922010000, 0.0f);
        map.setMapLineBoundings(-1000, 600, -265, 265);
        FootholdTree tree = new FootholdTree(new Point(-265, -100), new Point(265, 600));
        tree.insert(new Foothold(new Point(-265, 0), new Point(265, 0), 1));
        tree.insert(new Foothold(new Point(-100, 392), new Point(100, 392), 2));
        map.setFootholds(tree);
        Foothold highRow = map.getFootholds().getAllFootholds().get(0);
        assertTrue(DeadPitGuard.isLivableSurface(map, highRow, BotMovementProfile.base()),
                "a row with any real landing below (x=0 column) is live");
    }

    @Test
    void aRowWithNothingBelowStaysDead() {
        // The sealed-pocket shape (LPQ stage-1's top pocket): a floor inside two wall
        // columns with a higher surface ABOVE the walls (so the "topmost surface" rule
        // does not fire), no portal, no rope, and no landing below the pocket floor.
        // Walls are vertical footholds (x1==x2), exactly as the WZ lays them.
        MapleMap map = new MapleMap(922011913, 0, 0, 922010000, 0.0f);
        map.setMapLineBoundings(-1000, 600, -265, 265);
        FootholdTree tree = new FootholdTree(new Point(-265, -100), new Point(265, 600));
        tree.insert(new Foothold(new Point(-265, 0), new Point(265, 0), 1));      // open world above the walls
        tree.insert(new Foothold(new Point(-100, 60), new Point(-100, 392), 2));  // left wall (vertical)
        tree.insert(new Foothold(new Point(100, 60), new Point(100, 392), 3));    // right wall (vertical)
        tree.insert(new Foothold(new Point(-100, 392), new Point(100, 392), 4));  // the pocket floor
        map.setFootholds(tree);
        Foothold pocketFloor = map.getFootholds().getAllFootholds().get(3);
        assertFalse(DeadPitGuard.isLivableSurface(map, pocketFloor, BotMovementProfile.base()),
                "a walled pocket floor with no landing below, no rope, no portal stays dead");
    }

    @Test
    void aRopeBottomingAtTheFloorIsADownExit() {
        // The mid-shaft platform shape: no way up (rope tops far above jump reach beyond
        // the platform), no portal — but a rope whose BOTTOM ends at the platform lets the
        // bot mount and ride DOWN. The up-rope check ignores it; the down-rope check must not.
        MapleMap map = new MapleMap(922011914, 0, 0, 922010000, 0.0f);
        map.setMapLineBoundings(-1000, 600, -265, 265);
        FootholdTree tree = new FootholdTree(new Point(-265, -100), new Point(265, 600));
        tree.insert(new Foothold(new Point(-60, 392), new Point(60, 392), 1)); // lone platform
        tree.insert(new Foothold(new Point(-265, 600), new Point(265, 600), 2)); // bottom basin
        map.setFootholds(tree);
        map.addRope(new org.gms.server.maps.Rope(0, 200, 392, false)); // bottoms at the platform
        Foothold platform = map.getFootholds().getAllFootholds().get(0);
        assertTrue(DeadPitGuard.isLivableSurface(map, platform, BotMovementProfile.base()),
                "a rope bottoming at the floor is a ride-down exit");
    }
}
