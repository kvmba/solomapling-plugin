package soloMapling.ArtificialPlayer.BotTypes.Ludi;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the four "the bots are a stage behind / a minute late" behaviours in the Tower of
 * Toy quest at the place each was actually decided.
 *
 * <p>All of them share one shape: the quest's progress is noticed somewhere else (the leader
 * crossed a portal, a stage flag flipped, a box broke, a drop landed), and the bot's reaction
 * has to happen on the MACRO beat, not on the background beat and not behind a stale driver
 * goal.
 */
class LudiStageFlowCadenceTest {

    private static final String BOT = "src/main/java/soloMapling/ArtificialPlayer/BotTypes/Ludi/LudiPQBot.java";
    private static final String PQ_BOT =
            "src/main/java/soloMapling/ArtificialPlayer/PartyQuest/PartyQuestBot.java";
    private static final String STAGES = "src/main/java/soloMapling/ArtificialPlayer/BotTypes/Ludi/LudiStages.java";
    private static final String PQ_ACTIONS =
            "src/main/java/soloMapling/ArtificialPlayer/PartyQuest/PqActions.java";
    private static final String DROPS =
            "src/main/java/soloMapling/ArtificialPlayer/BotCommandsPack/DropCommands.java";

    /**
     * Stage 1 -> 2, and every later transition: the bot's own room is "unobserved" exactly when
     * the party has already moved on, so the base class's 36-48s background cadence fires on
     * the transitions that matter. The quest pins the normal beat.
     */
    @Test
    void aRunKeepsTheNormalMacroBeat() throws Exception {
        String bot = javaSource(PQ_BOT);
        assertTrue(bot.contains("protected long lowPriorityDelayMs()"),
                "a run must not be paced as unobserved background scenery (inherited by every "
                        + "quest bot, Ludi included)");
        int method = bot.indexOf("protected long lowPriorityDelayMs()");
        String body = bot.substring(method, bot.indexOf("\n    }", method));
        assertTrue(body.contains("getNormalDelayMs()"),
                "inside a quest the beat must be the normal one, not the background one");
        assertTrue(body.contains("isInsideQuest"),
                "the lobby keeps the cheap beat - only the run's own maps need the fast one");
    }

    /**
     * Stage 3's pass: a party member's kill and the bot's own kill both have to be lootable at
     * once. The old local rule only admitted "mine, or ffa type 2, or 15s old", so a pass a
     * PARTY-mate dropped was untouchable for the whole protection window - and the stage's
     * turn-in needs every pass.
     */
    @Test
    void aPartyMatesDropIsLootableAtOnce() throws Exception {
        String drops = javaSource(DROPS);
        int method = drops.indexOf("public static boolean botCanLoot");
        String body = drops.substring(method, drops.indexOf("\n    }", method));
        assertTrue(body.contains("canBePickedBy"),
                "entitlement must follow the host's own party-aware rule");
        assertFalse(body.contains("getDropTime()"),
                "a hand-rolled owner-protection window makes a party-mate's pass wait 15s");
        assertTrue(body.contains("isPermanentOwnerLocked"),
                "the addressed hand-off guard still outranks the host's rule");
    }

    /**
     * Stage 3's pass must be reachable by the macro tick's own sweep: the stage work scans a
     * kill-neighbourhood radius and the shared pickup path WALKS to an out-of-reach drop
     * (the combat beat only scoops at feet). The regression this pins is the entitlement gate
     * below, not a second walking helper.
     */
    @Test
    void theCrateStageLootsItsNeighbourhood() throws Exception {
        String stages = javaSource(STAGES);
        int method = stages.indexOf("public static void breakCratesAndHunt");
        String body = stages.substring(method, stages.indexOf("\n    }", method));
        assertTrue(body.contains("PqActions.loot("),
                "the crate stage must run the pickup sweep on its macro tick");
        assertTrue(body.contains("LudiPqData.PASS"), "and it must be sweeping for the pass");
    }

    /**
     * Stage 5's hide: the guards stand in the MAIN room, on the way to every sub-room door, so
     * a thief that only hides after entering the room crosses them bare. Both ways in - the
     * bot's own door walk and following the leader - must be covered, and the aura has to be up
     * before the movement starts.
     */
    @Test
    void theHideIsRaisedBeforeCrossingTheGuardedFloor() throws Exception {
        String bot = javaSource(BOT);
        int update = bot.indexOf("public void updateState()");
        assertTrue(update > 0, "updateState must exist");
        String body = bot.substring(update, bot.indexOf("\n    }", update));
        int hide = body.indexOf("LudiStages.stayHidden");
        int work = body.indexOf("tickPartyQuest()");
        assertTrue(hide > 0, "stage 5 must raise the hide on the macro tick");
        assertTrue(work > hide,
                "the hide must be raised BEFORE tickPartyQuest issues any walk (both the "
                        + "claim-and-walk and the follow walk are issued from it)");
        assertTrue(body.contains("roomStage(here) == 5") && body.contains("stageOf(here) == 5"),
                "it must cover both a stage-5 sub-room and the stage-5 main map");

        // The predicate must stay side-effect free: the follow decision is a query.
        int follow = bot.indexOf("protected boolean isFollowingLeader()");
        String followBody = bot.substring(follow, bot.indexOf("\n    }", follow));
        assertFalse(followBody.contains("stayHidden"),
                "isFollowingLeader is a predicate; raising an aura from it is a side effect in "
                        + "a query");
    }

    private static String javaSource(String path) throws Exception {
        try (var in = LudiStageFlowCadenceTest.class.getClassLoader().getResourceAsStream(path);
             var raw = in != null ? in : new java.io.FileInputStream(path)) {
            return new String(raw.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
