package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.server.maps.Foothold;
import org.gms.server.maps.FootholdTree;
import org.gms.server.maps.MapleMap;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.awt.Point;
import java.awt.Rectangle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WallFaceDepartureTest {
    private static MapleMap map(int solidSide) {
        MapleMap map = mock(MapleMap.class);
        FootholdTree tree = new FootholdTree(new Point(-500, -500), new Point(500, 500));
        tree.insert(new Foothold(new Point(-500, 500), new Point(500, 500), 1));
        Foothold wall = new Foothold(new Point(0, solidSide > 0 ? 0 : -100),
                new Point(0, solidSide > 0 ? -100 : 0), 2);
        Foothold lip = new Foothold(new Point(solidSide > 0 ? -100 : 0, 0),
                new Point(solidSide > 0 ? 0 : 100, 0), 3);
        if (solidSide > 0) wall.setPrev(3);
        else wall.setNext(3);
        tree.insert(wall);
        tree.insert(lip);
        when(map.getFootholds()).thenReturn(tree);
        when(map.getMapArea()).thenReturn(new Rectangle(-500, -500, 1000, 1000));
        return map;
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 1})
    void startingOnTheWallCannotEnterItsSolidSide(int solidSide) {
        var collision = BotPhysicsEngine.resolveAirCollision(map(solidSide),
                new Point(0, -50), new Point(solidSide * 9, -60));
        assertEquals(BotPhysicsEngine.AirCollisionType.WALL, collision.type());
        assertEquals(-solidSide, collision.point().x);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 1})
    void startingOnTheWallCanMoveAwayFromItsSolidSide(int solidSide) {
        var collision = BotPhysicsEngine.resolveAirCollision(map(solidSide),
                new Point(0, -50), new Point(-solidSide * 9, -60));
        assertEquals(BotPhysicsEngine.AirCollisionType.NONE, collision.type());
    }

    @ParameterizedTest
    @CsvSource({"-1, -100, -90", "1, -100, -90", "-1, 0, -10", "1, 0, -10"})
    void departingAnEndpointIntoTheWallIsStillBlocked(int solidSide, int y, int nextY) {
        var collision = BotPhysicsEngine.resolveAirCollision(map(solidSide),
                new Point(0, y), new Point(solidSide * 9, nextY));
        assertEquals(BotPhysicsEngine.AirCollisionType.WALL, collision.type());
    }

    @ParameterizedTest
    @CsvSource({"-1, -100, -110", "1, -100, -110", "-1, 0, 10", "1, 0, 10"})
    void clearingAVerticalEndDoesNotHitTheWall(int solidSide, int y, int nextY) {
        var collision = BotPhysicsEngine.resolveAirCollision(map(solidSide),
                new Point(0, y), new Point(solidSide * 9, nextY));
        assertNotEquals(BotPhysicsEngine.AirCollisionType.WALL, collision.type());
    }
}
