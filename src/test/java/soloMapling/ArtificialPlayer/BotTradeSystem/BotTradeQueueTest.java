package soloMapling.ArtificialPlayer.BotTradeSystem;

import org.gms.client.Character;
import org.gms.config.GameConfig;
import org.gms.dao.entity.GameConfigDO;
import org.gms.manager.ServerManager;
import org.gms.property.ServiceProperty;
import org.gms.service.ConfigService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.context.MessageSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The queue must key on the character ID, not the Character instance.
 *
 * <p>{@code org.gms.client.Character} has no equals/hashCode, so an object key makes an entry
 * invisible to any other instance of the same character - exactly what happens to a companion
 * re-loaded by {@code BotGeneration.loadPersistentBot} - and leaves the stale entry (and the
 * inviter Character it points at) in the static map forever.
 */
class BotTradeQueueTest {

    /** Same host-environment stub BotPartyQueueTest uses: Character's static init reads Spring. */
    @BeforeAll
    static void stubHostEnvironment() throws Exception {
        ApplicationContext ctx = mock(ApplicationContext.class, RETURNS_DEEP_STUBS);
        ConfigService configService = mock(ConfigService.class);
        when(configService.loadGameConfigs()).thenReturn(List.<GameConfigDO>of());
        when(ctx.getBean(ConfigService.class)).thenReturn(configService);
        ServiceProperty props = mock(ServiceProperty.class);
        when(props.getLanguage()).thenReturn("zh-CN");
        when(ctx.getBean(ServiceProperty.class)).thenReturn(props);
        MessageSource messageSource = mock(MessageSource.class);
        when(messageSource.getMessage(anyString(), any(Object[].class), any(java.util.Locale.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(ctx.getBean(eq("messageSource"), eq(MessageSource.class))).thenReturn(messageSource);
        when(ctx.getBean(eq("logSource"), eq(MessageSource.class))).thenReturn(messageSource);
        when(ctx.getBean(eq("exceptionSource"), eq(MessageSource.class))).thenReturn(messageSource);
        var setter = ServerManager.class.getDeclaredMethod("setApplicationContext", ApplicationContext.class);
        setter.setAccessible(true);
        setter.invoke(new ServerManager(), ctx);
    }

    @Test
    void entrySurvivesAReloadedCharacterWithTheSameId() {
        BotTradeQueue queue = BotTradeQueue.getInstance();
        Character beforeReload = bot(7001, "Companion");
        Character inviter = bot(9001, "Player");
        queue.addTradeRequest(beforeReload, inviter);

        Character afterReload = bot(7001, "Companion");
        assertTrue(queue.hasPendingTrades(afterReload), "the re-loaded instance must see the invite");
        assertSame(inviter, queue.getTradeRequest(afterReload));
        queue.forgetBot(afterReload.getId());
    }
    @Test
    void forgetBotDropsTheEntryAndItsInviter() {
        BotTradeQueue queue = BotTradeQueue.getInstance();
        Character bot = bot(7002, "Companion");
        queue.addTradeRequest(bot, bot(9002, "Player"));

        queue.forgetBot(bot.getId());

        assertFalse(queue.hasPendingTrades(bot));
        assertNull(queue.getTradeRequest(bot));
    }

    private static Character bot(int id, String name) {
        Character character = mock(Character.class);
        when(character.getId()).thenReturn(id);
        when(character.getName()).thenReturn(name);
        return character;
    }
}
