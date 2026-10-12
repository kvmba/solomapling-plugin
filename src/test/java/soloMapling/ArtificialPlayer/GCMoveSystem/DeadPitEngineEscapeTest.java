package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.config.GameConfig;
import org.gms.dao.entity.GameConfigDO;
import org.gms.manager.ServerManager;
import org.gms.property.ServiceProperty;
import org.gms.server.maps.Foothold;
import org.gms.server.maps.FootholdTree;
import org.gms.server.maps.MapleMap;
import org.gms.service.ConfigService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Root-cause pins for the engine-simulated dead-pit verdict (the pre-rework guard judged escapes
 * with static geometry and fixed probe columns - every case here was a verified false dead on the
 * full-WZ census):
 *
 * <ul>
 *   <li>a &lt;=40px step-up onto an unlinked (jump-only) surface is an exit;</li>
 *   <li>a slope's TOP is probed (per-column real surface y), so its drop is found;</li>
 *   <li>a narrow ledge's edge column is probed, not just its midpoint;</li>
 *   <li>forbidFallDown refuses only the pass-through drop, never the edge walk-off;</li>
 *   <li>a jump-off with horizontal momentum is an exit;</li>
 *   <li>a down-jump falls until it lands (no artificial depth cap);</li>
 *   <li>a magician's Teleport / a Hermit's Flash Jump exit counts only for that class.</li>
 * </ul>
 */
public class DeadPitEngineEscapeTest {

    @BeforeAll
    static void stubHostEnvironment() throws Exception {
        ApplicationContext ctx = mock(ApplicationContext.class, RETURNS_DEEP_STUBS);
        ConfigService configService = mock(ConfigService.class);
        when(configService.loadGameConfigs()).thenReturn(List.<GameConfigDO>of());
        when(ctx.getBean(ConfigService.class)).thenReturn(configService);
        ServiceProperty props = mock(ServiceProperty.class);
        when(props.getLanguage()).thenReturn("zh-CN");
        when(ctx.getBean(ServiceProperty.class)).thenReturn(props);
        var setter = ServerManager.class.getDeclaredMethod("setApplicationContext", ApplicationContext.class);
        setter.setAccessible(true);
        setter.invoke(new ServerManager(), ctx);
        GameConfig.add(config("server", "update_interval", "100"));
        org.gms.net.server.Server.getInstance();
    }

    private static GameConfigDO config(String type, String key, String value) {
        GameConfigDO d = new GameConfigDO();
        d.setConfigType(type);
        d.setConfigSubType("0");
        d.setConfigCode(key);
        d.setConfigValue(value);
        d.setConfigClazz("java.lang.Long");
        return d;
    }

    private static MapleMap map(int id) {
        MapleMap m = new MapleMap(id, 0, 0, 922010000, 0.0f);
        m.setMapLineBoundings(-4000, 4000, -4000, 4000);
        m.setFootholds(new FootholdTree(new Point(-4000, -4000), new Point(4000, 4000)));
        return m;
    }

    private static Foothold add(MapleMap m, int id, int x1, int y1, int x2, int y2, boolean ffd) {
        Foothold fh = new Foothold(new Point(x1, y1), new Point(x2, y2), id);
        fh.setForbidFallDown(ffd);
        m.getFootholds().insert(fh);
        return fh;
    }

    /** A world row far above every jump (kills the topmost shortcut) so the verdict must be earned. */
    private static void addWorld(MapleMap m, int id) {
        add(m, id, -800, -600, 800, -600, false);
    }

    @Test
    void aSmallStepUpOntoAnUnlinkedSurfaceIsAnExit() {
        // The 100040001 shape: a 13px step-up onto a neighbouring platform that is NOT walk-linked
        // (no prev/next). The engine's jump sim lands on it; a real player jumps up and leaves.
        MapleMap m = map(922011950);
        Foothold floor = add(m, 1, -100, 0, 100, 0, false);
        add(m, 2, -60, -13, 60, -13, false);
        addWorld(m, 3);
        assertTrue(DeadPitGuard.isLivableSurface(m, floor, BotMovementProfile.base()),
                "a 13px jump onto an unlinked platform is a real exit (rise <= the old skipped band)");
    }

    @Test
    void aSlopeTopDropIsFoundOnTheSlopesRealSurface() {
        // 103040200 fh3 shape: the slope is judged on its own per-column y, so the drop from its
        // TOP to the floor below is found (the pre-rework probe always started at the low end).
        MapleMap m = map(922011951);
        Foothold slope = add(m, 1, -100, 0, 0, 150, false);
        add(m, 2, -300, 150, 300, 150, false);
        addWorld(m, 3);
        assertTrue(DeadPitGuard.isLivableSurface(m, slope, BotMovementProfile.base()),
                "the slope top can drop/down-jump to the floor 150px below");
    }

    @Test
    void aNarrowLedgesEdgeDropIsFound() {
        // 211042200 fh286 shape: the only drop is the left-edge column; a midpoint-only probe
        // misses it. The floor below covers the left edge but not the middle.
        MapleMap m = map(922011952);
        Foothold ledge = add(m, 1, 100, 0, 130, 0, false);
        add(m, 2, 0, 100, 101, 100, false);
        addWorld(m, 3);
        assertTrue(DeadPitGuard.isLivableSurface(m, ledge, BotMovementProfile.base()),
                "the 100..101px edge column drops to the floor; the midpoint does not");
    }

