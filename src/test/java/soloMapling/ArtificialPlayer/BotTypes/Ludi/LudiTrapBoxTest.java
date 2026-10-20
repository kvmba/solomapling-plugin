package soloMapling.ArtificialPlayer.BotTypes.Ludi;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the stage-2 trap box (2200002) contract behind the "bots stall the stage at 11/15"
 * deadlock: the turn-in wants 15 passes, the tower's eleven 2202003 boxes each drop exactly
 * one (server reactordrops, chance 1, {@code reactorTime=-1} so they never respawn), and the
 * four remaining passes only exist in the trap room the box's script warps the party into -
 * so the bot MUST pop it, but only once the tower's own boxes are all spent (the warp yanks
 * the whole party at once; a bot warped off its own climb wastes the boxes it left standing).
 *
 * <p>Locked here:
 * <ul>
 *   <li>{@code breakTowerBoxes} reaches the trap box ONLY inside the spent-tower gate
 *       ({@code findAllReactorOids(BOX_STAGE2).isEmpty()}) - not merely on {@code box < 0},
 *       which also fires while standing boxes are claimed by teammates, springing the warp
 *       on bot(s) still mid-work;</li>
 *   <li>the trap box is claimed and struck with the same rotate machinery as the pass
 *       boxes, so a stuck approach rotates rather than replaying a dead edge;</li>
 *   <li>the trap-room map itself never routes to tower work: {@code workStage}'s stage-2
 *       arm keeps sending a bot standing in 922010201 to {@code workTrapRoom}.</li>
 * </ul>
 */
class LudiTrapBoxTest {

    private static String javaSource(String file) throws Exception {
        return Files.readString(Path.of("src/main/java/soloMapling/ArtificialPlayer",
                file));
    }

    @Test
    void theTrapBoxIsOnlyPoppedOnceEveryTowerBoxIsSpent() throws Exception {
        String java = javaSource("BotTypes/Ludi/LudiStages.java");
        int start = java.indexOf("public static void breakTowerBoxes");
        assertTrue(start > 0, "breakTowerBoxes must exist");
        String body = java.substring(start, java.indexOf("\n    }", start));

        int spentGate = body.indexOf("findAllReactorOids(bot, LudiPqData.BOX_STAGE2).isEmpty()");
        int trapWork = body.indexOf("LudiPqData.BOX_STAGE2_TRAP");
        assertTrue(spentGate > 0, "the spent-tower gate must exist");
        assertTrue(trapWork > spentGate,
                "the trap box must only be reached INSIDE the spent-tower gate - the warp drags "
                        + "the whole party, so popping it while any pass box stands wastes that box");
    }

    @Test
    void theTrapBoxIsWorkedWithTheSharedBoxMachinery() throws Exception {
        String java = javaSource("BotTypes/Ludi/LudiStages.java");
        int start = java.indexOf("public static void breakTowerBoxes");
        String body = java.substring(start, java.indexOf("\n    }", start));

        int trap = body.indexOf("nearestOwnedBoxOid(bot, LudiPqData.BOX_STAGE2_TRAP)");
        assertTrue(trap > 0, "the trap box must go through the claim machinery (no double-pops)");
        assertTrue(body.contains("hitReactorDescendRotate(bot, trap, LudiPqData.BOX_STAGE2_TRAP)"),
                "the trap box must use the descend-rotate strike (the tower is one vertical room)");
    }

    @Test
    void aBotStandingInTheTrapRoomNeverBreaksTowerBoxes() throws Exception {
        String java = javaSource("BotTypes/Ludi/LudiPQBot.java");
        int arm = java.indexOf("case 2 ->");
        assertTrue(arm > 0, "workStage must route stage 2");
        String body = java.substring(arm, java.indexOf("case 3 ->", arm));

        int trapCheck = body.indexOf("getChr().getMapId() == LudiPqData.TRAP_ROOM");
        int towerWork = body.indexOf("breakTowerBoxes(getChr())");
        assertTrue(trapCheck > 0 && towerWork > trapCheck,
                "the stage-2 arm must check the trap room BEFORE tower work");
        assertTrue(body.contains("workTrapRoom(getChr())"),
                "a bot warped into the trap room works that room, not the tower");
    }
}
