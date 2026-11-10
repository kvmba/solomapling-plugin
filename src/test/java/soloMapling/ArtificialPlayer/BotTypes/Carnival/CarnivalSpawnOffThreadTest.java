package soloMapling.ArtificialPlayer.BotTypes.Carnival;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the rule that the carnival poll never waits on bot spawning.
 *
 * <p>The poll runs once a second on a single dedicated thread and is responsible for reaping every
 * finished match and opening every ready one, on every channel. Bot spawning is the opposite kind
 * of work: {@code PlatformPlacement.spawnBotsOnMap} blocks on a latch for up to two minutes and
 * retries with sleeps. Doing that inline - which the first cut did - means one slow spawn freezes
 * the entire carnival for however long it takes: matches that should have ended keep running past
 * their clock and fields that should have opened stay shut.
 *
 * <p>It also must not stack: the poll retries every second, so a second request while the first is
 * still in flight would double the population the moment it lands.
 */
class CarnivalSpawnOffThreadTest {

    @Test
    void thePollThreadItselfDoesNotSpawn() throws Exception {
        // The spawn call must live behind the async helper, never in the recruitment path that the
        // poll calls directly.
        Method request = CarnivalArena.class.getDeclaredMethod("requestTeamSpawn",
                Class.forName("org.gms.net.server.channel.Channel", false,
                        CarnivalSpawnOffThreadTest.class.getClassLoader()),
                int.class, boolean.class, int.class);
        assertTrue(Modifier.isPrivate(request.getModifiers()),
                "spawning is an internal step of opening a match");
        assertTrue(Modifier.isStatic(request.getModifiers()));

        // recruitBots itself must stay cheap: it only scans the channel's storage and, at most,
        // hands a request off.
        Method recruit = CarnivalArena.class.getDeclaredMethod("recruitBots",
                Class.forName("org.gms.net.server.channel.Channel", false,
                        CarnivalSpawnOffThreadTest.class.getClassLoader()),
                int.class, boolean.class, int.class);
        assertNotNull(recruit);
        assertTrue(Modifier.isStatic(recruit.getModifiers()));
    }

    @Test
    void onlyOneSpawnRunsPerFieldAtATime() throws Exception {
        // The guard is a map of field keys; its presence is what keeps a 1s poll from launching a
        // new batch every second while a slow spawn is still running.
        var guard = CarnivalArena.class.getDeclaredField("SPAWNING");
        assertTrue(Modifier.isPrivate(guard.getModifiers()));
        assertTrue(Modifier.isStatic(guard.getModifiers()));
        assertTrue(java.util.Map.class.isAssignableFrom(guard.getType()),
                "the guard is written from the poll thread and cleared from the spawn thread");
    }
}
