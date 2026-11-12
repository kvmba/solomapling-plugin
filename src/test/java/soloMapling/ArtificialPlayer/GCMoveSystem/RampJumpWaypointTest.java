package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.server.maps.Foothold;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.awt.Point;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RampJumpWaypointTest {

    @ParameterizedTest
    @CsvSource({
            "17, -1244, -44, -1234, 6, -1234",
            "12, -1239, -44, -1234, 6, -1234",
            "10, -1237, 45, -1247, 21, -1247",
            "17, -1244, 45, -1247, 21, -1247"
    })
    void aRampLeadsToTheExitTowardTheLaunchRow(int x, int y, int launchX, int launchY,
                                             int exitX, int exitY) {
        BotNavigationGraph.Region region = new BotNavigationGraph.Region(1, List.of(
                segment(264, -59, -1234, 6, -1234),
                segment(265, 6, -1234, 21, -1247),
                segment(266, 21, -1247, 79, -1247)));

        assertEquals(new Point(exitX, exitY), waypoint(region, new Point(x, y),
                new Point(launchX, launchY)));
    }

    @Test
    void anOverlappingLaunchRowStillRequiresWalkingToTheRampExit() {
        BotNavigationGraph.Region region = new BotNavigationGraph.Region(1, List.of(
                segment(227, 26, 118, 49, 118),
                segment(226, 26, 118, 41, 131),
                segment(233, 41, 131, 106, 131)));

        assertEquals(new Point(26, 118), waypoint(region, new Point(28, 120),
                new Point(45, 118)), "the upper row overlaps this column but joins the ramp at x=26");
    }

    private static Point waypoint(BotNavigationGraph.Region region, Point position, Point launch) {
        BotNavigationGraph graph = new BotNavigationGraph(1, 1, List.of(region),
                Map.of(region.id, region), Map.of(), Map.of(), Set.of());
        BotNavigationGraph.Edge edge = new BotNavigationGraph.Edge(region.id, 2,
                BotNavigationGraph.EdgeType.JUMP, launch, new Point(0, -1400),
                0, 0, 0, 0, 0, 100);
        return BotNavigationManager.selectJumpWaypoint(graph, position, edge);
    }

    private static BotNavigationGraph.Segment segment(int id, int x1, int y1, int x2, int y2) {
        return new BotNavigationGraph.Segment(new Foothold(new Point(x1, y1), new Point(x2, y2), id));
    }
}
