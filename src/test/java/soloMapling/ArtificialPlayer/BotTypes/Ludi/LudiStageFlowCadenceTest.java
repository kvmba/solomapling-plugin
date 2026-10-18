package soloMapling.ArtificialPlayer.BotTypes.Ludi;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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

    /** Stage 3's own loop walks to the pass its kill left, instead of waiting for the sweep. */
    @Test
    void theCrateStageWalksToItsDrops() throws Exception {
        String stages = javaSource(STAGES);
        int method = stages.indexOf("public static void breakCratesAndHunt");
        String body = stages.substring(method, stages.indexOf("\n    }", method));
        assertTrue(body.contains("walkToNearestDrop"),
                "a crate kill's drop can land beyond the at-feet sweep; the macro tick must "
                        + "walk to it");
    }

    /**
     * A finished room must not be held by the chase's leftover goal: stage 4's exit and the
     * delivery post both stand down for a live move, and the live move was a walk to a corpse.
     */
    @Test
    void aQuietRoomReleasesTheChase() throws Exception {
        String stages = javaSource(STAGES);
        int doorRooms = stages.indexOf("public static void workDoorRooms");
        String body = stages.substring(doorRooms, stages.indexOf("\n    }", doorRooms));
        int finish = body.indexOf("PqActions.finishFight");
        assertTrue(finish > 0, "the door-room loop must end the fight once the room is quiet");
        // The cleared-stage branch at the top has its own exit; the QUIET-ROOM exit is the one
        // after the fight, and that is the one this release must precede.
        int quietExit = body.indexOf("exitDoorRoom(bot)", finish);
        assertTrue(quietExit > finish,
                "and it must do so BEFORE the quiet-room exit, which stands down for a live move");

        String pq = javaSource(PQ_ACTIONS);
        int finishStart = pq.indexOf("public static void finishFight");
        assertTrue(finishStart > 0, "the shared release seam must exist");
        String finishBody = pq.substring(finishStart, pq.indexOf("\n    }", finishStart));
        assertTrue(finishBody.contains("hasPickupWalk"),
                "a pending pickup is stage-required and must never be cancelled by this");
        assertTrue(finishBody.contains("movementShielded") && finishBody.contains("reactorBeatArmed"),
                "a stage walk or an armed box owns the steering and is not the fight's to cancel");
    }

    /**
     * Stage 5's hide: the guards stand in the MAIN room, on the way to the bottom room's door,
     * so a 飞侠 that only hides after entering the room crosses them bare.
     */
    @Test
    void theHideIsRaisedBeforeCrossingTheGuardedFloor() throws Exception {
        String stages = javaSource(STAGES);
        int mainRoom = stages.indexOf("private static void workStage5FromMainRoom");
        String body = stages.substring(mainRoom, stages.indexOf("\n    }", mainRoom));
        int hide = body.indexOf("holdHideWhileCrossing");
        int door = body.indexOf("enterDoorRoom");
        assertTrue(hide > 0, "the crossing must raise the hide");
        assertTrue(door > hide, "the hide must be up BEFORE the walk into the room");

        int helper = stages.indexOf("private static void holdHideWhileCrossing");
        assertTrue(helper > 0, "the crossing helper must exist");
        String helperBody = stages.substring(helper, stages.indexOf("\n    }", helper));
        assertTrue(helperBody.contains("hideSkillFor"),
                "only hide-carrying kits can hide; the posting table still decides who enters");
        assertTrue(helperBody.contains("nearbyGuardCount") || helperBody.contains("STAGE5_ROOM_LAST"),
                "the crossing is dangerous either because guards are in this room or because "
                        + "the target room is the guarded one");
    }

    private static String javaSource(String path) throws Exception {
        try (var in = LudiStageFlowCadenceTest.class.getClassLoader().getResourceAsStream(path);
             var raw = in != null ? in : new java.io.FileInputStream(path)) {
            return new String(raw.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
