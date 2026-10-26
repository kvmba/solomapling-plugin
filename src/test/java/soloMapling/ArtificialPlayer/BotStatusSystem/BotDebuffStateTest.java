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

    @Test
    void expiryLocksTheSameDiseaseOutForFiveSeconds() {
        BotDebuffState s = new BotDebuffState(null);
        long t0 = 1_000_000L;
        assertTrue(s.apply(Disease.STUN, 1, 1, null, t0));

        long expiredAt = t0 + 1_000;
        s.tickAt(expiredAt);
        assertFalse(s.has(Disease.STUN));
        assertEquals(0, s.size());

        // The same disease cannot land again while the post-expiry window is open (engine rule).
        assertFalse(s.apply(Disease.STUN, LONG, 1, null, expiredAt + 1));
        assertTrue(s.isImmune(Disease.STUN, expiredAt + BotDebuffTable.DEBUFF_IMMUNE_MS - 1));
        assertFalse(s.isImmune(Disease.STUN, expiredAt + BotDebuffTable.DEBUFF_IMMUNE_MS),
                "the window is exactly DEBUFF_IMMUNE_MS long");
        assertTrue(s.apply(Disease.STUN, LONG, 1, null, expiredAt + BotDebuffTable.DEBUFF_IMMUNE_MS),
                "once the window closes it can land again");
    }

    @Test
    void aStillRunningDiseaseIsNotExpired() {
        BotDebuffState s = new BotDebuffState(null);
        long t0 = 2_000_000L;
        assertTrue(s.apply(Disease.SEAL, LONG, 1, null, t0));

        s.tickAt(t0);
        assertTrue(s.has(Disease.SEAL));
        assertFalse(s.isImmune(Disease.SEAL, t0), "no lockout before it ends");
    }

    @Test
    void anotherDiseasesLockoutDoesNotBlockTheRest() {
        BotDebuffState s = new BotDebuffState(null);
        long t0 = 3_000_000L;
        s.apply(Disease.STUN, 1, 1, null, t0);
        long expiredAt = t0 + 1_000;
        s.tickAt(expiredAt);

        assertTrue(s.apply(Disease.SEAL, LONG, 1, null, expiredAt + 1), "SEAL has its own, unused window");
    }
}
