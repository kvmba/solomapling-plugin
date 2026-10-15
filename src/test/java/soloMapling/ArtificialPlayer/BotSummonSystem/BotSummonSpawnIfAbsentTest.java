package soloMapling.ArtificialPlayer.BotSummonSystem;

import org.gms.client.Character;
import org.gms.config.GameConfig;
import org.gms.dao.entity.GameConfigDO;
import org.gms.manager.ServerManager;
import org.gms.property.ServiceProperty;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Summon;
import org.gms.service.ConfigService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.util.List;
import java.util.concurrent.CountDownLatch;
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

/**
 * The spawn path's idempotence is ATOMIC. The beats that spawn race by design: the move tick and
 * the dedicated regrant sweep both call {@code BotSummonController.recastForBot}, and a
 * spawn-wave grant can race either. A "not tracked" check taken outside the spawn could be passed
 * by two callers, each putting its own summon entity on the wire (double MOVE_SUMMON broadcasts
 * and double strikes until the buff-time expiry tore both down). {@code spawnIfAbsent} holds the
 * follower's lifecycle lock across check + spawn; these cases pin that contract.
 */
class BotSummonSpawnIfAbsentTest {

    private static final int BOT_ID = 987_001;
    private static final BotSummonTable.Spec SPEC =
            new BotSummonTable.Spec(1234567, BotSummonTable.Move.FOLLOW, false, 1);

    @BeforeAll
    static void stubHost() throws Exception {
        // Mocking Character runs its static initializer, which resolves host services through
        // ServerManager - the standard test stub (see LpqStage3PitEntryTest).
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
        Character bot = mock(Character.class, RETURNS_DEEP_STUBS);
        when(bot.getId()).thenReturn(BOT_ID);
        BotSummonFollower.despawnAll(bot);
        assertFalse(BotSummonFollower.isTracked(BOT_ID), "the test must not leak its tracking entry");
    }

    @Test
    void aTrackedBotNeverSpawnsASecondTime() {
        assertTrue(BotSummonFollower.spawnIfAbsent(BOT_ID, this::registerSummon),
                "the first spawn takes the empty slot");
        AtomicBoolean bodyRan = new AtomicBoolean();
        assertTrue(BotSummonFollower.spawnIfAbsent(BOT_ID, () -> bodyRan.set(true)),
                "an already-tracked bot reports success without spawning");
        assertFalse(bodyRan.get(),
                "the second identical recast beat must not put a second entity on the wire");
    }

    @Test
    void aFailedSpawnDoesNotReportSuccess() {
        assertFalse(BotSummonFollower.spawnIfAbsent(BOT_ID, () -> { }),
                "a body that registered nothing is a failed recast - the lease must retry");
    }

    @Test
    void aCallerWaitingAtTheLockCannotSpawnOnTopOfTheFirst() throws Exception {
        AtomicInteger spawnBodies = new AtomicInteger();
        CountDownLatch firstBodyInside = new CountDownLatch(1);
        CountDownLatch releaseFirstBody = new CountDownLatch(1);

        Thread first = new Thread(() -> BotSummonFollower.spawnIfAbsent(BOT_ID, () -> {
            firstBodyInside.countDown();
            awaitQuietly(releaseFirstBody);
            registerSummon();
            spawnBodies.incrementAndGet();
        }), "first-recast");
        first.start();
        assertTrue(firstBodyInside.await(2, TimeUnit.SECONDS), "the first body must reach the latch");

        Thread second = new Thread(() -> BotSummonFollower.spawnIfAbsent(BOT_ID, () -> {
            registerSummon();
            spawnBodies.incrementAndGet();
        }), "second-recast");
        second.start();
        // The second caller must now be parked at the lifecycle lock (the lock spans the first
        // body); releasing the first body lets it re-check tracking, which must refuse it.
        Thread.sleep(100);
        releaseFirstBody.countDown();
        first.join(2_000);
        second.join(2_000);

        assertEquals(1, spawnBodies.get(), "only one spawn body may ever run for one bot");
        assertTrue(BotSummonFollower.isTracked(BOT_ID));
    }

    private void registerSummon() {
        BotSummonFollower.register(BOT_ID, SPEC,
                mock(Summon.class, RETURNS_DEEP_STUBS), mock(MapleMap.class));
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(2, TimeUnit.SECONDS);
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
