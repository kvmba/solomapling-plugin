package soloMapling.ArtificialPlayer.PartyQuest;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the combat beat's stepped-on pickup contract ({@code PqActions.lootSteppedOn}) and the
 * per-quest opt-in that scopes it.
 *
 * <p>Why this exists as a contract test rather than only as behaviour: the LPQ stage-3 report
 * ("the bot does not pick the pass up in time") is caused by loot belonging to the MACRO tick
 * while the fight runs on the 250ms combat sweep. The obvious fix - sweep everything on the floor
 * every beat - is a known defect, because several quest mechanics consume a floor drop by
 * identity (HPQ's seeds on a flower, OPQ's seed on Papa Pixie's spring, both via
 * {@code PqActions.feedReactor}, which matches the pile's stack size). The sweep therefore has to
 * stay: (a) named-item only, (b) at-feet only, (c) the existing entitlement rules. Those three
 * bounds are what this test keeps from being "simplified" away later.
 */
class PqSteppedOnLootTest {

    private static final String PQ_ACTIONS = "soloMapling/ArtificialPlayer/PartyQuest/PqActions.java";
    private static final String PQ_BOT = "soloMapling/ArtificialPlayer/PartyQuest/PartyQuestBot.java";
    private static final String LUDI_BOT = "soloMapling/ArtificialPlayer/BotTypes/Ludi/LudiPQBot.java";

    @Test
    void theCombatBeatScoopsOnlyTheItemsTheQuestNames() throws Exception {
        String bot = javaSource(PQ_BOT);
        assertTrue(bot.contains("PqActions.lootSteppedOn(bot, steppedOnLootIds())"),
                "the combat sweep must scoop the quest's stepped-on items");
        // Empty by default: a quest that does not opt in keeps the old behaviour exactly.
        assertTrue(bot.matches("(?s).*protected int\\[\\] steppedOnLootIds\\(\\) \\{\\s*return EMPTY_LOOT_IDS;\\s*}.*"),
                "the default opt-in must return no ids");
    }

    @Test
    void ludiOptsInWithItsPassAndNothingElse() throws Exception {
        String ludi = javaSource(LUDI_BOT);
        assertTrue(ludi.matches("(?s).*protected int\\[\\] steppedOnLootIds\\(\\) \\{\\s*"
                        + "return new int\\[\\]\\{LudiPqData.PASS};\\s*}.*"),
                "Ludi must scoop exactly its pass");
        assertFalse(ludi.contains("seeds") || ludi.contains("PAPA"),
                "Ludi must not sweep any other mechanic's ground item");
    }

    @Test
    void theSweepIsAtFeetOnlyAndKeepsTheEntitlementRules() throws Exception {
        String java = javaSource(PQ_ACTIONS);
        int start = java.indexOf("public static int lootSteppedOn");
        assertTrue(start > 0, "lootSteppedOn must exist");
        String body = java.substring(start, java.indexOf("\n    }", start));

        assertTrue(body.contains("STEPPED_ON_REACH_SQ"), "the reach is the stepped-on box");
        assertTrue(body.contains("botCanLoot"), "addressed piles stay refused");
        assertTrue(body.contains("isFloorLoot"), "multi-piece stacks stay refused");
        assertTrue(body.contains("nextSteppedOnPickupAtByBot"), "pickups are paced, not vacuumed");
        assertFalse(body.contains("walkForPickup") || body.contains("GCMovement.move"),
                "a combat beat must never steer the driver");
    }

    @Test
    void theSteppedOnReachIsTighterThanTheLootReach() throws Exception {
        // The macro tick's pickup may walk up to 150px; the stepped-on sweep must not reach that
        // far, or a bot would vacuum drops it has not walked onto.
        String java = javaSource(PQ_ACTIONS);
        assertTrue(java.contains("STEPPED_ON_REACH_SQ = 60.0 * 60.0"));
        assertTrue(java.contains("PICKUP_REACH_SQ = 150.0 * 150.0"));
    }

    @Test
    void thePerBotPacingStateIsReleasedWithTheBot() throws Exception {
        String java = javaSource(PQ_ACTIONS);
        Method clear = PqActions.class.getDeclaredMethod("clearSeekState", int.class);
        assertNotNull(clear, "the per-bot release seam must still exist");
        assertTrue(java.matches("(?s).*clearSeekState.*nextSteppedOnPickupAtByBot\\.remove\\(botId\\).*"),
                "the pickup throttle must not outlive the bot");
    }

    @Test
    void theSweepIsIdempotentWithoutAClient() {
        // No map/client means nothing to scoop: this must be a cheap no-op, never an NPE.
        assertEquals(0, PqActions.lootSteppedOn(null, new int[]{4001022}));
    }

    private static String javaSource(String path) throws Exception {
        try (var in = PqSteppedOnLootTest.class.getClassLoader().getResourceAsStream(path);
             var raw = in != null ? in : new java.io.FileInputStream("src/main/java/" + path)) {
            return new String(raw.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
