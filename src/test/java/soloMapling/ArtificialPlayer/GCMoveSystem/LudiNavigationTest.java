package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.server.maps.Foothold;
import org.gms.server.maps.FootholdTree;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Portal;
import org.gms.server.maps.Rope;
import org.junit.BeforeClass;
import org.junit.Test;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.awt.Point;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import static org.mockito.Mockito.*;
import static soloMapling.ArtificialPlayer.GCMoveSystem.NavigationReachabilityTest.*;

/** Optional real-WZ execution test: mvn test -Dnavigation.wz=.../wz/Map.wz/Map/Map9 */
public class LudiNavigationTest {
    @BeforeClass
    public static void initialize() throws Exception {
        initializeCharacterServices();
    }

    @Test
    public void stageOneCanActuallyReachEveryMonsterPlatform() throws Exception {
        verifyStage(922010100);
    }

    @Test
    public void stageTwoCanActuallyReachEveryBoxPlatform() throws Exception {
        verifyStage(922010200);
    }

    @Test
    public void stageThreeCanReachWorkPlatformsButNotBottomPit() throws Exception {
        verifyStage(922010300);
    }

    @Test
    public void ordinaryMageWalksAndBlinksToStageFiveTopDoor() throws Exception {
        initializeJobServices();
        MapleMap map = loadGeometry(922010500);
        Point start = BotPhysicsEngine.findGroundPoint(map, map.getPortal("sp").getPosition());
        Point target = map.getPortal("in01").getPosition();
        BotMovementProfile profile = new BotMovementProfile(105, 110);
        BotNavigationGraph graph = build(map, profile);
        int goal = graph.findRegionId(map, target);
        assertNull("ordinary ground movement cannot clear the top step", BotNavigationManager.findPath(
                graph, map, start, graph.findRegionId(map, start), goal, target));
        var approach = GCMovementSkills.findTeleportApproach(map, graph, start, target);
        assertNotNull("a learned blink must link reachable ground to the top door", approach);
        assertNotNull(BotNavigationManager.findPath(graph, map, start, graph.findRegionId(map, start),
                graph.findRegionId(map, approach.launch()), approach.launch()));
        var bot = character(map, new Point(start));
        when(bot.getJob()).thenReturn(org.gms.client.Job.FP_WIZARD);
        BotMovementState state = new BotMovementState(bot, null);
        state.movementProfile = profile;
        state.physX = bot.getPosition().x;
        state.physY = bot.getPosition().y;
        state.moveTarget = target;
        state.moveTargetMap = map;
        state.moveTargetPrecise = true;
        for (Foothold fh : map.getFootholds().getAllFootholds()) state.fhIndex.put(fh.getId(), fh);
        try (var provider = mockStatic(BotNavigationGraphProvider.class, CALLS_REAL_METHODS)) {
            provider.when(() -> BotNavigationGraphProvider.peekGraph(map)).thenReturn(graph);
            provider.when(() -> BotNavigationGraphProvider.peekGraph(map, profile)).thenReturn(graph);
            provider.when(() -> BotNavigationGraphProvider.peekBestGraph(map, profile)).thenReturn(graph);
            MovementClock.useVirtual(10_000);
            boolean blinked = false;
            try {
                for (int tick = 0; tick < 3000; tick++) {
                    MovementClock.advance(50);
                    Point before = bot.getPosition();
                    var nav = BotNavigationManager.resolveTarget(state, target, tick % 2 == 0);
                    if ("teleport-link".equals(state.lastNavDecision)) {
                        blinked = true;
                        assertTrue("a blink never bypasses its range", Math.abs(bot.getPosition().y - before.y)
                                <= GCMovementSkills.TELEPORT_RANGE_PX);
                    } else {
                        assertTrue("route approach cannot teleport", before.distance(bot.getPosition()) < 40);
                    }
                    if (!nav.consumedTick) {
                        if (state.moveTargetPrecise && state.navEdge == null) state.navPreciseTarget = true;
                        if (state.inAir) BotMovementManager.tickAirborne(state, nav.targetPos);
                        else if (state.climbing) BotMovementManager.tickClimbing(state, nav.targetPos, tick % 2 == 0);
                        else BotMovementManager.tickGrounded(state, nav.targetPos);
                        if (tick % 2 == 0 && !state.inAir && !state.climbing)
                            BotNavigationManager.tryExecuteCommittedEdgeAfterGroundMovement(state, target);
                    }
                    if (!state.inAir && !state.climbing && Math.abs(bot.getPosition().x - target.x) <= 8
                            && Math.abs(bot.getPosition().y - target.y) <= 8) break;
                }
                assertTrue("the mage must actually use its blink", blinked);
                assertEquals("the full walk/blink/walk reaches the door", goal,
                        graph.findRegionId(map, bot.getPosition()));
                assertTrue("arrive at the door, not merely its region: " + bot.getPosition(),
                        bot.getPosition().distance(target) <= 12);
            } finally {
                MovementClock.reset();
            }
        }
    }

