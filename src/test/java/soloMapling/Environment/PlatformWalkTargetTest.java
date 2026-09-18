package soloMapling.Environment;

import org.junit.jupiter.api.Test;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * A bot that walks must land on a real standing surface, never in mid-air.
 *
 * <p>Two ways the Free Market entrance produced a bot standing in the air / on the stairs:
 *
 * <ul>
 *   <li>{@link PlatformPlacement#getCurrentPlatform} (and {@code findPlatformAtPosition}) resolved a
 *       standable position onto one of the map's {@code c*} "connector" recordings — the climb/shaft
 *       paths whose reference points are the mid-air Y a climber passes through. The FM entrance's
 *       flat m1/m2 platforms span the whole map width and contain the ladder columns (x=933,
 *       x=1033), so a merchant shuffling "to its current platform" at an x over a ladder column
 *       resolved to a {@code c*} recording, walked there, and rendered a STAND pose in mid-air. The
 *       lookup is now restricted to main ({@code m*}) platforms, which are the walkable surfaces.</li>
 * </ul>
 */
class PlatformWalkTargetTest {

    private static final int FM_ENTRANCE = 910000000;

    @Test
    void ladderColumnPositionResolvesToTheMainPlatformNotAConnector() {
        // x=933 sits on the FM entrance's flat m1 (y=4) floor AND on the ladder column that the
        // c2-1b/c1-2b connector recordings follow. A bot standing here is on the floor.
        String platform = PlatformPlacement.findPlatformAtPosition(FM_ENTRANCE, new Point(933, 4));

        assertNotNull(platform, "the floor under the ladder column must still resolve");
        assertEquals("m1", platform, "a standable position must resolve to the main floor, not a connector");
        assertFalse(platform.startsWith("c"),
                "a c* connector's reference points are mid-air; it must never be a walk target");
    }

    @Test
    void ordinaryFloorPositionStillResolvesToItsMainPlatform() {
        assertEquals("m1", PlatformPlacement.findPlatformAtPosition(FM_ENTRANCE, new Point(1000, 4)));
        assertEquals("m2", PlatformPlacement.findPlatformAtPosition(FM_ENTRANCE, new Point(700, -266)));
    }
}
