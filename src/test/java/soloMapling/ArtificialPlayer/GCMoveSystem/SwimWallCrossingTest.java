package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.server.maps.Foothold;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the swim-map floor-wall crossing fix (Aqua Road / Crystal Canyon 230010100).
 *
 * <p>Those maps are {@code swim=true}, so they run on the heuristic fallback: no A* graph, and their
 * floor is cut by collidable vertical foothold walls far taller than a ground jump. The greedy swim
 * controller derives its vertical intent only from the target's relative height, so a target at the
 * bot's own depth gets no upward input — the bot just pins against the wall (applySwimMotion zeroes
 * vx on WALL) forever. {@link BotPhysicsEngine#swimWallTopAhead} is the missing probe: it reports the
 * wall top the swimmer must clear, which computeSwimIntents turns into an UP + burst intent.
 *
 * <p>Geometry mirrors the real map: a floor at y=580 with a collidable wall (x=51) rising to y=340.
 * The collision index is exercised through the wall-list overload so the case is pure geometry — a
 * real {@code MapleMap} cannot be built in a unit test (its constructor trips the Server static
 * initializer).
 */
class SwimWallCrossingTest {

    private static Foothold wall(int x, int yTop, int yBottom) {
        return new Foothold(new Point(x, yBottom), new Point(x, yTop), 1);
    }

    private static final List<Foothold> FLOOR_WALL = List.of(wall(51, 340, 580));

    @Test
    void reportsTheWallTopWhenAFloorWallBlocksTheSwimPath() {
        // Bot on the floor at the wall's own depth, target across it at the same depth.
        int top = BotPhysicsEngine.swimWallTopAhead(FLOOR_WALL, new Point(20, 570), new Point(120, 570));
        assertEquals(340, top, "wall top y the swimmer must rise above");
    }

    @Test
    void ignoresAWallBehindTheSwimmer() {
        int top = BotPhysicsEngine.swimWallTopAhead(FLOOR_WALL, new Point(60, 570), new Point(120, 570));
        assertEquals(Integer.MIN_VALUE, top, "wall at x=51 is behind a swimmer at x=60");
    }

    @Test
    void ignoresAWallTheSwimmerHasAlreadyCleared() {
        // Wall top is 340; the swimmer must clear it by SWIM_WALL_CLEAR_PX (40), so y <= 299 is past.
        int top = BotPhysicsEngine.swimWallTopAhead(FLOOR_WALL, new Point(20, 299), new Point(120, 299));
        assertEquals(Integer.MIN_VALUE, top, "wall top y=340 cleared by a swimmer at y=299");
    }

    @Test
    void stillBlocksInsideTheClearBandAboveTheTop() {
        // y=320 is above the top (340) but within the 40px clearance, so the wall still blocks: the
        // swimmer must end up unambiguously above the lip, not exactly level with it.
        int top = BotPhysicsEngine.swimWallTopAhead(FLOOR_WALL, new Point(20, 320), new Point(120, 320));
        assertEquals(340, top, "wall top y=340 still blocks a swimmer at y=320 (inside clearance)");
    }

    @Test
    void ignoresNoWallOnThePath() {
        int top = BotPhysicsEngine.swimWallTopAhead(FLOOR_WALL, new Point(20, 570), new Point(40, 570));
        assertEquals(Integer.MIN_VALUE, top, "wall at x=51 is past the target x=40");
    }

    @Test
    void returnsTheHighestTopWhenSeveralWallsBlock() {
        List<Foothold> twoWalls = List.of(wall(51, 340, 580), wall(90, 300, 580));
        int top = BotPhysicsEngine.swimWallTopAhead(twoWalls, new Point(20, 570), new Point(120, 570));
        assertEquals(300, top, "clearing every blocking wall needs the highest top (smallest y)");
    }
}
