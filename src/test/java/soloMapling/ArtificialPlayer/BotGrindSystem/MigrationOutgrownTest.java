package soloMapling.ArtificialPlayer.BotGrindSystem;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/*
 * A bot must not be forced off a continent that still has something for it to fight.
 *
 * The bug this pins: migration used to ask only "does a harder continent exist that my level clears?",
 * and treated any YES as "I have outgrown this continent". Those are different questions. Ludibrium's
 * bar is 30, so a 40-54 cohort based in 地球防御本部 (221000000, inside Ludibrium) cleared every
 * higher-bar continent and was ordered to leave on its first town visit — even though its own fields
 * (221030000..221040400, mobs lv41-54) were still exactly its level. Since migration only relocates to
 * another continent's TOWN and EDF is not a town anchor, the bot could never come back: EDF bled out,
 * monotonically, no refill.
 *
 * The rule is now content-aware (TrainingMapChooser.forcedCrossing, built on hasInBandTarget): a climb
 * is owed only when nothing level-appropriate remains reachable — with the beginner island's one-way
 * boat treated as its own forced-exit case. hasInBandTarget needs the live world graph, so it is
 * exercised in-world; the pure decisions it feeds, and their wiring, are pinned here.
 */
class MigrationOutgrownTest {

    @Test
    void aBotWithSomethingLeftToFightIsNotForcedOut() {
        // The EDF case: below the free-move level, and the continent still has targets -> stay.
        assertFalse(TrainingMapChooser.mustLeaveContinent(45, true),
                "a 45-level bot whose continent still has in-band mobs must not be forced to leave");
    }

    @Test
    void aBotThatHasTrulyOutgrownItsContinentLeaves() {
        // Nothing in band left -> the climb is owed (this is the behaviour the fix preserves).
        assertTrue(TrainingMapChooser.mustLeaveContinent(45, false),
                "with nothing left in band, a below-free-move bot must climb");
    }

    @Test
    void aFreeMoverIsNeverForceClimbed() {
        // At/above the free-move level a move is optional (the dice decide), so the forced-climb rule
        // must never fire — regardless of whether the continent still has targets.
        assertFalse(TrainingMapChooser.mustLeaveContinent(
                        TrainingRegions.FREE_MOVE_LEVEL, true),
                "a free mover must not be force-climbed even with an empty continent");
        assertFalse(TrainingMapChooser.mustLeaveContinent(
                        TrainingRegions.FREE_MOVE_LEVEL, false),
                "a free mover's move is optional, never forced");
    }

    @Test
    void theOutgrownBoundaryIsTheFreeMoveLevel() {
        int justBelow = TrainingRegions.FREE_MOVE_LEVEL - 1;
        int atFree = TrainingRegions.FREE_MOVE_LEVEL;
        assertTrue(TrainingMapChooser.mustLeaveContinent(justBelow, false));
        assertFalse(TrainingMapChooser.mustLeaveContinent(atFree, false));
    }

    // The beginner island (彩虹岛) is a one-way exit, not an outgrown continent: its boat leaves at level
    // 8 for a bot that would otherwise live there forever. It DOES hold level-appropriate mobs (snails
    // and such), so a content-based climb test would strand every bot on it — the exit must never be
    // gated on remaining content. forcedCrossing treats it as its own case, and both call sites use it.
    @Test
    void theBeginnerIslandIsRecognisedSoItsExitIsNeverGated() {
        // The island's maps are everything below Victoria's town id — including the walk 10000 ->
        // 2000000 that ends at Southperry's dock.
        assertTrue(TrainingRegions.isBeginnerIsland(10_000), "the spawn map is on the island");
        assertTrue(TrainingRegions.isBeginnerIsland(2_000_000), "Southperry's dock is on the island");
        assertFalse(TrainingRegions.isBeginnerIsland(100_000_000), "Victoria is not the island");
        assertFalse(TrainingRegions.isBeginnerIsland(221_000_000), "EDF is not the island");
    }

    // forcedCrossing is the single statement of the rule both call sites share. Pinned against the
    // island: an 8+ beginner MUST be forced off (the content probe is short-circuited, so it never
    // consults mobs and can never strand the bot). On a real continent the probe decides.
    @Test
    void forcedCrossingSendsTheIslandBotOffRegardlessOfItsMobs() {
        // 2000000 = Southperry's dock; its own fields are full of level-appropriate mobs, yet the boat
        // must still be forced — a content test would keep the bot there forever.
        assertTrue(TrainingMapChooser.forcedCrossing(2_000_000, 10),
                "an island bot must be forced to take the one-way boat");
    }

    @Test
    void theRuleIsSharedNotDuplicated() throws IOException {
        // Both call sites route through forcedCrossing; neither re-states the band/island logic.
        assertTrue(code(read(TRAINING)).contains("forcedCrossing"),
                "the training bot must delegate the forced-crossing decision to forcedCrossing");
        assertTrue(code(read(COMPANION)).contains("forcedCrossing"),
                "the companion must delegate the forced-crossing decision to forcedCrossing");
        assertFalse(code(read(TRAINING)).contains("isBeginnerIsland"),
                "the training bot must not re-state the island rule (it lives in forcedCrossing)");
        assertFalse(code(read(COMPANION)).contains("isBeginnerIsland"),
                "the companion must not re-state the island rule (it lives in forcedCrossing)");
    }

    // Both migration call sites route the forced-crossing decision through the shared rule rather than
    // re-deriving it, so the two can never drift apart. hasInBandTarget needs the live world graph
    // (GCMovement), so the wiring is pinned against the source, as the host-dependent exhibit-filter
    // test does — otherwise the gate could be bypassed by simply calling migrationTarget() alone, which
    // is the original defect.
    private static final Path TRAINING = Paths.get(
            "src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java");
    private static final Path COMPANION = Paths.get(
            "src/main/java/soloMapling/companion/execution/SoloGrindController.java");
    private static final Path CHOOSER = Paths.get(
            "src/main/java/soloMapling/ArtificialPlayer/BotGrindSystem/TrainingMapChooser.java");

    // hasInBandTarget must BAND-CHECK the finder's result. TrainingMapFinder falls back to the
    // closest-level maps when nothing is in band, so an emptiness check ("no map at all") is true almost
    // everywhere and would switch the forced climb off entirely — the exact regression this guards.
    @Test
    void inBandProbeChecksTheBandNotTheListLength() throws IOException {
        String code = code(read(CHOOSER));
        assertTrue(code.contains("m.mobLevel() >= minMob") && code.contains("m.mobLevel() <= maxMob"),
                "hasInBandTarget must test the returned maps against the band, because the finder's "
                        + "non-empty fallback (out-of-band closest-level maps) defeats an isEmpty() check");
        assertFalse(code.contains("!eligible.isEmpty()"),
                "hasInBandTarget must not treat the finder's fallback list as 'still has targets'");
    }

    /** Strips comments so assertions match real calls, not prose about them. */
    private static String code(String src) {
        StringBuilder out = new StringBuilder();
        for (String line : src.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
                continue;
            }
            out.append(line).append('\n');
        }
        return out.toString();
    }

    private static String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
