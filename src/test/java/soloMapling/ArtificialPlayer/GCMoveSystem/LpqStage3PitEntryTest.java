package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.config.GameConfig;
import org.gms.dao.entity.GameConfigDO;
import org.gms.manager.ServerManager;
import org.gms.property.ServiceProperty;
import org.gms.server.maps.Foothold;
import org.gms.server.maps.MapleMap;
import org.gms.service.ConfigService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.awt.Point;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The LPQ stage-3 pit entry (922010300) — the observed "bot falls from high up straight into
 * the dead pit, then gets pulled back".
 *
 * <p>Root cause: the pit's wall column (x=-265) and the stage row's end piece share the corner
 * (-265,-160). A fall crossing the row's plane at that column reports the WALL and the rim
 * LANDING at the SAME progress, and {@code resolveAirCollision} kept the wall on the tie
 * (strict {@code <}). The clamp parked the bot against the wall face at (or past) the row's
 * height, where the next straight-down probe (starting one pixel under the feet) could no
 * longer re-detect the row's surface — the bot fell through into the pit, recovered only
 * seconds later by the dead-pit self-heal.
 *
 * <p>Three layers pin the fix: the collision resolver's tie contract on the real geometry, an
 * exhaustive walk-off sweep over every ledge above the rim, and the production driver tick for
 * the exact fall that was observed. All three failed before the fix.
 */
public class LpqStage3PitEntryTest {

    private static final int MAP = 922010300;
    /** The pit basin: floor y=242 between the walls at x=-265/265. */
    private static boolean inPit(Point p) {
        return p != null && p.y >= 242 && p.x >= -265 && p.x <= 265;
    }

