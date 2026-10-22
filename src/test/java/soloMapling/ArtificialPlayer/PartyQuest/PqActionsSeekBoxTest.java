package soloMapling.ArtificialPlayer.PartyQuest;

import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.lang.reflect.Method;
import org.gms.server.life.Monster;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PqActionsSeekBoxTest {
    @Test
    void seeksHostileWorkThroughoutTheRoom() throws Exception {
        Monster monster = mock(Monster.class);
        when(monster.isAlive()).thenReturn(true);
        Method target = PqActions.class.getDeclaredMethod("isHuntTarget", Monster.class);
        target.setAccessible(true);
        for (Point position : new Point[]{new Point(-669, 225), new Point(0, -4575)}) {
            when(monster.getPosition()).thenReturn(position);
            assertTrue((boolean) target.invoke(null, monster));
        }
    }
}
