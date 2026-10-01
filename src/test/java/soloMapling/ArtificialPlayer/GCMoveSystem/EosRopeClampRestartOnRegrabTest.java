package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.config.GameConfig;
import org.gms.dao.entity.GameConfigDO;
import org.gms.manager.ServerManager;
import org.gms.property.ServiceProperty;
import org.gms.service.ConfigService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 修复回归：topClampSinceMs 必须在 attachToRope（重抓绳）时清零。
 * 场景：钳制 2s -> 释放下落 -> 抓住下方另一条绳 -> 攀爬 3.3s 到顶。
 * 若时钟不重启，重抓后第一次触碰绳顶就立即释放（now - stale >= 2500 恒真），
 * 长绳上形成爬->摔循环 —— 即 resolveClimbBoundary 注释警告要防的振荡。
 */
class EosRopeClampRestartOnRegrabTest {

    @BeforeAll
    static void stubHostEnvironment() throws Exception {
        ApplicationContext ctx = mock(ApplicationContext.class, RETURNS_DEEP_STUBS);
        ConfigService configService = mock(ConfigService.class);
        when(configService.loadGameConfigs()).thenReturn(java.util.List.<GameConfigDO>of());
        when(ctx.getBean(ConfigService.class)).thenReturn(configService);
        ServiceProperty props = mock(ServiceProperty.class);
        when(props.getLanguage()).thenReturn("zh-CN");
        when(ctx.getBean(ServiceProperty.class)).thenReturn(props);
        var setter = ServerManager.class.getDeclaredMethod("setApplicationContext", ApplicationContext.class);
        setter.setAccessible(true);
        setter.invoke(new ServerManager(), ctx);
        GameConfig.add(config("server", "update_interval", "100"));
        org.gms.net.server.Server.getInstance();
    }

    private static GameConfigDO config(String type, String key, String value) {
        GameConfigDO d = new GameConfigDO();
        d.setConfigType(type);
        d.setConfigSubType("0");
        d.setConfigCode(key);
        d.setConfigValue(value);
        d.setConfigClazz("java.lang.Long");
        return d;
    }

    @Test
    void regrabbingARopeRestartsTheClampClock() throws Exception {
        org.gms.server.maps.MapleMap map = BotNavigationMapLoader.loadMapGeometry(221020100);
        org.gms.client.Character bot = mock(org.gms.client.Character.class, RETURNS_DEEP_STUBS);
        when(bot.getMap()).thenReturn(map);
        when(bot.getPosition()).thenReturn(new Point(0, 0));

        BotMovementState st = new BotMovementState(bot, null);
        org.gms.server.maps.Rope rope = map.getRopes().get(0);

        // 模拟一次早已过期的钳制时钟（陈旧值，远超 TOP_CLAMP_RELEASE_MS），
        // 模拟"钳制->释放->落回并重抓"之后的状态。
        st.topClampSinceMs = System.currentTimeMillis() - 60_000;

        java.lang.reflect.Method attach = BotPhysicsEngine.class.getDeclaredMethod(
                "attachToRope", BotMovementState.class,
                org.gms.client.Character.class, org.gms.server.maps.Rope.class, int.class);
        attach.setAccessible(true);
        attach.invoke(null, st, bot, rope, 500);

        assertEquals(0L, st.topClampSinceMs,
                "re-grabbing a rope must restart the top-clamp clock");
        assertTrue(st.climbing, "attachToRope must leave the bot climbing");
    }
}
