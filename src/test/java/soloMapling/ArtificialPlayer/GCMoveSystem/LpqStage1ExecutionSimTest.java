package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.config.GameConfig;
import org.gms.dao.entity.GameConfigDO;
import org.gms.manager.ServerManager;
import org.gms.property.ServiceProperty;
import org.gms.server.maps.MapleMap;
import org.gms.service.ConfigService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.awt.Point;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

/**
 * Execution-level simulation of LPQ stage 1 (922010100) on the REAL WZ geometry, driving the
 * PRODUCTION driver tick ({@code GCMovementDriver.tick}) over a mocked Character so the whole
 * nav+physics pipeline runs: graph planning, edge execution, jump/climb physics, arrival.
 *
 * Regression sims for the LPQ stage-1 tower (922010100): the full spawn-ground -> first-Ratz
 * climb (ropes + exit row + rope grab), the post-kill vertical perch chain (fh323/324/325,
 * 60-68px perches) from every kill spot, and the graph-warmup fallback window. Each sim drives
 * the PRODUCTION driver tick over the REAL WZ geometry with a mocked Character, mirroring the
 * PQ macro tick's periodic move re-issue.
 */
public class LpqStage1ExecutionSimTest {

    @BeforeAll
    static void stubHostEnvironment() throws Exception {
        ApplicationContext ctx = mock(ApplicationContext.class, RETURNS_DEEP_STUBS);
        ConfigService configService = mock(ConfigService.class);
        when(configService.loadGameConfigs()).thenReturn(List.<GameConfigDO>of());
        when(ctx.getBean(ConfigService.class)).thenReturn(configService);
        ServiceProperty props = mock(ServiceProperty.class);
        when(props.getLanguage()).thenReturn("zh-CN");
        when(ctx.getBean(ServiceProperty.class)).thenReturn(props);
        // I18nUtil statics resolve these by name at class-init (Job.<clinit> touches them)
        org.springframework.context.MessageSource messageSource =
                mock(org.springframework.context.MessageSource.class);
        when(messageSource.getMessage(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(Object[].class),
                org.mockito.ArgumentMatchers.any(java.util.Locale.class)))
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
        GameConfig.add(config("server", "update_interval", "100"));
        org.gms.net.server.Server.getInstance();
        // The sims load the REAL 922010100 geometry through the production WZ loader, which
        // resolves the server's wz directory relative to the working directory. On a machine
        // without it every case would just error "Map data not found" - skip with a reason.
        org.junit.jupiter.api.Assumptions.assumeTrue(mapWzAvailable(),
                "Map.wz not resolvable from this working directory (sim needs the real WZ)");
    }

