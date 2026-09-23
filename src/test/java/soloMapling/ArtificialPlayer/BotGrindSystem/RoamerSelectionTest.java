package soloMapling.ArtificialPlayer.BotGrindSystem;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the roamer selection profile in {@link TrainingMapChooser}: a RoamerBot hunts LOW-level monsters
 * on purpose, so its admission ceiling is a constant, its discovery reach is the whole landmass, and its
 * occupancy is a SEPARATE table from the training one (so it never consumes a training quota).
 *
 * <p>The profile is applied inside {@code choose(...)} (which needs a live world graph), so the pieces
 * that are pure constants are pinned here the way {@code DiscoveryRadiusTest} pins MAX_HOPS — a future
 * "tidy up the roamer numbers" has to argue with these.
 */
class RoamerSelectionTest {

    private static int constant(String className, String field) throws Exception {
        Field f = Class.forName(className).getDeclaredField(field);
        f.setAccessible(true);
        return f.getInt(null);
    }

    // The whole point of the roamer: a hard low ceiling that does NOT scale with the bot's level. If this
    // ever became level-relative, a high-level roamer would silently start hunting high maps again.
    @Test
    void roamerHuntsALowCeilingThatDoesNotScaleWithLevel() throws Exception {
        assertEquals(60, constant("soloMapling.ArtificialPlayer.BotGrindSystem.TrainingMapChooser",
                        "ROAMER_MAX_MOB_LEVEL"),
                "the roamer mob ceiling must be a constant (low-level areas only), not level-scaled");
    }

    // Roamers must reach the whole connected landmass from any town (that is what makes the far ends of a
    // continent — Ludibrium's 怪兽地区 at 57 hops, say — reachable on foot from a town hub). It rides the
    // same MAX_HOPS the training "anywhere" radius uses.
    @Test
    void roamerReachIsTheWholeLandmass() throws Exception {
        int maxHops = constant("soloMapling.ArtificialPlayer.BotGrindSystem.TrainingMapChooser", "MAX_HOPS");
        assertTrue(maxHops >= 57,
                "discovery radius must clear the deepest intra-continent span (57 hops), got " + maxHops);
    }

    // ---------------------------------------------------------------------------------------------
    // Occupancy isolation: reserve/release under ROAMER must not move the TRAINING table (and vice versa).
    // ---------------------------------------------------------------------------------------------
    @Test
    void roamerAndTrainingOccupancyAreIndependent() {
        int map = 4_000_001; // an id no test pack references
        assertEquals(0, TrainingMapChooser.botsOnMap(map));
        assertEquals(0, TrainingMapChooser.botsOnMap(TrainingMapChooser.Scope.ROAMER, map));

        TrainingMapChooser.reserve(TrainingMapChooser.Scope.ROAMER, map);
        assertEquals(1, TrainingMapChooser.botsOnMap(TrainingMapChooser.Scope.ROAMER, map));
        assertEquals(0, TrainingMapChooser.botsOnMap(map),
                "a roamer reservation must not consume a TRAINING slot");
        assertEquals(0, TrainingMapChooser.botsTargeting(map),
                "the training peek (!env grindprofile) must not see roamer occupancy");

        TrainingMapChooser.reserve(map); // TRAINING
        assertEquals(1, TrainingMapChooser.botsOnMap(map));
        assertEquals(1, TrainingMapChooser.botsOnMap(TrainingMapChooser.Scope.ROAMER, map),
                "a training reservation must not disturb the roamer table");

        TrainingMapChooser.release(TrainingMapChooser.Scope.ROAMER, map);
        TrainingMapChooser.release(map);
        assertEquals(0, TrainingMapChooser.botsOnMap(map));
        assertEquals(0, TrainingMapChooser.botsOnMap(TrainingMapChooser.Scope.ROAMER, map));
    }

    // The historical no-arg API must address the TRAINING table (it is what TrainingBot and the companion
    // call), so an existing caller cannot be silently rerouted to the roamer table.
    @Test
    void theLegacyNoArgApiStillTargetsTheTrainingTable() {
        int map = 4_000_002;
        TrainingMapChooser.reserve(map);
        assertEquals(1, TrainingMapChooser.botsOnMap(TrainingMapChooser.Scope.TRAINING, map));
        TrainingMapChooser.release(map);
        assertEquals(0, TrainingMapChooser.botsOnMap(TrainingMapChooser.Scope.TRAINING, map));
    }

    // The roamer's migration is its OWN rule (free, self-rolled), not the TrainingBot/companion's
    // outgrown/island rule. Pinned against the source so a future refactor cannot quietly fold the roamer
    // onto forcedCrossing — which would re-introduce the "only move up / only when outgrown" behaviour the
    // whole bot is defined against.
    @Test
    void roamerDoesNotUseTheTrainingBotForcedCrossingRule() throws Exception {
        String code = stripped("src/main/java/soloMapling/ArtificialPlayer/BotTypes/RoamerBot.java");
        assertFalse(code.contains("forcedCrossing"),
                "a roamer migrates freely — it must not borrow the training bots' outgrown/island rule");
        assertFalse(code.contains("isBeginnerIsland"),
                "the roamer has no forced island exit; its crossings are its own");
        assertTrue(code.contains("returnTarget"),
                "the roamer's free crossing is TrainingRegions.returnTarget (any qualifying continent)");
    }

    // The scope split must not disturb the TRAINING path — the change is additive (a new `roamer`
    // overload; the old signature delegates). This pins the two things a refactor of this method is most
    // likely to silently drop: the chill fallback (retry a chill trip as a normal one when there is
    // nothing to chill at) and the downward-only floor. Both are training-only behaviour.
    @Test
    void theTrainingPathKeepsItsChillFallbackAndDeepHubFloor() throws Exception {
        String code = stripped("src/main/java/soloMapling/ArtificialPlayer/BotGrindSystem/TrainingMapChooser.java");
        assertTrue(code.contains("chill = false"),
                "the training path must still fall a chill trip back to a normal one");
        assertTrue(code.contains("DOWNWARD_HUB_LOWER_SPAN"),
                "the training path must still apply the deep-hub downward floor");
        assertTrue(code.contains("hopsForLevel(level)"),
                "the training path must still use the level-scaled discovery radius");
    }

    /** File contents with comment-only lines stripped (so assertions match calls, not prose). */
    private static String stripped(String path) throws Exception {
        String src = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)),
                java.nio.charset.StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder();
        for (String line : src.split("\n")) {
            String t = line.trim();
            if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) {
                continue;
            }
            out.append(line).append('\n');
        }
        return out.toString();
    }
}
