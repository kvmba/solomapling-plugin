package soloMapling.ArtificialPlayer.PartyQuest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the wiring of the combat beat's pickup gate, which lives in the only class that knows the
 * quest's own {@code steppedOnLootIds()}.
 *
 * <p>Why wiring and not behaviour: the behaviour is driven by the real driver in
 * {@code LudiPickupGateTest}; what this pins is that the beat
 * consults the gate BEFORE it lets the chase steer, and that the gate is derived from the driver's
 * live goal rather than from a flag (a flag would go stale whenever another producer replaced the
 * order, and a stale one kills the bot's chase for the rest of the run).
 */
class PqPickupGateWiringTest {

    @Test
    void theBeatConsultsThePickupGateBeforeTheChase() throws Exception {
        String body = methodBody(source(), "public void grindTick()");
        int gate = body.indexOf("walkingAtALootableDrop");
        int chase = body.lastIndexOf("seekAndAttack");
        assertTrue(gate > 0, "the combat beat must consult the pickup gate");
        assertTrue(chase > gate, "the gate must be checked BEFORE the chase is allowed to steer");
        assertTrue(body.contains("PqActions.attack(bot)"),
                "while a pickup order owns the wheel the beat must still swing");
    }

    @Test
    void theGateIsDerivedFromTheDriversLiveGoal() throws Exception {
        String body = methodBody(source(), "private boolean walkingAtALootableDrop(Character bot)");
        assertTrue(body.contains("PqActions.moveTargetOf"),
                "the gate must read where the driver is really walking");
        assertTrue(body.contains("steppedOnLootIds"),
                "only a quest's own scoopable items may hold the beat");
        assertTrue(body.contains("itemsOnFloorAt"),
                "the gate must be a pure query - it must never pick anything up");
        assertFalse(body.contains("GCMovement.move"),
                "the gate must not steer; the walk it protects was issued by the macro tick");
        assertTrue(body.contains("PICKUP_ARRIVAL_PX"),
                "the gate must release once the bot stands on its goal, or it freezes the bot");
    }

    private static String source() throws Exception {
        try (var in = PqPickupGateWiringTest.class.getClassLoader()
                .getResourceAsStream("soloMapling/ArtificialPlayer/PartyQuest/PartyQuestBot.java");
             var raw = in != null ? in
                     : new java.io.FileInputStream(
                             "src/main/java/soloMapling/ArtificialPlayer/PartyQuest/PartyQuestBot.java")) {
            return new String(raw.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private static String methodBody(String java, String signature) {
        int start = java.indexOf(signature);
        assertTrue(start > 0, "method must exist: " + signature);
        int open = java.indexOf('{', start);
        int depth = 0;
        for (int i = open; i < java.length(); i++) {
            char c = java.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return java.substring(open, i);
        }
        throw new IllegalStateException("unbalanced method body: " + signature);
    }
}
