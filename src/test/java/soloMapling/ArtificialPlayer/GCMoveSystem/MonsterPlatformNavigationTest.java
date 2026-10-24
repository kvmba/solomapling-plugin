package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.client.Character;
import org.gms.server.maps.Foothold;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Rope;
import org.junit.BeforeClass;
import org.junit.Test;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static soloMapling.ArtificialPlayer.GCMoveSystem.NavigationReachabilityTest.*;

/** Pins the two halves of the LPQ stage-1 freeze: legal monster platforms stay enterable, and a
 *  graph without a crossing hands an upward target to the physics-validated local ascent. */
public class MonsterPlatformNavigationTest {

    @BeforeClass
    public static void initialize() throws Exception {
        initializeCharacterServices();
    }

    @Test
    public void monsterSpawnPlatformIsNotSweptAsDead() {
        Foothold base = floor(1, -100, 0, 0);
        Foothold spawnShelf = floor(2, 50, 100, -100);
        BotNavigationGraph.Region baseRegion = new BotNavigationGraph.Region(
                1, List.of(new BotNavigationGraph.Segment(base)));
        BotNavigationGraph.Region shelfRegion = new BotNavigationGraph.Region(
                2, List.of(new BotNavigationGraph.Segment(spawnShelf)));
        Map<Integer, Integer> regionByFoothold = Map.of(1, 1, 2, 2);
        BotNavigationGraph.Edge inbound = new BotNavigationGraph.Edge(1, 2,
                BotNavigationGraph.EdgeType.JUMP, new Point(0, 0), new Point(75, -100),
                0, 0, 0, 0, 0, 100);
        Map<Integer, List<BotNavigationGraph.Edge>> outgoing = Map.of(
                1, new ArrayList<>(List.of(inbound)));

        MapleMap map = mock(MapleMap.class);
        when(map.getId()).thenReturn(424242);
        when(map.getPortals()).thenReturn(List.of());

        Set<Integer> dead = BotNavigationGraphProvider.findDeadRegions(map,
                List.of(baseRegion, shelfRegion), regionByFoothold, outgoing, Set.of(2));
        assertFalse("a monster platform is a legal destination", dead.contains(2));
        assertFalse("a platform whose only exit is a legal destination stays live", dead.contains(1));

        Set<Integer> withoutMonster = BotNavigationGraphProvider.findDeadRegions(map,
                List.of(baseRegion, shelfRegion), regionByFoothold, outgoing, Set.of());
        assertEquals("without the spawn proof the missing exit makes the shelf terminal",
                Set.of(1, 2), withoutMonster);
    }

    @Test
    public void upwardTargetOnClosestProfileGraphUsesLocalAscent() throws Exception {
        MapleMap map = spy(geometry(floor(1, -100, 100, 150), floor(2, 30, 33, -65)));
        when(map.getMonsterSpawnPositions()).thenReturn(List.of(new Point(31, -65)));
        map.addRope(new Rope(0, -150, 150, true));
        BotNavigationGraph graph = build(map, BotMovementProfile.base());
        Character bot = character(map, new Point(0, 100));
        BotMovementState state = new BotMovementState(bot, null);
        state.movementProfile = new BotMovementProfile(120, 80);
        state.physX = 0;
        state.physY = 100;
        state.navGraph = graph;
        for (Foothold foothold : map.getFootholds().getAllFootholds()) {
            state.fhIndex.put(foothold.getId(), foothold);
        }
        Point target = new Point(31, -65);

        try (var provider = mockStatic(BotNavigationGraphProvider.class, CALLS_REAL_METHODS)) {
            provider.when(() -> BotNavigationGraphProvider.peekBestGraph(map, state.movementProfile))
                    .thenReturn(graph);
            provider.when(() -> BotNavigationGraphProvider.peekGraph(map, state.movementProfile))
                    .thenReturn(null);
            provider.when(() -> BotNavigationGraphProvider.warmGraphAsync(any(), any()))
                    .thenReturn(CompletableFuture.completedFuture(null));

            BotNavigationManager.NavigationDirective nav =
                    BotNavigationManager.resolveTarget(state, target, true);
            assertTrue("the closest-profile graph must not plan the climb", state.graphWarmupFallback);
            assertEquals("graph-fallback-profile-up", state.lastNavDecision);
            assertFalse(nav.consumedTick);
            assertEquals(target, nav.targetPos);
        }
    }

    @Test
    public void noPathUpwardTargetUsesLocalAscentAndReachesTheShelf() throws Exception {
        Foothold base = floor(1, -100, 100, 150);
        Foothold upper = floor(2, 0, 3, -65);
        MapleMap map = spy(geometry(base, upper));
        when(map.getMonsterSpawnPositions()).thenReturn(List.of(new Point(1, -65)));
        map.addRope(new Rope(0, -65, 150, true));
        BotNavigationGraph graph = build(map, BotMovementProfile.base());
        int baseRegion = region(graph, base);
        int targetRegion = region(graph, upper);
        graph.getOutgoing(baseRegion).removeIf(edge -> edge.fromRegionId != edge.toRegionId);

        Character bot = character(map, new Point(0, 150));
        BotMovementState state = new BotMovementState(bot, null);
        state.movementProfile = graph.movementProfile;
        state.physX = 0;
        state.physY = 150;
        state.navGraph = graph;
        for (Foothold foothold : map.getFootholds().getAllFootholds()) {
            state.fhIndex.put(foothold.getId(), foothold);
        }
        Point target = new Point(1, -65);
        MovementClock.useVirtual(10000);
        try (var provider = mockStatic(BotNavigationGraphProvider.class, CALLS_REAL_METHODS)) {
            provider.when(() -> BotNavigationGraphProvider.peekGraph(map)).thenReturn(graph);
            provider.when(() -> BotNavigationGraphProvider.peekGraph(map, state.movementProfile))
                    .thenReturn(graph);
            provider.when(() -> BotNavigationGraphProvider.peekBestGraph(map, state.movementProfile))
                    .thenReturn(graph);

            BotNavigationManager.NavigationDirective nav =
                    BotNavigationManager.resolveTarget(state, target, true);
            assertEquals("no-path-fallback", state.lastNavDecision);
            assertTrue(state.graphWarmupFallback);
            assertFalse(nav.consumedTick);
            assertEquals(target, nav.targetPos);

            boolean climbed = false;
            for (int tick = 0; tick < 200; tick++) {
                MovementClock.advance(50);
                BotNavigationManager.NavigationDirective step =
                        BotNavigationManager.resolveTarget(state, target, tick % 2 == 0);
                if (step.consumedTick) {
                    continue;
                }
                if (state.inAir) {
                    BotMovementManager.tickAirborne(state, step.targetPos);
                } else if (state.climbing) {
                    climbed = true;
                    BotMovementManager.tickClimbing(state, step.targetPos, tick % 2 == 0);
                } else {
                    BotMovementManager.tickGrounded(state, step.targetPos);
                }
                if (BotNavigationManager.resolveCurrentRegionId(graph, state, map, bot.getPosition())
                        == targetRegion) {
                    break;
                }
            }
            assertTrue("the local ascent must put the bot on the rope", climbed);
            assertEquals("the ascent must end on the monster shelf", targetRegion,
                    BotNavigationManager.resolveCurrentRegionId(graph, state, map, bot.getPosition()));
        } finally {
            MovementClock.reset();
        }
    }
}
