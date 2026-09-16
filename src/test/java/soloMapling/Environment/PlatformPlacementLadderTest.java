package soloMapling.Environment;

import org.gms.server.maps.Rope;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A spawned bot must land on the floor, not on a rope/ladder column: the Free Market entrance's
 * flat m1/m2 platforms span x∈[387,1499] / [425,1462] and each contains a ladder (x=933, x=1033),
 * so a uniform x-pick used to drop the odd bot straight onto a ladder axis, where it rendered a
 * standing pose on the ladder sprite. {@link PlatformPlacement#avoidLadderColumn} nudges such a
 * spawn clear of the column.
 */
class PlatformPlacementLadderTest {

    // FM entrance lower ladder: x=933, y -264..2.
    private static final List<Rope> LOWER = List.of(new Rope(933, -264, 2, true));

    @Test
    void pointOnTheLadderColumnIsNudgedClear() {
        Point avoided = PlatformPlacement.avoidLadderColumn(LOWER, new Point(933, 4), 387, 1499);

        assertNotEquals(933, avoided.x, "must leave the ladder column");
        assertEquals(4, avoided.y, "y is preserved");
        assertEquals(951, avoided.x, "a point exactly on the axis goes right by the clear margin (18px)");
    }

    @Test
    void pointLeaningRightOfTheAxisKeepsGoingRight() {
        Point avoided = PlatformPlacement.avoidLadderColumn(LOWER, new Point(940, 4), 387, 1499);
        assertEquals(951, avoided.x);
    }

    @Test
    void pointLeaningLeftOfTheAxisGoesLeft() {
        Point avoided = PlatformPlacement.avoidLadderColumn(LOWER, new Point(925, 4), 387, 1499);
        assertEquals(915, avoided.x);
    }

    @Test
    void pointJustOffTheColumnIsLeftAlone() {
        Point off = new Point(880, 4);
        assertSame(off, PlatformPlacement.avoidLadderColumn(LOWER, off, 387, 1499),
                "a point already clear of the column is returned as-is");
    }

    @Test
    void pointOnTheColumnButOutsideItsVerticalSpanIsLeftAlone() {
        // The same ladder's column, but a y far below its bottom (a different floor entirely).
        Point elsewhere = new Point(933, 400);
        assertSame(elsewhere, PlatformPlacement.avoidLadderColumn(LOWER, elsewhere, 387, 1499),
                "the column only casts within its own vertical span (+sprite overhang)");
    }

    @Test
    void emptyRopeListLeavesEveryPointAlone() {
        Point p = new Point(933, 4);
        assertSame(p, PlatformPlacement.avoidLadderColumn(List.of(), p, 387, 1499));
        assertSame(p, PlatformPlacement.avoidLadderColumn(null, p, 387, 1499),
                "an unloaded map must not break the spawn batch");
    }

    @Test
    void nudgeStaysWithinThePlatformBounds() {
        // A ladder near the platform's right edge: the right nudge would exceed maxX, so it clamps.
        List<Rope> ropes = List.of(new Rope(395, -264, 2, true));
        Point avoided = PlatformPlacement.avoidLadderColumn(ropes, new Point(395, 4), 387, 400);
        assertTrue(avoided.x >= 387 && avoided.x <= 400, "the nudged point stays on the platform");
    }
}
