package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.client.BuffStat;
import org.gms.client.Character;
import org.gms.client.Job;
import org.gms.config.GameConfig;
import org.gms.dao.entity.GameConfigDO;
import org.gms.manager.ServerManager;
import org.gms.property.ServiceProperty;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The stage-5 top door (LPQ 922010500, portal in01) and the 快速移动 blink that owns it.
 *
 * <p>The door's ledge sits 100px above the row beneath it, with no foothold, rope or portal
 * bridging the step. WZ Physics (gravity 2000, jumpSpeed 555) gives the whole 35-50 LPQ band 93px
 * of rise - 116px even with maxed party Haste - so no jump in the game clears it, and one blink
 * (150px) does. That asymmetry is the reason this class exists: a mage could always make the step
 * in the real client, while the bot's graph had no idea the move existed, and every mage sent to
 * the top room walked at the door and hopped until the run ended.
 *
 * <p>The tests below pin the two halves of the fix together:
 * <ul>
 *   <li>the blink is a REAL graph edge, baked only for a body whose class owns the skill;</li>
 *   <li>the production driver then walks the mage up to the door - and a warrior still cannot.</li>
 * </ul>
 */
public class LudiStage5MageBlinkTest {

    private static final int MAP = 922010500;
    private static final Point DOOR = new Point(-145, -1291);

    private static final java.lang.reflect.Method TICK;

