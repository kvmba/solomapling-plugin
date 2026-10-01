package soloMapling.ArtificialPlayer.BotTypes.Ludi;

import org.gms.config.GameConfig;
import org.gms.dao.entity.GameConfigDO;
import org.gms.manager.ServerManager;
import org.gms.property.ServiceProperty;
import org.gms.service.ConfigService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the invincible-scenery carve-out behind the stage-5 deadlock the post-work
 * delivery introduced: the main map's Block Golems (9300013) carry WZ invincible with
 * a 99999 HP pool, so an anyAlive "room quiet" test never turned true and the bots
 * held their passes forever. The quiet test must read GUARD_MOB as scenery.
 */
class LudiGuardSceneryTest {

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

    private static boolean isInvincibleScenery(org.gms.server.life.Monster m) throws Exception {
        java.lang.reflect.Method method = LudiStages.class.getDeclaredMethod("isInvincibleScenery",
                org.gms.server.life.Monster.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, m);
    }

    @Test
    void theStage5GuardIsScenery() throws Exception {
        // The guard's own WZ: invincible=1, maxHP 99999, level 200 - a 35-50 party's
        // damage pool cannot end it, so it must never count as the room's work. The
        // LifeFactory WZ load needs the host's provider stack, so the HP/invincible half
        // of the claim is read straight from the XML the server parses.
        org.gms.server.life.Monster guard = mock(org.gms.server.life.Monster.class, RETURNS_DEEP_STUBS);
        when(guard.getId()).thenReturn(LudiPqData.GUARD_MOB);
        assertTrue(isInvincibleScenery(guard),
                "the stage-5 guard is scenery; counting it as work deadlocked the delivery");
        String wz = java.nio.file.Files.readString(
                java.nio.file.Path.of("wz/Mob.wz/" + LudiPqData.GUARD_MOB + ".img.xml"));
        assertTrue(wz.contains("<int name=\"maxHP\" value=\"99999\"/>")
                        && wz.contains("<int name=\"invincible\" value=\"1\"/>"),
                "the guard's WZ HP pool + invincible flag are why it never dies to a 35-50 party");
    }

    @Test
    void ordinaryPassCarriersAreNotScenery() throws Exception {
        org.gms.server.life.Monster blocktopus = mock(org.gms.server.life.Monster.class, RETURNS_DEEP_STUBS);
        when(blocktopus.getId()).thenReturn(9300007); // the stage-3 crate spawn
        assertTrue(!isInvincibleScenery(blocktopus),
                "a pass carrier the party must kill is work, not scenery");
        assertEquals(LudiPqData.GUARD_MOB, 9300013);
    }
}
