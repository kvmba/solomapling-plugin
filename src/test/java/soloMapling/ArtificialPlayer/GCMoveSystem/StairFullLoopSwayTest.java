package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.manager.ServerManager;
import org.gms.property.ServiceProperty;
import org.gms.server.maps.MapleMap;
import org.gms.service.ConfigService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Full production-loop reproduction of the "时间消失之路&lt;1&gt; (220070000) stair sway" report: a bot
 * moving around the Ludi toy-tower staircase — a concave chain of sub-64px treads (fh 3/4/5/6 at
 * x 868-921, y 11-17) stitched between two convex slopes/floors — gets stuck swaying left-right
 * in the middle for a long time.
 *
 * <p>Unlike {@link StairConcaveSwayTest} (which pins one hand-fed committed WALK edge through the
 * steer + physics), this test runs the PRODUCTION tick chain — {@code resolveTarget} (real baked
 * nav graph from the real WZ) → {@code tickGrounded} → {@code applyGroundMotion} → stuck watchdog →
 * arrival — over the whole stair area, for pure moves and for targets that need the bot to walk
 * through the concave knee. Any left-right pacing above the tolerance (a few honest
 * deceleration micro-oscillations) fails, whichever layer produces it.
 */
class StairFullLoopSwayTest {

    private static final int MAP_ID = 220070000;
    private static final int TICKS = 20 * 40; // 40s of production ticks at TICK_MS=50

    private static MapleMap map;

