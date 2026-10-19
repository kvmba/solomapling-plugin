package soloMapling.ArtificialPlayer.BotPartySystem;

import org.gms.client.Character;
import org.gms.config.GameConfig;
import org.gms.dao.entity.GameConfigDO;
import org.gms.manager.ServerManager;
import org.gms.net.server.coordinator.world.InviteCoordinator;
import org.gms.property.ServiceProperty;
import org.gms.service.ConfigService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.context.MessageSource;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The decline-cooldown drain must not wedge the invite path.
 *
 * <p>The coordinator holds ONE invite per target character ({@code invites.putIfAbsent}), so an
 * unanswered entry blocks every later invite - whoever sends it - until that entry is answered.
 * A drain that "keeps" a cooled-down queue entry (answers NONE, clears nothing) therefore locks
 * the whole path for the cooldown's duration: the declined player's re-invite AND every other
 * player's fresh invite both die at the host with "already handling another invitation", because
 * the entry can never be displaced (a new invite never reaches {@code addPartyInvite}) and nothing
 * in the NONE path answered the old one. The drain must REJECT - silently, so the player who was
 * already told "no" is not notice-bombed every tick - which releases the coordinator slot and the
 * queue entry in the same beat.
 */
class BotRecruitManagerCooldownDrainTest {

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

    @SuppressWarnings("unchecked")
    private static Map<Long, Long> declinedUntil() throws Exception {
        Field f = BotRecruitManager.class.getDeclaredField("DECLINED_UNTIL");
        f.setAccessible(true);
        return (Map<Long, Long>) f.get(null);
    }

    private static long pairKey(int botId, int playerId) throws Exception {
        Method m = BotRecruitManager.class.getDeclaredMethod("pairKey", int.class, int.class);
        m.setAccessible(true);
        return (Long) m.invoke(null, botId, playerId);
    }

    @Test
    void aCooledDownInviteIsRejectedSilentlyAndTheQueueIsReleased() throws Exception {
        Map<Long, Long> map = declinedUntil();
        map.clear();
        try {
            map.put(pairKey(30, 40), System.currentTimeMillis() + 60_000);
            assertTrue(BotRecruitManager.declinedByThisBot(30, 40), "precondition: pair on cooldown");

            Character bot = bot("CoolBot", 30);
            Character player = bot("CoolPlayer", 40);
            // The REAL coordinator records the invite, exactly as PartyOperationHandler case 4 did
            // before publishInvite: putIfAbsent now holds bot 30's slot against every later invite.
            assertTrue(InviteCoordinator.createInvite(InviteCoordinator.InviteType.PARTY,
                    player, 900, 30), "precondition: the host accepted the invite");
            BotPartyQueue queue = BotPartyQueue.getInstance();
            queue.addPartyInvite(bot, player, 900);

            // The bot is NOT armed for anyone: the drain reaches the cooldown branch.
            BotRecruitManager.InvitePoll poll = BotRecruitManager.pollInvites(bot);
            assertSame(BotRecruitManager.InvitePoll.REJECTED, poll,
                    "a cooled-down invite must still be ANSWERED - the coordinator slot depends on it");
            assertNull(queue.getPartyInvite(bot),
                    "the queue entry must be released, or the drain re-answers it every tick");

            // The coordinator entry is gone: a fresh invite from ANYONE lands again instead of
            // dying at putIfAbsent with "already handling another invitation".
            assertFalse(InviteCoordinator.hasInvite(InviteCoordinator.InviteType.PARTY, 30),
                    "declining must release the coordinator's per-target invite slot");

            // ...and the player who was already refused is NOT re-notified every tick.
            verify(player, never()).sendPacket(any());
        } finally {
            map.clear();
            BotPartyQueue.getInstance().removePartyInvite(bot("CoolBot", 30));
            InviteCoordinator.removePlayerIncomingInvites(30);
        }
    }

    @Test
    void anUncooledInviteIsStillAnsweredWithTheDeclinedNotice() throws Exception {
        Map<Long, Long> map = declinedUntil();
        map.clear();
        try {
            assertFalse(BotRecruitManager.declinedByThisBot(31, 41), "precondition: no cooldown");

            Character bot = bot("FreshBot", 31);
            Character player = bot("FreshPlayer", 41);
            assertTrue(InviteCoordinator.createInvite(InviteCoordinator.InviteType.PARTY,
                    player, 901, 31), "precondition: the host accepted the invite");
            BotPartyQueue queue = BotPartyQueue.getInstance();
            queue.addPartyInvite(bot, player, 901);

            BotRecruitManager.InvitePoll poll = BotRecruitManager.pollInvites(bot);
            assertSame(BotRecruitManager.InvitePoll.REJECTED, poll);
            assertNull(queue.getPartyInvite(bot));
            assertFalse(InviteCoordinator.hasInvite(InviteCoordinator.InviteType.PARTY, 31));
        } finally {
            map.clear();
            BotPartyQueue.getInstance().removePartyInvite(bot("FreshBot", 31));
            InviteCoordinator.removePlayerIncomingInvites(31);
        }
    }

    private static Character bot(String name, int id) {
        // The inviter reaches answerInvite's DENIED notice through the coordinator's inviteFrom
        // table; its sendPacket is a mock-absorbed no-op, so the "notice" side needs no further
        // stubbing and the two tests above pin everything the drain itself decides.
        Character character = mock(Character.class, RETURNS_DEEP_STUBS);
        when(character.getName()).thenReturn(name);
        when(character.getId()).thenReturn(id);
        when(character.isLoggedInWorld()).thenReturn(true);
        return character;
    }
}
