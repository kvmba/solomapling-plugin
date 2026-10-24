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

    private static void verifyStage(int mapId) throws Exception {
        String directory = System.getProperty("navigation.wz");
        assumeTrue("pass -Dnavigation.wz to exercise local WZ maps", directory != null);
        Path file = Path.of(directory, mapId + ".img.xml");
        assertTrue(Files.isRegularFile(file));
        Element root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file.toFile()).getDocumentElement();
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
        Point spawn = null;
        for (Element node : children(child(root, "portal"))) {
            Portal portal = mock(Portal.class);
            Point position = new Point(integer(node, "x"), integer(node, "y"));
            when(portal.getId()).thenReturn(Integer.parseInt(node.getAttribute("name")));
            when(portal.getName()).thenReturn(value(node, "pn"));
            when(portal.getTarget()).thenReturn(value(node, "tn"));
            when(portal.getPosition()).thenReturn(position);
            when(portal.getTargetMapId()).thenReturn(integer(node, "tm"));
            when(portal.getPortalStatus()).thenReturn(true);
            map.addPortal(portal);
            if ("sp".equals(portal.getName())) {
                spawn = position;
            }
        }
        assertNotNull(spawn);
        Point start = BotPhysicsEngine.findGroundPoint(map, spawn);
        for (BotMovementProfile profile : List.of(BotMovementProfile.base(), new BotMovementProfile(140, 120))) {
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