    private static void initializeJobServices() throws Exception {
        try (var server = mockStatic(org.gms.manager.ServerManager.class)) {
            var context = mock(org.springframework.context.ApplicationContext.class);
            var properties = mock(org.gms.property.ServiceProperty.class);
            when(properties.getLanguage()).thenReturn("en-US");
            when(context.getBean(org.gms.property.ServiceProperty.class)).thenReturn(properties);
            var messages = mock(org.springframework.context.MessageSource.class);
            when(messages.getMessage(anyString(), any(), any(java.util.Locale.class))).thenAnswer(call -> call.getArgument(0));
            when(context.getBean(anyString(), eq(org.springframework.context.MessageSource.class))).thenReturn(messages);
            server.when(org.gms.manager.ServerManager::getApplicationContext).thenReturn(context);
            Class.forName(org.gms.client.Job.class.getName());
        }
    }

    private static void verifyStage(int mapId) throws Exception {
        MapleMap map = loadGeometry(mapId);
        Element root = readMap(mapId);
        Point spawn = map.getPortal("sp").getPosition();
        assertNotNull(spawn);
        Point start = BotPhysicsEngine.findGroundPoint(map, spawn);
        for (BotMovementProfile profile : List.of(BotMovementProfile.base(), new BotMovementProfile(140, 120))) {
            verifyRoutes(mapId, map, root, start, profile);
        }
    }

