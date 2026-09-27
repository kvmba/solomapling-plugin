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
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/*
 * MANUAL diagnostic for the LPQ stage-1 tower: builds 922010100's real footprint
 * (transcribed from Map9/922010100.img.xml) and asserts the nav graph can plan
 * ground → first mob floor → perch staircase, the chain PqActions.seekAndAttack's
 * chase depends on. Run it explicitly (-Dtest=LpqStage1ClimbPathTest) rather than
 * in the suite: it stubs the host's Spring context, and the suite's other tests
 * (GCTravelTransitCeilingTest) poison Server's class-init first when they run
 * without that stub, which would fail this class no matter how correct it is.
 *
 * Status at the time of writing: BOTH paths mint on the real footprint — the
 * climb chain exists in the graph, so a stage-1 "walks floor 1, never climbs"
 * report points at runtime execution (chase target choice / climb execution),
 * not at a missing baked edge.
 */
@Disabled("manual diagnostic: needs the host Spring stub before any other suite class touches Server")
class LpqStage1ClimbPathTest {

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
        // Touch Server once HERE, under the stub: its static block pulls a dozen service
        // beans from the context. First-touch happens in whichever test class runs first,
        // so the stub must be in place before any other class can poison the class init.
        org.gms.net.server.Server.getInstance();
    }

    private static GameConfigDO config(String type, String key, String value) {
        GameConfigDO gameConfigDO = new GameConfigDO();
        gameConfigDO.setConfigType(type);
        gameConfigDO.setConfigSubType("0");
        gameConfigDO.setConfigCode(key);
        gameConfigDO.setConfigValue(value);
        gameConfigDO.setConfigClazz("java.lang.Long");
        return gameConfigDO;
    }

    private static MapleMap stage1Geometry(int mapId) {
        MapleMap map = new MapleMap(mapId, 0, 0, 922010000, 0.0f);
        map.setMapLineBoundings(-4000, 600, -265, 265);

        FootholdTree tree = new FootholdTree(new Point(-265, -4000), new Point(265, 600));
        int id = 1;
        // The FULL stage-1 tower, transcribed from Map9/922010100.img.xml (walkable fh only)
        int[][] floors = {
                {-3945, -365, -265}, {-3945, 265, 365},
                {-3488, -26, 265}, {-3461, -265, -185}, {-3461, -185, -26},
                {-3293, -265, -260}, {-3280, -245, -180}, {-3258, -217, -153},
                {-3236, -193, -125}, {-3214, -169, -98},
                {-3181, -184, -103}, {-3181, -103, -31}, {-3180, 113, 173},
                {-3147, 85, 152}, {-3114, 56, 124}, {-3080, -27, 58},
                {-3016, 33, 117}, {-3016, 117, 209}, {-2982, -265, -221}, {-2982, -221, -29},
                {-2818, -183, 174}, {-2552, -178, 179},
                {-2289, -208, -116}, {-2289, -116, -32}, {-2288, 69, 154},
                {-2123, -169, -109}, {-2090, -148, -81}, {-2057, -120, -52}, {-2057, 112, 172},
                {-2024, 84, 151}, {-1991, 55, 123}, {-1990, -174, -114}, {-1957, -153, -86},
                {-1925, 109, 169}, {-1924, -125, -57}, {-1892, 81, 148},
                {-1859, -120, -69}, {-1859, 52, 120},
                {-1793, -203, -111}, {-1793, -111, -27}, {-1793, 77, 128},
                {-1728, 32, 116}, {-1728, 116, 208},
                {-1512, -265, -243}, {-1512, 244, 265},
                {-1499, -228, -163}, {-1499, 164, 229},
                {-1477, -200, -136}, {-1477, 137, 201},
                {-1455, -176, -108}, {-1455, 109, 177},
                {-1433, -152, -81}, {-1433, 82, 153},
                {-1399, -180, 177},
                {-1153, 27, 107}, {-1147, -173, -92}, {-1147, -92, -20},
                {-1092, 62, 142}, {-1031, 97, 177},
                {-968, 20, 212}, {-968, 212, 265},
                {-751, -171, -90}, {-751, -90, -18},
                {-713, 31, 115}, {-713, 115, 207},
                {-572, 106, 166}, {-539, 78, 145}, {-506, 49, 117},
                {-450, 30, 222}, {-450, 222, 265},
                {-180, -178, 179},
                {130, -265, -185}, {130, -185, -33}, {130, -33, 45}, {130, 45, 265},
                {542, -265, -225}, {542, -225, -135}, {542, -135, -45}, {542, -45, 45},
                {542, 45, 135}, {542, 135, 225}, {542, 225, 265},
        };
        for (int[] f : floors) {
            tree.insert(new Foothold(new Point(f[1], f[0]), new Point(f[2], f[0]), id++));
        }
        map.setFootholds(tree);

        map.addRope(new Rope(-117, -178, 85, true));    // ground → -180 row
        map.addRope(new Rope(164, -448, -185, true));   // -180 row → -450 floor
        map.addRope(new Rope(165, -711, -622, false));  // -572 perch → -713 row
        map.addRope(new Rope(220, -966, -698, false));  // -968 floor ↔ -713 row
        map.addRope(new Rope(-123, -1397, -1222, false)); // -1147 row → -1399 row
        map.addRope(new Rope(-93, -1791, -1524, false));  // -1455 row → -1793 row
        map.addRope(new Rope(-72, -2287, -2111, false));  // -2123 row → -2289 row
        map.addRope(new Rope(-119, -2550, -2291, false)); // -2289 row → -2552 row
        map.addRope(new Rope(72, -2816, -2554, false));   // -2552 row → -2818 row
        map.addRope(new Rope(97, -3014, -2839, false));   // -2818 row → -2982 row
        map.addRope(new Rope(165, -3463, -3219, false));  // -3488 row ↔ -3180 perch
        return map;
    }

    @Test
    void groundReachesTheFirstMobFloorThroughTheTwoLadders() {
        MapleMap map = stage1Geometry(922010100); // the stage's own id: exercises the real cache key
        BotNavigationGraph g = BotNavigationGraphProvider.getGraph(map);
        System.out.println("[probe] regions=" + g.regions.size()
                + " ropes=" + map.getRopes().size());

        int ground = g.findRegionId(map, new Point(-9, 130));     // st00 spawn
        int mobFloor = g.findRegionId(map, new Point(136, -450)); // nearest mob's floor
        System.out.println("[probe] ground=" + ground + " mobFloor=" + mobFloor);
        for (BotNavigationGraph.Region r : g.regions) {
            if (!r.isRopeRegion && r.minY >= 100 && r.maxY <= 600) {
                System.out.println("[probe] bottom region id=" + r.id
                        + " x " + r.minX + ".." + r.maxX + " y " + r.minY + ".." + r.maxY);
            }
        }
        assertTrue(ground >= 0, "ground floor unresolved");
        assertTrue(mobFloor >= 0, "mob floor unresolved");

        List<BotNavigationGraph.Edge> path = BotNavigationManager.findPath(
                g, map, new Point(-9, 130), ground, mobFloor, new Point(136, -450));
        assertTrue(path != null && !path.isEmpty(),
                "no graph path from the ground to the -450 mob floor: the seek chase degrades to floor-1 steering");
    }

    @Test
    void groundReachesThePerchStaircaseAbove() {
        // Distinct map id: the nav cache is keyed by mapId, and a second variant on the
        // same id would silently load the first variant's baked graph (cache poisoning).
        MapleMap map = stage1Geometry(922010199);
        BotNavigationGraph g = BotNavigationGraphProvider.getGraph(map);

        int ground = g.findRegionId(map, new Point(-9, 130));
        int perch = g.findRegionId(map, new Point(136, -572)); // the nearest mob's own perch
        assertTrue(perch >= 0, "-572 perch unresolved");

        List<BotNavigationGraph.Edge> path = BotNavigationManager.findPath(
                g, map, new Point(-9, 130), ground, perch, new Point(136, -572));
        assertTrue(path != null && !path.isEmpty(),
                "no graph path from the ground to the -572 perch: mobs there are permanently unchaseable");
    }
}
