package soloMapling.ArtificialPlayer.BotSummonSystem;

import org.gms.client.Character;
import org.gms.config.GameConfig;
import org.gms.dao.entity.GameConfigDO;
import org.gms.manager.ServerManager;
import org.gms.property.ServiceProperty;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Summon;
import org.gms.service.ConfigService;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mockStatic;

/**
 * The lifecycle lock is PER BOT and a {@code java.util.concurrent.locks.ReentrantLock}, and the
 * spawn path stays exactly-once under it.
 *
 * <p>Two regression axes are pinned here:</p>
 *
 * <ul>
 *   <li><b>Concurrency breadth.</b> The old single global {@code synchronized} monitor serialised
 *       every bot in the server AND pinned virtual threads on JDK 21 (a thread inside a monitor
 *       cannot unmount): wave 7 funnelled ~200 spawn-wave creation threads through one monitor and
 *       the whole startup froze with the host respawn task parked on a fair map read lock behind
 *       it. Per-bot locks restore cross-bot parallelism; the concurrency test below would time out
 *       (or deadlock) under any reintroduction of a single global lock spanning the spawn body,
 *       while a monitor used for the per-bot lock itself is caught by the lock-type assertion.</li>
 *   <li><b>Exactly-once.</b> The beats that spawn race by design - the move tick, the regrant
 *       sweep and a spawn-wave grant can all reach {@code spawnIfAbsent} for one bot at once - so
 *       only one spawn body may ever run for one bot, and a body that registers nothing reports
 *       failure (the lease must retry).</li>
 * </ul>
 */
class BotSummonPerBotLifecycleLockTest {

    private static final BotSummonTable.Spec SPEC =
            new BotSummonTable.Spec(1234567, BotSummonTable.Move.FOLLOW, false, 1);