    @Test
    void ffdSurfaceKeepsItsEdgeWalkOffButNotThePassThrough() {
        // forbidFallDown blocks the down-jump pass-through only. The edge walk-off with real
        // momentum stays legal (and real maps rely on it - 670010200 fh321).
        MapleMap m = map(922011953);
        Foothold ffdPlatform = add(m, 1, 0, 0, 200, 0, true);
        add(m, 2, -400, 150, 400, 150, false);
        addWorld(m, 3);
        assertTrue(DeadPitGuard.isLivableSurface(m, ffdPlatform, BotMovementProfile.base()),
                "an ffd platform's open edge is still a walk-off exit");

        // And the pass-through itself stays refused: a floor exactly under the platform means the
        // only way down would be through the ffd surface - no edge walk-off reaches a landing.
        MapleMap passThroughOnly = map(922011954);
        Foothold ffdClosed = add(passThroughOnly, 1, 0, 0, 200, 0, true);
        add(passThroughOnly, 2, 0, 150, 200, 150, false);
        addWorld(passThroughOnly, 3);
        assertFalse(DeadPitGuard.isLivableSurface(passThroughOnly, ffdClosed, BotMovementProfile.base()),
                "with no edge landing, the ffd pass-through is the only way down: still dead");
    }

    @Test
    void aJumpOffWithHorizontalMomentumIsAnExit() {
        // The jump arc reaches a lower platform the straight drop and the walk-off cannot: the
        // pre-rework fallEscape only probed stepX=0.
        MapleMap m = map(922011955);
        Foothold ledge = add(m, 1, 0, 0, 60, 0, false);
        add(m, 2, 130, 100, 400, 100, false);
        addWorld(m, 3);
        assertTrue(DeadPitGuard.isLivableSurface(m, ledge, BotMovementProfile.base()),
                "a directional jump drifts onto the lower platform 70px across");
    }

    @Test
    void aDeepDownJumpLandingStillCounts() {
        // The client's down-jump falls until it lands; the deleted 200px cap was dead code (the
        // uncapped fall branch always answered first) but the deep drop must stay an exit.
        MapleMap m = map(922011956);
        Foothold ledge = add(m, 1, 0, 0, 100, 0, false);
        add(m, 2, 0, 500, 100, 500, false);
        addWorld(m, 3);
        assertTrue(DeadPitGuard.isLivableSurface(m, ledge, BotMovementProfile.base()),
                "a 500px straight drop with a real landing is an exit");
    }

    @Test
    void teleportIsAnExitOnlyForBlinkClasses() {
        // A pit whose only exit is a platform 120px above: beyond every jump (apex ~75 for base,
        // ~113 for max jump) but inside the mage blink range (150).
        MapleMap m = map(922011957);
        Foothold pitFloor = add(m, 1, -100, 0, 100, 0, false);
        add(m, 2, -100, -120, 100, -120, false);
        addWorld(m, 3);

        BotMovementProfile base = BotMovementProfile.base();
        assertFalse(DeadPitGuard.isLivableSurface(m, pitFloor, base, BotMovementCapabilities.NONE),
                "no jump reaches a 120px shelf - a non-blink bot is trapped");
        assertTrue(DeadPitGuard.isLivableSurface(m, pitFloor, base, new BotMovementCapabilities(true, 0f)),
                "a magician blinks up to the 120px shelf (blink range 150)");
    }

    @Test
    void flashJumpIsAnExitOnlyForDashingClasses() {
        // A shelf 120px up and extending sideways: a max-jump profile (apex ~113) cannot land on
        // it with a plain jump, but the dash's mid-air relaunch adds ~30px of rise.
        MapleMap m = map(922011958);
        Foothold floor = add(m, 1, -100, 0, 100, 0, false);
        add(m, 2, -100, -120, 900, -120, false);
        addWorld(m, 3);

        BotMovementProfile thief = new BotMovementProfile(120, 123);
        assertFalse(DeadPitGuard.isLivableSurface(m, floor, thief, BotMovementCapabilities.NONE),
                "a plain max-stat jump (apex ~113) cannot reach a 120px shelf");
        assertTrue(DeadPitGuard.isLivableSurface(m, floor, thief, new BotMovementCapabilities(false, 1f)),
                "a full flash-jump dash reaches the shelf");
    }

    @Test
    void flashJumpSimTravelMatchesTheTierCalibration() {
        // FJ_TRAVEL_PX says a full dash covers ~340px of ground. The sim must land in the same
        // class as the executor's own tier calibration, or the guard and the executor disagree
        // about where a dash ends up.
        MapleMap m = map(922011959);
        add(m, 1, -2000, 0, 2000, 0, false);
        BotMovementProfile thief = new BotMovementProfile(120, 123);
        BotPhysicsEngine.JumpLanding landing =
                BotPhysicsEngine.simulateFlashJumpLanding(m, new Point(0, 0), 1, thief, 1f);
        assertNotNull(landing, "the dash lands on the flat ground");
        int travel = landing.point().x;
        System.out.println("[pin] flash-jump sim travel = " + travel + "px (FJ_TRAVEL_PX full=340)");
        assertTrue(travel >= 200 && travel <= 500,
                "full-dash travel is in the executor's calibration class, got " + travel);
    }
}
