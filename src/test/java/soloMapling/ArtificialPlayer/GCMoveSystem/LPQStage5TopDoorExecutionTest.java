package soloMapling.ArtificialPlayer.GCMoveSystem;

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

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Execution-level pin for the stage-5 top door (LPQ 922010500, portal in01).
 *
 * <p>Two bodies, same map, same start pixel:
 * <ul>
 *   <li>an UNHASTED 35-50 body (110 jump, 93px rise) has no route to the door and the graph says
 *       so with null - it must NOT be fed a phantom "walk straight there";</li>
 *   <li>a HASTED body (123 jump after the +20 party-Haste jump grant, 116px rise) does have a
 *       route, and the production driver tick actually walks it up to the door pixel.</li>
 * </ul>
 *
 * <p>This is the difference the whole stage depends on: the top room holds four of the 24 passes
 * the stage NPC asks for, so "cannot get up there" is a stage that cannot be cleared.
 */
public class LPQStage5TopDoorExecutionTest {

    private static final int MAP = 922010500;

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
                "Map.wz not resolvable (this execution sim needs the real stage-5 geometry)");
    }

    @AfterAll
    static void restoreClock() {
        MovementClock.reset();
    }

    static boolean wzAvailable() {
        try {
            return org.gms.provider.DataProviderFactory.getDataProvider(org.gms.provider.wz.WZFiles.MAP)
                    .getData("Map/Map9/922010500.img") != null;
        } catch (Throwable t) {
            return false;
        }
    }

    static GameConfigDO cfg(String t, String k, String v) {
        GameConfigDO d = new GameConfigDO();
        d.setConfigType(t); d.setConfigSubType("0"); d.setConfigCode(k); d.setConfigValue(v);
        d.setConfigClazz("java.lang.Long");
        return d;
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

    private record Sim(BotMovementState st, AtomicReference<Point> pos) {
        Point p() {
            return pos.get();
        }
    }

    private static Sim sim(BotMovementProfile profile) {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(MAP);
        BotNavigationGraphProvider.getGraph(map, profile);
        Point start = BotPhysicsEngine.findGroundPoint(map, map.getPortal("st00").getPosition());
        AtomicReference<Point> pos = new AtomicReference<>(new Point(start));
        org.gms.client.Character bot = mock(org.gms.client.Character.class, RETURNS_DEEP_STUBS);
        when(bot.getMap()).thenReturn(map);
        when(bot.getMapId()).thenReturn(MAP);
        when(bot.getId()).thenReturn(4242);
        when(bot.getHp()).thenReturn(9000);
        when(bot.getChair()).thenReturn(0);
        when(bot.getJob()).thenReturn(org.gms.client.Job.BEGINNER);
        when(bot.getPosition()).thenAnswer(inv -> pos.get());
        doAnswer(inv -> {
            pos.set(new Point(inv.getArgument(0)));
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

    private static void tickOnce(Sim sim) {
        ObserverTracker.markObservedNow(MAP);
        try {
            TICK.invoke(null, sim.st());
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e.getCause() != null ? e.getCause() : e);
        }
        MovementClock.advance(BotPhysicsEngine.cfg.TICK_MS);
    }

    @Test
    void anUnhastedBotHasNoPhantomRoute() {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(MAP);
        Point from = BotPhysicsEngine.findGroundPoint(map, map.getPortal("st00").getPosition());
        Point door = BotPhysicsEngine.findGroundPoint(map, new Point(-145, -1292));
        BotNavigationGraph g = BotNavigationGraphProvider.getGraph(map, new BotMovementProfile(110, 110));
        int sr = g.findRegionId(map, from);
        int dr = g.findRegionId(map, door);
        assertTrue(sr != dr, "the door must sit in a different region for this case to mean anything");
        assertNull(BotNavigationManager.findPath(g, map, from, sr, dr, door),
                "an unreachable door answers null - never an empty 'walk straight there'");
    }

    @Test
    void theHastedBodyWalksUpToTheDoor() {
        // 110 (the LPQ band) + 20 party-Haste jump, clamped to the 123 ceiling: 116px of rise.
        Sim sim = sim(new BotMovementProfile(110, 123));
        Point door = new Point(-145, -1291);
        for (int t = 0; t < 1600; t++) {
            if (sim.st().moveTarget == null || t % 40 == 0) {
                sim.st().following = false;
                sim.st().farmAnchor = null;
                sim.st().farmAnchorMapId = -1;
                sim.st().moveTarget = new Point(door);
                sim.st().moveTargetMap = sim.st().bot.getMap();
                sim.st().moveTargetPrecise = true;
                sim.st().moveBestDist = Integer.MAX_VALUE;
                sim.st().moveProgressAtMs = MovementClock.nowMs();
            }
            tickOnce(sim);
            Point p = sim.p();
            if (Math.abs(p.x - door.x) <= 8 && Math.abs(p.y - door.y) <= 8) {
                return; // reached the door pixel: the portal is enterable
            }
        }
        throw new AssertionError("the hasted bot never reached the stage-5 top door; ended at " + sim.p());
    }

    @Test
    void aPartyHastedBodyWalksUpToTheDoor() throws Exception {
        // End-to-end over the PRODUCTION profile reader: a level-50 non-thief sharing a party with
        // a level-35 haste-thief. This is the path the bug lived on - hand-building a profile
        // would bypass BotMovementProfile.fromCharacter and pass even with the grant deleted.
        org.gms.client.Character bot = mock(org.gms.client.Character.class);
        when(bot.getLevel()).thenReturn(50);
        when(bot.getJob()).thenReturn(org.gms.client.Job.FIGHTER);
        when(bot.getTotalMoveSpeedStat()).thenReturn(100);
        when(bot.getTotalJumpStat()).thenReturn(100);
        when(bot.getId()).thenReturn(9090);
        when(bot.getBuffedValue(org.gms.client.BuffStat.MONSTER_RIDING)).thenReturn(null);
        org.gms.net.server.world.PartyCharacter mate = mock(org.gms.net.server.world.PartyCharacter.class);
        when(mate.getId()).thenReturn(9091);
        when(mate.getLevel()).thenReturn(35);
        when(mate.getJob()).thenReturn(org.gms.client.Job.ASSASSIN);
        when(mate.isOnline()).thenReturn(true);
        org.gms.net.server.world.PartyCharacter self = mock(org.gms.net.server.world.PartyCharacter.class);
        when(self.getId()).thenReturn(9090);
        org.gms.net.server.world.Party party = mock(org.gms.net.server.world.Party.class);
        when(party.getMembers()).thenReturn(List.of(self, mate));
        when(bot.getParty()).thenReturn(party);

        BotMovementProfile profile = BotMovementProfile.fromCharacter(bot);
        org.junit.jupiter.api.Assertions.assertEquals(123, profile.totalJumpStat(),
                "the party Haste must reach the profile the physics runs");

        // Drive the production tick with THAT profile and require the door pixel.
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(MAP);
        BotNavigationGraphProvider.getGraph(map, profile);
        Point start = BotPhysicsEngine.findGroundPoint(map, map.getPortal("st00").getPosition());
        AtomicReference<Point> pos = new AtomicReference<>(new Point(start));
        when(bot.getMap()).thenReturn(map);
        when(bot.getMapId()).thenReturn(MAP);
        when(bot.getHp()).thenReturn(9000);
        when(bot.getChair()).thenReturn(0);
        when(bot.getPosition()).thenAnswer(inv -> pos.get());
        doAnswer(inv -> {
            pos.set(new Point(inv.getArgument(0)));
            return null;
        }).when(bot).setPosition(any());
        BotMovementState st = new BotMovementState(bot, null);
        st.movementProfile = profile;
        st.lastProfileRefreshMs = Long.MAX_VALUE / 4;
        st.lastMap = map;
        st.fhIndex = BotMovementManager.buildFhIndex(map);
        BotPhysicsEngine.teleportTo(st, bot, new Point(start));
        BotMovementManager.resetEntryStateAfterTeleport(st);
        Sim sim = new Sim(st, pos);

        Point door = new Point(-145, -1291);
        for (int t = 0; t < 1600; t++) {
            if (st.moveTarget == null || t % 40 == 0) {
                st.following = false;
                st.farmAnchor = null;
                st.farmAnchorMapId = -1;
                st.moveTarget = new Point(door);
                st.moveTargetMap = map;
                st.moveTargetPrecise = true;
                st.moveBestDist = Integer.MAX_VALUE;
                st.moveProgressAtMs = MovementClock.nowMs();
            }
            tickOnce(sim);
            Point p = sim.p();
            if (Math.abs(p.x - door.x) <= 8 && Math.abs(p.y - door.y) <= 8) {
                return;
            }
        }
        throw new AssertionError("the party-hasted bot never reached the top door; ended at " + sim.p());
    }

}
