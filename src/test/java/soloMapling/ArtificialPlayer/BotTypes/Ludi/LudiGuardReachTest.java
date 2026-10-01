package soloMapling.ArtificialPlayer.BotTypes.Ludi;

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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the guard-reach geometry behind the stage-5 deadlock the post-work delivery
 * exposed: the guards stand on the ground floor (y≈58) while the delivery post (the
 * stage NPC, y≈-215) sits ~273px above them - untouchable, so the bot must keep
 * delivering there. The old tower-wide seek box (dy<=3200) read the guards as "near"
 * from the post and short-circuited the whole case-5 branch forever.
 *
 * <p>{@code guardCanReach} is the touch box (120px x / 150px y): same-ledge proximity,
 * not "somewhere in the vertical room".
 */
class LudiGuardReachTest {

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

    /** A live guard standing where the WZ seats them: x=162, y=54 (ground floor). */
    private static org.gms.server.life.Monster guardAt(int x, int y) {
        org.gms.server.life.Monster guard = mock(org.gms.server.life.Monster.class, RETURNS_DEEP_STUBS);
        when(guard.isAlive()).thenReturn(true);
        when(guard.getId()).thenReturn(LudiPqData.GUARD_MOB);
        when(guard.getPosition()).thenReturn(new Point(x, y));
        return guard;
    }

    private static org.gms.client.Character botAt(int x, int y) {
        org.gms.server.life.Monster guard = guardAt(162, 54);
        org.gms.server.maps.MapleMap map = mock(org.gms.server.maps.MapleMap.class, RETURNS_DEEP_STUBS);
        when(map.getAllMonsters()).thenReturn(List.of(guard));
        org.gms.client.Character bot = mock(org.gms.client.Character.class, RETURNS_DEEP_STUBS);
        when(bot.getPosition()).thenReturn(new Point(x, y));
        when(bot.getMap()).thenReturn(map);
        return bot;
    }

    @Test
    void guardOnTheSameFloorIsReachable() {
        // A bot down on the guards' ground floor, 100px away: inside the touch box.
        assertTrue(LudiStages.guardCanReach(botAt(62, 54)),
                "same-ledge proximity is the hide-or-die case");
    }

    @Test
    void theDeliveryPostIsOutOfGuardReach() {
        // The live geometry: the stage NPC post (58,-215) is ~273px ABOVE the guards'
        // floor. The old seek box counted this as "guard near"; the touch box must not.
        assertFalse(LudiStages.guardCanReach(botAt(58, -215)),
                "the post-work delivery post is untouchable - delivering there must not be short-circuited");
    }

    @Test
    void theTowerWideSeekBoxStillCountsTheGuards() {
        // The combat-sweep gate keeps the wide box: no swings anywhere in the room while
        // the guards are up (a swing breaks hide and starts a fight the bot cannot win).
        assertTrue(LudiStages.nearbyGuardCount(botAt(58, -215)) > 0,
                "the sweep gate stays tower-wide by design");
    }
}
