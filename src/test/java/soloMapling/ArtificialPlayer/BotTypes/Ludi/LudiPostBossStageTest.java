package soloMapling.ArtificialPlayer.BotTypes.Ludi;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the boss-stage hand-off into the two post-boss maps.
 *
 * <p>The reported shape ("after the boss dies the bots do not enter the bonus stage, and never
 * reach the final exit stage") had two roots, both of them "the run is over" judgements that
 * were made too early:
 *
 * <ul>
 *   <li>{@code LudiPQBot.isInsideQuest} stopped at {@code 922010900}, the boss map. The two maps
 *       the event still has to warp the team through - the bonus stage {@code 922011000} and the
 *       exit stage {@code 922011100} - therefore read as "outside the quest", and the base
 *       {@code tickPartyQuest} took its lobby branch before any stage work ran.</li>
 *   <li>{@code workStage} returned {@code stage >= 9} on a cleared boss stage, which is
 *       {@code true}: the base class read that as "the run is over" and called
 *       {@code returnToLobby} on the spot.</li>
 * </ul>
 *
 * <p>Source-level assertions on purpose: the sequence is the contract (which judgement gates
 * which branch), and every alternative - a live instance, a real {@code EventInstanceManager} -
 * would pin the engine rather than this hand-off.
 */
class LudiPostBossStageTest {

    private static final String BOT = "src/main/java/soloMapling/ArtificialPlayer/BotTypes/Ludi/LudiPQBot.java";
    private static final String STAGES = "src/main/java/soloMapling/ArtificialPlayer/BotTypes/Ludi/LudiStages.java";
    private static final String DATA = "src/main/java/soloMapling/ArtificialPlayer/BotTypes/Ludi/LudiPqData.java";

    @Test
    void theQuestRangeCoversBothMapsAfterTheBoss() throws Exception {
        String data = javaSource(DATA);
        assertTrue(data.contains("BONUS_MAP = 922011000"), "the bonus map must be named");
        assertTrue(data.contains("EXIT_MAP  = 922011100") || data.contains("EXIT_MAP = 922011100"),
                "the exit map must be named");

        String bot = javaSource(BOT);
        int method = bot.indexOf("protected boolean isInsideQuest(int mapId)");
        assertTrue(method > 0, "isInsideQuest must exist");
        String body = bot.substring(method, bot.indexOf("\n    }", method));
        assertTrue(body.contains("LudiPqData.EXIT_MAP"),
                "the run's map range must reach the exit stage - it does not end at the boss");
        assertFalse(body.contains("<= LudiPqData.STAGE_9"),
                "stopping at the boss map sends every bot to the lobby before the reward stages");
    }

    @Test
    void aClearedBossStageDoesNotReportTheRunOver() throws Exception {
        String bot = javaSource(BOT);
        int cleared = bot.indexOf("if (LudiStages.stageCleared(getChr(), stage))");
        assertTrue(cleared > 0, "the cleared-stage branch must exist");
        String body = bot.substring(cleared, bot.indexOf("\n        }", cleared));
        assertFalse(body.contains("return stage >= 9"),
                "a cleared stage 9 must keep the bot in the run: the event still warps the team "
                        + "through the bonus and exit stages");
        assertTrue(body.contains("return false"), "the cleared branch must keep working the run");
    }

    @Test
    void bothPostBossMapsHaveTheirOwnWork() throws Exception {
        String bot = javaSource(BOT);
        int work = bot.indexOf("protected boolean workStage()");
        String body = bot.substring(work, bot.indexOf("\n    }", work));
        assertTrue(body.contains("LudiPqData.BONUS_MAP"), "the bonus stage needs its own work");
        assertTrue(body.contains("LudiPqData.EXIT_MAP"), "the exit stage needs its own work");
        assertTrue(body.indexOf("LudiPqData.BONUS_MAP") < body.indexOf("stageOf("),
                "the post-boss routing must run before the stage arithmetic, which reads those "
                        + "maps as unknown stages");

        String stages = javaSource(STAGES);
        assertTrue(stages.contains("public static void workBonusStage"),
                "the bonus room's reward boxes must be worked");
        assertTrue(stages.contains("public static void workExitStage"),
                "the exit room's reward NPC must be talked to");
        assertTrue(stages.contains("LudiPqData.BONUS_BOX"),
                "the bonus stage is a reward-box room");
    }

    @Test
    void thePostBossMapsAreNotFollowedThroughPortals() throws Exception {
        String bot = javaSource(BOT);
        int follow = bot.indexOf("protected boolean isFollowingLeader()");
        String body = bot.substring(follow, bot.indexOf("\n    }", follow));
        assertTrue(body.contains("LudiPqData.BONUS_MAP") && body.contains("LudiPqData.EXIT_MAP"),
                "neither post-boss map moves by a portal the leader walks - both are event "
                        + "warps, so the follow beat must stand down there");
        assertTrue(body.indexOf("LudiPqData.BONUS_MAP") < body.indexOf("TRAP_ROOM"),
                "the post-boss gate is a map-identity test and belongs before the room logic");
    }

    @Test
    void theBonusStageMovesOnToTheExitStage() throws Exception {
        String stages = javaSource(STAGES);
        int method = stages.indexOf("public static void workBonusStage");
        String body = stages.substring(method, stages.indexOf("\n    }", method));
        assertTrue(body.contains("PqActions.lootAll"),
                "the reward boxes spray RANDOM items - an id-filtered sweep would pick up nothing");
        assertFalse(body.contains("ludiLootIds"),
                "an id filter cannot cover a random reward spray");
        assertTrue(body.contains("goToExitStage"),
                "once the boxes are spent the bot must take the Pink Balloon's warp on, not "
                        + "stand in an empty room until the timer");
        int helper = stages.indexOf("private static void goToExitStage");
        assertTrue(helper > 0, "the leave-the-bonus-stage step must exist");
        assertTrue(stages.substring(helper).contains("LudiPqData.BONUS_NPC"),
                "the Pink Balloon is the NPC that warps the body to the exit stage");
    }

    @Test
    void theExitStageTalksToTheRewardNpc() throws Exception {
        String stages = javaSource(STAGES);
        int method = stages.indexOf("public static void workExitStage");
        String body = stages.substring(method, stages.indexOf("\n    }", method));
        assertTrue(body.contains("PqActions.talkTo"),
                "the per-body reward is handed out by Arturo's own conversation");
        assertTrue(body.contains("LudiPqData.EXIT_NPC"), "the reward NPC is named, not guessed");
        assertTrue(body.contains("PqActions.walkTo"),
                "a dialogue cannot be opened across the room");
    }

    @Test
    void thePostBossMapsAreInsideTheSameInstance() {
        // Pure data check: the ids are contiguous quest maps, and both sit above the boss.
        assertEquals(922010900, LudiPqData.STAGE_9);
        assertEquals(922011000, LudiPqData.BONUS_MAP);
        assertEquals(922011100, LudiPqData.EXIT_MAP);
        assertTrue(LudiPqData.BONUS_MAP > LudiPqData.STAGE_9);
        assertTrue(LudiPqData.EXIT_MAP > LudiPqData.BONUS_MAP);
    }

    private static String javaSource(String path) throws Exception {
        try (var in = LudiPostBossStageTest.class.getClassLoader().getResourceAsStream(path);
             var raw = in != null ? in : new java.io.FileInputStream(path)) {
            return new String(raw.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
