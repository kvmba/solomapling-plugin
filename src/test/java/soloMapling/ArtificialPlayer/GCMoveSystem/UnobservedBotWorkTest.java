package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.client.Character;
import org.gms.client.inventory.Pet;
import org.gms.config.GameConfig;
import org.gms.constants.game.CharacterStance;
import org.gms.dao.entity.GameConfigDO;
import org.gms.manager.ServerManager;
import org.gms.property.ServiceProperty;
import org.gms.server.maps.Foothold;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Portal;
import org.gms.server.maps.Summon;
import org.gms.service.ConfigService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.context.ApplicationContext;
import org.springframework.context.MessageSource;
import soloMapling.ArtificialPlayer.BotPetSystem.BotPetConfig;
import soloMapling.ArtificialPlayer.BotPetSystem.BotPetFollower;
import soloMapling.ArtificialPlayer.BotSummonSystem.BotSummonConfig;
import soloMapling.ArtificialPlayer.BotSummonSystem.BotSummonFollower;
import soloMapling.ArtificialPlayer.BotSummonSystem.BotSummonTable;

import java.awt.Point;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UnobservedBotWorkTest {
    private static final int MAP_ID = 100_000_000;
    private static final int BOT_ID = 990_501;
    private static final int PET_ID = 990_502;
    private static final int SKILL_ID = 990_503;
    private Character bot;
    private MapleMap map;

    @BeforeAll
    static void stubHost() throws Exception {
        ApplicationContext context = mock(ApplicationContext.class, RETURNS_DEEP_STUBS);
        ConfigService configs = mock(ConfigService.class);
        when(configs.loadGameConfigs()).thenReturn(List.<GameConfigDO>of());
        when(context.getBean(ConfigService.class)).thenReturn(configs);
        ServiceProperty properties = mock(ServiceProperty.class);
        when(properties.getLanguage()).thenReturn("zh-CN");
        when(context.getBean(ServiceProperty.class)).thenReturn(properties);
        MessageSource messages = mock(MessageSource.class);
        when(messages.getMessage(anyString(), any(Object[].class), any(java.util.Locale.class)))
                .thenAnswer(call -> call.getArgument(0));
        for (String name : List.of("messageSource", "logSource", "exceptionSource")) {
            when(context.getBean(name, MessageSource.class)).thenReturn(messages);
        }
        Method setter = ServerManager.class.getDeclaredMethod("setApplicationContext", ApplicationContext.class);
        setter.setAccessible(true);
        setter.invoke(new ServerManager(), context);
        GameConfigDO updateInterval = new GameConfigDO();
        updateInterval.setConfigType("server");
        updateInterval.setConfigSubType("0");
        updateInterval.setConfigCode("update_interval");
        updateInterval.setConfigValue("100");
        updateInterval.setConfigClazz("java.lang.Long");
        GameConfig.add(updateInterval);
    }

    @BeforeEach
    void setUp() {
        MovementClock.useVirtual(10_000_000L);
        map = mock(MapleMap.class, RETURNS_DEEP_STUBS);
        when(map.getId()).thenReturn(MAP_ID);
        bot = mock(Character.class);
        when(bot.getId()).thenReturn(BOT_ID);
        when(bot.getMap()).thenReturn(map);
        when(bot.getMapId()).thenReturn(MAP_ID);
        when(bot.getPosition()).thenReturn(new Point(100, 100));
    }

    @AfterEach
    void cleanUp() throws Exception {
        ObserverTracker.observe(Set.of(), Set.of(), Long.MAX_VALUE - 10_000L);
        MovementClock.reset();
        staticMap(GCMovement.class, "STATES").remove(BOT_ID);
        staticMap(BotPetFollower.class, "followDistanceByPet").remove(PET_ID);
        staticMap(BotSummonFollower.class, "TRACKED").remove(BOT_ID);
        staticMap(BotSummonFollower.class, "BUFF_TIME_BY_SKILL").remove(SKILL_ID);
    }

    @Test
    void fullHaloAndDwellNeverLeakToAnotherMapInstanceWithTheSameId() {
        MapleMap otherChannel = mock(MapleMap.class);
        MapleMap otherInstance = mock(MapleMap.class);
        when(otherChannel.getId()).thenReturn(MAP_ID);
        when(otherInstance.getId()).thenReturn(MAP_ID);
        ObserverTracker.observe(Set.of(map), Set.of(), MovementClock.nowMs());
        assertTrue(GCMovement.isMapObserved(map));
        assertFalse(GCMovement.isMapObserved(otherChannel));
        assertFalse(GCMovement.isMapObserved(otherInstance));

        ObserverTracker.observe(Set.of(), Set.of(map), MovementClock.nowMs());
        assertFalse(GCMovement.isMapObserved(map));
        assertTrue(ObserverTracker.isHalo(map));
        assertFalse(ObserverTracker.isHalo(otherChannel));
        ObserverTracker.observe(Set.of(), Set.of(), MovementClock.nowMs());
        assertTrue(ObserverTracker.isActiveMap(map));
        assertFalse(ObserverTracker.isActiveMap(otherInstance));
        MovementClock.advance(4_000L);
        ObserverTracker.observe(Set.of(), Set.of(), MovementClock.nowMs());
        assertFalse(ObserverTracker.isActiveMap(map));
    }

    @Test
    void forcedObservationIsScopedAndExpiresWithoutFurtherMapReads() throws Exception {
        MapleMap other = mock(MapleMap.class);
        when(other.getId()).thenReturn(MAP_ID);
        ObserverTracker.markObservedNow(map);
        assertTrue(GCMovement.isMapObserved(map));
        assertFalse(GCMovement.isMapObserved(other));
        MovementClock.advance(2_001L);
        ObserverTracker.observe(Set.of(), Set.of(), MovementClock.nowMs());
        assertFalse(GCMovement.isMapObserved(map));
        assertTrue(staticMap(ObserverTracker.class, "forcedFull").isEmpty());
    }

    @Test
    void haloUsesThePlayersMapManagerAndNeverLoadsColdMaps() throws Exception {
        when(map.getEventInstance()).thenReturn(null);
        Portal portal = mock(Portal.class);
        when(portal.getTargetMapId()).thenReturn(100000001);
        when(portal.getPosition()).thenReturn(new Point(100, 100));
        when(map.getPortals()).thenReturn(List.of(portal));
        var manager = map.getChannelServer().getMapFactory();
        MapleMap neighbor = mock(MapleMap.class);
        when(manager.isMapLoaded(100000001)).thenReturn(true);
        when(manager.getMap(100000001)).thenReturn(neighbor);
        Set<MapleMap> halo = new java.util.HashSet<>();
        try (MockedStatic<GCWorldGraph> graph = mockStatic(GCWorldGraph.class)) {
            graph.when(() -> GCWorldGraph.portalNeighbors(MAP_ID)).thenReturn(new int[]{100000001});
            invoke(ObserverTracker.class, "addNearbyPortalMaps",
                    new Class<?>[]{Character.class, Set.class, Set.class}, bot, Set.of(), halo);
            assertEquals(Set.of(neighbor), halo);
            halo.clear();
            clearInvocations(manager);
            when(manager.isMapLoaded(100000001)).thenReturn(false);
            invoke(ObserverTracker.class, "addNearbyPortalMaps",
                    new Class<?>[]{Character.class, Set.class, Set.class}, bot, Set.of(), halo);
            assertTrue(halo.isEmpty());
            verify(manager, never()).getMap(anyInt());
            var instance = mock(org.gms.scripting.event.EventInstanceManager.class, RETURNS_DEEP_STUBS);
            when(map.getEventInstance()).thenReturn(instance);
            MapleMap instanceNeighbor = mock(MapleMap.class);
            when(instance.getMapFactory().isMapLoaded(100000001)).thenReturn(true);
            when(instance.getMapFactory().getMap(100000001)).thenReturn(instanceNeighbor);
            invoke(ObserverTracker.class, "addNearbyPortalMaps",
                    new Class<?>[]{Character.class, Set.class, Set.class}, bot, Set.of(), halo);
            assertEquals(Set.of(instanceNeighbor), halo);
        }
    }

    @Test
    void promotionWakesMovementOnceRatherThanOnEveryObserverPoll() {
        when(map.getAllPlayers()).thenReturn(List.of(bot));
        try (MockedStatic<GCMovement> movement = mockStatic(GCMovement.class)) {
            ObserverTracker.observe(Set.of(), Set.of(map), MovementClock.nowMs());
            ObserverTracker.observe(Set.of(map), Set.of(), MovementClock.nowMs());
            movement.verify(() -> GCMovement.wake(bot), times(1));
        }
    }

    @Test
    void onlyExplicitUnobservedRopeRestsUseTheIdleCadence() {
        BotMovementState state = new BotMovementState(bot, null);
        state.climbing = true;
        assertEquals(50L, GCMovementDriver.nextCadenceMs(state));
        state.resting = true;
        assertEquals(4_000L, GCMovementDriver.nextCadenceMs(state));
        state.inAir = true;
        assertEquals(50L, GCMovementDriver.nextCadenceMs(state));
        state.inAir = false;
        ObserverTracker.markObservedNow(map);
        assertEquals(50L, GCMovementDriver.nextCadenceMs(state));
    }

    @Test
    void aNewMoveWakesTheIdleDeadlineWithoutForkingTheTickChain() throws Exception {
        BotMovementState state = new BotMovementState(bot, null);
        ScheduledFuture<?> idle = mock(ScheduledFuture.class);
        when(idle.getDelay(TimeUnit.MILLISECONDS)).thenReturn(4_000L);
        state.task = idle;
        synchronized (state) {
            try {
                invoke(GCMovement.class, "setMoveTarget",
                        new Class<?>[]{BotMovementState.class, int.class, int.class, Runnable.class},
                        state, 500, 100, null);
                assertEquals(new Point(500, 100), state.moveTarget);
                assertNotSame(idle, state.task);
                verify(idle).cancel(false);
                long generation = state.tickGeneration;
                ScheduledFuture<?> pending = state.task;
                GCMovementDriver.wake(state);
                assertEquals(generation, state.tickGeneration, "an already-due tick is not scheduled twice");
                assertSame(pending, state.task);
            } finally {
                GCMovementDriver.stop(state);
            }
        }
    }

    @Test
    void unobservedFidgetDoesNotGenerateMovementButResumesWhenObserved() throws Exception {
        Class<?> sessionType = Class.forName(GCFidget.class.getName() + "$Session");
        var constructor = sessionType.getDeclaredConstructor(Character.class);
        constructor.setAccessible(true);
        Object session = constructor.newInstance(bot);
        Field base = sessionType.getDeclaredField("base");
        base.setAccessible(true);
        base.set(session, new Point(-200, 100));
        try (MockedStatic<GCMovement> movement = mockStatic(GCMovement.class)) {
            movement.when(() -> GCMovement.isEnabled(bot)).thenReturn(true);
            invoke(GCFidget.class, "tick", new Class<?>[]{sessionType}, session);
            movement.verify(() -> GCMovement.nudgeTo(any(), anyInt(), anyInt()), never());
            verify(bot, never()).getPosition();
            movement.when(() -> GCMovement.isMoving(bot)).thenReturn(true);
            invoke(GCFidget.class, "tick", new Class<?>[]{sessionType}, session);
            assertNull(base.get(session), "real orders must re-anchor even while unseen");
            movement.when(() -> GCMovement.isMoving(bot)).thenReturn(false);
            base.set(session, new Point(-200, 100));
            ObserverTracker.markObservedNow(map);
            invoke(GCFidget.class, "tick", new Class<?>[]{sessionType}, session);
            movement.verify(() -> GCMovement.nudgeTo(bot, -200, 100));
        }
    }

    @Test
    void releasingARopeRestWakesItsBackgroundDeadline() throws Exception {
        BotMovementState state = new BotMovementState(bot, null);
        state.climbing = true;
        state.resting = true;
        ScheduledFuture<?> idle = mock(ScheduledFuture.class);
        when(idle.getDelay(TimeUnit.MILLISECONDS)).thenReturn(4_000L);
        state.task = idle;
        staticMap(GCMovement.class, "STATES").put(BOT_ID, state);
        synchronized (state) {
            try {
                GCMovement.setRestHold(bot, false);
                assertFalse(state.resting);
                assertNotSame(idle, state.task);
                verify(idle).cancel(false);
            } finally {
                GCMovementDriver.stop(state);
            }
        }
    }

    @Test
    void settledUnobservedPetsDoNotProbeAgainUntilTheirFootholdIsInvalidated() throws Exception {
        Pet pet = pet();
        AtomicInteger foothold = new AtomicInteger(7);
        when(pet.getFh()).thenAnswer(call -> foothold.get());
        doAnswer(call -> { foothold.set(call.getArgument(0)); return null; }).when(pet).setFh(anyInt());
        try (MockedStatic<GCMovement> movement = mockStatic(GCMovement.class)) {
            movement.when(() -> GCMovement.groundFoothold(eq(map), any(Point.class)))
                    .thenReturn(new Foothold(new Point(0, 100), new Point(200, 100), 7));
            syncPets();
            movement.verify(() -> GCMovement.groundFoothold(any(), any()), never());
            foothold.set(0); // host map entry invalidates even a same-id instance's old footing
            syncPets();
            syncPets();
            movement.verify(() -> GCMovement.groundFoothold(eq(map), any(Point.class)), times(1));
            verify(pet, never()).setPos(any());
        }
    }

    @Test
    void unobservedRopeAndSwimSnapshotsKeepTheirRealSpawnPose() throws Exception {
        Pet pet = pet();
        when(pet.getStance()).thenReturn(5); // retain the pet's own left-facing bit
        when(bot.getStance()).thenReturn(14); // rope
        try (MockedStatic<GCMovement> movement = mockStatic(GCMovement.class)) {
            syncPets();
            verify(pet).setPos(new Point(100, 100));
            verify(pet).setStance(31);
            verify(pet).setFh(0);
            clearInvocations(pet);
            when(bot.getStance()).thenReturn(CharacterStance.SWIM_RIGHT_STANCE);
            syncPets();
            verify(pet).setStance(13);
            verify(pet).setFh(0);
            movement.verify(() -> GCMovement.groundFoothold(any(), any()), never());
        }
    }

    @Test
    void summonRegistrationPublishesStableListsAndUnseenFlyersDoNotAnimate() throws Exception {
        staticMap(BotSummonFollower.class, "BUFF_TIME_BY_SKILL").put(SKILL_ID, 0L);
        Summon summon = mock(Summon.class);
        AtomicReference<Point> position = new AtomicReference<>(new Point(-200, 0));
        when(summon.getPosition()).thenAnswer(call -> position.get());
        doAnswer(call -> { position.set(call.getArgument(0)); return null; }).when(summon).setPosition(any());
        BotSummonTable.Spec spec = new BotSummonTable.Spec(SKILL_ID, BotSummonTable.Move.FOLLOW, false, 1);
        Class<?>[] registerTypes = {int.class, BotSummonTable.Spec.class, Summon.class, MapleMap.class};
        invoke(BotSummonFollower.class, "register", registerTypes, BOT_ID, spec, summon, map);
        Map<Integer, List<?>> tracked = staticMap(BotSummonFollower.class, "TRACKED");
        List<?> first = tracked.get(BOT_ID);
        invoke(BotSummonFollower.class, "register", registerTypes, BOT_ID, spec, mock(Summon.class), map);
        assertEquals(1, first.size());
        assertEquals(2, tracked.get(BOT_ID).size());
        Object state = first.get(0);
        Field distance = state.getClass().getDeclaredField("followDistancePx");
        distance.setAccessible(true);
        distance.setInt(state, 40);
        BotSummonConfig config = spy(BotSummonConfig.defaults());
        Class<?>[] moveTypes = {Character.class, state.getClass(), BotSummonConfig.class, boolean.class};
        invoke(BotSummonFollower.class, "moveSummon", moveTypes, bot, state, config, false);
        assertEquals(new Point(60, 100 + config.hoverOffsetY()), position.get());
        invoke(BotSummonFollower.class, "moveSummon", moveTypes, bot, state, config, false);
        verify(summon, times(1)).setPosition(any());
        verify(config, never()).bobXAmplitude();
        verify(config, never()).bobYAmplitude();
        verify(config, never()).followSpeedX();
        verify(config, never()).moveTickMs();
    }

    private Pet pet() throws Exception {
        Pet pet = mock(Pet.class);
        when(pet.getUniqueId()).thenReturn(PET_ID);
        when(pet.getPos()).thenReturn(new Point(60, 100));
        when(pet.getStance()).thenReturn(4);
        when(bot.getPets()).thenReturn(new Pet[]{pet});
        staticMap(BotPetFollower.class, "followDistanceByPet").put(PET_ID, 40);
        return pet;
    }

    private void syncPets() throws Exception {
        invoke(BotPetFollower.class, "syncUnobservedPositions",
                new Class<?>[]{Character.class, BotPetConfig.class}, bot, BotPetConfig.defaults());
    }

    @SuppressWarnings("unchecked")
    private static <K, V> Map<K, V> staticMap(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return (Map<K, V>) field.get(null);
    }

    private static Object invoke(Class<?> type, String name, Class<?>[] parameters, Object... arguments)
            throws Exception {
        Method method = type.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method.invoke(null, arguments);
    }
}
