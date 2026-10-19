package soloMapling.ArtificialPlayer.BotGrindSystem;

import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/*
 * Guards the engage beat's ordering against the two reports it exists to prevent:
 *
 *  1. "keeps adjusting, never attacks" - adjusts (turn / kite / AoE re-centre) must never outrank the
 *     swing indefinitely. Each adjust either changes the condition that triggered it or refuses to
 *     act, and the settle window is bounded, so the swing always comes back.
 *  2. "attacks, then instantly walks/jumps" - the beat must never start a second swing (or a
 *     reposition) on top of a live attack animation. The window and its enforcement live in the
 *     movement layer; the beat only has to consult it.
 *
 * The old version of this test pinned a 500ms self-owned walk lock and asserted the adjusts fired
 * their own swing ("shoot-on-move"). Both are gone: 500ms is shorter than the WZ swing itself, and a
 * swing fired on an adjust beat arms the animation lock over the step it just issued.
 */
class EngageBeatSwingPriorityTest {

    private static int constant(String name) throws Exception {
        Field f = EngageBeat.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.getInt(null);
    }

    private static long longConstant(String name) throws Exception {
        Field f = EngageBeat.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.getLong(null);
    }

    /** The class-file constant pool, a proxy for "this method reference exists in the bytecode". */
    private static String constantPool() throws Exception {
        String res = EngageBeat.class.getName().replace('.', '/') + ".class";
        try (var in = EngageBeat.class.getClassLoader().getResourceAsStream(res)) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.ISO_8859_1);
        }
    }

    @Test
    void swingGateConsultsTheMovementLayerAttackLock() throws Exception {
        assertTrue(constantPool().contains("isAttackActionLocked"),
                "preSwingAdjust must consult the movement layer's attack action lock, not keep a "
                        + "second, shorter copy of the window");
        assertEquals(false, GCMovement.isAttackActionLocked(null),
                "an unknown/uncontrolled bot must never read as mid-swing");
    }

    @Test
    void attackActionLockOutlastsEveryBasicSwingTheBotsPlay() {
        // The longest basic body pose the bot profiles use is shoot2 (820ms); swingO/T/P and stabO
        // are 800ms, the finisher forms 700ms (wz/Character.wz/00002000.img.xml). The lock must cover
        // the whole animation or the next walk packet truncates its last frames.
        assertTrue(GCMovement.ATTACK_ACTION_LOCK_MS >= 820L,
                "the attack action lock must cover the longest basic swing (820ms)");
        assertTrue(GCMovement.ATTACK_ACTION_LOCK_MS >= 1_000L,
                "the reported bot-perception floor is a full second between a swing and its next move");
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
    void aRepositionStepCannotHoldTheSwingForever() throws Exception {
        long cap = longConstant("ADJUST_SETTLE_MAX_MS");
        assertTrue(cap > 0 && cap <= 3_000L,
                "a reposition whose move never lands must stop holding the swing back within a few "
                        + "beats, else the bot returns to 'keeps repositioning, never fires'");
    }

    @Test
    void aoeRecenterDeadzoneIsWiderThanAMobBeat() throws Exception {
        int deadzone = constant("AOE_REPOSITION_DEADZONE_PX");
        assertTrue(deadzone >= 100,
                "the re-center deadzone must exceed a mob's ~100px/beat drift, else a walking pack "
                        + "re-centers the bot on every cooldown beat (the left-right shuffle)");
    }
}
