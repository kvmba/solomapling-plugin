package soloMapling.ArtificialPlayer.BotStatusSystem;

import org.gms.client.Disease;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behaviour of a bot's live debuffs. Drives the character-free {@code apply(disease, duration, x, null)}
 * core, so no {@code Character} / WZ data is needed (the engine's constructors trip the Server
 * initializer, and Mockito is not on the test classpath).
 */
class BotDebuffStateTest {

    private static final long LONG = 60_000L;

    @Test
    void freshStateHasNoEffects() {
        BotDebuffState s = new BotDebuffState(null);

        assertEquals(0, s.size());
        assertFalse(s.isFrozen());
        assertFalse(s.blocksAttack());
        assertEquals(1.0, s.moveFactor());
        assertEquals(1.0, s.outFactor());
        assertEquals(1.0, s.takenFactor());
        assertFalse(s.whiffs());
    }

    @Test
    void stunFreezesAndDisarms() {
        BotDebuffState s = new BotDebuffState(null);
        assertTrue(s.apply(Disease.STUN, LONG, 1, null));

        assertTrue(s.isFrozen());
        assertTrue(s.blocksAttack());
    }

    @Test
    void sealDisarmsWithoutFreezing() {
        BotDebuffState s = new BotDebuffState(null);
        s.apply(Disease.SEAL, LONG, 1, null);

        assertFalse(s.isFrozen());
        assertTrue(s.blocksAttack());
    }

    @Test
    void slowScalesMoveOnly() {
        BotDebuffState s = new BotDebuffState(null);
        s.apply(Disease.SLOW, LONG, 1, null);

        assertTrue(s.moveFactor() < 1.0);
        assertEquals(1.0, s.outFactor());
        assertEquals(1.0, s.takenFactor());
        assertFalse(s.blocksAttack());
    }

    @Test
    void weakenScalesOutputAndIntake() {
        BotDebuffState s = new BotDebuffState(null);
        s.apply(Disease.WEAKEN, LONG, 1, null);

        assertTrue(s.outFactor() < 1.0);
        assertTrue(s.takenFactor() > 1.0);
        assertFalse(s.blocksAttack());
    }

    @Test
    void darknessRollsWhiffs() {
        BotDebuffState s = new BotDebuffState(null);
        s.apply(Disease.DARKNESS, LONG, 1, null);

        boolean sawMiss = false;
        boolean sawHit = false;
        for (int i = 0; i < 500 && !(sawMiss && sawHit); i++) {
            if (s.whiffs()) {
                sawMiss = true;
            } else {
                sawHit = true;
            }
        }
        assertTrue(sawMiss, "darkness should sometimes whiff");
        assertTrue(sawHit, "darkness should sometimes still land");
    }

    @Test
    void reapplyingTheSameDiseaseIsIgnored() {
        BotDebuffState s = new BotDebuffState(null);
        assertTrue(s.apply(Disease.STUN, LONG, 1, null));
        assertFalse(s.apply(Disease.STUN, LONG, 1, null)); // already stunned
        assertEquals(1, s.size());
    }

    @Test
    void engineTwoDiseaseCapIsHonoured() {
        BotDebuffState s = new BotDebuffState(null);
        assertTrue(s.apply(Disease.STUN, LONG, 1, null));
        assertTrue(s.apply(Disease.POISON, LONG, 1, null));
        assertFalse(s.apply(Disease.SLOW, LONG, 1, null)); // third refused
        assertEquals(BotDebuffTable.MAX_ACTIVE, s.size());
    }

    @Test
    void nullDiseaseIsRejected() {
        BotDebuffState s = new BotDebuffState(null);
        assertFalse(s.apply(Disease.NULL, LONG, 1, null));
        assertEquals(0, s.size());
    }

    @Test
    void clearAllDropsEverything() {
        BotDebuffState s = new BotDebuffState(null);
        s.apply(Disease.STUN, LONG, 1, null);
        s.apply(Disease.SLOW, LONG, 1, null);

        s.clearAll();

        assertEquals(0, s.size());
        assertFalse(s.isFrozen());
        assertEquals(1.0, s.moveFactor());
    }

    @Test
    void curseIsTrackedButGatesNoBehaviour() {
        BotDebuffState s = new BotDebuffState(null);
        assertTrue(s.apply(Disease.CURSE, LONG, 1, null)); // still counted (its packet gets cancelled)

        assertTrue(s.has(Disease.CURSE));
        assertFalse(s.blocksAttack());
        assertFalse(s.isFrozen());
        assertEquals(1.0, s.moveFactor());
    }
}
