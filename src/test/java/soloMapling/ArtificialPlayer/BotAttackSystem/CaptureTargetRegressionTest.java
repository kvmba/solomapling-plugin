package soloMapling.ArtificialPlayer.BotAttackSystem;

import org.gms.server.life.Monster;
import org.junit.Test;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class CaptureTargetRegressionTest {
    @Test
    public void healthyScorpionDoesNotAuthorizeHittingACatchableNeighbour() {
        Monster healthy = mock(Monster.class), catchable = mock(Monster.class);
        for (Monster monster : new Monster[]{healthy, catchable}) {
            when(monster.getId()).thenReturn(9300157);
            when(monster.getMaxHp()).thenReturn(1000);
        }
        when(healthy.getHp()).thenReturn(1000);
        when(catchable.getHp()).thenReturn(100);
        assertTrue(BotTargetRules.isCombatTarget(healthy));
        assertFalse(BotTargetRules.isCombatTarget(catchable));
        assertEquals(601, BotTargetRules.catchSafeDamage(healthy, 2000));
        assertEquals(0, BotTargetRules.catchSafeDamage(catchable, 2000));
        when(healthy.getHp()).thenReturn(399); // an earlier delayed/summon hit already weakened it
        assertEquals(0, BotTargetRules.catchSafeDamage(healthy, 100));
    }
}
