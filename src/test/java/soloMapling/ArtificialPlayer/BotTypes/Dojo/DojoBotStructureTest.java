package soloMapling.ArtificialPlayer.BotTypes.Dojo;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Structural guard for the two rules the dojo fix depends on.
 *
 * <p>{@code Character} needs a Spring context and a room cannot be spun up in a unit test, so the
 * bot's own decisions are pinned at the source level (the same approach the attack-layer guards
 * use). The behaviour behind each rule is pinned separately: {@link DojoRoomModelTest} for the
 * clear/boss model, {@code GCMoveSystem.DojoDoorReachabilityTest} for the geometry that makes
 * walking to the door impossible.
 *
 * <p>What this catches, both of which shipped once already:
 *
 * <ul>
 *   <li>using "the map has no monsters" as the clear test - the revive marker {@code 9300216}
 *       never leaves the map, so that test is ALWAYS false and the bot fights a 2.09B-HP
 *       invisible wall for the rest of the run;</li>
 *   <li>reaching the door with {@code walkTo} - the door platform is 372px up a wall with no
 *       rope, so arrival can never be reported and the climb branch never fires.</li>
 * </ul>
 */
class DojoBotStructureTest {

    private static final Path BOT = Path.of(
            "src/main/java/soloMapling/ArtificialPlayer/BotTypes/Dojo/DojoPQBot.java");

    @Test
    void theClearTestIsTheMarkersPresenceNotAnEmptyMap() throws IOException {
        String stage = methodBody(read(BOT), "protected boolean workStage()");
        assertFalse(stage.contains("getMonsters()"),
                "getMonsters() still reports the revive marker - it can never be empty in a fought room");
        assertTrue(stage.contains("roomCleared("),
                "the clear test must be the script's own signal (marker present / resting floor)");
        String cleared = methodBody(read(BOT), "private static boolean roomCleared(");
        assertTrue(cleared.contains("CLEAR_MARKER"),
                "roomCleared must read the marker, not monster count");
        assertTrue(cleared.contains("isRestingFloor"),
                "a resting floor is clear by design - it raises no boss");
    }

    @Test
    void theClimbOpensTheDoorAndPlacesTheBodyOnThePlatform() throws IOException {
        String climb = methodBody(read(BOT), "private void climb(");
        assertTrue(climb.contains("setReactorState()"),
                "dojang_next refuses while the door reactor is shut; only dojang_up opens it");
        assertTrue(climb.contains("GCMovement.teleportTo("),
                "the platform is unreachable on foot - the placement stands in for DOJO_WARP_UP");
        assertFalse(climb.contains("PqActions.walkTo("),
                "walkTo can never report arrival on that platform (DojoDoorReachabilityTest)");
        assertTrue(climb.indexOf("setReactorState()") < climb.indexOf("enterPortal("),
                "the door must be open BEFORE the script is invoked, or it refuses");
    }

    /**
     * A resting floor must NOT take the door: those maps have no {@code door} reactor and no
     * {@code out00}, so {@code dojang_next} returns false on {@code gate == null} and the portal
     * can never advance the run. Its only advance is the leader's NPC turn (menu 0 of
     * {@code 2091005}, which warps the party), so the bot waits.
     */
    @Test
    void aRestingFloorWaitsForTheLeadersNpcTurn() throws IOException {
        String climb = methodBody(read(BOT), "private void climb(");
        assertTrue(climb.contains("isRestingFloor("),
                "climb must branch on the resting floor before touching out001");
        assertTrue(climb.indexOf("isRestingFloor(") < climb.indexOf("enterPortal("),
                "the resting-floor test must come BEFORE the door branch, or the door is walked");
        String resting = climb.substring(climb.indexOf("isRestingFloor("));
        resting = resting.substring(0, resting.indexOf('}'));
        assertFalse(resting.contains("changeMap("),
                "a rest floor is not the bot's to warp: NPC 2091005 only advances the LEADER");
        assertFalse(resting.contains("enterPortal("),
                "dojang_next refuses on a rest floor (no door reactor) - the portal is a dead end");
    }

    @Test
    void theBossIsRaisedBehindTheChannelsOwnProgressGate() throws IOException {
        String raise = methodBody(read(BOT), "private void raiseBoss(");
        assertTrue(raise.contains("setDojoProgress("),
                "dojang_1st's own gate: monotonic per tower slot, so the leader's entry cannot double-spawn");
        assertTrue(raise.indexOf("setDojoProgress(") < raise.indexOf("spawnDojoMonster("),
                "the gate has to be taken BEFORE spawning");
        assertTrue(raise.contains("bossIdFor("),
                "the boss id is the script's arithmetic, not a per-room table");
    }

    /** Waiting below the party is the guard against running the tower alone. */
    @Test
    void theBotWaitsWhenThePartyIsBelow() throws IOException {
        String stage = methodBody(read(BOT), "protected boolean workStage()");
        assertTrue(stage.contains("partyIsBelow("),
                "a bot that climbed alone must not keep climbing: the room's boss is the party's fight");
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static String methodBody(String src, String signature) {
        int start = src.indexOf(signature);
        assertFalse(start < 0, "method not found: " + signature);
        int depth = 0;
        int i = src.indexOf('{', start);
        for (; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return src.substring(start, i + 1);
            }
        }
        return src.substring(start);
    }
}
