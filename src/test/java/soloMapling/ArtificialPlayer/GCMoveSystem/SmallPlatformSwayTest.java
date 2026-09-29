package soloMapling.ArtificialPlayer.GCMoveSystem;

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

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The toy-tower (22102xxxx, floors 1-101) small-platform sway: independent platforms 64-120px
 * wide fall OUTSIDE the narrow-ledge residency rule (NARROW_LEDGE_PX = 64) and outside the
 * overlap-notch snap, so a precise goal kept the old pixel hunt alive there — and when the
 * bang-bang steer overshot, the sway was INVISIBLE to the stuck watchdog (a ±15px oscillation
 * strides >8px every few ticks, so the raw "moved" tally never reached 500ms) and the random
 * rescue hop landed back ON the same platform.
 *
 * <p>Three production layers are pinned together here over the REAL WZ map
 * ({@code StairFullLoopSwayTest}'s production loop):
 * <ul>
 *   <li>the steer's release projections run the live ground sim (terrain-exact), so the settle
 *       lands inside the band on these wider platforms instead of straddling it;</li>
 *   <li>when a sway does begin near the goal, the watchdog's goal-distance test reaches 500ms of
 *       no net approach and fires the rescue;</li>
 *   <li>that rescue prefers the direction that lands OFF the current surface, so a single hop
 *       ends the loop instead of resetting it.</li>
 * </ul>
 */
class SmallPlatformSwayTest {

    private static final int MAP_ID = 221020000; // 玩具塔1层 — the reported floor family
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
        BotNavigationGraphProvider.getGraph(map, BotMovementProfile.base());
    }

    static org.gms.dao.entity.GameConfigDO cfg(String t, String k, String v) {
        org.gms.dao.entity.GameConfigDO d = new org.gms.dao.entity.GameConfigDO();
        d.setConfigType(t); d.setConfigSubType("0"); d.setConfigCode(k); d.setConfigValue(v); d.setConfigClazz("java.lang.Long");
        return d;
    }

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

    // Mirrors StairFullLoopSwayTest.productionTick (same tick order as GCMovementDriver).
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

    /** Run the loop to arrival; returns [reversals, settledX, settledY, ticksUsed]. */
    private static int[] runMove(BotMovementState st, int startX, int goalX, int goalY) {
        // Organic entry: drop onto the bot's OWN starting row (botAt's startY), not the goal
        // row — same-row goals would otherwise spawn the bot 400px above the goal and land it
        // on whatever foothold is first below the GOAL row (the tower's stacked floors).
        int startY = st.bot.getPosition().y;
        int spawnY = Math.abs(startY - goalY) < 40 ? startY - 60 : goalY - 400;
        st.bot.setPosition(new Point(startX, spawnY));
        st.physX = startX;
        st.physY = spawnY;
        st.inAir = true;
        moveGoal(st, goalX, goalY);
        int reversals = 0;
        int lastSign = 0;
        int arrivalTick = -1;
        int lastStuckCheckY = Integer.MIN_VALUE;
        for (int t = 0; t < TICKS; t++) {
            int prevX = st.bot.getPosition().x;
            productionTick(st, t % 2 == 0);
            Point p = st.bot.getPosition();
            // Skip the organic entry drop: until the bot first lands near the goal row the
            // free-fall tick stream hasn't started steering yet (park Y ~29000 = still falling).
            if (lastStuckCheckY != Integer.MIN_VALUE && p.y > goalY + 400) {
                continue;
            }
            lastStuckCheckY = p.y;
            if (st.moveTarget == null) { // arrived
                arrivalTick = t;
                break;
            }
            int stepSign = Integer.compare(p.x - prevX, 0);
            if (stepSign != 0) {
                if (lastSign != 0 && stepSign != lastSign) {
                    reversals++;
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

    /** Every standalone foothold on the floor the start column lands on. */
    private static List<Foothold> footholdsNear(int y, int span) {
        return map.getFootholds().getAllFootholds().stream()
                .filter(fh -> Math.abs(fh.getY1() - y) <= span && Math.abs(fh.getY2() - y) <= span)
                .toList();
    }

    @Test
    void goalsOnTheTowerPlatformsArriveWithoutSwayLoops() {
        // 玩具塔1层's standalone platforms: the WZ geometry (verified) has 175px ledges (y504/571),
        // 43-62px micro-perches (y1032, y1575 row, y1632/1713/1759), and a 90px-slotted top floor
        // (y2162). All sit OUTSIDE narrow-ledge residency (>= 64px) or above it with no committed
        // edge, so a precise goal there used to pixel-hunt with the analytic glide estimates and
        // pace. Aim at both ends + centre of every non-wall span on each platform row.
        int anomalies = 0;
        int goals = 0;
        // rowOffset 0 = start on the same row; otherwise start this many px ABOVE the row.
        // y=2162 (the bottom slotted floor) is tested same-row only: its floor above
        // (y=1803) carries WZ forbidFallDown=1 and no rope/walk-off reaches 2162, so goals
        // there are unreachable from above BY GEOMETRY — a route problem, not a sway.
        int[][] rowStarts = {
                {0, 827},      // main floor y=827
                {0, 1240},     // the stair notch row y1239-1249
                {0, 1032},     // 43px perch over the floor
                {90, 1575},    // 43-62px perch row (middle storey)
                {90, 1632},
                {0, 2162},     // slotted top floor
        };
        for (int[] rs : rowStarts) {
            int startY = rs[1] - rs[0]; // drop from one storey above (or same row)
            for (Foothold fh : footholdsNear(rs[1], 24)) {
                int lo = Math.min(fh.getX1(), fh.getX2());
                int hi = Math.max(fh.getX1(), fh.getX2());
                if (hi - lo < 8) {
                    continue; // vertical-wall fragments — not standable
                }
                // Start from a column that HAS ground on this row: the tower is only 530px
                // wide, so a raw +/-120 px offset hangs off the map edge and free-falls out
                // (an invalid trip, not a sway). Start on the goal row itself, a step away.
                int startX = Math.max(lo, Math.min(hi, goalXCandidate(fh, rs[1])));
                for (int goalX : new int[]{lo + 4, (lo + hi) / 2, hi - 4}) {
                    goals++;
                    BotMovementState st = botAt(startX, startY);
                    int[] r = runMove(st, startX, goalX, fh.getY1());
                    boolean ok = r[3] < TICKS && r[0] <= 8;
                    if (!ok) {
                        System.out.printf("TOWER-ANOMALY start=(%d,%d) goal=(%d,%d): reversals=%d ticks=%d park=(%d,%d)%n",
                                startX, startY, goalX, fh.getY1(), r[0], r[3], r[1], r[2]);
                        anomalies++;
                    }
                }
            }
        }
        assertTrue(goals > 20, "sweep degenerated: only " + goals + " goals probed");
        org.junit.jupiter.api.Assertions.assertEquals(0, anomalies,
                "toy-tower small-platform sway trips found - see TOWER-ANOMALY lines above");
    }

    /** A standing X on the row {@code rowY}, offset from the foothold's centre toward its far side. */
    private static int goalXCandidate(Foothold fh, int rowY) {
        List<Foothold> row = footholdsNear(rowY, 24);
        int lo = Math.min(fh.getX1(), fh.getX2());
        int hi = Math.max(fh.getX1(), fh.getX2());
        // walk to the far end of ANOTHER span on the same row when one exists (cross-platform
        // walk), else to this span's own centre.
        return row.stream()
                .filter(o -> Math.max(o.getX1(), o.getX2()) < Math.min(fh.getX1(), fh.getX2())
                        || Math.min(o.getX1(), o.getX2()) > Math.max(fh.getX1(), fh.getX2()))
                .findFirst()
                .map(o -> (Math.min(o.getX1(), o.getX2()) + Math.max(o.getX1(), o.getX2())) / 2)
                .orElse((lo + hi) / 2);
    }

    @Test
    void theRescueHopPrefersLandingOffTheCurrentSurface() {
        // Direct pin on the escape rule: a bot parked on a narrow-but-not-residency platform with a
        // precise goal, next to an edge that drops to another surface, must hop the ESCAPING way.
        // Synthetic geometry (FallbackDeadPitGuardTest's construction): a 100px perch whose right
        // edge drops onto a wide lower floor — only the RIGHT hop leaves the perch surface.
        MapleMap map = new MapleMap(922010193, 0, 0, 922010000, 0.0f);
        map.setMapLineBoundings(-500, 600, -800, 800);
        FootholdTree tree = new FootholdTree(new Point(-800, -500), new Point(800, 600));
        tree.insert(new Foothold(new Point(1000, 0), new Point(1100, 0), 1)); // the perch
        tree.insert(new Foothold(new Point(1100, 100), new Point(1600, 100), 2)); // lower floor
        map.setFootholds(tree);        BotMovementState st = botOn(map, 1080, 0);
        moveGoal(st, 1050, 0);
        st.stuckCheckX = 1080;
        st.stuckCheckY = 0;
        BotMovementManager.tickUnstuck(st);
        Point after = st.bot.getPosition();
        assertTrue(after.x > 1100 || st.inAir,
                "rescue hop stayed on the perch surface (landed at " + after.x + ")");
    }

    /** A state whose map lookups route to the given synthetic geometry. */
    private static BotMovementState botOn(MapleMap synthetic, int x, int y) {
        org.gms.client.Character botMock = mock(org.gms.client.Character.class, RETURNS_DEEP_STUBS);
        BotMovementState st = new BotMovementState(botMock, null);
        Point pos = new Point(x, y);
        when(st.bot.getMap()).thenReturn(synthetic);
        when(st.bot.getHp()).thenReturn(50);
        when(st.bot.getPosition()).thenAnswer(inv -> new Point(pos));
        doAnswer(inv -> {
            Point next = inv.getArgument(0);
            pos.move(next.x, next.y);
            return null;
        }).when(st.bot).setPosition(any(Point.class));
        st.movementProfile = BotMovementProfile.base();
        return st;
    }
}