    static boolean mapWzAvailable() {
        try {
            org.gms.provider.DataProvider mapSource =
                    org.gms.provider.DataProviderFactory.getDataProvider(org.gms.provider.wz.WZFiles.MAP);
            for (int mapId : new int[]{922010100}) {
                if (mapSource.getData("Map/Map" + (mapId / 100000000) + "/"
                        + String.format("%09d", mapId) + ".img") == null) {
                    return false;
                }
            }
            return true;
        } catch (Throwable unavailable) {
            return false;
        }
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

    private record Sim(BotMovementState st, AtomicReference<Point> pos, StringBuilder trace) {
        Point p() {
            return pos.get();
        }
    }

    private static Sim sim() {
        return sim(BotMovementProfile.base());
    }

    private static Sim sim(BotMovementProfile profile) {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(922010100);
        // Bake the graph up front (the production path warms it async; the bake itself is what
        // the audit already covers). The tick driver peeks this cache.
        BotNavigationGraphProvider.getGraph(map, profile);

        AtomicReference<Point> pos = new AtomicReference<>(new Point(0, 130));
        org.gms.client.Character bot = mock(org.gms.client.Character.class, RETURNS_DEEP_STUBS);
        when(bot.getMap()).thenReturn(map);
        when(bot.getMapId()).thenReturn(922010100);
        when(bot.getId()).thenReturn(777);
        when(bot.getHp()).thenReturn(5000);
        when(bot.getChair()).thenReturn(0);
        when(bot.getPosition()).thenAnswer(inv -> pos.get());
        org.mockito.Mockito.doAnswer(inv -> {
            pos.set(new Point(inv.getArgument(0)));
            return null;
        }).when(bot).setPosition(any());

        BotMovementState st = new BotMovementState(bot, null);
        st.movementProfile = profile;
        // Freeze the 20s profile recompute: the mock Character's stat accessors return deep-stub
        // garbage, which would wreck the profile (and thus the graph key) on the first tick.
        // Real characters recompute to their own real stats, which is a no-op on the bucket.
        st.lastProfileRefreshMs = Long.MAX_VALUE / 4;
        st.lastMapId = 922010100;
        st.fhIndex = BotMovementManager.buildFhIndex(map);
        BotPhysicsEngine.teleportTo(st, bot, new Point(0, 130));
        BotMovementManager.resetEntryStateAfterTeleport(st);
        return new Sim(st, pos, new StringBuilder());
    }

    private static final java.lang.reflect.Method TICK;

    static {
        try {
            TICK = GCMovementDriver.class.getDeclaredMethod("tick", BotMovementState.class);
            TICK.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static void tickN(Sim sim, int ticks, boolean keepActive) {
        for (int i = 0; i < ticks; i++) {
            if (keepActive && i % 30 == 0) {
                ObserverTracker.markObservedNow(922010100);
            }
            try {
                TICK.invoke(null, sim.st());
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e.getCause() != null ? e.getCause() : e);
            }
            BotMovementState st = sim.st();
            if (i % 5 == 0) {
                sim.trace().append(String.format("t=%4d pos=%s nav=%-18s edge=%s air=%b climb=%b prof=%d/%d warmup=%b mt=%s%n",
                        i, sim.p(), st.lastNavDecision,
                        st.navEdge == null ? "-" : st.navEdge.type + "@" + st.navEdge.fromRegionId + "->" + st.navEdge.toRegionId,
                        st.inAir, st.climbing,
                        st.movementProfile.totalSpeedStat(), st.movementProfile.totalJumpStat(),
                        st.graphWarmupFallback, st.moveTarget));
            }
        }
    }

    /** Mirrors GCMovement.move()'s state writes without the enable() detour (we hold the state). */
    private static void move(BotMovementState st, int x, int y) {
        st.following = false;
        st.farmAnchor = null;
        st.farmAnchorMapId = -1;
        st.moveTarget = new Point(x, y);
        st.moveTargetPrecise = true;
        st.moveTargetSource = "gcmove";
        st.moveBestDist = Integer.MAX_VALUE;
        st.moveProgressAtMs = System.currentTimeMillis();
    }

    private void report(String label, Sim sim, Point end) {
        StringBuilder sb = new StringBuilder("=== " + label + " (end " + end + ")\n");
        java.util.Arrays.stream(sim.trace().toString().split("\n"))
                .filter(l -> l.contains("nav=") && !l.endsWith("nav= same-region mt=null") || !l.endsWith("mt=null"))
                .limit(200)
                .forEach(l -> sb.append(l).append('\n'));
        System.out.println(sb);
    }

    @Test
    void simSpawnToFirstRatz() {
        Sim sim = sim();
        move(sim.st(), 88, -450);
        tickN(sim, 900, true);
        Point end = sim.p();
        report("spawn->firstRatz", sim, end);
        org.junit.jupiter.api.Assertions.assertTrue(Math.abs(end.x - 88) <= 30 && Math.abs(end.y - -450) <= 30,
                "bot should reach the first Ratz platform (88,-450), ended at " + end);
    }

    @Test
    void simFirstRatzOnward() {
        Sim sim = sim();
        // Seat the bot directly on the first Ratz platform, then send it onward/upward:
        // the mid-air kill scenario - the next mob is a hop-chain up (y=-572 step) or the
        // mob row across the rope (120,-713).
        sim.pos().set(new Point(88, -450));
        BotPhysicsEngine.teleportTo(sim.st(), sim.st().bot, new Point(88, -450));
        BotMovementManager.resetEntryStateAfterTeleport(sim.st());

        move(sim.st(), 136, -572);
        tickN(sim, 400, true);
        System.out.println("after hop-step leg at " + sim.p());
        move(sim.st(), 120, -713);
        tickN(sim, 500, true);
        Point end = sim.p();
        report("ratz->onward", sim, end);
        org.junit.jupiter.api.Assertions.assertTrue(Math.abs(end.y - -713) <= 40,
                "bot should climb to the y=-713 mob row, ended at " + end);
    }

    /**
     * REGRESSION (the LPQ stage-1 "小步停顿走路/原地踱步, 上不去高台" report): the PQ macro tick
     * re-issues the seek move every ~1.5s beat, and each re-issue is instantly satisfied by the
     * narrow-ledge residency arrival. Before the fix the per-beat 50ms "no motion" tallies
     * summed into a phantom 500ms stall (idle beats never ran the watchdog, so it never reset)
     * and tickUnstuck hopped the bot OFF the fh325 perch it had just reached - the observed
     * reach -> hop-off -> climb back loop. An arrival now resets the stuck window, so the bot
     * holds the perch across re-issued beats forever. 40 beats x 30 ticks = 60s on the perch.
     */
    @Test
    void simMacroBeatReissuesNeverHopTheBotOffThePerch() {
        int[][] profiles = {{105, 110}, {120, 115}, {105, 100}, {110, 120}};
        int[] spawnXs = {30, 60, 88, 120, 136, 160, 200, 240};
        for (int[] stat : profiles) {
            BotMovementProfile profile = new BotMovementProfile(stat[0], stat[1]);
            for (int sx : spawnXs) {
                Sim sim = sim(profile);
                sim.pos().set(new Point(sx, -450));
                BotPhysicsEngine.teleportTo(sim.st(), sim.st().bot, new Point(sx, -450));
                BotMovementManager.resetEntryStateAfterTeleport(sim.st());
                for (int burst = 0; burst < 40; burst++) {
                    move(sim.st(), 136, -572);
                    tickN(sim, 30, true);
                }
                Point end = sim.p();
                org.junit.jupiter.api.Assertions.assertTrue(
                        Math.abs(end.x - 136) <= 30 && Math.abs(end.y - -572) <= 30,
                        "profile " + stat[0] + "/" + stat[1] + " from kill spot x=" + sx
                                + ": the re-issued seek must hold the fh325 perch, ended at " + end);
            }
        }
    }

    /** LPQ-realistic profiles: level 35-50 non-thief (105/110), thief (120/115), low level (105/100). */
    @Test
    void simRealProfilesReachTheFirstRatz() {
        int[][] profiles = {{105, 110}, {120, 115}, {105, 100}, {110, 120}};
        for (int[] stat : profiles) {
            BotMovementProfile profile = new BotMovementProfile(stat[0], stat[1]);
            Sim sim = sim(profile);
            move(sim.st(), 88, -450);
            tickN(sim, 900, true);
            Point end = sim.p();
            System.out.println("PROFILE " + stat[0] + "/" + stat[1] + " END " + end);
            org.junit.jupiter.api.Assertions.assertTrue(Math.abs(end.x - 88) <= 30 && Math.abs(end.y - -450) <= 30,
                    "profile " + stat[0] + "/" + stat[1] + " should reach (88,-450), ended at " + end);
        }
    }

    /** Kill-spot matrix: after killing the first Ratz the bot stands anywhere on y=-450; the next
     * mob (136,-581) sits on the fh325 perch reached ONLY by vertical hops up fh323/324. */
    @Test
    void simKillSpotMatrixClimbsToSecondRatz() {
        int[] spawnXs = {30, 60, 88, 120, 136, 160, 200, 240};
        for (int sx : spawnXs) {
            Sim sim = sim();
            sim.pos().set(new Point(sx, -450));
            BotPhysicsEngine.teleportTo(sim.st(), sim.st().bot, new Point(sx, -450));
            BotMovementManager.resetEntryStateAfterTeleport(sim.st());
            move(sim.st(), 136, -572);
            tickN(sim, 400, true);
            Point end = sim.p();
            boolean ok = Math.abs(end.x - 136) <= 30 && Math.abs(end.y - -572) <= 30;
            System.out.println("KILLSPOT start=" + sx + " end=" + end.x + "," + end.y + (ok ? " OK" : " FAIL nav=" + sim.st().lastNavDecision));
            org.junit.jupiter.api.Assertions.assertTrue(ok,
                    "from kill spot x=" + sx + " the bot must reach the fh325 perch (136,-572), ended at " + end);
        }
    }

    /** Fallback-window sim: the same climb while the nav graph NEVER lands (fallback engine owns
     * every tick). Mirrors the first seconds of a fresh-profile party on 922010100. */
    @Test
    void simFallbackWindowClimbFromKillSpot() {
        for (int sx : new int[]{88, 136, 200}) {
            Sim sim = sim();
            sim.pos().set(new Point(sx, -450));
            BotPhysicsEngine.teleportTo(sim.st(), sim.st().bot, new Point(sx, -450));
            BotMovementManager.resetEntryStateAfterTeleport(sim.st());
            sim.st().graphWarmupFallback = true; // force the fallback engine for the whole run
            move(sim.st(), 136, -572);
            // Real party cadence: the PQ macro tick re-issues seekAndAttack every ~1.5s+ and the
            // seek layer re-issues GCMovement.move each beat (retarget eps 16px), which RESETS
            // moveProgressAtMs each time. Mirror that: re-issue the same move every 30 ticks.
            for (int burst = 0; burst < 12; burst++) {
                move(sim.st(), 136, -572);
                tickN(sim, 30, true);
            }
            tickN(sim, 140, true);
            Point end = sim.p();
            boolean ok = Math.abs(end.x - 136) <= 30 && Math.abs(end.y - -572) <= 30;
            System.out.println("FALLBACK start=" + sx + " end=" + end.x + "," + end.y
                    + (ok ? " OK" : " STUCK nav=" + sim.st().lastNavDecision));
        }
    }
}
