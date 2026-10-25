package soloMapling.ArtificialPlayer.PartyQuest;

import org.gms.client.Character;
import org.gms.server.maps.MapleMap;
import org.junit.BeforeClass;
import org.junit.Test;
import soloMapling.ArtificialPlayer.BotTypes.Ellin.EllinPQBot;
import soloMapling.ArtificialPlayer.BotTypes.OPQ.OrbisStages;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.ArtificialPlayer.GCMoveSystem.NavigationReachabilityTest;

import java.awt.Point;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.AdditionalMatchers.aryEq;

public class QuestFlowRegressionTest {
    @BeforeClass
    public static void initialize() throws Exception {
        NavigationReachabilityTest.initializeCharacterServices();
    }

    @Test
    public void papaRoomStillCollectsWhenItsLastMonsterHasGone() {
        Character bot = mock(Character.class);
        MapleMap map = mock(MapleMap.class);
        when(bot.getMap()).thenReturn(map);
        when(bot.getPosition()).thenReturn(new Point(0, 0));
        when(map.getAllMonsters()).thenReturn(List.of());
        when(map.getAllReactors()).thenReturn(List.of());
        try (var pq = mockStatic(PqActions.class)) {
            pq.when(() -> PqActions.readEimInt(bot, "statusStg7", -1)).thenReturn(-1);
            assertFalse(OrbisStages.settlePapaRoom(bot));
            pq.verify(() -> PqActions.loot(eq(bot), eq(new Point(0, 0)), eq(4000.0),
                    aryEq(new int[]{4001053, 4001074, 4001054})));
        }
    }

    @Test
    public void mazeExitRetriesAFailedAskAndDoesNotLatchAcrossRuns() throws Exception {
        Character bot = mock(Character.class);
        when(bot.getMapId()).thenReturn(930000300);
        EllinPQBot brain = mock(EllinPQBot.class, CALLS_REAL_METHODS);
        when(brain.getChr()).thenReturn(bot);
        Method ask = EllinPQBot.class.getDeclaredMethod("askMazeExit");
        ask.setAccessible(true);
        Method clear = PartyQuestBot.class.getDeclaredMethod("clearRunState");
        clear.setAccessible(true);
        try (var pq = mockStatic(PqActions.class)) {
            pq.when(() -> PqActions.talkTo(bot, 2133001, 0)).thenReturn(false, true, true);
            ask.invoke(brain);
            ask.invoke(brain);
            clear.invoke(brain);
            ask.invoke(brain);
            pq.verify(() -> PqActions.talkTo(bot, 2133001, 0), times(3));
        }
    }

    @Test
    public void pqWalkDoesNotTreatAnUnsettledPixelAsArrival() {
        Character bot = mock(Character.class);
        MapleMap map = mock(MapleMap.class);
        when(bot.getMap()).thenReturn(map);
        when(bot.getPosition()).thenReturn(new Point(0, 0));
        try (var movement = mockStatic(GCMovement.class)) {
            assertFalse(PqActions.walkTo(bot, new Point(0, 0)));
            movement.verify(() -> GCMovement.stop(bot), never());
            movement.verify(() -> GCMovement.move(eq(bot), eq(0), eq(0), any(), any()));
        } finally {
            PqActions.clearSeekState(bot.getId());
        }
    }
}
