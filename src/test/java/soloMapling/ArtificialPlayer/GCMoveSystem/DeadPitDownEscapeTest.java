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
 * stage-1's top platform (whose descent rope hangs BELOW the platform face) dead.
 *
 * Every case here pins a rule the up-only model got WRONG or must keep right:
 * each geometry removes the up-escapes by construction (a strictly higher world
 * surface beyond jump reach kills the "topmost surface" rule, there are no
 * portals, and any rope present hangs too high to jump-grab) so the verdict can
 * only come from the new down-escape checks.
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

    /**
     * A surface under open sky (nothing above it within jump reach) with NO landing
     * below at all: the sealed-pocket shape. {@code surfaceY} is the surface whose
     * verdict is asked; the world row 200px above is strictly beyond the base apex
     * (77+25=102px reach) so no up-escape can fire — the verdict must come from the
     * down-escape probes alone.
     */
    private static Foothold sealedPocketShape(MapleMap map, int surfaceY) {
        FootholdTree tree = new FootholdTree(new Point(-265, -100), new Point(265, 600));
        Foothold world = new Foothold(new Point(-265, surfaceY - 200), new Point(265, surfaceY - 200), 1);
        Foothold surface = new Foothold(new Point(-100, surfaceY), new Point(100, surfaceY), 2);
        tree.insert(world);
        tree.insert(surface);
        map.setFootholds(tree);
        return surface;
    }

    private static MapleMap map(int mapId) {
        MapleMap m = new MapleMap(mapId, 0, 0, 922010000, 0.0f);
        m.setMapLineBoundings(-1000, 600, -265, 265);
        return m;
    }

    @Test
    void aDropThroughWithARealLandingBelowIsLivable() {
        // The LPQ stage-1 spawn-row shape: a lower floor 392px below (far beyond jump
        // reach), and a world row 200px ABOVE the surface (beyond the base apex of
        // 102px) so the "topmost surface" rule cannot fire. The only way off is DOWN,
        // and down works -> LIVE. The old up-only model answered dead here.
        MapleMap map = map(922011911);
        FootholdTree tree = new FootholdTree(new Point(-265, -100), new Point(265, 600));
        tree.insert(new Foothold(new Point(-265, -200), new Point(265, -200), 3)); // world beyond apex
        Foothold highRow = new Foothold(new Point(-265, 0), new Point(265, 0), 1);
        Foothold lowerFloor = new Foothold(new Point(-265, 392), new Point(265, 392), 2);
        tree.insert(highRow);
        tree.insert(lowerFloor);
        map.setFootholds(tree);
        assertTrue(DeadPitGuard.isLivableSurface(map, highRow, BotMovementProfile.base()),
                "a drop with a real landing 392px below is an exit (LPQ stage-1 spawn row)");
    }

    @Test
    void aRowWithNoLandingBelowStaysDead() {
        // The sealed pocket: floor inside two wall columns, world above the walls, no
        // landing below, no rope, no portal. Wall columns are vertical footholds
        // (x1==x2) as the WZ lays them. Must stay DEAD - this is the verdict that
        // keeps the fall-off-map recovery out of LPQ stage-1's top pocket.
        MapleMap map = map(922011913);
        FootholdTree tree = new FootholdTree(new Point(-265, -100), new Point(265, 600));
        tree.insert(new Foothold(new Point(-265, 0), new Point(265, 0), 1));      // world above the walls
        tree.insert(new Foothold(new Point(-100, 60), new Point(-100, 392), 2));  // left wall (vertical)
        tree.insert(new Foothold(new Point(100, 60), new Point(100, 392), 3));    // right wall (vertical)
        tree.insert(new Foothold(new Point(-100, 392), new Point(100, 392), 4));  // the pocket floor
        map.setFootholds(tree);
        Foothold pocketFloor = tree.getAllFootholds().get(3);
        assertFalse(DeadPitGuard.isLivableSurface(map, pocketFloor, BotMovementProfile.base()),
                "a walled pocket floor with no landing below, no rope, no portal stays dead");
    }

    @Test
    void anFfdSourceNeverCountsTheStraightDrop() {
        // The forbidFallDown source: the same geometry as the live drop-through case
        // (world beyond apex above, real landing below), but the source carries the WZ
        // ffd flag - the client blocks the straight drop-through there (canStartDownJump
        // honours the flag), so the guard must not mint a phantom exit from a move the
        // bot can never make. fallEscape refuses -> no escape left -> DEAD.
        MapleMap map = map(922011915);
        FootholdTree tree = new FootholdTree(new Point(-265, -100), new Point(265, 600));
        tree.insert(new Foothold(new Point(-265, -200), new Point(265, -200), 3)); // world beyond apex
        Foothold ffdRow = new Foothold(new Point(-265, 0), new Point(265, 0), 1);
        ffdRow.setForbidFallDown(true);
        tree.insert(ffdRow);
        tree.insert(new Foothold(new Point(-265, 392), new Point(265, 392), 2));
        map.setFootholds(tree);
        assertFalse(DeadPitGuard.isLivableSurface(map, ffdRow, BotMovementProfile.base()),
                "an ffd source's straight drop-through is not an exit");
    }

    @Test
    void aRopeToppingJustBelowThePlatformFaceIsARideDownExit() {
        // The LPQ stage-1 top-platform shape: the descent rope's TOP hangs 2px BELOW
        // the platform face - ropeAbove skips it (top below the floor: nothing to climb
        // up), and the rope cannot be jump-grabbed from the face. But its bottom ends
        // 267px down at a real foothold: mount at the face and ride down. ropeBelow
        // must count it. (Anchored near the platform's right edge, like the map's.)
        // The world row 200px above the face (beyond the apex) kills the topmost rule,
        // so the verdict can only come from ropeBelow.
        MapleMap map = map(922011914);
        FootholdTree tree = new FootholdTree(new Point(-265, -100), new Point(265, 600));
        tree.insert(new Foothold(new Point(-265, -200), new Point(265, -200), 3)); // world beyond apex
        Foothold platform = new Foothold(new Point(-265, 0), new Point(265, 0), 1);
        Foothold bottomFh = new Foothold(new Point(100, 267), new Point(230, 267), 2);
        tree.insert(platform);
        tree.insert(bottomFh);
        map.setFootholds(tree);
        // x=165, top=-2 relative to the face (y=0 -> 2), bottom at the lower foothold.
        map.addRope(new org.gms.server.maps.Rope(165, 2, 267, false));
        assertTrue(DeadPitGuard.isLivableSurface(map, platform, BotMovementProfile.base()),
                "a rope topping just below the face and bottoming on a real foothold is a ride-down exit");
    }
}
