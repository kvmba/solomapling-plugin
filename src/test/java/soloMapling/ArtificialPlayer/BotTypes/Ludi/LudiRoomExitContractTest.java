package soloMapling.ArtificialPlayer.BotTypes.Ludi;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the two LPQ room contracts that caused "the stage advances but one bot only shows up
 * minutes later":
 *
 * <ul>
 *   <li>A bot inside a door room / maze room must leave the moment its stage is turned in.
 *       Those rooms have no {@code next00} (only {@code sp}/{@code st00}/{@code out00}, or the
 *       maze's {@code in01..in06}), so {@link soloMapling.ArtificialPlayer.PartyQuest.PartyQuestBot#followLeaderIntoNextRoom()}
 *       can never follow the party out - the room loop's own exit is the only way back.</li>
 *   <li>The maze room's exit used to require a pass in the pocket. A bot sent into a room a
 *       teammate had already emptied therefore stood there until some unrelated drop wandered
 *       into its loot radius, which is exactly the "appears minutes later" report.</li>
 * </ul>
 *
 * <p>Source-level assertions on purpose: the room loops need a live {@code Character} and
 * {@code EventInstanceManager}, while the ordering property ("the cleared gate is the FIRST
 * thing the loop does") is what has to be locked - a test that only checks a helper would not
 * notice the gate drifting below the box work.
 */
class LudiRoomExitContractTest {

    private static final String STAGES = "soloMapling/ArtificialPlayer/BotTypes/Ludi/LudiStages.java";

    @Test
    void aClearedStageLeavesADoorRoomBeforeAnyWork() throws Exception {
        String body = methodBody("public static void workDoorRooms");
        int clearedGate = body.indexOf("stageCleared(bot, LudiPqData.roomStage(mapId))");
        int firstWork = body.indexOf("PqActions.seekAndAttack");
        assertTrue(clearedGate > 0,
                "the door-room loop must leave when its stage is already turned in");
        assertTrue(firstWork > 0, "the door-room loop must still fight the room");
        assertTrue(clearedGate < firstWork,
                "the cleared-stage gate must run BEFORE the fight: a turned-in stage has no "
                        + "work left, and a bot that keeps fighting stays one map behind");
        assertTrue(body.contains("exitDoorRoom(bot)"),
                "the cleared branch must take the room's own out portal");
    }

    @Test
    void aClearedStageLeavesTheMazeBeforeAnyWork() throws Exception {
        String body = methodBody("public static void sneakDoorRooms");
        int clearedGate = body.indexOf("stageCleared(bot, 5)");
        int firstBox = body.indexOf("nearestBoxOid");
        assertTrue(clearedGate > 0,
                "the maze loop must leave when stage 5 is already turned in");
        assertTrue(firstBox > 0, "the maze loop must still break its boxes");
        assertTrue(clearedGate < firstBox,
                "the cleared-stage gate must run BEFORE the box search");
    }

    @Test
    void theMazeExitNoLongerWaitsForAPass() throws Exception {
        String body = methodBody("public static void sneakDoorRooms");
        assertFalse(body.contains("countItem(bot, LudiPqData.PASS) <= 0"),
                "the exit must not depend on carrying a pass: a room a teammate already emptied "
                        + "has nothing to break and nothing to pocket, and the bot used to stand "
                        + "in it until an unrelated drop wandered into its loot radius");
    }

    @Test
    void theMazeExitAwayFromAClearedStageStillWaitsForTheLeader() throws Exception {
        String body = methodBody("public static void sneakDoorRooms");
        // Assert the EXIT GATE, not the literal source text: this condition legitimately grows
        // (collectRemainingLoot was added between these two arms) and a substring match would
        // break on every such addition even though the contract never changed. What must hold is
        // that the `if` which precedes the room exit blocks on BOTH the standing-box arm and the
        // leader arm, in whatever order / company the condition carries them.
        String exitGate = conditionBefore(body, "markRoomCleared(bot, mapId)");
        assertTrue(exitGate.contains("box >= 0"),
                "while the stage is open, leaving with a box still standing must stay blocked");
        assertTrue(exitGate.contains("leaderInRoom(bot)"),
                "while the stage is open, leaving ahead of the leader must stay blocked");
    }

    /** The full parenthesised condition of the {@code if} that guards {@code statement}. */
    private static String conditionBefore(String body, String statement) {
        int open = body.lastIndexOf("if (", body.lastIndexOf(statement));
        assertTrue(open > 0, "no `if` precedes " + statement);
        int depth = 0;
        for (int i = open + 3; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return body.substring(open, i + 1);
            }
        }
        throw new AssertionError("unbalanced condition before " + statement);
    }

    private static String methodBody(String signature) throws Exception {
        String java;
        try (var in = LudiRoomExitContractTest.class.getClassLoader().getResourceAsStream(STAGES)) {
            java = in != null
                    ? new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    : Files.readString(Path.of("src/main/java/" + STAGES), StandardCharsets.UTF_8);
        }
        int start = java.indexOf(signature);
        assertTrue(start > 0, signature + " must exist");
        int end = java.indexOf("\n    }", start);
        return java.substring(start, end);
    }
}
