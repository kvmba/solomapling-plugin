package soloMapling.ArtificialPlayer.BotGrindSystem;

import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackDriver;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/*
 * Guards the swing-first engage ordering that fixes the "bot keeps adjusting, never attacks" report.
 *
 * preSwingAdjust runs every in-range beat before engageAndSwing. Its adjusts (turn / kite / AoE
 * re-center) each fire on trigger conditions a moving mob satisfies repeatedly, so without the
 * swing-ready gate in front of the block they outrank the attack itself: the driver's per-swing
 * cooldown is the same order of magnitude as the adjust throttles, and a chasing mob keeps every
 * trigger hot. The fix: when the attack would land, swing this beat; adjusts happen in the
 * cooldown gap. The kite loop additionally gained hysteresis (a retreat must be re-earned by the
 * mob re-entering the comfort band) and fires the swing mid-retreat (shoot-on-move).
 */
class EngageBeatSwingPriorityTest {

    private static int constant(String name) throws Exception {
        Field f = EngageBeat.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.getInt(null);
    }

    /** The class-file constant pool, a proxy for "this method reference exists in the bytecode". */
    private static String constantPool() throws Exception {
        String res = EngageBeat.class.getName().replace('.', '/') + ".class";
        try (var in = EngageBeat.class.getClassLoader().getResourceAsStream(res)) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.ISO_8859_1);
        }
    }

    @Test
    void swingGateConsultsTheDriverCooldownSeam() throws Exception {
        // The gate must read the attack driver's own cooldown, not a parallel guess.
        Method seam = BotAttackDriver.class.getDeclaredMethod("nextAttackEpochMs", int.class);
        seam.setAccessible(true);
        assertEquals(0L, seam.invoke(null, 999_999_999),
                "an unknown bot must read as swing-ready (cooldown epoch 0)");
        assertTrue(constantPool().contains("nextAttackEpochMs"),
                "preSwingAdjust must consult the driver's cooldown seam for its swing-first gate");
    }

    @Test
    void kiteStepCannotRetriggerInsideTheGapItJustOpened() throws Exception {
        int min = constant("KITE_MIN_PX");
        int margin = constant("KITE_RETRIGGER_MARGIN_PX");
        assertTrue(margin > 0 && margin < min,
                "the re-trigger threshold (KITE_MIN_PX - margin) must sit strictly inside the band, "
                        + "else the hysteresis is a no-op and a chasing mob re-triggers every cooldown");
    }

    @Test
    void adjustsFireTheAttackOnTheirOwnBeat() throws Exception {
        // kiteIfTooClose / doTurnBeat / aoeRepositionIfWorthwhile all swing on the adjust beat
        // (shoot-on-move) instead of spending a beat and deferring the swing to the next one.
        assertTrue(constantPool().contains("botAttack"),
                "the engage adjusts must fire the in-reach swing on their own beat - a move-only beat "
                        + "is the 'steps around without attacking' behavior under fix");
    }

    @Test
    void aoeRecenterDeadzoneIsWiderThanAMobBeat() throws Exception {
        int deadzone = constant("AOE_REPOSITION_DEADZONE_PX");
        assertTrue(deadzone >= 100,
                "the re-center deadzone must exceed a mob's ~100px/beat drift, else a walking pack "
                        + "re-centers the bot on every cooldown beat (the left-right shuffle)");
    }
}
