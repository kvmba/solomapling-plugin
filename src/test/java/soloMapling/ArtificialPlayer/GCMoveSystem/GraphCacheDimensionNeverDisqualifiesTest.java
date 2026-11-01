package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.config.GameConfig;
import org.gms.dao.entity.GameConfigDO;
import org.gms.manager.ServerManager;
import org.gms.property.ServiceProperty;
import org.gms.server.maps.MapleMap;
import org.gms.service.ConfigService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

/**
 * REGRESSION for the "cold start makes every bot walk a step and stop" report.
 *
 * <p>A bot navigates against {@code peekBestGraph}, which is allowed to serve an approximate
 * profile for the map while the bot's own exact graph bakes. That reuse is load-bearing, not a
 * convenience: without it a bot with no graph of its own key falls back to the heuristic
 * steering path ({@code graphWarmupFallback} - no A*, no committed edge), which on screen is
 * "walk, stop, walk, stop".
 *
 * <p>The trap is a graph-cache dimension that DISQUALIFIES rather than reweights. When the blink
 * flag was added as a 4th key dimension it briefly did exactly that, and the effect was measured
 * on 922010100: 111-127 warmup ticks (~6s) per bot on a cold start, and zero once any graph for
 * the map could be reused. Hence "cold start is always broken; with a warm cache it is fine".
 *
 * <p>So the rule this pins is: a cached graph for the map is ALWAYS a usable fallback, whatever
 * the requesting profile. Whether the body may perform a blink is the executor's question
 * ({@code BotNavigationManager.tryExecuteTeleport}), not the cache's.
 */
public class GraphCacheDimensionNeverDisqualifiesTest {

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
        for (String n : new String[]{"messageSource", "logSource", "exceptionSource"}) {
            when(ctx.getBean(eq(n), eq(org.springframework.context.MessageSource.class))).thenReturn(ms);
        }
        var setter = ServerManager.class.getDeclaredMethod("setApplicationContext", ApplicationContext.class);
        setter.setAccessible(true);
        setter.invoke(new ServerManager(), ctx);
        GameConfig.add(cfg("server", "update_interval", "100"));
        org.gms.net.server.Server.getInstance();
        org.junit.jupiter.api.Assumptions.assumeTrue(wzAvailable(), "Map.wz not resolvable from here");
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

    /**
     * A blink-less body asking for a graph while only a mage's graph is baked for that map must
     * still get that graph. Returning null here is the cold-start "walk, stop" bug.
     */
    @Test
    void aBakedGraphStandsInForAnyProfile() {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(MAP);
        BotMovementProfile mage = new BotMovementProfile(105, 110, false, true);
        BotNavigationGraphProvider.rebuildGraph(map, mage);

        BotMovementProfile warrior = new BotMovementProfile(105, 110, false, false);
        assertNotNull(BotNavigationGraphProvider.peekBestGraph(map, warrior),
                "a cached graph must stand in while the exact-profile one bakes: returning null "
                        + "drops the bot into heuristic warmup steering (the walk-stop-walk report)");
    }

    /**
     * The same rule in the other direction, plus the preference that makes it safe: a mage must
     * still get a graph for a baked map, and when BOTH profiles are baked the exact one wins (so a
     * mage gets its blink edges and a warrior does not have to filter them out every tick).
     */
    @Test
    void theExactGraphStillWinsWhenPresent() {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(MAP);
        BotNavigationProfileGuard.rebuild(map, false);
        BotNavigationProfileGuard.rebuild(map, true);

        BotMovementProfile mage = new BotMovementProfile(105, 110, false, true);
        BotNavigationGraph forMage = BotNavigationGraphProvider.peekBestGraph(map, mage);
        assertNotNull(forMage, "a mage must never be left without a graph for a baked map");
        assertTrue(forMage.movementProfile.teleport(),
                "with the exact graph cached, the mage must be served ITS graph (the one carrying "
                        + "the blink edges), not a warrior's stand-in");
    }

    /** Local helper: rebuild both cache keys for the map (the ones every LPQ body uses). */
    private static final class BotNavigationProfileGuard {
        static void rebuild(MapleMap map, boolean teleport) {
            BotNavigationGraphProvider.rebuildGraph(map, new BotMovementProfile(105, 110, false, teleport));
        }
    }
}
