package soloMapling.ArtificialPlayer.PartyQuest;

import org.gms.client.Character;
import org.gms.client.inventory.Item;
import org.gms.config.GameConfig;
import org.gms.dao.entity.GameConfigDO;
import org.gms.manager.ServerManager;
import org.gms.property.ServiceProperty;
import org.gms.server.maps.MapItem;
import org.gms.service.ConfigService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import soloMapling.ArtificialPlayer.BotCommandsPack.DropCommands;

import java.awt.Point;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The permanent-owner gate reads under the item lock. The host writes the flag under that lock
 * ({@code MapItem.setPermanentOwnerLocked}) and reads it under that lock on the pickup path
 * ({@code Character.pickupItem} -> {@code canBePickedBy}), so the plugin's entitlement gates must
 * read it the same way - one seam ({@code DropCommands.isPermanentOwnerLocked}) serving
 * {@code botCanLoot}, {@code isFloorLoot} and the hand-off reclaim. These cases pin the seam and
 * both gate outcomes it feeds: the leader's addressed hand-off must never enter a bot's sweep,
 * and an ordinary drop must remain sweepable.
 */
class PqOwnershipLockedReadTest {

    private static final int PASS = 4001022;

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

    @Test
    void theLockedReadReflectsTheFlag() {
        MapItem plain = drop();
        assertFalse(DropCommands.isPermanentOwnerLocked(plain));

        markAddressed(plain);
        assertTrue(DropCommands.isPermanentOwnerLocked(plain));
    }

    @Test
    void anAddressedPileIsNeitherLootNorFloorLoot() {
        Character bot = mock(Character.class, RETURNS_DEEP_STUBS);
        when(bot.getId()).thenReturn(777);

        MapItem plain = drop();
        assertTrue(DropCommands.botCanLoot(bot, plain), "an ordinary free-for-all drop is sweepable");
        assertTrue(PqActions.isFloorLoot(plain));

        MapItem addressed = drop();
        markAddressed(addressed);
        assertFalse(DropCommands.botCanLoot(bot, addressed),
                "the leader's addressed hand-off must never enter a bot's sweep");
        assertFalse(PqActions.isFloorLoot(addressed),
                "a qty-1 addressed pile is not floor loot (the pass ping-pong guard)");
    }

    /**
     * The read really takes the item lock: an unlocked read would return while another thread
     * holds the lock, which is exactly the torn/stale read the host's field contract forbids
     * (the write and the pickup path both hold it).
     */
    @Test
    void theReadWaitsForTheItemLock() throws Exception {
        MapItem drop = drop();
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            drop.lockItem();
            try {
                held.countDown();
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                drop.unlockItem();
            }
        }, "lock-holder");
        holder.start();
        assertTrue(held.await(2, TimeUnit.SECONDS), "the holder must take the item lock");

        AtomicBoolean returned = new AtomicBoolean();
        Thread reader = new Thread(() -> {
            DropCommands.isPermanentOwnerLocked(drop);
            returned.set(true);
        }, "locked-reader");
        reader.start();
        Thread.sleep(100);
        assertFalse(returned.get(), "the read must wait for the item lock, not race the writer");
        release.countDown();
        reader.join(2_000);
        holder.join(2_000);
        assertTrue(returned.get());
    }

    /** A single pass dropped by a third party, under the ordinary owner window. */
    private static MapItem drop() {
        Character owner = mock(Character.class, RETURNS_DEEP_STUBS);
        when(owner.getId()).thenReturn(4242);
        return new MapItem(new Item(PASS, (short) 0, (short) 1), new Point(0, 0),
                null, owner, null, (byte) 2, false);
    }

    /** Mark addressed the way the host does: under the item lock (setPermanentOwnerLocked). */
    private static void markAddressed(MapItem drop) {
        drop.lockItem();
        try {
            drop.setPermanentOwnerLocked(true);
        } finally {
            drop.unlockItem();
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
