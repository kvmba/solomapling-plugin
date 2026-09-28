package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.manager.ServerManager;
import org.gms.property.ServiceProperty;
import org.gms.server.maps.Foothold;
import org.gms.server.maps.MapleMap;
import org.gms.service.ConfigService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.*;

/**
 * Regression guard for the "时间消失之路&lt;1&gt; (Ludi tower stair) concave stair sway" report:
 * a bot steering a committed WALK edge along the toy-tower staircase — a run of sub-64px treads
 * (fh 3/5/6 at x 868-921, y 11→17) stitched to a slope (fh 2) and a floor span — crossed the
 * concave knee onto the stair treads, then swayed left-right for a long time before the trip
 * ended.
 *
 * <p>Root cause: the narrow-ledge residency release ({@code settledNarrowLedge}, the fix for the
 * original toy-tower stair sway) only exempts PURE settles ({@code navEdge == null}). A committed
 * WALK edge kept the bang-bang corrector firing ±1-step pulses against treads narrower than one
 * step (a WALK step is ~7px against a 29px tread) — the same sway, one level down.
 *
 * <p>The steer now restricts a committed WALK edge to the DOWNSLOPE-concave span: the run of
 * narrow footholds at or below the edge's target Y between the bot and the target X. Steering
 * stops there (the bot settles on the first convex segment short of the target), which matches
 * how a player walks a concave staircase: step down into the bowl, stand on a flat tread, done.
 */
class StairConcaveSwayTest {

    private static final int MAP_ID = 220070000;
    // The concave stair chain right of the y=-26 deck: slope fh2 (837→868, -26→11), tread fh3
    // (868→897, y=11), micro-steps fh4 (897→901, 11→13) and fh5 (901→906, 13→17), tread fh6
    // (906→921, y=17). WZ next-chain ends at fh6 (next=0): the "stairs going down from the deck".
    private static final int EDGE_START_X = 810;  // on the y=-26 deck, left of the stair head
    private static final int EDGE_END_X = 918;    // mid fh6, the committed WALK edge's target
    private static final int TARGET_Y = 17;       // the edge target sits on fh6

    private static MapleMap map;

    @BeforeAll
    static void loadRealMap() {
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
    }

    static org.gms.dao.entity.GameConfigDO cfg(String t, String k, String v) {
        org.gms.dao.entity.GameConfigDO d = new org.gms.dao.entity.GameConfigDO();
        d.setConfigType(t); d.setConfigSubType("0"); d.setConfigCode(k); d.setConfigValue(v); d.setConfigClazz("java.lang.Long");
        return d;
    }

    private static BotMovementState botOn(int x, int y) {
        org.gms.client.Character botMock = mock(org.gms.client.Character.class, RETURNS_DEEP_STUBS);
        BotMovementState st = new BotMovementState(botMock, null);
        // One mutable holder + thenAnswer: the loop updates the holder between ticks instead of
        // re-stubbing (re-stubbing inside the loop left the previous when(...) unfinished).
        Point pos = new Point(x, y);
        when(st.bot.getMap()).thenReturn(map);
        when(st.bot.getMapId()).thenReturn(MAP_ID);
        when(st.bot.getPosition()).thenAnswer(inv -> new Point(pos));
        doAnswer(inv -> {
            Point next = inv.getArgument(0);
            pos.move(next.x, next.y);
            return null;
        }).when(st.bot).setPosition(any(Point.class));
        st.movementProfile = BotMovementProfile.base();
        st.inAir = false;
        st.climbing = false;
        st.physX = x;
        st.physY = y;
        return st;
    }

    private static BotNavigationGraph.Edge walkEdge() {
        return new BotNavigationGraph.Edge(90, 91, BotNavigationGraph.EdgeType.WALK,
                new Point(EDGE_START_X, -26), new Point(EDGE_END_X, TARGET_Y),
                EDGE_START_X, EDGE_START_X, 0, 0, 0, 0, 0, 1000);
    }

    /**
     * The sway loop, production physics only: steer (resolveGroundStepX) → walk/hold intent →
     * applyGroundMotion (the real force/drag integrator with snap/lost-ground handling). No target
     * clearing, no replanning: a committed-edge bot that still needs to steer after crossing the
     * concave knee IS the sway, however the higher layers would respond.
     */
    private static int[] walkSlope(BotMovementState st, int startX, int ticks) {
        BotNavigationGraph.Edge edge = st.navEdge;
        Point steer = new Point(edge.endPoint.x, edge.endPoint.y);
        int reversals = 0;
        int lastDir = 0;
        for (int t = 0; t < ticks; t++) {
            Point pos = st.bot.getPosition();
            int stepX = BotMovementManager.resolveGroundStepX(st, pos, steer, 4, 4);
            st.moveDir = Integer.signum(stepX);
            if (st.moveDir != lastDir && st.moveDir != 0 && lastDir != 0) {
                reversals++;
            }
            if (st.moveDir != 0) {
                lastDir = st.moveDir;
            }
            Foothold currentFh = BotPhysicsEngine.syncAndDetectGround(st, st.bot);
            BotPhysicsEngine.applyGroundMotion(st, st.bot, currentFh);
            if (stepX == 0 && st.hspeed == 0.0) {
                break; // settled: the bot held still for a full tick
            }
        }
        return new int[]{reversals, st.bot.getPosition().x, st.bot.getPosition().y};
    }

    @Test
    void aCommittedWalkEdgeAcrossTheConcaveKneeDoesNotSwayOnTheTreads() {
        // Crossed the knee: bot stands on tread fh3 (x=880, y=11) steering the committed WALK
        // edge toward x=918. Before the fix the bang-bang corrector fires ±7px pulses against
        // the 29px tread and the concave micro-steps — the reported left-right sway.
        BotMovementState st = botOn(880, 11);
        st.navEdge = walkEdge();
        st.wasMovingX = true;
        int[] result = walkSlope(st, 880, 1000);
        assertTrue(result[0] <= 1,
                "bot swayed " + result[0] + " times and parked at x=" + result[1] + " y=" + result[2]
                        + " — the concave-stair sway is back");
    }

    @Test
    void aFreshDescentSettlesOnAFlatTreadShortOfTheTarget() {
        // Sliding down the slope fh2 from the deck: the steer must release while still steering
        // downslope (on fh3) and settle on flat ground, not grind to the end of the chain and
        // pace across the micro-steps.
        BotMovementState st = botOn(850, -20);
        st.navEdge = walkEdge();
        st.wasMovingX = true;
        int[] result = walkSlope(st, 850, 1000);
        assertTrue(result[0] <= 1,
                "bot swayed " + result[0] + " times and parked at x=" + result[1] + " y=" + result[2]
                        + " — the concave-stair sway is back");
        assertTrue(result[2] >= 11,
                "bot stopped on the slope (y=" + result[2] + " < 11) — descent never reached flat ground");
    }
}
