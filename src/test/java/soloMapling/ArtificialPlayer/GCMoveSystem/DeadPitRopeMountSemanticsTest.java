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

import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Root-cause regression for the ride-down rope rule (DeadPitGuard.ropeBelow, review F2).
 *
 * <p>The rule must be decided by the ENGINE'S OWN mount mechanisms — the same ones the graph
 * builder and the executor use — not by a geometry guess:
 *
 * <ul>
 *   <li>a rope whose climbable bottom reaches the floor is an exit (the straight ride-down);</li>
 *   <li>a rope whose head sits within the executor's top-attach band (MAX_SNAP_DROP below the
 *       floor, within the grab column) is mountable and counts — the standard way down a tower's
 *       face rope (this is the band 7ad7fd3's blanket guard wrongly rejected: a full-WZ scan finds
 *       2 255 such genuinely-mountable pairs the guard skipped);</li>
 *   <li>a rope that hangs entirely below the floor and is out of every mechanism's reach is
 *       scenery, not an exit (the phantom the guard's report was right about);</li>
 *   <li>an off-axis rope is only an exit when the floor row actually WALKS to its column — a rope
 *       across a gap/pit must not mint an escape over the void.</li>
 * </ul>
 */
public class DeadPitRopeMountSemanticsTest {

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

    /**
     * A plain platform at y=0 over open air, with a walled pocket far below so nothing under it
     * lands, and one rope at x=165 (inside the grab column of the [-265..265] span).
     * The world row 200px above the platform kills the topmost rule, and the platform is ffd so
     * the straight drop is out — the verdict can only come from the rope rule.
     */
    private static MapleMap platformWithRope(int mapId, int ropeTop, int ropeBottom) {
        MapleMap map = new MapleMap(mapId, 0, 0, 922010000, 0.0f);
        map.setMapLineBoundings(-1000, 600, -265, 265);
        FootholdTree tree = new FootholdTree(new Point(-265, -100), new Point(265, 600));
        tree.insert(new Foothold(new Point(-265, -200), new Point(265, -200), 3)); // world beyond apex
        Foothold platform = new Foothold(new Point(-265, 0), new Point(265, 0), 1);
        platform.setForbidFallDown(true);
        tree.insert(platform);
        map.setFootholds(tree);
        map.addRope(new org.gms.server.maps.Rope(165, ropeTop, ropeBottom, false));
        return map;
    }

    private static boolean platformLivable(MapleMap map) {
        Foothold platform = BotPhysicsEngine.findGroundFoothold(map, new Point(0, 0));
        return DeadPitGuard.isLivableSurface(map, platform, BotMovementProfile.base());
    }

    @Test
    void aRopeToppingWithinTheTopAttachBandIsAnExit() {
        // The rope head 2px below the face: exactly the band the executor's own attach rule
        // (canAttachToRopeFromTopPlatform / the graph's canTopGrab) uses, and exactly the shape
        // 7ad7fd3's blanket top guard skipped. It must read as an exit again.
        MapleMap map = platformWithRope(922011970, 2, 267);
        org.junit.jupiter.api.Assertions.assertTrue(platformLivable(map),
                "a rope whose head sits in the top-attach band is a real ride-down exit");
    }

    @Test
    void aRopeHangingEntirelyBelowAndOutOfReachIsScenery() {
        // Head 300px below the floor: outside the top-attach band, below-when-hanging means the
        // bot must already be falling to catch it, and a plain fall cannot grab (no climb intent).
        // The bottom hangs far below the floor, so the pre-guard slack check would have counted it.
        MapleMap map = platformWithRope(922011971, 300, 900);
        org.junit.jupiter.api.Assertions.assertFalse(platformLivable(map),
                "a rope entirely below the floor and out of every mount mechanism is not an exit");
    }

    @Test
    void aRopeReachingTheFloorIsAnExit() {
        MapleMap map = platformWithRope(922011972, -2, 267);
        org.junit.jupiter.api.Assertions.assertTrue(platformLivable(map),
                "a rope spanning the floor is a straight ride-down exit");
    }

    @Test
    void anOffAxisRopeAcrossAGapIsNotAnExit() {
        // Two platforms 200px apart, a rope beside the far one at the same level. The near
        // platform's row does NOT walk to the rope column (the gap breaks it), so the rope must
        // not mint an escape for the near platform.
        MapleMap map = new MapleMap(922011973, 0, 0, 922010000, 0.0f);
        map.setMapLineBoundings(-1000, 600, -265, 265);
        FootholdTree tree = new FootholdTree(new Point(-265, -100), new Point(265, 600));
        tree.insert(new Foothold(new Point(-265, -200), new Point(265, -200), 3));
        Foothold near = new Foothold(new Point(-265, -65), new Point(-100, 0), 1); // slope to the gap edge
        near.setForbidFallDown(true);
        tree.insert(near);
        Foothold far = new Foothold(new Point(100, 0), new Point(265, 0), 2);
        tree.insert(far);
        map.setFootholds(tree);
        map.addRope(new org.gms.server.maps.Rope(220, -2, 267, false)); // beside the FAR platform
        Foothold nearPlatform = BotPhysicsEngine.findGroundFoothold(map, new Point(-200, -30));
        org.junit.jupiter.api.Assertions.assertFalse(
                DeadPitGuard.isLivableSurface(map, nearPlatform, BotMovementProfile.base()),
                "a rope beside the platform across the gap must not be an exit for the near side");
    }
}