    private static Element readMap(int mapId) throws Exception {
        String directory = System.getProperty("navigation.wz");
        assumeTrue("pass -Dnavigation.wz to exercise local WZ maps", directory != null);
        Path file = Path.of(directory, mapId + ".img.xml");
        assertTrue(Files.isRegularFile(file));
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file.toFile()).getDocumentElement();
    }

    private static MapleMap loadGeometry(int mapId) throws Exception {
        Element root = readMap(mapId);
        List<Foothold> footholds = new ArrayList<>();
        for (Element layer : children(child(root, "foothold"))) {
            for (Element group : children(layer)) {
                for (Element node : children(group)) {
                    Foothold fh = new Foothold(new Point(integer(node, "x1"), integer(node, "y1")),
                            new Point(integer(node, "x2"), integer(node, "y2")), Integer.parseInt(node.getAttribute("name")));
                    fh.setPrev(integer(node, "prev"));
                    fh.setNext(integer(node, "next"));
                    fh.setForbidFallDown(integer(node, "forbidFallDown") != 0);
                    footholds.add(fh);
                }
            }
        }
        MapleMap map = geometryMap(mapId);
        FootholdTree tree = new FootholdTree(new Point(-10000, -10000), new Point(10000, 10000));
        footholds.forEach(tree::insert);
        map.setFootholds(tree);
        Element info = child(root, "info");
        map.setMapLineBoundings(integer(info, "VRTop"), integer(info, "VRBottom"),
                integer(info, "VRLeft"), integer(info, "VRRight"));
        for (Element node : children(child(root, "ladderRope"))) {
            map.addRope(new Rope(integer(node, "x"), integer(node, "y1"), integer(node, "y2"), integer(node, "l") == 1));
        }
        for (Element node : children(child(root, "portal"))) {
            Portal portal = mock(Portal.class);
            Point position = new Point(integer(node, "x"), integer(node, "y"));
            when(portal.getId()).thenReturn(Integer.parseInt(node.getAttribute("name")));
            when(portal.getName()).thenReturn(value(node, "pn"));
            when(portal.getTarget()).thenReturn(value(node, "tn"));
            when(portal.getPosition()).thenReturn(position);
            when(portal.getTargetMapId()).thenReturn(integer(node, "tm"));
            when(portal.getScriptName()).thenReturn(value(node, "script"));
            when(portal.getPortalStatus()).thenReturn(true);
            map.addPortal(portal);
        }
        return map;
    }

    private static void verifyRoutes(int mapId, MapleMap map, Element root, Point start,
                                      BotMovementProfile profile) throws Exception {
            BotNavigationGraph graph = build(map, profile);
            HashSet<Integer> tested = new HashSet<>();
            List<Point> goals = new ArrayList<>();
            for (Element node : children(child(root, "life"))) {
                if ("m".equals(value(node, "type"))) {
                    int targetRegion = graph.regionIdByFootholdId.get(integer(node, "fh"));
                    goals.add(graph.getRegion(targetRegion).pointAt(integer(node, "x")));
                }
            }
            for (Element node : children(child(root, "reactor"))) {
                goals.add(BotPhysicsEngine.findGroundPoint(map, new Point(integer(node, "x"), integer(node, "y"))));
            }
            for (Point target : goals) {
                int targetRegion = graph.findRegionId(map, target);
                if (!tested.add(targetRegion)) {
                    continue;
                }
                assertFalse("work platform must not be misclassified dead: " + target, graph.deadRegionIds.contains(targetRegion));
                assertNotNull("missing route to work platform: " + target, BotNavigationManager.findPath(graph, map,
                        start, graph.findRegionId(map, start), targetRegion, target));
                runTo(map, graph, new Point(start), target, targetRegion);
                if (BotNavigationManager.findPath(graph, map, target, targetRegion, graph.findRegionId(map, start), start) != null) {
                    runTo(map, graph, new Point(target), start, graph.findRegionId(map, start));
                }
            }
            assertTrue("test must include monster platforms", tested.size() > 0);
            int workPlatforms = tested.size();
            // Also execute routes to the intermediate treads, including those only a few
            // pixels wide, rather than testing only the comfortable combat shelves.
            for (BotNavigationGraph.Region region : graph.regions) {
                if (region.isRopeRegion || tested.contains(region.id) || graph.deadRegionIds.contains(region.id)) {
                    continue;
                }
                Point target = region.centerPoint();
                if (BotNavigationManager.findPath(graph, map, start, graph.findRegionId(map, start), region.id, target) != null) {
                    runTo(map, graph, new Point(start), target, region.id);
                    if (BotNavigationManager.findPath(graph, map, target, region.id, graph.findRegionId(map, start), start) != null) {
                        runTo(map, graph, new Point(target), start, graph.findRegionId(map, start));
                    }
                    tested.add(region.id);
                }
            }
            for (int dead : graph.deadRegionIds) {
                BotNavigationGraph.Region region = graph.getRegion(dead);
                if (!region.isRopeRegion && dead != graph.findRegionId(map, start)) {
                    assertNull("no path may enter true dead region R" + dead,
                            BotNavigationManager.findPath(graph, map, start, graph.findRegionId(map, start), dead, region.centerPoint()));
                }
            }
            System.out.println("LPQ " + mapId + " " + profile.totalSpeedStat() + "/" + profile.totalJumpStat()
                    + ": executed " + workPlatforms + " work platforms / " + tested.size()
                    + " total platforms; excluded " + graph.deadRegionIds.size() + " dead regions");
    }

    private static Element child(Element parent, String name) {
        return BotWzXml.findNamedChild(parent, name);
    }

    private static List<Element> children(Element parent) {
        List<Element> result = new ArrayList<>();
        if (parent != null) {
            for (var node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
                if (node instanceof Element element) {
                    result.add(element);
                }
            }
        }
        return result;
    }

    private static String value(Element parent, String name) {
        Element element = child(parent, name);
        return element == null ? "" : element.getAttribute("value");
    }

    private static int integer(Element parent, String name) {
        return BotWzXml.getIntValue(child(parent, name), 0);
    }
}