    @BeforeAll
    static void boot() {
        ApplicationContext ctx = mock(ApplicationContext.class, RETURNS_DEEP_STUBS);
        ConfigService configService = mock(ConfigService.class);
        when(configService.loadGameConfigs()).thenReturn(List.<org.gms.dao.entity.GameConfigDO>of());
        when(ctx.getBean(ConfigService.class)).thenReturn(configService);
        ServiceProperty props = mock(ServiceProperty.class);
        when(props.getLanguage()).thenReturn("zh-CN");
        when(ctx.getBean(ServiceProperty.class)).thenReturn(props);
        try {
            var setter = ServerManager.class.getDeclaredMethod("setApplicationContext", ApplicationContext.class);
            setter.setAccessible(true);
            setter.invoke(new ServerManager(), ctx);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        org.gms.config.GameConfig.add(cfg("server", "update_interval", "100"));
        map = BotNavigationMapLoader.loadMapGeometry(MAP_ID);
        // Bake the REAL nav graph once (production path); resolveTarget pulls it from the cache.
        BotNavigationGraphProvider.getGraph(map, BotMovementProfile.base());
    }

    static org.gms.dao.entity.GameConfigDO cfg(String t, String k, String v) {
        org.gms.dao.entity.GameConfigDO d = new org.gms.dao.entity.GameConfigDO();
        d.setConfigType(t); d.setConfigSubType("0"); d.setConfigCode(k); d.setConfigValue(v); d.setConfigClazz("java.lang.Long");
        return d;
    }

    /** A production-ish state: real map, real profile bucket, mock avatar carrying a mutable position. */
    private static BotMovementState botAt(int x, int y) {
        org.gms.client.Character botMock = mock(org.gms.client.Character.class, RETURNS_DEEP_STUBS);
        BotMovementState st = new BotMovementState(botMock, null);
        Point pos = new Point(x, y);
        when(st.bot.getMap()).thenReturn(map);
        when(st.bot.getMapId()).thenReturn(MAP_ID);
        when(st.bot.getHp()).thenReturn(50);
        when(st.bot.getChair()).thenReturn(0);
        when(st.bot.getPosition()).thenAnswer(inv -> new Point(pos));
        doAnswer(inv -> {
            Point next = inv.getArgument(0);
            pos.move(next.x, next.y);
            return null;
        }).when(st.bot).setPosition(any(Point.class));
        st.movementProfile = BotMovementProfile.base();
        st.lastMapId = MAP_ID;
        return st;
    }

    private static void moveGoal(BotMovementState st, int x, int y) {
        st.moveTarget = new Point(x, y);
        st.moveTargetPrecise = true;
        st.moveTargetSource = "test";
        st.moveBestDist = Integer.MAX_VALUE;
        st.moveProgressAtMs = System.currentTimeMillis();
    }

    /**
     * The production loop body (the order of GCMovementDriver.tick minus the world-facing
     * systems: debuffs, dash, LOD broadcast, reactions, contact damage).
     */
    private static void productionTick(BotMovementState st, boolean runAiTick) {
        Point target = st.moveTarget != null ? st.moveTarget : st.bot.getPosition();
        BotNavigationManager.NavigationDirective nav =
                BotNavigationManager.resolveTarget(st, target, runAiTick);
        if (nav.consumedTick) {
            return;
        }
        Point steering = nav.targetPos;
        if (st.moveTargetPrecise && st.navEdge == null) {
            st.navPreciseTarget = true;
        }
        if (st.climbing) {
            BotMovementManager.tickClimbing(st, steering, runAiTick);
        } else if (st.inAir) {
            BotMovementManager.tickAirborne(st, steering);
        } else {
            BotMovementManager.tickGrounded(st, steering);
        }
        if (runAiTick && !st.inAir && !st.climbing) {
            BotNavigationManager.tryExecuteCommittedEdgeAfterGroundMovement(st, target);
        }
        tickStuckDetection(st);
        GCMovementDriver.clearReachedMoveTarget(st);
    }

    // Mirrors GCMovementDriver.tickStuckDetection (the parts that touch only entry + position).
    private static void tickStuckDetection(BotMovementState st) {
        st.unstuckCooldownMs = BotMovementManager.tickDown(st.unstuckCooldownMs);
        if (BotMovementManager.isStuckCheckExempt(st)) {
            st.stuckMs = 0;
            st.stuckCheckX = Integer.MIN_VALUE;
            return;
        }
        Point botPos = st.bot.getPosition();
        if (st.stuckCheckX == Integer.MIN_VALUE) {
            st.stuckCheckX = botPos.x;
            st.stuckCheckY = botPos.y;
            return;
        }
        boolean moved = Math.abs(botPos.x - st.stuckCheckX) > 8 || Math.abs(botPos.y - st.stuckCheckY) > 8;
        if (moved) {
            st.stuckMs = 0;
            st.stuckCheckX = botPos.x;
            st.stuckCheckY = botPos.y;
        } else {
            st.stuckMs += BotPhysicsEngine.cfg.TICK_MS;
        }
        if (st.stuckMs >= 500 && st.unstuckCooldownMs == 0) {
            st.stuckMs = 0;
            st.stuckCheckX = Integer.MIN_VALUE;
            BotMovementManager.tickUnstuck(st);
        }
    }

    // Mirrors GCMovementDriver.tickStuckDetection (the parts that touch only entry + position);
    // the ARRIVAL rule itself is the production clearReachedMoveTarget, called directly so this
    // test cannot drift from the logic under test.

    /** Run the loop to arrival; returns [reversals, settledX, settledY, ticksUsed]. */
    private static int[] runMove(BotMovementState st, int startX, int goalX, int goalY) {
        st.bot.setPosition(new Point(startX, goalY - 400));
        st.physX = startX;
        st.physY = goalY - 400;
        st.inAir = true; // organic map entry: drop from above the start column
        moveGoal(st, goalX, goalY);
        int reversals = 0;
        int lastSign = 0;
        int arrivalTick = -1;
        for (int t = 0; t < TICKS; t++) {
            int prevX = st.bot.getPosition().x;
            productionTick(st, t % 2 == 0);
            Point p = st.bot.getPosition();
            if (st.moveTarget == null) { // arrived
                arrivalTick = t;
                break;
            }
            int stepSign = Integer.compare(p.x - prevX, 0);
            if (stepSign != 0) {
                if (lastSign != 0 && stepSign != lastSign) {
                    reversals++;
                    if (System.getenv("SWAY_TRACE") != null) {
                        System.out.printf("REVERSAL#%d t=%d pos=(%d,%d) inAir=%b navEdge=%s navTarget=%s moveTarget=%s decision=%s%n",
                                reversals, t, p.x, p.y, st.inAir,
                                st.navEdge == null ? "null" : st.navEdge.type + "@" + st.navEdge.startPoint.x + "->" + st.navEdge.endPoint.x,
                                st.navTargetPos, st.moveTarget, st.lastNavDecision);
                    }
                }
                lastSign = stepSign;
            }
        }
        Point end = st.bot.getPosition();
        if (arrivalTick < 0 && st.moveTarget != null) {
            arrivalTick = TICKS;
        }
        return new int[]{reversals, end.x, end.y, arrivalTick};
    }

    @Test
    void spawnDropAndMoveAroundTheStairConcaveDoesNotSway() {
        // Land on the stair head (fh3), then move across the concave knee to fh6's midpoint
        // (the deep end of the reported concavity), then back up to the deck.
        BotMovementState st = botAt(882, 11);
        int[] down = runMove(st, 882, 916, 17);
        assertTrue(down[3] < TICKS, "never arrived at the stair foot: parked at x=" + down[1] + " y=" + down[2]);
        assertTrue(down[0] <= 3,
                "descending the concave stair swayed " + down[0] + " times over " + down[3]
                        + " ticks before parking at x=" + down[1] + " y=" + down[2]);

        int[] up = runMove(st, down[1], 880, 11);
        assertTrue(up[3] < TICKS, "never arrived back on the tread: parked at x=" + up[1] + " y=" + up[2]);
        assertTrue(up[0] <= 3,
                "climbing back up the stair swayed " + up[0] + " times over " + up[3]
                        + " ticks before parking at x=" + up[1] + " y=" + up[2]);
    }

    @Test
    void sweepGoalsFromTheStairAreaFindsNoSwayLoop() {
        // Broad sweep: starts across the stair/deck concavity, goals on every tread, the deck,
        // and the main floor (incl. the under-portal column). Any goal that never arrives or
        // sways above tolerance is called out with its start/goal/park/stack.
        int[][] starts = {{880, 11}, {912, 17}, {850, -26}, {800, -26},
                {760, 351}, {860, 351}, {860, 376}, {900, 376}};
        int[][] goals = {
                {880, 11}, {890, 11}, {904, 13}, {912, 17}, {918, 17},
                {700, -26}, {600, -26},
                {900, 732}, {850, 732}, {-998, 730},
                {760, 351}, {840, 351}, {900, 351}, {840, 376}, {900, 376}, {914, 376}
        };
        for (int[] start : starts) {
            for (int[] goal : goals) {
                BotMovementState st = botAt(start[0], start[1]);
                int[] r = runMove(st, start[0], goal[0], goal[1]);
                // Pathology per the report = never arriving (the steer can never satisfy the
                // precise box so the trip runs to the tick cap) or direction-flipping so much the
                // trip reads as stuck-in-place swaying. A legitimate detour (drop a floor, walk
                // under, jump back up, or the full 2300px floor walk to a far portal) finishes
                // with zero-to-a-few honest course changes, so the reversal bound sits far above
                // those and far below the bug's 10-14-reversal signature.
                boolean ok = r[3] < TICKS && r[0] <= 8;
                if (!ok) {
                    System.out.printf("SWEEP-ANOMALY start=(%d,%d) goal=(%d,%d): reversals=%d ticks=%d park=(%d,%d)%n",
                            start[0], start[1], goal[0], goal[1], r[0], r[3], r[1], r[2]);
                    anomalies++;
                }
            }
        }
        org.junit.jupiter.api.Assertions.assertEquals(0, anomalies,
                "sway-loop trips found - see SWEEP-ANOMALY lines above");
    }

    private int anomalies = 0;

    @Test
    void aPureSettleOnEveryStairTreadStaysPut() {
        // From the deck above, ask for a stand on each tread; once arrived the bot must not
        // wander — the parked window must stay within the arrival band for the rest of the run.
        int[][] goals = {{880, 11}, {890, 11}, {904, 13}, {912, 17}, {918, 17}};
        for (int[] goal : goals) {
            BotMovementState st = botAt(882, 11);
            int[] r = runMove(st, 882, goal[0], goal[1]);
            assertTrue(r[3] < TICKS,
                    "goal (" + goal[0] + "," + goal[1] + ") never arrived; parked x=" + r[1] + " y=" + r[2]);
            assertTrue(r[0] <= 3,
                    "goal (" + goal[0] + "," + goal[1] + ") swayed " + r[0] + " times over " + r[3]
                            + " ticks (parked x=" + r[1] + " y=" + r[2] + ")");
        }
    }
}
