package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Rope;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 玩具塔 A 类梯（顶部容差带内无落脚台的 28/394 条，如 221020100 x=-3）：
 * findTopExitLanding 必须对这类梯返回 null（顶部钳制路径的前提），
 * 而普通梯头必须仍然找到落脚点 —— 容差任何一侧的改动都会被这里钉住。
 */
class EosLadderTopClampTest {

    @Test
    void firstClimbableYSitsJustBelowTheRopeTop() {
        Rope r = new Rope(0, -100, 300, true);
        assertEquals(-99, BotPhysicsEngine.firstClimbableY(r));
    }

    @Test
    void aBareRopeHeadHasNoTopExit() throws Exception {
        // Real WZ: 221020100's rope at x=-3 (top 466, bottom 757) has its nearest
        // on-axis foothold ~300px below the head - nothing in the -24..+20 band.
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(221020100);
        Rope target = ropeAt(map, -3, 466);
        assertNotNull(target, "221020100 must still carry the bare rope head at x=-3");
        assertNull(BotPhysicsEngine.findTopExitLanding(map, target),
                "the x=-3 rope head (top 466, floor 788) has no landing in the tol band");
    }

    @Test
    void aNormalRopeHeadStillHasItsTopExit() throws Exception {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(221020100);
        Rope target = ropeAt(map, 165, 1208);
        assertNotNull(target);
        assertNotNull(BotPhysicsEngine.findTopExitLanding(map, target),
                "the x=165 ladder head must still step off");
    }

    private static Rope ropeAt(MapleMap map, int x, int topY) {
        for (Rope r : map.getRopes()) {
            if (r.x() == x && Math.min(r.y1(), r.y2()) == topY) {
                return r;
            }
        }
        return null;
    }
}
