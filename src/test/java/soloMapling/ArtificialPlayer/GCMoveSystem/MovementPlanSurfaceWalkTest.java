package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.config.GameConfig;
import org.gms.dao.entity.GameConfigDO;
import org.gms.manager.ServerManager;
import org.gms.property.ServiceProperty;
import org.gms.server.maps.Foothold;
import org.gms.server.maps.FootholdTree;
import org.gms.server.maps.MapleMap;
import org.gms.service.ConfigService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationContext;

import java.awt.Point;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Guards the analytic plan for a walk across ONE region - the case A* answers with an empty edge
 * list, and the movement driver used to read as "already there".
 *
 * <p>The symptom that made this a bug: a goal on the bot's own platform, up to a whole screen
 * away, arrived as a single {@code setPosition} onto the goal's pixel whenever nobody was watching
 * the map. Quest rooms were the visible case (a whole bot team "flying" to the result NPC the
 * moment it landed), but the rule applied on every unobserved map.
 *
 * <p>What the fix must keep: the analytic position is always a point the floor really carries
 * (a joining player is shown it before physics takes over), and a route that is not a walk - a
 * gap, a step - must be refused so physics owns it.
 */
class MovementPlanSurfaceWalkTest {

    @TempDir
    static Path navigationCache;
    private static Path originalCacheDir;

    @BeforeAll
    static void bootHost() throws Exception {
        originalCacheDir = BotNavigationGraphProvider.cacheDir();
        BotNavigationGraphProvider.setCacheDir(navigationCache);
        ApplicationContext context = mock(ApplicationContext.class,
                org.mockito.Mockito.RETURNS_DEEP_STUBS);
        ConfigService config = mock(ConfigService.class);
        when(config.loadGameConfigs()).thenReturn(List.of());
        when(context.getBean(ConfigService.class)).thenReturn(config);
        ServiceProperty properties = mock(ServiceProperty.class);
        when(properties.getLanguage()).thenReturn("zh-CN");
        when(context.getBean(ServiceProperty.class)).thenReturn(properties);
        var messages = mock(org.springframework.context.MessageSource.class);
        when(messages.getMessage(anyString(), any(Object[].class), any(java.util.Locale.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        for (String name : List.of("messageSource", "logSource", "exceptionSource")) {
            when(context.getBean(name, org.springframework.context.MessageSource.class))
                    .thenReturn(messages);
        }
        Method setter = ServerManager.class.getDeclaredMethod(
                "setApplicationContext", ApplicationContext.class);
        setter.setAccessible(true);
        setter.invoke(new ServerManager(), context);
        GameConfigDO interval = new GameConfigDO();
        interval.setConfigType("server");
        interval.setConfigSubType("0");
        interval.setConfigCode("update_interval");
        interval.setConfigValue("100");
        interval.setConfigClazz("java.lang.Long");
        GameConfig.add(interval);
        org.gms.net.server.Server.getInstance();
    }

    @AfterAll
    static void restoreCacheDir() {
        BotNavigationGraphProvider.setCacheDir(originalCacheDir);
    }

    private static MapleMap map(int id) {
        MapleMap map = new MapleMap(id, 0, 0, 0, 0f);
        map.setMapLineBoundings(-4000, 4000, -4000, 4000);
        map.setFootholds(new FootholdTree(new Point(-4000, -4000), new Point(4000, 4000)));
        return map;
    }

    private static void floor(MapleMap map, int id, int left, int y, int right) {
        map.getFootholds().insert(new Foothold(new Point(left, y), new Point(right, y), id));
    }

    @Test
    void aSameRegionWalkIsAPlanNotAnArrival() {
        MapleMap map = map(1);
        floor(map, 1, -300, 100, 300);

        MovementPlan plan = MovementPlan.surfaceWalk(map, new Point(-250, 100), new Point(250, 100), 125.0);
        assertNotNull(plan, "a 500px walk across one platform is a trip, not an arrival");
        // 500px at 125px/s is four seconds of travel on the analytic clock.
        assertEquals(4_000L, plan.totalTimeMs, "cost comes from this body's own walk pace");
        assertTrue(plan.positionAt(2_000).x < 250,
                "halfway through the walk the bot is halfway along it");
    }

    @Test
    void anInterpolatedPositionIsAlwaysOnTheFloor() {
        MapleMap map = map(2);
        // One continuous RAMP: the surface climbs 60px across its span, so the analytic position
        // must ride the slope rather than lerp through the air above it.
        map.getFootholds().insert(new Foothold(new Point(-300, 100), new Point(300, 40), 1));

        MovementPlan plan = MovementPlan.surfaceWalk(map, new Point(-250, 100), new Point(250, 40), 125.0);
        assertNotNull(plan, "a ramp is a walk");
        for (long t = 0; t <= plan.totalTimeMs; t += 100) {
            Point at = plan.positionAt(t);
            Point ground = BotPhysicsEngine.findGroundPoint(map, new Point(at.x, at.y));
            if (ground != null) {
                // 1px is the same slope-interpolation slack the physics itself keeps between the
                // tree's truncating interpolation and the graph's rounding (GROUND_LINE_SLACK_PX).
                assertTrue(Math.abs(at.y - ground.y) <= 1,
                        "an unobserved bot is never shown standing in the air (t=" + t
                                + ": y=" + at.y + ", floor=" + ground.y + ")");
            }
        }
    }

    @Test
    void aRouteOverAHoleIsRefused() {
        MapleMap map = map(3);
        floor(map, 1, -300, 100, -60);
        floor(map, 2, 60, 100, 300); // a 120px hole in the middle

        assertNull(MovementPlan.surfaceWalk(map, new Point(-200, 100), new Point(200, 100), 125.0),
                "walking over a hole is not a walk: hand the trip back to physics");
    }

    @Test
    void aStepPastTheSlopeBandIsRefused() {
        MapleMap map = map(4);
        floor(map, 1, -300, 100, 0);
        floor(map, 2, 0, 100 + BotPhysicsEngine.cfg.MAX_SLOPE_UP + 20, 300);

        assertNull(MovementPlan.surfaceWalk(map, new Point(-200, 100), new Point(200, 180), 125.0),
                "a ledge is not a slope: physics must own the jump/drop");
    }

    @Test
    void aZeroLengthWalkHasNoPlan() {
        MapleMap map = map(5);
        floor(map, 1, -300, 100, 300);
        assertNull(MovementPlan.surfaceWalk(map, new Point(0, 100), new Point(0, 100), 125.0));
    }
}
