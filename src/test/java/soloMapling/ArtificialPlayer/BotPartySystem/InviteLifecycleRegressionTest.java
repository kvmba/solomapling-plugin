package soloMapling.ArtificialPlayer.BotPartySystem;

import org.gms.client.Character;
import org.gms.net.server.coordinator.world.InviteCoordinator;
import org.gms.net.server.coordinator.world.InviteCoordinator.InviteType;
import org.junit.BeforeClass;
import org.junit.Test;
import soloMapling.ArtificialPlayer.GCMoveSystem.NavigationReachabilityTest;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class InviteLifecycleRegressionTest {
    @BeforeClass
    public static void initialize() throws Exception {
        NavigationReachabilityTest.initializeCharacterServices();
    }

    private static Character character(int id) {
        Character character = mock(Character.class);
        when(character.getId()).thenReturn(id);
        when(character.isLoggedInWorld()).thenReturn(true);
        return character;
    }

    @Test
    public void pluginRetainsAHostInvitationAfterSixSweepsThenReleasesIt() {
        Character bot = character(992001), inviter = character(992002);
        BotPartyQueue queue = BotPartyQueue.getInstance();
        try (var wake = mockStatic(BotRecruitManager.class)) {
            queue.addPartyInvite(bot, new BotPartyQueue.PartyInviteEntry(inviter, 12345,
                    System.currentTimeMillis() - 185_000));
            assertTrue(InviteCoordinator.createInvite(InviteType.PARTY, inviter, 12345, bot.getId()));
            for (int i = 0; i < 6; i++) InviteCoordinator.runTimeoutSchedule();
            assertTrue(InviteCoordinator.hasInvite(InviteType.PARTY, bot.getId()));
            assertTrue(queue.hasPendingInvite(bot));
            assertTrue(BotPartyCommands.botRejectPartyInviteSilently(bot));
            assertFalse(InviteCoordinator.hasInvite(InviteType.PARTY, bot.getId()));
            assertTrue(InviteCoordinator.createInvite(InviteType.PARTY, inviter, 12345, bot.getId()));
            InviteCoordinator.removeInvite(InviteType.PARTY, bot.getId());
            queue.addPartyInvite(bot, new BotPartyQueue.PartyInviteEntry(inviter, 12345,
                    System.currentTimeMillis() - 185_000));
            assertFalse(queue.hasPendingInvite(bot));
        } finally {
            InviteCoordinator.removeInvite(InviteType.PARTY, bot.getId());
            queue.removePartyInvite(bot);
        }
    }

    @SuppressWarnings("unchecked")
    private static void arm(Character bot, Character inviter, long expires) throws Exception {
        Class<?> type = Class.forName(BotRecruitManager.class.getName() + "$Armed");
        var constructor = type.getDeclaredConstructor(int.class, long.class);
        constructor.setAccessible(true);
        Field field = BotRecruitManager.class.getDeclaredField("ARMED");
        field.setAccessible(true);
        ((Map<Integer, Object>) field.get(null)).put(bot.getId(), constructor.newInstance(inviter.getId(), expires));
    }

    @Test
    public void consentIsJudgedAtInviteArrivalNotAtDelayedDrain() throws Exception {
        Character bot = character(992003), inviter = character(992004);
        BotPartyQueue queue = BotPartyQueue.getInstance();
        long expires = System.currentTimeMillis() - 1000;
        arm(bot, inviter, expires);
        // Do not mock BotRecruitManager: the authorization path is the code under test.
        try (var commands = mockStatic(BotPartyCommands.class)) {
            commands.when(() -> BotPartyCommands.botAcceptPartyInvite(bot, inviter.getId())).thenReturn(true);
            putInvite(queue, bot, new BotPartyQueue.PartyInviteEntry(inviter, 12346, expires - 1000));
            assertEquals(BotRecruitManager.InvitePoll.JOINED, BotRecruitManager.pollInvites(bot));
            arm(bot, inviter, expires);
            var late = new BotPartyQueue.PartyInviteEntry(inviter, 12346, expires + 500);
            putInvite(queue, bot, late);
            BotRecruitManager.pollInvites(bot);
            commands.verify(() -> BotPartyCommands.botAcceptPartyInvite(bot, inviter.getId()), times(1));
            commands.verify(() -> BotPartyCommands.botRejectPartyInvite(bot, late, true));
        } finally {
            queue.removePartyInvite(bot);
            BotRecruitManager.clearHandoffs(bot.getId());
        }
    }

    @SuppressWarnings("unchecked")
    private static void putInvite(BotPartyQueue queue, Character bot, BotPartyQueue.PartyInviteEntry entry) throws Exception {
        Field field = BotPartyQueue.class.getDeclaredField("queues");
        field.setAccessible(true);
        ((Map<Character, BotPartyQueue.PartyInviteEntry>) field.get(queue)).put(bot, entry);
    }

    @SuppressWarnings("unchecked")
    @Test
    public void anOldConsentCannotOverrideTheCurrentDeclineCooldown() throws Exception {
        Character bot = character(992005), inviter = character(992006);
        arm(bot, inviter, System.currentTimeMillis() + 10000);
        Field field = BotRecruitManager.class.getDeclaredField("DECLINED_UNTIL");
        field.setAccessible(true);
        var method = BotRecruitManager.class.getDeclaredMethod("pairKey", int.class, int.class);
        method.setAccessible(true);
        long key = (long) method.invoke(null, bot.getId(), inviter.getId());
        Map<Long, Long> declines = (Map<Long, Long>) field.get(null);
        declines.put(key, System.currentTimeMillis() + 10000);
        var entry = new BotPartyQueue.PartyInviteEntry(inviter, 12347);
        BotPartyQueue queue = BotPartyQueue.getInstance();
        putInvite(queue, bot, entry);
        try (var commands = mockStatic(BotPartyCommands.class)) {
            BotRecruitManager.pollInvites(bot);
            commands.verify(() -> BotPartyCommands.botAcceptPartyInvite(any(), anyInt()), never());
            commands.verify(() -> BotPartyCommands.botRejectPartyInvite(bot, entry, false));
        } finally {
            declines.remove(key);
            queue.removePartyInvite(bot);
            BotRecruitManager.clearHandoffs(bot.getId());
        }
    }
}
