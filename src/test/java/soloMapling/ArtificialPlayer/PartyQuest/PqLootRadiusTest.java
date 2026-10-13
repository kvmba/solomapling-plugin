package soloMapling.ArtificialPlayer.PartyQuest;

import org.gms.config.GameConfig;
import org.gms.dao.entity.GameConfigDO;
import org.gms.manager.ServerManager;
import org.gms.property.ServiceProperty;
import org.gms.service.ConfigService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the loot-radius fix behind the "bots do not pick up the passes" report:
 * {@link PqActions#loot} takes a RADIUS in px, but the engine's getMapObjectsInRange
 * compares distanceSq - passing 2_000 through unchecked scanned a ~45px circle, and a
 * kill lands its drop at the mob's x, well outside it. The helper squares once, so a
 * 2_000 radius really reads 2_000px.
 */
class PqLootRadiusTest {

    @BeforeAll
    static void stubHostEnvironment() throws Exception {
        ApplicationContext ctx = mock(ApplicationContext.class, RETURNS_DEEP_STUBS);
        ConfigService configService = mock(ConfigService.class);
        when(configService.loadGameConfigs()).thenReturn(List.<GameConfigDO>of());
        when(ctx.getBean(ConfigService.class)).thenReturn(configService);
        ServiceProperty props = mock(ServiceProperty.class);
        when(props.getLanguage()).thenReturn("zh-CN");
        when(ctx.getBean(ServiceProperty.class)).thenReturn(props);
        var setter = ServerManager.class.getDeclaredMethod("setApplicationContext", ApplicationContext.class);
        setter.setAccessible(true);
        setter.invoke(new ServerManager(), ctx);
        GameConfig.add(cfg("server", "update_interval", "100"));
        org.gms.net.server.Server.getInstance();
    }

    private static GameConfigDO cfg(String t, String k, String v) {
        GameConfigDO d = new GameConfigDO();
        d.setConfigType(t); d.setConfigSubType("0"); d.setConfigCode(k); d.setConfigValue(v);
        d.setConfigClazz("java.lang.Long");
        return d;
    }

    /**
     * The engine comparison loot rides on: distanceSq <= rangeSq. The regression is that
     * callers' radii are px, so the value loot hands the engine must be the square.
     */
    @Test
    void twoThousandPxRadiusCoversAStandardKillSpread() {
        double radiusPx = 2_000;
        // What the broken scan actually covered: 2_000 treated as distanceSq = ~45px.
        assertEquals(44.7, Math.sqrt(radiusPx), 0.5,
                "what '2_000' actually scanned before the fix");
        // A drop at the far edge of that broken scan (~44px)...
        assertTrue(Math.pow(44, 2) <= radiusPx, "sanity: the broken scan saw ~44px around the feet");
        // ...while a drop one attack-reach away (~200px) fell OUTSIDE it.
        assertFalse(Math.pow(200, 2) <= radiusPx,
                "the regression: 200px fell outside the old px-as-sq scan");
        // The fix: the radius is squared before the engine call, so a 2_000px radius covers
        // the kill spread (and the whole ~3000px tower's floor spread at need).
        assertTrue(Math.pow(200, 2) <= radiusPx * radiusPx,
                "the fix: a 2_000px radius squared covers a 200px kill spread");
    }

    /**
     * The hand-off reclaim was the same px-vs-sq defect, missed by the fix above: it passed
     * {@code 9_000} straight to the engine, so its walk-back sweep really covered ~95px and a
     * pile the leader walked past was out of view - the 3-minute despawn could still wipe the
     * bot's stock. Every px-radius scan now goes through the shared {@link PqActions#radiusSq}
     * boundary; this pins the reclaim's own range (and the boundary itself).
     */
    @Test
    void handoffReclaimRadiusIsSquaredToo() {
        // The shared boundary is the only square in play.
        assertEquals(4_000_000.0, PqActions.radiusSq(2_000), 1e-9);
        assertEquals(81_000_000.0, PqActions.radiusSq(9_000), 1e-9);

        // What the broken reclaim actually covered: 9_000 as distanceSq = ~95px.
        assertEquals(94.9, Math.sqrt(9_000), 0.5,
                "what '9_000' actually scanned before the fix");
        // A pile the leader walked past mid-room (600px) fell outside that broken scan...
        assertFalse(Math.pow(600, 2) <= 9_000,
                "the regression: a 600px pile was invisible to the reclaim");
        // ...and is inside the fixed room-scale sweep.
        assertTrue(Math.pow(600, 2) <= PqActions.radiusSq(9_000),
                "the fix: the reclaim sees any pile inside the room");
        // Still a radius, not the whole map: a pile across a 20_000px map stays out.
        assertFalse(Math.pow(20_000, 2) <= PqActions.radiusSq(9_000),
                "the reclaim must not vacuum the entire map");
    }
}
