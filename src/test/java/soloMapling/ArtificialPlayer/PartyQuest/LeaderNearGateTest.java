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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the leader-proximity gate behind the stage-1 oscillation fix: the delivery walk toward
 * a faraway leader is what yanked fighting bots off the LPQ stage-1 climb every time they
 * pocketed a pass (kill -> loot -> walk to the leader -> seek again -> climb -> loot ...),
 * so gatherPasses now delivers only when the leader is ALREADY near, or once the room is
 * quiet. leaderNear is that gate; the same/different-map and null edges decide the rest.
 */
class LeaderNearGateTest {

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

    private static org.gms.client.Character chrAt(Point p, int mapId) {
        org.gms.client.Character c = mock(org.gms.client.Character.class, RETURNS_DEEP_STUBS);
        when(c.getMapId()).thenReturn(mapId);
        when(c.getPosition()).thenReturn(p);
        return c;
    }

    @Test
    void leaderWithin400pxIsNear() {
        org.gms.client.Character bot = chrAt(new Point(88, -450), 922010100);
        org.gms.client.Character leader = chrAt(new Point(95, -450), 922010100);
        assertTrue(PqActions.leaderNear(bot, leader));
    }

    @Test
    void leader600pxBelowOnTheTowerIsNotNear() {
        // The live geometry: leader on the ground floor (y=130), bot on the -450 fight row.
        org.gms.client.Character bot = chrAt(new Point(88, -450), 922010100);
        org.gms.client.Character leader = chrAt(new Point(0, 130), 922010100);
        assertFalse(PqActions.leaderNear(bot, leader),
                "a ground-floor leader is out of hand-off range from the mid-tower fight");
    }

    @Test
    void leaderOnAnotherMapIsNeverNear() {
        org.gms.client.Character bot = chrAt(new Point(88, -450), 922010100);
        org.gms.client.Character leader = chrAt(new Point(88, -450), 922010200);
        assertFalse(PqActions.leaderNear(bot, leader));
    }

    @Test
    void nullsAndSelfAreNotNear() {
        org.gms.client.Character bot = chrAt(new Point(0, 0), 922010100);
        assertFalse(PqActions.leaderNear(null, bot));
        assertFalse(PqActions.leaderNear(bot, null));
        assertFalse(PqActions.leaderNear(bot, bot), "the bot is not its own delivery target");
    }
}
