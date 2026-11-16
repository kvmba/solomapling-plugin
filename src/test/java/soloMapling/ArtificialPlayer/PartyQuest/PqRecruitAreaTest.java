package soloMapling.ArtificialPlayer.PartyQuest;

import org.gms.client.Character;
import org.gms.server.life.NPC;
import org.gms.server.maps.MapleMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotGrindSystem.BotSpotPicker;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.Environment.EnvironmentManager;
import soloMapling.Environment.PlatformPlacement;

import java.awt.Point;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PqRecruitAreaTest {
    @BeforeAll
    static void stubHost() throws Exception {
        PqLobbySpawnTest.stubHost();
    }

    @Test
    void onlyTownRecruitmentMapsAreLeashedAndRadiusUsesExpectedHeadcount() {
        try (var environment = mockStatic(EnvironmentManager.class)) {
            environment.when(() -> EnvironmentManager.scaledAmbient(anyInt()))
                    .thenAnswer(call -> call.getArgument(0));
            List<PqRecruitPoints.Point> points = new ArrayList<>(PqRecruitPoints.ALL);
            points.add(PqRecruitPoints.ORBIS);
            points.add(PqBotSpawner.CARNIVAL);
            points.add(PqBotSpawner.CARNIVAL_2);
            for (var point : points) {
                MapleMap map = mock(MapleMap.class);
                when(map.getId()).thenReturn(point.recruitMap());
                if (point.recruitMap() == 100000200 || point.recruitMap() == 103000000) {
                    boolean henesys = point.recruitMap() == 100000200;
                    Point center = new Point(henesys ? 1434 : -540, henesys ? 352 : 421);
                    NPC npc = mock(NPC.class);
                    when(npc.getPosition()).thenReturn(center);
                    when(map.getNPCById(henesys ? 1012112 : 9020000)).thenReturn(npc);

                    var area = PqRecruitArea.forMap(map);

                    assertEquals(center, area.center());
                    assertEquals(henesys ? 15 : 12, PqBotSpawner.recruitCount(point));
                    assertEquals(henesys ? 350 : 300, area.radius());
                    environment.when(() -> EnvironmentManager.scaledAmbient(anyInt()))
                            .thenAnswer(call -> (int) call.getArgument(0) * 2);
                    assertEquals(henesys ? 650 : 410, PqRecruitArea.forMap(map).radius());
                    environment.when(() -> EnvironmentManager.scaledAmbient(anyInt()))
                            .thenAnswer(call -> call.getArgument(0));
                } else {
                    assertNull(PqRecruitArea.forMap(map), point.name());
                    verify(map, never()).getNPCById(anyInt());
                }
            }
        }
    }

    @Test
    void circleUsesActualGroundDistanceWithoutRestrictingPlatformOrWidth() {
        MapleMap map = mock(MapleMap.class);
        try (var movement = mockStatic(GCMovement.class)) {
            movement.when(() -> GCMovement.reachableRegions(map, 0, 0)).thenReturn(Set.of(1));
            for (int y : new int[]{-200, 0, 200}) {
                movement.when(() -> GCMovement.walkableLedges(map))
                        .thenReturn(List.of(new GCMovement.Ledge(1, 100, 100, 100, y)));
                movement.when(() -> GCMovement.groundPointInRegion(map, 1, 100))
                        .thenReturn(new Point(100, y));

                List<Point> spots = BotSpotPicker.pickGroundSpotsInRadius(
                        map, 0, 0, new Point(), 300, 12);

                assertEquals(12, spots.size());
                Point expected = new Point(100, y);
                for (Point spot : spots) {
                    assertEquals(expected, spot);
                }
            }
            // Inside the bounding square, but outside the circle. The ledge centre is
            // deliberately misleading: the check must use its resolved ground point.
            movement.when(() -> GCMovement.walkableLedges(map))
                    .thenReturn(List.of(new GCMovement.Ledge(1, 250, 250, 250, 0)));
            movement.when(() -> GCMovement.groundPointInRegion(map, 1, 250))
                    .thenReturn(new Point(250, 250));
            assertTrue(BotSpotPicker.pickGroundSpotsInRadius(
                    map, 0, 0, new Point(), 300, 12).isEmpty());
        }
    }

    @Test
    void circleDoesNotPlaceBotsOnUnreachableLedges() {
        MapleMap map = mock(MapleMap.class);
        try (var movement = mockStatic(GCMovement.class)) {
            movement.when(() -> GCMovement.walkableLedges(map))
                    .thenReturn(List.of(new GCMovement.Ledge(2, 100, 200, 150, 0)));
            movement.when(() -> GCMovement.reachableRegions(map, 0, 0)).thenReturn(Set.of(1));

            assertTrue(BotSpotPicker.pickGroundSpotsInRadius(
                    map, 0, 0, new Point(), 300, 12).isEmpty());

            movement.verify(() -> GCMovement.groundPointInRegion(eq(map), anyInt(), anyInt()), never());
        }
    }

    @Test
    void missingGroundIsNotReplacedWithTheLedgeCentreHeight() {
        MapleMap map = mock(MapleMap.class);
        try (var movement = mockStatic(GCMovement.class)) {
            movement.when(() -> GCMovement.walkableLedges(map))
                    .thenReturn(List.of(new GCMovement.Ledge(1, 100, 100, 100, 0)));
            movement.when(() -> GCMovement.reachableRegions(map, 0, 0)).thenReturn(Set.of(1));

            assertTrue(BotSpotPicker.pickGroundSpotsInRadius(
                    map, 0, 0, new Point(), 300, 12).isEmpty());
        }
    }

    @Test
    void outlyingBotMayTakeAShortStepBackInsideTheRadius() {
        MapleMap map = mock(MapleMap.class);
        Character bot = mock(Character.class);
        when(bot.getMap()).thenReturn(map);
        when(bot.getPosition()).thenReturn(new Point(301, 0));
        Point center = new Point();
        Point target = new Point(299, 0);
        try (var movement = mockStatic(GCMovement.class);
             var spots = mockStatic(BotSpotPicker.class)) {
            spots.when(() -> BotSpotPicker.pickGroundSpotsInRadius(map, 301, 0, center, 300, 8))
                    .thenReturn(List.of(target));
            movement.when(() -> GCMovement.canPathTo(bot, target.x, target.y)).thenReturn(true);

            new PqRecruitArea(center, 300).stroll(bot);

            movement.verify(() -> GCMovement.move(bot, target.x, target.y));
        }
    }

    @Test
    void realTownTerrainOffersGroundedSpotsForSpawningAndReturning() throws Exception {
        assumeTrue(Files.isDirectory(Path.of("wz/Map.wz")), "Map.wz needed");
        var load = Class.forName("soloMapling.ArtificialPlayer.GCMoveSystem.BotNavigationMapLoader")
                .getDeclaredMethod("loadMapGeometry", int.class);
        load.setAccessible(true);
        for (int[] town : new int[][]{
                {100000200, 1434, 352, 350, 15},
                {103000000, -540, 421, 300, 12}}) {
            MapleMap map = (MapleMap) load.invoke(null, town[0]);
            var area = new PqRecruitArea(new Point(town[1], town[2]), town[3]);
            for (Point from : List.of(area.center(), map.getPortal(0).getPosition())) {
                List<Point> spots = area.spots(map, from, town[4]);
                assertFalse(spots.isEmpty(), "No recruitment ground on " + town[0] + " from " + from);
                for (Point spot : spots) {
                    assertTrue(area.contains(spot), "Out of radius: " + spot);
                    Point ground = GCMovement.groundPointBelow(map, spot.x, spot.y - 1);
                    assertNotNull(ground, "No physical ground under " + spot);
                    assertTrue(spot.distanceSq(ground) <= 1, "Floating spot: " + spot + " above " + ground);
                }
            }
        }
    }

    @Test
    void outlyingTownBotWalksBackBeforeTheNextStrollAndDoesNotUseTheWholeMapPicker() throws Exception {
        MapleMap map = mock(MapleMap.class);
        when(map.getId()).thenReturn(100000200);
        NPC npc = mock(NPC.class);
        Point center = new Point(1434, 352);
        when(npc.getPosition()).thenReturn(center);
        when(map.getNPCById(1012112)).thenReturn(npc);
        Character bot = mock(Character.class);
        when(bot.getMap()).thenReturn(map);
        when(bot.getMapId()).thenReturn(100000200);
        when(bot.getPosition()).thenReturn(new Point(27, 304));
        PartyQuestBot quest = lobbyBot(bot, Long.MAX_VALUE);
        Point target = new Point(1600, 574); // A different floor, still within Tory's radius.

        try (var environment = mockStatic(EnvironmentManager.class);
             var movement = mockStatic(GCMovement.class);
             var spots = mockStatic(BotSpotPicker.class);
             var platforms = mockStatic(PlatformPlacement.class)) {
            environment.when(() -> EnvironmentManager.scaledAmbient(anyInt()))
                    .thenAnswer(call -> call.getArgument(0));
            spots.when(() -> BotSpotPicker.pickGroundSpotsInRadius(
                            eq(map), anyInt(), anyInt(), eq(center), eq(350), eq(8)))
                    .thenReturn(List.of(target));
            movement.when(() -> GCMovement.canPathTo(bot, target.x, target.y)).thenReturn(true);

            quest.tickLobby(bot);
            quest.tickLobby(bot);

            movement.verify(() -> GCMovement.move(bot, target.x, target.y), times(2));
            verify(map, times(1)).getNPCById(1012112);
            platforms.verifyNoInteractions();

            when(bot.getPosition()).thenReturn(center);
            quest.tickLobby(bot);
            movement.verify(() -> GCMovement.move(bot, target.x, target.y), times(2));

            when(bot.getPosition()).thenReturn(new Point(27, 304));
            movement.when(() -> GCMovement.isMoving(bot)).thenReturn(true);
            quest.tickLobby(bot);
            movement.verify(() -> GCMovement.move(bot, target.x, target.y), times(2));
        }
    }

    @Test
    void dedicatedLobbyKeepsItsOriginalWholeMapStroll() throws Exception {
        MapleMap map = mock(MapleMap.class);
        when(map.getId()).thenReturn(221024500);
        Character bot = mock(Character.class);
        when(bot.getMap()).thenReturn(map);
        when(bot.getMapId()).thenReturn(221024500);
        PartyQuestBot quest = lobbyBot(bot, 1L); // An expired deadline; no real-time waiting.
        try (var platforms = mockStatic(PlatformPlacement.class)) {
            quest.tickLobby(bot);

            platforms.verify(() -> PlatformPlacement.botStrollOnMap(bot));
            verify(map, never()).getNPCById(anyInt());
        }
    }

    private static PartyQuestBot lobbyBot(Character bot, long strollDeadline) throws Exception {
        PartyQuestBot quest = mock(PartyQuestBot.class, CALLS_REAL_METHODS);
        doReturn(bot.getMapId()).when(quest).lobbyMapId();
        for (String name : List.of("nextRecruitShoutAtMs", "nextLobbyStrollAtMs")) {
            var field = PartyQuestBot.class.getDeclaredField(name);
            field.setAccessible(true);
            field.setLong(quest, name.equals("nextLobbyStrollAtMs") ? strollDeadline : Long.MAX_VALUE);
        }
        return quest;
    }
}
