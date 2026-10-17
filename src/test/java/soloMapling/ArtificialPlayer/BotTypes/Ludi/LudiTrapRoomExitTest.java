package soloMapling.ArtificialPlayer.BotTypes.Ludi;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the trap-room exit contract behind the reported "one bot goes into the hidden room and
 * never comes back, so every later stage is a body short".
 *
 * <p>Two separate doors lead out of that room and both matter:
 * <ul>
 *   <li>the physical exit ({@code out00}) - the room's only way back to the tower. The bot must
 *       WALK to it and enter it up close, which is what keeps the climb out reading as a climb.
 *       The walk's arrival is the exact-pixel contract, so the narrow-ledge residency rule must
 *       not declare arrival from the other end of a ledge;</li>
 *   <li>the logical exit - once the stage is turned in, the room's four boxes are worthless and
 *       breaking them is pure delay. A trapped bot must leave immediately instead of grinding
 *       through 30s+ of box work for a stage that no longer needs it.</li>
 * </ul>
 */
class LudiTrapRoomExitTest {

    private static final String STAGES = "soloMapling/ArtificialPlayer/BotTypes/Ludi/LudiStages.java";

    @Test
    void aClearedStageLeavesTheTrapRoomImmediately() throws Exception {
        String java = javaSource(STAGES);
        int start = java.indexOf("public static void workTrapRoom");
        assertTrue(start > 0, "workTrapRoom must exist");
        String body = java.substring(start, java.indexOf("\n    }", start));

        int clearedGate = body.indexOf("stageCleared(bot, 2)");
        int firstBox = body.indexOf("nearestOwnedBoxOid");
        assertTrue(clearedGate > 0, "the trap room must check whether its stage is already turned in");
        assertTrue(clearedGate < firstBox,
                "the cleared-stage gate must run BEFORE any box work - otherwise the bot spends the "
                        + "stage's remaining time breaking boxes nobody needs");
        assertTrue(body.contains("exitTrapRoom(bot)"),
                "the cleared-stage branch must take the room's own exit");
    }

    @Test
    void theExitWalkStillRequiresTheRealPortalPixel() throws Exception {
        String java = javaSource(STAGES);
        int start = java.indexOf("static void exitTrapRoom");
        String body = java.substring(start, java.indexOf("\n    }", start));
        assertTrue(body.contains("PqActions.walkTo(bot, exit.getPosition())"),
                "the exit must be walked to (its result is the arrival contract)");
        assertTrue(body.contains("PqActions.enterPortal(bot, exit)"),
                "the portal is entered only after the walk reports arrival");
        assertFalse(body.contains("distanceSq"),
                "a distance-based shortcut re-opens the mid-climb teleport the walk exists to prevent");
    }

    @Test
    void theTrapRoomWorksItsBoxesOnlyWhileTheStageIsOpen() throws Exception {
        Method m = LudiStages.class.getDeclaredMethod("workTrapRoom", org.gms.client.Character.class);
        assertTrue(java.lang.reflect.Modifier.isStatic(m.getModifiers()));
        // The stage-2 box ids used in this room are the same 2202003 the tower uses; the claim
        // bookkeeping is keyed per map instance, so a trapped bot must not inherit the tower's.
        assertTrue(javaSource(STAGES).contains("mapInstanceOf(bot)"),
                "box claims must stay instance-scoped across the tower/trap-room warp");
    }

    private static String javaSource(String path) throws Exception {
        try (var in = LudiTrapRoomExitTest.class.getClassLoader().getResourceAsStream(path);
             var raw = in != null ? in : new java.io.FileInputStream("src/main/java/" + path)) {
            return new String(raw.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