    @BeforeAll
    static void stub() throws Exception {
        ApplicationContext ctx = mock(ApplicationContext.class, RETURNS_DEEP_STUBS);
        ConfigService configService = mock(ConfigService.class);
        when(configService.loadGameConfigs()).thenReturn(List.<GameConfigDO>of());
        when(ctx.getBean(ConfigService.class)).thenReturn(configService);
        ServiceProperty props = mock(ServiceProperty.class);
        when(props.getLanguage()).thenReturn("zh-CN");
        when(ctx.getBean(ServiceProperty.class)).thenReturn(props);
        org.springframework.context.MessageSource messageSource =
                mock(org.springframework.context.MessageSource.class);
        when(messageSource.getMessage(org.mockito.ArgumentMatchers.anyString(),
                any(Object[].class), any(java.util.Locale.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(ctx.getBean(org.mockito.ArgumentMatchers.eq("messageSource"),
                org.mockito.ArgumentMatchers.eq(org.springframework.context.MessageSource.class)))
                .thenReturn(messageSource);
        when(ctx.getBean(org.mockito.ArgumentMatchers.eq("logSource"),
                org.mockito.ArgumentMatchers.eq(org.springframework.context.MessageSource.class)))
                .thenReturn(messageSource);
        when(ctx.getBean(org.mockito.ArgumentMatchers.eq("exceptionSource"),
                org.mockito.ArgumentMatchers.eq(org.springframework.context.MessageSource.class)))
                .thenReturn(messageSource);
        var setter = ServerManager.class.getDeclaredMethod("setApplicationContext", ApplicationContext.class);
        setter.setAccessible(true);
        setter.invoke(new ServerManager(), ctx);
        GameConfig.add(cfg("server", "update_interval", "100"));
        org.gms.net.server.Server.getInstance();
        MovementClock.useVirtual(1_000_000L);
    }

    @AfterAll
    static void restoreClock() {
        MovementClock.reset();
    }

    private static boolean wzAvailable() {
        try {
            return org.gms.provider.DataProviderFactory.getDataProvider(org.gms.provider.wz.WZFiles.MAP)
                    .getData("Map/Map9/922010300.img") != null;
        } catch (Throwable t) {
            return false;
        }
    }

    static GameConfigDO cfg(String t, String k, String v) {
        GameConfigDO d = new GameConfigDO();
        d.setConfigType(t);
        d.setConfigSubType("0");
        d.setConfigCode(k);
        d.setConfigValue(v);
        d.setConfigClazz("java.lang.Long");
        return d;
    }

    // ── 1. the resolver's tie contract on the real geometry ─────────────────

    /**
     * A fall crossing the rim plane at the wall column: the same step carries both a WALL
     * (x=-265) and a rim LANDING (fh243, y=-160) at the same progress. The landing must win —
     * the wall answer clamps the bot onto the pit mouth column.
     */
    @Test
    void aWallAndLandingAtTheSameProgressResolveToTheLanding() {
        Assumptions.assumeTrue(wzAvailable(), "Map.wz needed");
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(MAP);

        // The two production-observed leak steps: the fh254 walk-off and the stuck-hop descent.
        // Plus the right wall's leak mirror (same corner geometry at x=265).
        Point[][] steps = {
                {new Point(-264, -172), new Point(-267, -136)},
                {new Point(-264, -168), new Point(-265, -143)},
                {new Point(264, -172), new Point(267, -136)},
        };
        for (Point[] s : steps) {
            BotPhysicsEngine.AirCollision collision =
                    BotPhysicsEngine.resolveAirCollision(map, s[0], s[1]);
            org.junit.jupiter.api.Assertions.assertEquals(
                    BotPhysicsEngine.AirCollisionType.LAND, collision.type(),
                    "the crossing step " + s[0] + " -> " + s[1]
                            + " must land on the rim, not clamp onto the pit-mouth wall column");
            org.junit.jupiter.api.Assertions.assertEquals(-160, collision.point().y,
                    "the landing must sit on the rim plane at the corner column");
            org.junit.jupiter.api.Assertions.assertEquals(s[1].x > 0 ? 265 : -265, collision.point().x,
                    "the landing must sit on the corner column of the wall it grazed");
        }
    }

    /**
     * The mirror geometry: a fall OUTSIDE the room drifting in ties the same corner, but its
     * landing floor lies beyond the wall face the step just hit. The wall must keep the step —
     * taking that landing would move the bot through the wall into the room.
     */
    @Test
    void aFallOutsideThePitWallIsStillStoppedByTheWall() {
        Assumptions.assumeTrue(wzAvailable(), "Map.wz needed");
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(MAP);

        Point[][] outside = {
                {new Point(-266, -172), new Point(-264, -136)},
                {new Point(266, -172), new Point(264, -136)},
        };
        for (Point[] s : outside) {
            BotPhysicsEngine.AirCollision collision =
                    BotPhysicsEngine.resolveAirCollision(map, s[0], s[1]);
            org.junit.jupiter.api.Assertions.assertEquals(
                    BotPhysicsEngine.AirCollisionType.WALL, collision.type(),
                    "a fall outside the wall drifting in must be stopped by the wall, not teleported through");
            org.junit.jupiter.api.Assertions.assertEquals(s[0].x, collision.point().x,
                    "the clamp must keep the bot on the side it came from");
        }
    }

    // ── 2. exhaustive sweep: no ledge above the rim drops a walk-off into the pit ──

    /**
     * Every walkable ledge above the rim plane, every plausible walk-off instead (base up to
     * Haste-class speeds): the sustained-walk simulation must never land in the pit. Before the
     * fix, fh254's left lip (y=-943) leaked straight to the pit floor.
     */
    @Test
    void noWalkOffFromAnyLedgeAboveTheRimReachesThePit() {
        Assumptions.assumeTrue(wzAvailable(), "Map.wz needed");
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(MAP);

        int probes = 0;
        int leaks = 0;
        StringBuilder leakList = new StringBuilder();
        for (Foothold fh : map.getFootholds().getAllFootholds()) {
            if (fh.isWall() || fh.getY1() != fh.getY2() || fh.getY1() >= -160) {
                continue; // flat walkable ledges strictly above the rim plane
            }
            int loX = Math.min(fh.getX1(), fh.getX2());
            int hiX = Math.max(fh.getX1(), fh.getX2());
            for (int speed : new int[]{100, 110, 120, 130, 140, 150, 160, 180}) {
                BotMovementProfile profile = new BotMovementProfile(speed, 100);
                BotPhysicsEngine.WalkOffLanding left = BotPhysicsEngine.simulateWalkOffLanding(
                        map, new Point(loX + 2, fh.getY1()), -1, profile);
                probes++;
                if (left != null && inPit(left.landing().point())) {
                    leaks++;
                    leakList.append("fh").append(fh.getId()).append(" (y=").append(fh.getY1())
                            .append(") left lip speed=").append(speed).append('\n');
                }
                BotPhysicsEngine.WalkOffLanding right = BotPhysicsEngine.simulateWalkOffLanding(
                        map, new Point(hiX - 2, fh.getY1()), 1, profile);
                probes++;
                if (right != null && inPit(right.landing().point())) {
                    leaks++;
                    leakList.append("fh").append(fh.getId()).append(" (y=").append(fh.getY1())
                            .append(") right lip speed=").append(speed).append('\n');
                }
            }
        }
        org.junit.jupiter.api.Assertions.assertEquals(0, leaks,
                "walk-offs into the pit:\n" + leakList);
        org.junit.jupiter.api.Assertions.assertTrue(probes > 500,
                "the sweep must have covered the tower's ledges; got " + probes + " probes");
    }

    // ── 3. the production driver tick: the observed fall never enters the pit ──

    private static final java.lang.reflect.Method TICK;

    static {
        try {
            TICK = GCMovementDriver.class.getDeclaredMethod("tick", BotMovementState.class);
            TICK.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * The observed fall, driven through the production loop: a bot on the y=-943 row (fh254)
     * walking off its left lip with a goal beyond it. The bot must go airborne (the scenario
     * really executed) and must never be inside the pit basin — before the fix it reached
     * (-264,242) at tick 81 and sat there until the self-heal pulled it back.
     */
    @Test
    void aBotWalkingOffTheHighLipNeverFallsIntoThePit() throws Exception {
        Assumptions.assumeTrue(wzAvailable(), "Map.wz needed");
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(MAP);
        BotNavigationGraphProvider.getGraph(map, BotMovementProfile.base());

        AtomicReference<Point> pos = new AtomicReference<>(new Point(-187, -943));
        org.gms.client.Character bot = mock(org.gms.client.Character.class, RETURNS_DEEP_STUBS);
        when(bot.getMap()).thenReturn(map);
        when(bot.getMapId()).thenReturn(MAP);
        when(bot.getId()).thenReturn(8201);
        when(bot.getHp()).thenReturn(5000);
        when(bot.getChair()).thenReturn(0);
        when(bot.getJob()).thenReturn(null);
        when(bot.getPosition()).thenAnswer(inv -> pos.get());
        doAnswer(inv -> {
            pos.set(new Point(inv.getArgument(0)));
            return null;
        }).when(bot).setPosition(any());

        BotMovementState st = new BotMovementState(bot, null);
        st.movementProfile = BotMovementProfile.base();
        st.lastProfileRefreshMs = Long.MAX_VALUE / 4;
        st.lastMap = map;
        st.fhIndex = BotMovementManager.buildFhIndex(map);
        BotPhysicsEngine.teleportTo(st, bot, new Point(-187, -943));
        BotMovementManager.resetEntryStateAfterTeleport(st);

        // Reproduce the physical walk-off directly. An out-of-map moveTarget is no
        // longer permission to walk into a void: navigation now correctly refuses it.
        // The collision regression still needs the real fall, then the full driver.
        for (int i = 0; i < 40 && !st.inAir; i++) {
            st.moveDir = -1;
            BotPhysicsEngine.applyGroundMotion(st, bot, BotPhysicsEngine.syncAndDetectGround(st, bot));
            MovementClock.advance(BotPhysicsEngine.cfg.TICK_MS);
        }
        boolean sawAirborne = st.inAir;
        Point pitAt = null;
        for (int i = 0; i < 400; i++) {
            st.following = false;
            st.farmAnchor = null;
            st.farmAnchorMapId = -1;
            st.moveTarget = new Point(-587, -943); // the leftward drive off the lip
            st.moveTargetPrecise = true;
            st.moveTargetSource = "gcmove";
            st.moveBestDist = Integer.MAX_VALUE;
            st.moveProgressAtMs = MovementClock.nowMs();
            TICK.invoke(null, st);
            MovementClock.advance(BotPhysicsEngine.cfg.TICK_MS);
            Point p = pos.get();
            if (st.inAir) {
                sawAirborne = true;
            }
            if (pitAt == null && inPit(p)) {
                pitAt = new Point(p);
            }
        }
        org.junit.jupiter.api.Assertions.assertTrue(sawAirborne,
                "the scenario must actually walk off the lip (no airborne tick observed)");
        org.junit.jupiter.api.Assertions.assertNull(pitAt,
                "the bot must never be inside the pit basin; it reached " + pitAt);
    }
}
