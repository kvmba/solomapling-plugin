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
import static org.mockito.Mockito.when;

/**
 * REGRESSION (the live "追着上方(小于y轴)的怪小步卡顿走路, 左右移动, 不继续上去打怪" report):
 * the mob ABOVE the bot is ALIVE — a real player aggroes it, its controller client walks it,
 * it gets knocked back, it falls/jumps — so its position changes every 250ms combat beat. The
 * old seek re-derived the floor point from the mob's LIVE position each beat, which (a) flipped
 * the target across nav regions on every airborne arc, discarding the committed climb edge
 * mid-rope, and (b) re-issued GCMovement.move constantly, resetting the progress watchdog. The
 * bot paced under the mob forever.
 *
 * This sim drives the production driver tick while the CHASE TARGET MOVES every burst — the
 * per-beat re-issue cadence mirrors PqActions.seekAndAttack on the shared 250ms sweep.
 */
public class LpqMovingMobChaseReproTest {

    @BeforeAll
    static void stubHostEnvironment() throws Exception {
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
                org.mockito.ArgumentMatchers.any(Object[].class),
                org.mockito.ArgumentMatchers.any(java.util.Locale.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(ctx.getBean(org.mockito.ArgumentMatchers.eq("messageSource"),
                org.mockito.ArgumentMatchers.eq(org.springframework.context.MessageSource.class)))
                .thenReturn(messageSource);
        when(ctx.getBean(org.mockito.ArgumentMatchers.eq("logSource"),
                org.mockito.ArgumentMatchers.eq(org.springframework.context.MessageSource.class)))
                .thenReturn(messageSource);
        var setter = ServerManager.class.getDeclaredMethod("setApplicationContext", ApplicationContext.class);
        setter.setAccessible(true);
        setter.invoke(new ServerManager(), ctx);
        GameConfig.add(config("server", "update_interval", "100"));
        org.gms.net.server.Server.getInstance();
        org.junit.jupiter.api.Assumptions.assumeTrue(mapWzAvailable(), "Map.wz needed");
    }

    static boolean mapWzAvailable() {
        try {
            org.gms.provider.DataProvider s = org.gms.provider.DataProviderFactory.getDataProvider(org.gms.provider.wz.WZFiles.MAP);
            return s.getData("Map/Map9/922010100.img") != null;
        } catch (Throwable t) {
            return false;
        }
    }

    static GameConfigDO config(String type, String key, String value) {
        GameConfigDO d = new GameConfigDO();
        d.setConfigType(type);
        d.setConfigSubType("0");
        d.setConfigCode(key);
        d.setConfigValue(value);
        d.setConfigClazz("java.lang.Long");
        return d;
    }

    private record Sim(BotMovementState st, AtomicReference<Point> pos, StringBuilder trace) {
        Point p() { return pos.get(); }
    }

    private static Sim sim(BotMovementProfile profile, int sx, int sy) {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(922010100);
        BotNavigationGraphProvider.getGraph(map, profile);

        AtomicReference<Point> pos = new AtomicReference<>(new Point(sx, sy));
        org.gms.client.Character bot = mock(org.gms.client.Character.class, RETURNS_DEEP_STUBS);
        when(bot.getMap()).thenReturn(map);
        when(bot.getMapId()).thenReturn(922010100);
        when(bot.getId()).thenReturn(781);
        when(bot.getHp()).thenReturn(5000);
        when(bot.getChair()).thenReturn(0);
        when(bot.getPosition()).thenAnswer(inv -> pos.get());
        org.mockito.Mockito.doAnswer(inv -> {
            pos.set(new Point(inv.getArgument(0)));
            return null;
        }).when(bot).setPosition(any());

        BotMovementState st = new BotMovementState(bot, null);
        st.movementProfile = profile;
        st.lastProfileRefreshMs = Long.MAX_VALUE / 4;
        st.lastMapId = 922010100;
        st.fhIndex = BotMovementManager.buildFhIndex(map);
        BotPhysicsEngine.teleportTo(st, bot, new Point(sx, sy));
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

    private static void tickN(Sim sim, int ticks) {
        for (int i = 0; i < ticks; i++) {
            if (i % 30 == 0) ObserverTracker.markObservedNow(922010100);
            try { TICK.invoke(null, sim.st()); }
            catch (ReflectiveOperationException e) { throw new RuntimeException(e.getCause()); }
            BotMovementState st = sim.st();
            if (i % 10 == 0) {
                sim.trace().append(String.format("t=%4d pos=%s nav=%-16s edge=%s block=%s air=%b climb=%b mt=%s%n",
                        i, sim.p(), st.lastNavDecision,
                        st.navEdge == null ? "-" : st.navEdge.type + "@" + st.navEdge.fromRegionId + "->" + st.navEdge.toRegionId,
                        st.lastEdgeBlockReason, st.inAir, st.climbing, st.moveTarget));
            }
        }
    }

    /** Mirrors PqActions.seekAndAttack's move re-issue: fresh floor point each burst. */
    private static void moveAtMobFloor(Sim sim, int mobX, int mobY) {
        Point ground = GCMovement.groundPointBelow(sim.st().bot.getMap(), mobX, mobY);
        int tx = mobX;
        int ty = (ground != null) ? ground.y : mobY;
        sim.st().following = false;
        sim.st().farmAnchor = null;
        sim.st().farmAnchorMapId = -1;
        sim.st().moveTarget = new Point(tx, ty);
        sim.st().moveTargetPrecise = true;
        sim.st().moveTargetSource = "gcmove";
        sim.st().moveBestDist = Integer.MAX_VALUE;
        sim.st().moveProgressAtMs = System.currentTimeMillis();
    }

    /**
     * The mob patrols on the y=-968 row (fh332, x 20..212) while the bot chases from the fh325
     * perch (136,-572) below: the LIVE floor point tracks the mob every burst. The contract is
     * not "reach the exact moving x" but "reliably get ONTO the mob's platform row" — the frozen
     * anchor must keep the climb committed instead of pacing below.
     */
    @Test
    void chaseOfAPatrollingMobAboveStillClimbsToItsRow() {
        int[] speeds = {105, 120};
        for (int stat : speeds) {
            BotMovementProfile profile = new BotMovementProfile(stat, 110);
            Sim sim = sim(profile, 136, -572);
            int mobX = 179;
            int dir = -1;
            // PQ cadence: 30-tick bursts (1.5s), mob position refreshed each burst (patrol).
            for (int burst = 0; burst < 40; burst++) {
                mobX += dir * 12;
                if (mobX <= 40) { mobX = 40; dir = 1; }
                if (mobX >= 200) { mobX = 200; dir = -1; }
                moveAtMobFloor(sim, mobX, -977);
                tickN(sim, 30);
            }
            Point end = sim.p();
            // The mob row is y=-968: the bot must be ON it (not pacing below at -572).
            boolean onRow = end.y <= -900;
            System.out.println("MOVINGMOB s" + stat + " end=" + end.x + "," + end.y
                    + (onRow ? " OK" : " STUCK-BELOW") + " nav=" + sim.st().lastNavDecision);
            if (!onRow) {
                System.out.println(sim.trace());
            }
            org.junit.jupiter.api.Assertions.assertTrue(onRow,
                    "profile " + stat + ": chasing a patrolling mob above must still reach its row, ended at " + end);
        }
    }

    /**
     * The mob FALLS/JUMPS through the shaft beside the bot's perch: its live column crosses
     * unrelated footholds (y=-450 entry floor, mid-air columns), so the per-beat floor probe
     * used to yank the target region every burst. The bot must still climb to the mob's REAL
     * platform (the row it lands and stays on, y=-968) instead of being dragged to the y=-450
     * floor the airborne arcs probed.
     */
    @Test
    void chaseSurvivesAirborneArcsOfTheMob() {
        BotMovementProfile profile = new BotMovementProfile(105, 110);
        Sim sim = sim(profile, 136, -572);
        // First 12 bursts: mob airborne/arcing over the entry floor column (x=200 probes
        // fh321/322 at y=-450 through the open shaft). Then it lands on its row and patrols.
        int[][] arcXs = {{200, -450}, {200, -977}, {200, -450}, {200, -977}};
        int arcIdx = 0;
        int mobX = 179;
        for (int burst = 0; burst < 40; burst++) {
            int mx, my;
            if (burst < 12) {
                int[] a = arcXs[arcIdx++ % arcXs.length];
                mx = a[0];
                my = a[1];
            } else {
                mx = 60 + (burst % 4) * 40;
                my = -977;
            }
            moveAtMobFloor(sim, mx, my);
            tickN(sim, 30);
        }
        Point end = sim.p();
        boolean onRow = end.y <= -900;
        System.out.println("ARCMOB end=" + end.x + "," + end.y + (onRow ? " OK" : " DRAGGED") + " nav=" + sim.st().lastNavDecision);
        if (!onRow) {
            System.out.println(sim.trace());
        }
        org.junit.jupiter.api.Assertions.assertTrue(onRow,
                "airborne-arc probes of the mob must not drag the bot off the climb, ended at " + end);
    }
}
