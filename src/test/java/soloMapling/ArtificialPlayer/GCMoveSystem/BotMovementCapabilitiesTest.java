package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.client.Character;
import org.gms.client.Job;
import org.gms.config.GameConfig;
import org.gms.dao.entity.GameConfigDO;
import org.gms.manager.ServerManager;
import org.gms.property.ServiceProperty;
import org.gms.service.ConfigService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import soloMapling.ArtificialPlayer.BotGrindSystem.FlashJumpTiers;
import soloMapling.ArtificialPlayer.BotGrindSystem.MovementStyle;
import soloMapling.ArtificialPlayer.BotGrindSystem.MovementStylePolicy;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the class-ability table the dead-pit verdict keys on against the two policies it mirrors
 * (MovementStylePolicy's teleport mages, FlashJumpTiers' dash tiers), so the guard's notion of
 * "this bot's player can leave" cannot drift from what the bot actually executes.
 */
class BotMovementCapabilitiesTest {

    @BeforeAll
    static void stubHostEnvironment() throws Exception {
        ApplicationContext ctx = mock(ApplicationContext.class, RETURNS_DEEP_STUBS);
        ConfigService configService = mock(ConfigService.class);
        when(configService.loadGameConfigs()).thenReturn(List.<GameConfigDO>of());
        when(ctx.getBean(ConfigService.class)).thenReturn(configService);
        ServiceProperty props = mock(ServiceProperty.class);
        when(props.getLanguage()).thenReturn("zh-CN");
        when(ctx.getBean(ServiceProperty.class)).thenReturn(props);
        // Job's static initializer resolves its names through I18nUtil's MessageSource; mocking the
        // final Job class here requires it to initialize.
        org.springframework.context.MessageSource messageSource =
                mock(org.springframework.context.MessageSource.class);
        when(messageSource.getMessage(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(Object[].class),
                org.mockito.ArgumentMatchers.any(java.util.Locale.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(ctx.getBean(org.mockito.ArgumentMatchers.eq("messageSource"),
                org.mockito.ArgumentMatchers.eq(org.springframework.context.MessageSource.class)))
                .thenReturn(messageSource);
        when(ctx.getBean(org.mockito.ArgumentMatchers.eq("logSource"),
                org.mockito.ArgumentMatchers.eq(org.springframework.context.MessageSource.class)))
                .thenReturn(messageSource);
        when(ctx.getBean(org.mockito.ArgumentMatchers.eq("exceptionSource"),
                org.mockito.ArgumentMatchers.eq(org.springframework.context.MessageSource.class)))
                .thenReturn(messageSource);
        var setter = ServerManager.class.getDeclaredMethod("setApplicationContext", ApplicationContext.class);
        setter.setAccessible(true);
        setter.invoke(new ServerManager(), ctx);
        GameConfig.add(config("server", "update_interval", "100"));
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

    private static Character bot(int jobId, int level) {
        Character bot = mock(Character.class);
        Job job = mock(Job.class);
        when(job.getId()).thenReturn(jobId);
        when(bot.getJob()).thenReturn(job);
        when(bot.getLevel()).thenReturn(level);
        return bot;
    }

    @Test
    void teleportCapabilityMatchesTheMovementPolicy() {
        for (int jobId : new int[]{210, 211, 212, 220, 221, 222, 230, 231, 232}) {
            Character bot = bot(jobId, 60);
            assertTrue(BotMovementCapabilities.of(bot).teleport(), "mage " + jobId + " has Teleport");
            assertEquals(MovementStyle.TELEPORT, MovementStylePolicy.forBot(bot),
                    "capability and movement style must agree for " + jobId);
        }
        for (int jobId : new int[]{200, 100, 110, 400, 410, 300, 310, 500, 520, 411, 412}) {
            Character bot = bot(jobId, 60);
            assertFalse(BotMovementCapabilities.of(bot).teleport(), "job " + jobId + " has no Teleport");
            assertFalse(MovementStylePolicy.forBot(bot) == MovementStyle.TELEPORT,
                    "movement policy must not grant Teleport to " + jobId);
        }
    }

    @Test
    void flashJumpCapabilityMatchesTheTierTable() {
        for (int jobId : new int[]{411, 412}) {
            for (int level : new int[]{10, 70, 100, 160}) {
                Character bot = bot(jobId, level);
                assertEquals(FlashJumpTiers.capFor(bot), BotMovementCapabilities.of(bot).flashJumpScale(),
                        0.001f, "dash scale must match FlashJumpTiers for job " + jobId + " level " + level);
            }
        }
        assertEquals(1f, BotMovementCapabilities.of(bot(412, 60)).flashJumpScale(), 0.001f);
        assertEquals(1f, BotMovementCapabilities.of(bot(411, 100)).flashJumpScale(), 0.001f);
        assertEquals(0.75f, BotMovementCapabilities.of(bot(411, 70)).flashJumpScale(), 0.001f);
        for (int jobId : new int[]{410, 420, 421, 422, 400, 210, 100, 520}) {
            assertEquals(0f, BotMovementCapabilities.of(bot(jobId, 60)).flashJumpScale(), 0.001f,
                    "job " + jobId + " has no Flash Jump");
        }
    }

    @Test
    void missingCharacterOrJobIsNone() {
        assertSame(BotMovementCapabilities.NONE, BotMovementCapabilities.of(null));
        Character noJob = mock(Character.class);
        when(noJob.getJob()).thenReturn(null);
        assertSame(BotMovementCapabilities.NONE, BotMovementCapabilities.of(noJob));
    }
}
