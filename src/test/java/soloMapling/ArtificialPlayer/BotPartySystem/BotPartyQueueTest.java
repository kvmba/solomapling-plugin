package soloMapling.ArtificialPlayer.BotPartySystem;

import org.gms.client.Character;
import org.gms.config.GameConfig;
import org.gms.dao.entity.GameConfigDO;
import org.gms.manager.ServerManager;
import org.gms.property.ServiceProperty;
import org.gms.service.ConfigService;
import org.gms.extension.event.PartyInviteEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.context.MessageSource;

import java.util.List;
import java.util.concurrent.TimeUnit;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotSM;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BotPartyQueueTest {

    /**
     * The host's static initializers (Character's, Job's) reach into Spring for config and i18n,
     * so a Character cannot even be mocked until that context exists. Same stub
     * BotMovementCapabilitiesTest uses for the same reason.
     */
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
    void lastInviteWins() {
        BotPartyQueue queue = BotPartyQueue.getInstance();
        Character bot = bot("Alpha");
        Character first = bot("First");
        Character second = bot("Second");

        queue.addPartyInvite(bot, first, 100);
        queue.addPartyInvite(bot, second, 200);

        BotPartyQueue.PartyInviteEntry entry = queue.getPartyInvite(bot);
        assertNotNull(entry);
        assertSame(second, entry.getInviter());
        assertEquals(200, entry.getPartyId());
        queue.removePartyInvite(bot);
    }

    @Test
    void entryOlderThanTheHostsInviteWindowIsDropped() {
        // The host's InviteCoordinator drops an unanswered invite after ~3 minutes. The queue used
        // to keep its entry forever, so a bot answering a stale partyId got NOT_FOUND - the player
        // saw "accepted" and no party - and the entry went on authorising the same dead invite on
        // every later turn.
        BotPartyQueue queue = BotPartyQueue.getInstance();
        Character bot = bot("Stale");
        Character inviter = bot("Inviter");
        queue.addPartyInvite(bot, inviter, 300);
        assertNotNull(queue.getPartyInvite(bot), "a fresh entry answers");

        queue.addPartyInvite(bot, new BotPartyQueue.PartyInviteEntry(
                inviter, 300, System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(3) - 1));

        assertNull(queue.getPartyInvite(bot),
                "an entry older than the host's invite window must not answer");
        assertNull(queue.getPartyInvite(bot),
                "and the expired entry must be removed, not merely hidden");
    }

    @Test
    void entryInsideTheHostsInviteWindowStillAnswers() {
        BotPartyQueue queue = BotPartyQueue.getInstance();
        Character bot = bot("Edge");
        Character inviter = bot("Inviter");
        queue.addPartyInvite(bot, inviter, 500);

        queue.addPartyInvite(bot, new BotPartyQueue.PartyInviteEntry(
                inviter, 500, System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(3) + 1));

        BotPartyQueue.PartyInviteEntry entry = queue.getPartyInvite(bot);
        assertNotNull(entry, "one millisecond inside the window must still answer");
        assertEquals(500, entry.getPartyId());
        queue.removePartyInvite(bot);
    }

    @Test
    void pendingQueryExpiresInvitesWithoutAConsumerTick() {
        BotPartyQueue queue = BotPartyQueue.getInstance();
        Character bot = bot("Stopped");
        Character inviter = bot("Inviter");
        queue.addPartyInvite(bot, inviter, 600);
        assertTrue(queue.hasPendingInvite(bot));
        queue.addPartyInvite(bot, new BotPartyQueue.PartyInviteEntry(
                inviter, 600, System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(4)));
        assertFalse(queue.hasPendingInvite(bot));
        assertNull(queue.getPartyInvite(bot));
    }

    @Test
    void inviteBridgeDoesNotReinsertARemovedCharacter() throws Exception {
        BotPartyQueue queue = BotPartyQueue.getInstance();
        Character bot = bot("Removed");
        Character inviter = bot("Inviter");
        BotSM owner = mock(BotSM.class);
        when(owner.getChr()).thenReturn(bot);
        CharacterStorage.addActiveBot(bot.getId(), owner);
        var receive = BotPartyInviteBridge.class.getDeclaredMethod("onPartyInvite", PartyInviteEvent.class);
        receive.setAccessible(true);
        try {
            receive.invoke(null, new PartyInviteEvent(bot, inviter, 700));
            assertTrue(queue.hasPendingInvite(bot));
            CharacterStorage.removeActiveBot(bot.getId());
            queue.removePartyInvite(bot);
            receive.invoke(null, new PartyInviteEvent(bot, inviter, 700));
            assertFalse(queue.hasPendingInvite(bot));
        } finally {
            CharacterStorage.removeActiveBot(bot.getId());
            queue.removePartyInvite(bot);
        }
    }

    private static Character bot(String name) {
        Character character = mock(Character.class);
        when(character.getName()).thenReturn(name);
        when(character.getId()).thenReturn(Math.abs(name.hashCode()));
        return character;
    }
}
