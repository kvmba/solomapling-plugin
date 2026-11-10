package soloMapling.ArtificialPlayer.PartyQuest;

import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotTypes.Ariant.AriantPqData;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the cadence contract for the quests whose arenas carry no {@code EventInstance}.
 *
 * <p>{@code PartyQuestBot.lowPriorityDelayMs} only drops to the 2.5s in-run beat when the map
 * belongs to an event instance <em>or</em> the subclass says the run keeps its own clock. Only
 * scripted party quests register an instance; a disposable arena map does not, so a bot there
 * stays on the 36-48s background tick unless it opts in.
 *
 * <p>That is not a cosmetic difference. Ariant is a ten-minute catching race: on the background
 * cadence a bot gets roughly a dozen decisions for the whole match, so it almost never swings and
 * practically never fires a catch at the moment a scorpion crosses the 40% line. Monster Carnival
 * has the same shape, which is why it opts in already; this test keeps Ariant from regressing.
 *
 * <p>Asserted from the sources rather than by constructing a bot: {@code Character} cannot be
 * built in a unit test, and this is a one-line policy the source can state exactly.
 */
class PqArenaCadenceTest {

    private static final String ARIANT_BOT =
            "src/main/java/soloMapling/ArtificialPlayer/BotTypes/Ariant/AriantPQBot.java";
    private static final String CARNIVAL_BOT =
            "src/main/java/soloMapling/ArtificialPlayer/BotTypes/Carnival/CarnivalPQBot.java";

    @Test
    void ariantOptsIntoTheFastCadenceInsideItsArena() throws Exception {
        String body = methodBody(ARIANT_BOT, "protected boolean runsOnOwnClock()");
        assertTrue(body.contains("AriantPqData.isArena"),
                "the fast cadence must be scoped to the arena, not to the queue hall");
    }

    @Test
    void carnivalStillOptsIntoTheFastCadence() throws Exception {
        String body = methodBody(CARNIVAL_BOT, "protected boolean runsOnOwnClock()");
        assertTrue(body.contains("return true"),
                "a carnival match is ten minutes long and must not run on the 36-48s wheel");
    }

    /**
     * The gate that makes the opt-in necessary: an Ariant lobby map is not an arena, so a bot
     * waiting there keeps the slow background pace (correct - it is just standing around), while
     * the arena itself is what flips the fast beats on.
     */
    @Test
    void theFastCadenceIsScopedToTheArenaNotTheHall() {
        assertTrue(AriantPqData.isQueueLobby(AriantPqData.queueMapFor(0)),
                "the queue hall is a run map, but not an arena");
        assertTrue(AriantPqData.arenaEdition(AriantPqData.queueMapFor(0) + 1) == 0,
                "the arena id is the queue id + 1");
    }

    /** The named method's body, read from the shipped source so the policy cannot drift. */
    private static String methodBody(String path, String signature) throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(path));
        int start = source.indexOf(signature);
        assertTrue(start > 0, "method must exist: " + signature + " in " + path);
        int open = source.indexOf('{', start);
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return source.substring(open, i);
            }
        }
        throw new IllegalStateException("unbalanced method body: " + signature);
    }
}