    static {
        try {
            TICK = GCMovementDriver.class.getDeclaredMethod("tick", BotMovementState.class);
            TICK.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
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
        org.springframework.context.MessageSource ms = mock(org.springframework.context.MessageSource.class);
        when(ms.getMessage(anyString(), any(Object[].class), any(java.util.Locale.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(ctx.getBean(eq("messageSource"), eq(org.springframework.context.MessageSource.class))).thenReturn(ms);
        when(ctx.getBean(eq("logSource"), eq(org.springframework.context.MessageSource.class))).thenReturn(ms);
        when(ctx.getBean(eq("exceptionSource"), eq(org.springframework.context.MessageSource.class))).thenReturn(ms);
        var setter = ServerManager.class.getDeclaredMethod("setApplicationContext", ApplicationContext.class);
        setter.setAccessible(true);
        setter.invoke(new ServerManager(), ctx);
        GameConfig.add(cfg("server", "update_interval", "100"));
        org.gms.net.server.Server.getInstance();
        MovementClock.useVirtual(1_000_000L);
        Assumptions.assumeTrue(wzAvailable(),
                "Map.wz not resolvable from this working directory (sim needs the real stage-5 geometry)");
    }

    @AfterAll
    static void restoreClock() {
        MovementClock.reset();
    }

    static boolean wzAvailable() {
        try {
            return org.gms.provider.DataProviderFactory.getDataProvider(org.gms.provider.wz.WZFiles.MAP)
                    .getData("Map/Map9/922010500.img") != null;
        } catch (Throwable unavailable) {
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

    /** The profile the PRODUCTION reader builds for a level-40 body of this job. */
    private static BotMovementProfile profileOf(Job job) {
        Character probe = mock(Character.class, RETURNS_DEEP_STUBS);
        when(probe.getLevel()).thenReturn(40);
        when(probe.getJob()).thenReturn(job);
        when(probe.getTotalMoveSpeedStat()).thenReturn(100);
        when(probe.getTotalJumpStat()).thenReturn(100);
        when(probe.getBuffedValue(BuffStat.MONSTER_RIDING)).thenReturn(null);
        return BotMovementProfile.fromCharacter(probe);
    }

    private record Sim(BotMovementState st, AtomicReference<Point> pos) {
        Point p() {
            return pos.get();
        }
    }

    private static Sim sim(Job job) {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(MAP);
        BotMovementProfile profile = profileOf(job);
        BotNavigationGraphProvider.getGraph(map, profile);
        Point start = BotPhysicsEngine.findGroundPoint(map, map.getPortal("st00").getPosition());

        AtomicReference<Point> pos = new AtomicReference<>(new Point(start));
        Character bot = mock(Character.class, RETURNS_DEEP_STUBS);
        when(bot.getMap()).thenReturn(map);
        when(bot.getMapId()).thenReturn(MAP);
        when(bot.getId()).thenReturn(4343);
        when(bot.getHp()).thenReturn(9000);
        when(bot.getChair()).thenReturn(0);
        when(bot.getLevel()).thenReturn(40);
        when(bot.getJob()).thenReturn(job);
        when(bot.getPosition()).thenAnswer(inv -> pos.get());
        doAnswer(inv -> {
            pos.set(new Point((Point) inv.getArgument(0)));
            return null;
        }).when(bot).setPosition(any());

        BotMovementState st = new BotMovementState(bot, null);
        st.movementProfile = profile;
        st.lastProfileRefreshMs = Long.MAX_VALUE / 4;
        st.lastMap = map;
        st.fhIndex = BotMovementManager.buildFhIndex(map);
        BotPhysicsEngine.teleportTo(st, bot, new Point(start));
        BotMovementManager.resetEntryStateAfterTeleport(st);
        return new Sim(st, pos);
    }

    /**
     * The REGRESSION: a mage ordered to the top door actually gets there, over the production
     * driver tick at the quest's own macro cadence (PqActions.walkTo re-issues the order every
     * 2.5s).
     */
    @Test
    void theMageWalksUpToTheTopDoor() {
        Sim sim = sim(Job.CLERIC);
        Point door = new Point(-145, -1291);
        int ticksPerBeat = (int) (2500 / BotPhysicsEngine.cfg.TICK_MS);
        for (int beat = 0; beat < 6; beat++) {
            sim.st().following = false;
            sim.st().farmAnchor = null;
            sim.st().farmAnchorMapId = -1;
            sim.st().moveTarget = new Point(door);
            sim.st().moveTargetPrecise = true;
            sim.st().moveBestDist = Integer.MAX_VALUE;
            sim.st().moveProgressAtMs = MovementClock.nowMs();
            for (int t = 0; t < ticksPerBeat; t++) {
                if (t % 30 == 0) ObserverTracker.markObservedNow(MAP);
                try {
                    TICK.invoke(null, sim.st());
                } catch (ReflectiveOperationException e) {
                    throw new RuntimeException(e.getCause() != null ? e.getCause() : e);
                }
                MovementClock.advance(BotPhysicsEngine.cfg.TICK_MS);
                if (Math.abs(sim.p().x - door.x) <= 8 && Math.abs(sim.p().y - door.y) <= 8) {
                    return; // on the door pixel: the portal is enterable
                }
            }
        }
        throw new AssertionError("the mage never reached the stage-5 top door; ended at " + sim.p());
    }

    /**
     * The other half: a warrior must NOT be handed the mage's route.
     *
     * <p>Two layers, and the second one is the load-bearing test. The graph bakes no TELEPORT edge
     * for a profile without the skill, so {@code findPath} answers null on the warrior's own graph.
     * But {@code peekBestGraph} serves the CLOSEST cached profile while a bot's own graph is still
     * baking, which really does hand a warrior a mage's blink edges (measured: a fighter walked the
     * blink chain to the stage-5 top door). The executor therefore refuses a blink whose body has no
     * skill, which is what this pins.
     */
    @Test
    void aWarriorIsNotGivenAMageRoute() {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(MAP);
        Point from = BotPhysicsEngine.findGroundPoint(map, map.getPortal("st00").getPosition());
        Point door = BotPhysicsEngine.findGroundPoint(map, DOOR);
        BotMovementProfile warrior = profileOf(Job.FIGHTER);
        assertFalse(warrior.teleport(), "a fighter has no 快速移动");
        BotNavigationGraph g = BotNavigationGraphProvider.getGraph(map, warrior);
        int sr = g.findRegionId(map, from);
        int dr = g.findRegionId(map, door);
        assertNull(BotNavigationManager.findPath(g, map, from, sr, dr, door),
                "without the skill there is no route to a step no jump clears");
        assertEquals(0, countTeleportEdges(g), "a warrior's graph must carry no blink edges at all");
    }

    /**
     * The fallback-graph hole, pinned at the layer that actually closes it: a warrior driving a
     * MAGE's graph (the shape {@code peekBestGraph} produces while the bot's own graph bakes) must
     * not blink. The graph cannot decide this - A* has no character - so the navigation executor's
     * class gate is the only thing standing between a warrior and a mage's move set. Measured
     * without it: a fighter walked the blink chain all the way to the stage-5 top door.
     */
    @Test
    void aWarriorCannotBlinkOffAFallbackMageGraph() {
        Sim warrior = sim(Job.FIGHTER);
        // Hand the bot the mage's profile, exactly as peekClosestGraph would.
        warrior.st().movementProfile = profileOf(Job.CLERIC);
        assertTrue(warrior.st().movementProfile.teleport(),
                "the fallback profile must claim the skill for this test to mean anything");

        // The warrior may still CLIMB with its own jump kit (the rows below the door are ordinary
        // stairs, and -1029/-1176 are reachable by jumping), so asserting on the final position
        // would be both weak and misleading. What must never happen is a BLINK: a single-tick cut
        // of more than one walk step with no airborne transition. That is the mage's move, and it
        // is the only way this route exists at all.
        Point goal = new Point(-145, -1291);
        Point prev = warrior.p();
        int blinks = 0;
        for (int t = 0; t < 400; t++) {
            if (t % 50 == 0) {
                warrior.st().following = false;
                warrior.st().farmAnchor = null;
                warrior.st().farmAnchorMapId = -1;
                warrior.st().moveTarget = new Point(goal);
                warrior.st().moveTargetPrecise = true;
                warrior.st().moveBestDist = Integer.MAX_VALUE;
                warrior.st().moveProgressAtMs = MovementClock.nowMs();
            }
            if (t % 30 == 0) ObserverTracker.markObservedNow(MAP);
            try {
                TICK.invoke(null, warrior.st());
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e.getCause() != null ? e.getCause() : e);
            }
            MovementClock.advance(BotPhysicsEngine.cfg.TICK_MS);
            Point now = warrior.p();
            if (!warrior.st().inAir
                    && Math.abs(now.x - prev.x) + Math.abs(now.y - prev.y) > 40) {
                blinks++;
            }
            prev = now;
        }
        assertEquals(0, blinks,
                "a fighter must never blink, whatever graph planned the route; ended at " + warrior.p());
    }

    /**
     * A blink is a cast, not a frame: two of them must never land inside one cast delay. The
     * production driver is what has to honour it, so this drives real ticks and measures the
     * wall-clock gap between consecutive blinks.
     */
    @Test
    void blinksAreThrottledByTheCastDelay() {
        Sim sim = sim(Job.CLERIC);
        Point goal = new Point(-145, -1291);
        long lastBlinkAt = Long.MIN_VALUE;
        Point prev = sim.p();
        int blinks = 0;
        for (int t = 0; t < 400; t++) {
            if (t % 50 == 0) { // the quest's macro beat re-issues the order
                sim.st().following = false;
                sim.st().farmAnchor = null;
                sim.st().farmAnchorMapId = -1;
                sim.st().moveTarget = new Point(goal);
                sim.st().moveTargetPrecise = true;
                sim.st().moveBestDist = Integer.MAX_VALUE;
                sim.st().moveProgressAtMs = MovementClock.nowMs();
            }
            if (t % 30 == 0) ObserverTracker.markObservedNow(MAP);
            try {
                TICK.invoke(null, sim.st());
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e.getCause() != null ? e.getCause() : e);
            }
            MovementClock.advance(BotPhysicsEngine.cfg.TICK_MS);
            Point now = sim.p();
            // A blink shows up as a single-tick position cut of well over a walk step.
            if (Math.abs(now.x - prev.x) + Math.abs(now.y - prev.y) > 40) {
                blinks++;
                if (lastBlinkAt != Long.MIN_VALUE) {
                    long gap = MovementClock.nowMs() - lastBlinkAt;
                    assertTrue(gap >= TELEPORT_CAST_DELAY_MS,
                            "two blinks " + gap + "ms apart; the cast delay is "
                                    + TELEPORT_CAST_DELAY_MS + "ms");
                }
                lastBlinkAt = MovementClock.nowMs();
            }
            prev = now;
        }
        assertTrue(blinks > 0, "this test needs the route to actually blink");
    }

    /** Mirrors BotNavigationManager's cast delay; kept here so a silent retune fails the test. */
    private static final long TELEPORT_CAST_DELAY_MS = 400L;

    /** The blink edges exist for a mage and are the move that clears the 100px step. */
    @Test
    void theMageGraphCarriesTheBlinkAndTheDoorIsReachable() {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(MAP);
        Point from = BotPhysicsEngine.findGroundPoint(map, map.getPortal("st00").getPosition());
        Point door = BotPhysicsEngine.findGroundPoint(map, DOOR);
        BotMovementProfile mage = profileOf(Job.IL_WIZARD);
        assertTrue(mage.teleport(), "a 2nd-job magician owns 快速移动");
        BotNavigationGraph g = BotNavigationGraphProvider.getGraph(map, mage);
        assertTrue(countTeleportEdges(g) > 0, "the mage's graph must carry its blink edges");
        int sr = g.findRegionId(map, from);
        int dr = g.findRegionId(map, door);
        List<BotNavigationGraph.Edge> path = BotNavigationManager.findPath(g, map, from, sr, dr, door);
        assertNotNull(path, "the top door must be reachable for a mage");
        assertTrue(path.stream().anyMatch(e -> e.type == BotNavigationGraph.EdgeType.TELEPORT),
                "the route must use a blink - a jump cannot make that step");
    }

    /**
     * Every baked blink must be the warp the executor would perform from its own anchor. A plan
     * that says "up 100px" while {@code execTeleport} lands somewhere else is worse than no edge:
     * the bot commits to it, the warp goes elsewhere, and the goal is left unreachable.
     */
    @Test
    void everyBakedBlinkIsTheLandingTheExecutorWouldTake() {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(MAP);
        BotMovementProfile mage = profileOf(Job.CLERIC);
        BotNavigationGraph g = BotNavigationGraphProvider.getGraph(map, mage);
        int checked = 0;
        for (List<BotNavigationGraph.Edge> edges : g.outgoingByRegionId.values()) {
            for (BotNavigationGraph.Edge edge : edges) {
                if (edge.type != BotNavigationGraph.EdgeType.TELEPORT) {
                    continue;
                }
                checked++;
                Point actual = GCMovementSkills.landingToward(map, edge.startPoint,
                        edge.startPoint.x, edge.endPoint.y, mage, BotMovementCapabilities.NONE);
                assertEquals(edge.endPoint, actual,
                        "baked blink " + edge.fromRegionId + "->" + edge.toRegionId
                                + " disagrees with the executor at " + edge.startPoint);
            }
        }
        assertTrue(checked > 0, "this test needs baked blinks to be meaningful");
    }

    private static int countTeleportEdges(BotNavigationGraph graph) {
        int count = 0;
        for (List<BotNavigationGraph.Edge> edges : graph.outgoingByRegionId.values()) {
            for (BotNavigationGraph.Edge edge : edges) {
                if (edge.type == BotNavigationGraph.EdgeType.TELEPORT) {
                    count++;
                }
            }
        }
        return count;
    }
}