    @BeforeAll
    static void stubHost() throws Exception {
        // Mocking Character runs its static initializer, which resolves host services through
        // ServerManager - the standard test stub (see BotSummonSpawnIfAbsentTest).
        ApplicationContext ctx = mock(ApplicationContext.class, RETURNS_DEEP_STUBS);
        ConfigService configService = mock(ConfigService.class);
        when(configService.loadGameConfigs()).thenReturn(List.<GameConfigDO>of());
        when(ctx.getBean(ConfigService.class)).thenReturn(configService);
        ServiceProperty props = mock(ServiceProperty.class);
        when(props.getLanguage()).thenReturn("zh-CN");
        when(ctx.getBean(ServiceProperty.class)).thenReturn(props);
        org.springframework.context.MessageSource messageSource =
                mock(org.springframework.context.MessageSource.class);
        when(messageSource.getMessage(anyString(), any(Object[].class), any(java.util.Locale.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(ctx.getBean(eq("messageSource"), eq(org.springframework.context.MessageSource.class)))
                .thenReturn(messageSource);
        when(ctx.getBean(eq("logSource"), eq(org.springframework.context.MessageSource.class)))
                .thenReturn(messageSource);
        when(ctx.getBean(eq("exceptionSource"), eq(org.springframework.context.MessageSource.class)))
                .thenReturn(messageSource);
        var setter = ServerManager.class.getDeclaredMethod("setApplicationContext", ApplicationContext.class);
        setter.setAccessible(true);
        setter.invoke(new ServerManager(), ctx);
        GameConfig.add(cfg("server", "update_interval", "100"));
        org.gms.net.server.Server.getInstance();
    }

    @AfterEach
    void releaseTracking() {
        for (int botId : new int[]{BOT_A, BOT_B}) {
            Character bot = mock(Character.class, RETURNS_DEEP_STUBS);
            when(bot.getId()).thenReturn(botId);
            BotSummonFollower.despawnAll(bot);
            assertFalse(BotSummonFollower.isTracked(botId), "the test must not leak its tracking entry");
        }
    }

    private static final int BOT_A = 987_101;
    private static final int BOT_B = 987_102;

    @Test
    void aStaleRecastCannotResurrectATornDownLease() throws Exception {
        Character bot = mock(Character.class);
        when(bot.getId()).thenReturn(BOT_A);
        var field = BotSummonFollower.class.getDeclaredField("REGRANT_DUE_AT");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        var leases = (java.util.Map<Integer, Long>) field.get(null);
        leases.put(BOT_A, 0L);
        BotSummonFollower.despawnAll(bot);
        var recast = BotSummonFollower.class.getDeclaredMethod("regrantFor",
                Integer.class, BotSummonConfig.class, long.class, long.class);
        recast.setAccessible(true);
        try (var controller = mockStatic(BotSummonController.class)) {
            recast.invoke(null, BOT_A, BotSummonConfig.defaults(), System.currentTimeMillis(), generation());
            controller.verifyNoInteractions();
        }
        assertFalse(leases.containsKey(BOT_A));
    }

    @Test
    void anExpiredSummonIsNotRehomedOrAttackedFromItsRetiredList() throws Exception {
        Character bot = mock(Character.class);
        MapleMap map = mock(MapleMap.class);
        when(bot.getId()).thenReturn(BOT_A);
        when(bot.getMap()).thenReturn(map);
        Summon entity = mock(Summon.class, RETURNS_DEEP_STUBS);
        BotSummonFollower.register(BOT_A, SPEC, entity, map);
        var tracked = BotSummonFollower.class.getDeclaredField("TRACKED");
        tracked.setAccessible(true);
        @SuppressWarnings("unchecked")
        var lists = (java.util.Map<Integer, List<BotSummon>>) tracked.get(null);
        lists.get(BOT_A).get(0).expireAtMs = 1;
        var tick = BotSummonFollower.class.getDeclaredMethod("tickBot", int.class, BotSummonConfig.class, long.class);
        tick.setAccessible(true);
        try (var helpers = mockStatic(BotHelpers.class); var movement = mockStatic(GCMovement.class)) {
            helpers.when(() -> BotHelpers.getCharFromChannelStorage(BOT_A)).thenReturn(bot);
            movement.when(() -> GCMovement.isMapObserved(0)).thenReturn(true);
            tick.invoke(null, BOT_A, BotSummonConfig.defaults(), generation());
        }
        assertFalse(BotSummonFollower.isTracked(BOT_A));
        org.mockito.Mockito.verify(map).removeMapObject(entity);
    }

    @Test
    void anOldServiceTickCannotRetireTheNewServicesSummon() throws Exception {
        Character bot = mock(Character.class);
        MapleMap map = mock(MapleMap.class);
        when(bot.getId()).thenReturn(BOT_A);
        when(bot.getMap()).thenReturn(map);
        BotSummonFollower.register(BOT_A, SPEC, mock(Summon.class, RETURNS_DEEP_STUBS), map);
        var tick = BotSummonFollower.class.getDeclaredMethod("tickBot", int.class, BotSummonConfig.class, long.class);
        tick.setAccessible(true);
        try (var helpers = mockStatic(BotHelpers.class); var movement = mockStatic(GCMovement.class)) {
            helpers.when(() -> BotHelpers.getCharFromChannelStorage(BOT_A)).thenReturn(bot);
            tick.invoke(null, BOT_A, BotSummonConfig.defaults(), generation() - 1);
            movement.verifyNoInteractions();
        }
        assertTrue(BotSummonFollower.isTracked(BOT_A));
        org.mockito.Mockito.verifyNoInteractions(map);
    }

    @Test
    void anOldServiceRecastCannotConsumeTheNewServicesLease() throws Exception {
        var field = BotSummonFollower.class.getDeclaredField("REGRANT_DUE_AT");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        var leases = (java.util.Map<Integer, Long>) field.get(null);
        leases.put(BOT_A, 0L);
        var recast = BotSummonFollower.class.getDeclaredMethod("regrantFor",
                Integer.class, BotSummonConfig.class, long.class, long.class);
        recast.setAccessible(true);
        try (var helpers = mockStatic(BotHelpers.class)) {
            recast.invoke(null, BOT_A, BotSummonConfig.defaults(), System.currentTimeMillis(), generation() - 1);
            helpers.verifyNoInteractions();
        }
        assertEquals(0L, leases.get(BOT_A));
    }

    private static long generation() throws Exception {
        var field = BotSummonFollower.class.getDeclaredField("generation");
        field.setAccessible(true);
        return field.getLong(null);
    }

    @Test
    void lifecycleStripesAreBoundedAndStableAcrossRetiredBotIds() throws Exception {
        var field = BotSummonFollower.class.getDeclaredField("LIFECYCLE_LOCKS");
        field.setAccessible(true);
        var locks = (java.util.concurrent.locks.ReentrantLock[]) field.get(null);
        var before = locks.clone();
        for (int id = 1; id <= 10_000; id++) {
            BotSummonFollower.spawnIfAbsent(id, () -> { });
        }
        assertTrue(locks.length > 1 && locks.length <= 1024);
        for (int i = 0; i < locks.length; i++) assertTrue(locks[i] == before[i]);
    }

    @Test
    void differentBotsDoNotSerialiseEachOthersSpawnBodies() throws Exception {
        CountDownLatch bothInside = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger registered = new AtomicInteger();

        // Each thread's body registers its OWN bot (captured per thread - no shared mutable id).
        Thread a = new Thread(() -> BotSummonFollower.spawnIfAbsent(BOT_A, () -> {
            bothInside.countDown();
            awaitQuietly(release);
            BotSummonFollower.register(BOT_A, SPEC,
                    mock(Summon.class, RETURNS_DEEP_STUBS), mock(MapleMap.class));
            registered.incrementAndGet();
        }));
        Thread b = new Thread(() -> BotSummonFollower.spawnIfAbsent(BOT_B, () -> {
            bothInside.countDown();
            awaitQuietly(release);
            BotSummonFollower.register(BOT_B, SPEC,
                    mock(Summon.class, RETURNS_DEEP_STUBS), mock(MapleMap.class));
            registered.incrementAndGet();
        }));
        a.start();
        b.start();
        // Both bodies must reach the latch together: under the old single global lock the second
        // bot's body could not even enter while the first held the lock through its latch wait.
        assertTrue(bothInside.await(2, TimeUnit.SECONDS),
                "two different bots' spawn bodies must run concurrently (per-bot locks)");
        release.countDown();
        a.join(3_000);
        b.join(3_000);
        assertEquals(2, registered.get(), "each bot's body must have run to completion");
        assertTrue(BotSummonFollower.isTracked(BOT_A) && BotSummonFollower.isTracked(BOT_B));
    }

    @Test
    void concurrentBeatsForOneBotSpawnExactlyOnce() throws Exception {
        AtomicInteger bodies = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<java.util.concurrent.Future<Boolean>> results = java.util.stream.IntStream
                    .range(0, 4).mapToObj(i -> pool.submit(() -> {
                        // Every beat parks inside its spawn body (if it wins the race to enter),
                        // so a losing beat must be parked at the per-bot lock, not past the check.
                        return BotSummonFollower.spawnIfAbsent(BOT_A, () -> {
                            try {
                                release.await(3, TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            bodies.incrementAndGet();
                            BotSummonFollower.register(BOT_A, SPEC,
                                    mock(Summon.class, RETURNS_DEEP_STUBS), mock(MapleMap.class));
                        });
                    })).toList();

            Thread.sleep(200); // let the beats pile up (at most one inside the body)
            release.countDown();
            for (java.util.concurrent.Future<Boolean> f : results) {
                f.get(3, TimeUnit.SECONDS);
            }
            assertEquals(1, bodies.get(), "only one spawn body may ever run for one bot");
            assertTrue(BotSummonFollower.isTracked(BOT_A));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aCallerWaitingAtThePerBotLockCannotSpawnOnTopOfTheFirst() throws Exception {
        AtomicBoolean secondBodyRan = new AtomicBoolean();
        CountDownLatch firstBodyInside = new CountDownLatch(1);
        CountDownLatch releaseFirstBody = new CountDownLatch(1);

        Thread first = new Thread(() -> BotSummonFollower.spawnIfAbsent(BOT_A, () -> {
            firstBodyInside.countDown();
            try {
                releaseFirstBody.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            BotSummonFollower.register(BOT_A, SPEC,
                    mock(Summon.class, RETURNS_DEEP_STUBS), mock(MapleMap.class));
        }));
        first.start();
        assertTrue(firstBodyInside.await(2, TimeUnit.SECONDS));

        Thread second = new Thread(() -> BotSummonFollower.spawnIfAbsent(BOT_A,
                () -> secondBodyRan.set(true)));
        second.start();
        Thread.sleep(100); // the second caller must now be parked at the per-bot lock
        releaseFirstBody.countDown();
        first.join(3_000);
        second.join(3_000);

        assertFalse(secondBodyRan.get(),
                "a beat that entered after the register must not run its spawn body");
        assertTrue(BotSummonFollower.isTracked(BOT_A));
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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
}
