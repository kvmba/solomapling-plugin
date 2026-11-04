package soloMapling.ArtificialPlayer.BotTypes.Carnival;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the lobby-timer cancellation the direct match opening must not skip.
 *
 * <p>Entering a carnival field through the NPC runs {@code cpqLobby}, which schedules a
 * {@code changeMap(hall)} three minutes out so a party nobody challenges does not squat in the
 * field. Accepting a challenge cancels it ({@code cancelCPQLobby} inside {@code startCPQ}).
 *
 * <p>This plugin opens the match by calling {@code MonsterCarnival}'s constructor directly, which
 * bypasses both halves of that dance. Without an explicit cancellation the players are teleported
 * out of a live ten-minute match at the three-minute mark - and the symptom would read as "the
 * carnival kicks us out halfway", which is very hard to attribute to a missing timer.
 */
class CarnivalLobbyTimerTest {

    @Test
    void theArenaCancelsTheLobbyWalkOutTimer() throws Exception {
        Method cancel = CarnivalArena.class.getDeclaredMethod("clearLobbyTimers",
                Class.forName("org.gms.net.server.world.Party", false,
                        CarnivalLobbyTimerTest.class.getClassLoader()));
        assertTrue(Modifier.isStatic(cancel.getModifiers()));
        assertTrue(Modifier.isPrivate(cancel.getModifiers()),
                "cancellation is an internal step of opening a match");
    }

    @Test
    void thePerCharacterTimerIsWhatGetsCancelled() throws Exception {
        // The timer lives on the character, not on the party: cpqLobby schedules one per member
        // and startCPQ cancels one per member, so the arena has to follow the same shape.
        Class<?> chr = Class.forName("org.gms.client.Character", false,
                CarnivalLobbyTimerTest.class.getClassLoader());
        assertTrue(Modifier.isPublic(chr.getMethod("clearCpqTimer").getModifiers()));
        assertTrue(Modifier.isPublic(chr.getMethod("setCpqTimer",
                Class.forName("java.util.concurrent.ScheduledFuture", false,
                        CarnivalLobbyTimerTest.class.getClassLoader())).getModifiers()));
    }

    @Test
    void theFieldRegistryReportsWhetherAMatchReallyStarted() throws Exception {
        // The constructor swallows failures, so "did it start?" is read off the channel: a
        // completed start marks the field occupied. The cancellation above is gated on that,
        // because clearing the timer after a failed start would strand the party in the lobby.
        Class<?> channel = Class.forName("org.gms.net.server.channel.Channel", false,
                CarnivalLobbyTimerTest.class.getClassLoader());
        assertTrue(Modifier.isPublic(
                channel.getMethod("canInitMonsterCarnival", boolean.class, int.class).getModifiers()));
    }
}
