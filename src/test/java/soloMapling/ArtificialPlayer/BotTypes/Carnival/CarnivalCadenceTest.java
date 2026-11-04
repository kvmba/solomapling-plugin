package soloMapling.ArtificialPlayer.BotTypes.Carnival;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the two behavioural hooks Carnival had to add to the shared party-quest base.
 *
 * <p>Both are the sort of thing that "works" in a unit test and fails in a live match, because
 * each depends on a property of the arena map that no other quest has:
 *
 * <ul>
 *   <li>the arena is a {@code getDisposableMap} map, so it carries <b>no</b> {@code EventInstance}
 *       and the base class's in-run cadence gate would silently keep the bot at its 36-48s
 *       background tick;</li>
 *   <li>the bot's side and match pointer are assigned by the engine's constructor before the work
 *       that can fail, so a failed start leaves members dressed as a team of a match that does not
 *       exist.</li>
 * </ul>
 *
 * <p>Neither is observable without a running server, so what is pinned here is the shape of the
 * seams: the override exists, is protected, and defaults to off for every other quest.
 */
class CarnivalCadenceTest {

    @Test
    void theBaseClassOffersAnOwnClockHookThatDefaultsOff() throws Exception {
        Class<?> base = Class.forName("soloMapling.ArtificialPlayer.PartyQuest.PartyQuestBot",
                false, CarnivalCadenceTest.class.getClassLoader());
        Method hook = base.getDeclaredMethod("runsOnOwnClock");
        assertTrue(Modifier.isProtected(hook.getModifiers()),
                "the hook must be overridable by a quest bot, not public API");
        assertFalse(Modifier.isAbstract(hook.getModifiers()),
                "every other quest must inherit the default without changes");
        assertFalse(Modifier.isStatic(hook.getModifiers()));
    }

    @Test
    void theCarnivalBotOptsIn() throws Exception {
        Method hook = CarnivalPQBot.class.getDeclaredMethod("runsOnOwnClock");
        assertTrue(Modifier.isProtected(hook.getModifiers()));
        assertNotNull(hook);
    }

    @Test
    void theFastCadenceIsReservedForMapsWithoutAnEventInstance() {
        // The rule the hook feeds: an arena SCRIPT registers an EventInstance and keeps the
        // existing gate; a disposable arena does not, and needs the override. Transcribed here so
        // the distinction cannot be folded away later.
        assertTrue(inRun(/* hasEventInstance */ true, /* runsOnOwnClock */ false),
                "a scripted quest room keeps its 2.5s cadence");
        assertTrue(inRun(false, true), "a disposable carnival arena gets the same cadence");
        assertFalse(inRun(false, false), "an ordinary map keeps the background cadence");
    }

    private static boolean inRun(boolean hasEventInstance, boolean runsOnOwnClock) {
        return hasEventInstance || runsOnOwnClock;
    }
}
