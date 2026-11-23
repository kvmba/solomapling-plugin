package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.client.Character;
import org.gms.config.GameConfig;
import org.gms.dao.entity.GameConfigDO;
import org.gms.manager.ServerManager;
import org.gms.property.ServiceProperty;
import org.gms.server.maps.MapleMap;
import org.gms.service.ConfigService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ApplicationContext;
import org.springframework.context.MessageSource;

import java.awt.Point;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Walk-off routes must land whether a ledge ends at the map boundary or at an interior wall. */
class ShopWalkOffBoundaryDropTest {
    private static final int ELLINIA_TREE = 100040003;
    private static MapleMap shop;
    private static MapleMap tree;
    private static Method tick;

    @BeforeAll
    static void boot() throws Exception {
        ApplicationContext ctx = mock(ApplicationContext.class);
        ConfigService configService = mock(ConfigService.class);
        when(configService.loadGameConfigs()).thenReturn(List.of());
        when(ctx.getBean(ConfigService.class)).thenReturn(configService);
        ServiceProperty props = mock(ServiceProperty.class);
        when(props.getLanguage()).thenReturn("zh-CN");
        when(ctx.getBean(ServiceProperty.class)).thenReturn(props);
        MessageSource messages = mock(MessageSource.class);
        when(messages.getMessage(anyString(), any(Object[].class), any(Locale.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        for (String name : List.of("messageSource", "logSource", "exceptionSource")) {
            when(ctx.getBean(name, MessageSource.class)).thenReturn(messages);
        }
        var setter = ServerManager.class.getDeclaredMethod("setApplicationContext", ApplicationContext.class);
        setter.setAccessible(true);
        setter.invoke(new ServerManager(), ctx);
        GameConfigDO config = new GameConfigDO();
        config.setConfigType("server");
        config.setConfigSubType("0");
        config.setConfigCode("update_interval");
        config.setConfigValue("100");
        config.setConfigClazz("java.lang.Long");
        GameConfig.add(config);
        org.gms.net.server.Server.getInstance();
        shop = BotNavigationMapLoader.loadMapGeometry(220000002);
        tree = BotNavigationMapLoader.loadMapGeometry(ELLINIA_TREE);
        tick = GCMovementDriver.class.getDeclaredMethod("tick", BotMovementState.class);
        tick.setAccessible(true);
    }

    @ParameterizedTest
    @ValueSource(ints = {220000002, 221020000, ELLINIA_TREE})
    void everyBakedWalkOffDropHasARealLanding(int mapId) {
        MapleMap map = switch (mapId) {
            case 220000002 -> shop;
            case ELLINIA_TREE -> tree;
            default -> BotNavigationMapLoader.loadMapGeometry(mapId);
        };
        BotNavigationGraph graph = BotNavigationGraphProvider.getGraph(map);
        int drops = 0;
        for (BotNavigationGraph.Region region : graph.regions) {
            for (BotNavigationGraph.Edge edge : graph.getOutgoing(region.id)) {
                if (edge.type != BotNavigationGraph.EdgeType.DROP || edge.launchStepX == 0) {
                    continue;
                }
                drops++;
                String route = mapId + ": " + edge.startPoint + " -> " + edge.endPoint;
                BotPhysicsEngine.WalkOffLanding outcome = BotPhysicsEngine.simulateWalkOffLanding(
                        map, edge.startPoint, Integer.signum(edge.launchStepX), graph.movementProfile);
                assertNotNull(outcome, route);
                assertEquals(edge.toRegionId, graph.regionIdByFootholdId.get(outcome.landing().foothold().getId()), route);
            }
        }
        assertTrue(drops > 0, "no directional drops in map " + mapId);
    }

    @Test
    void theShopGroundRegionsStayMutuallyReachable() {
        BotNavigationGraph graph = BotNavigationGraphProvider.getGraph(shop);
        List<Integer> ground = graph.regions.stream().filter(region -> !region.isRopeRegion)
                .map(region -> region.id).toList();
        assertTrue(ground.size() > 1, "expected several ground regions");
        for (int from : ground) {
            Set<Integer> reached = reachableRegions(graph, from);
            for (int to : ground) {
                assertTrue(reached.contains(to), "removing the boundary drops stranded region r" + from + " -> r" + to);
            }
        }
    }

    @Test
    void theShopTopPlatformRouteToTheFloorBelowUsesTheLadder() {
        BotNavigationGraph graph = BotNavigationGraphProvider.getGraph(shop);
        Point start = new Point(-300, -161);
        Point goal = new Point(-300, -22);
        List<BotNavigationGraph.Edge> path = BotNavigationManager.findPath(
                graph, shop, start, graph.findRegionId(shop, start), graph.findRegionId(shop, goal), goal, false);
        assertNotNull(path);
        assertFalse(path.isEmpty());
        BotNavigationGraph.Edge first = path.getFirst();
        assertEquals(BotNavigationGraph.EdgeType.CLIMB, first.type);
        assertEquals(-267, first.endPoint.x, "the route should use the ladder");
    }

    @ParameterizedTest
    @CsvSource({"299, 309, -155", "306, 278, -394", "366, 309, -623"})
    void theElliniaTreeDescentReachesTheLowerMobPlatform(int startFoothold, int x, int y) throws Exception {
        BotNavigationGraph graph = BotNavigationGraphProvider.getGraph(tree);
        Point start = new Point(x, y);
        Point goal = new Point(253, -23);
        int destination = graph.regionIdByFootholdId.get(289);
        assertEquals(startFoothold, BotPhysicsEngine.findGroundFoothold(tree, start).getId());
        assertEquals(destination, graph.findRegionId(tree, goal));

        AtomicReference<Point> position = new AtomicReference<>(start);
        Character bot = mock(Character.class, RETURNS_DEEP_STUBS);
        when(bot.getMap()).thenReturn(tree);
        when(bot.getMapId()).thenReturn(ELLINIA_TREE);
        when(bot.getHp()).thenReturn(5000);
        when(bot.getPosition()).thenAnswer(inv -> position.get());
        doAnswer(inv -> {
            position.set(inv.getArgument(0));
            return null;
        }).when(bot).setPosition(any(Point.class));

        BotMovementState state = new BotMovementState(bot, null);
        state.lastMap = tree;
        state.lastProfileRefreshMs = Long.MAX_VALUE / 4;
        BotPhysicsEngine.teleportTo(state, bot, start);
        state.moveTarget = goal;
        state.moveTargetMap = tree;
        state.moveTargetTracking = true;
        state.moveTargetPrecise = true;

        MovementClock.useVirtual(1_000_000L);
        state.moveProgressAtMs = MovementClock.nowMs();
        try {
            for (int i = 0; i < 160; i++) {
                ObserverTracker.markObservedNow(tree);
                tick.invoke(null, state);
                MovementClock.advance(BotPhysicsEngine.cfg.TICK_MS);
                assertEquals(0L, state.unstuckCooldownMs, "the descent must not trigger a rescue hop");
                if (!state.inAir && !state.climbing && graph.findRegionId(tree, position.get()) == destination) {
                    return;
                }
            }
            fail("did not descend from FH " + startFoothold + "; ended at " + position.get());
        } finally {
            MovementClock.reset();
        }
    }

    private static Set<Integer> reachableRegions(BotNavigationGraph graph, int start) {
        Set<Integer> seen = new HashSet<>();
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        seen.add(start);
        queue.add(start);
        while (!queue.isEmpty()) {
            for (BotNavigationGraph.Edge edge : graph.getOutgoing(queue.removeFirst())) {
                if (seen.add(edge.toRegionId)) {
                    queue.addLast(edge.toRegionId);
                }
            }
        }
        return seen;
    }
}
