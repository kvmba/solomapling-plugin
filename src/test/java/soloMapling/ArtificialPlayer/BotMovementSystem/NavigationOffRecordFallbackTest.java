package soloMapling.ArtificialPlayer.BotMovementSystem;

import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotMovementSystem.NavigationSystem.PathFinder;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/*
 * Guards the recorded-navigation freeze.
 *
 * A bot can be placed outside every recorded main area - a clientless portal
 * placement keeps the portal's airborne coordinates because nothing applies
 * client gravity. createPath used to hand the graph a null start area, and the
 * SHORTEST branch threw "Graph must contain the source vertex!" from the bot's
 * tick on every retry, so the bot never moved. It must degrade to "no route".
 */
class NavigationOffRecordFallbackTest {

    @Test
    void offRecordEndpointReturnsNoRouteInsteadOfThrowing() {
        List<String> path = PathFinder.createPath(
                920010000,
                new Point(9_999_999, 9_999_999),
                new Point(14, 143),
                PathFinder.PathType.SHORTEST);

        assertTrue(path.isEmpty(), "expected no route, got " + path);
    }

    @Test
    void randomPathTypeAlsoDegradesQuietly() {
        List<String> path = PathFinder.createPath(
                920010000,
                new Point(9_999_999, 9_999_999),
                new Point(14, 143),
                PathFinder.PathType.RANDOM);

        assertTrue(path.isEmpty(), "expected no route, got " + path);
    }
}
