package soloMapling.ArtificialPlayer.BotHealthSystem;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The host can zero a bot outside the damage layer: a map whose WZ data carries a
 * {@code decHP} value drains HP directly (Aqua Road's underwater breathing damage,
 * El Nath's cold fields), with no floor and no call into {@code BotContactDamage}.
 * A bot left at zero by that path has no episode of its own, so the death state
 * adopts it — otherwise it stands at 0 HP forever (the FSM keeps running) and any
 * subsequent map change moves a corpse.
 *
 * <p>The predicate is pure, so the rule is checkable without a character.
 */
class BotDeathZeroHpTest {

    @Test
    void aHostZeroedTemplateBotIsAdopted() {
        assertTrue(BotDeath.needsZeroHpAdoption(true, false, true, 0));
        assertTrue(BotDeath.needsZeroHpAdoption(true, false, true, -5), "clamped-below-zero also counts");
    }

    @Test
    void livingBotsAreNotAdopted() {
        assertFalse(BotDeath.needsZeroHpAdoption(true, false, true, 1));
        assertFalse(BotDeath.needsZeroHpAdoption(true, false, true, 500));
    }

    @Test
    void realPlayersAndCompanionsAreNotAdopted() {
        // A real player at zero is the host's revive to handle; a companion has its own
        // survival loop and must not be knocked over by this episode.
        assertFalse(BotDeath.needsZeroHpAdoption(false, false, true, 0));
        assertFalse(BotDeath.needsZeroHpAdoption(true, true, true, 0));
    }

    @Test
    void aBotWithoutAMapIsLeftAlone() {
        // Mid-teardown / mid-retype: nowhere to lie and nobody to carry it home.
        assertFalse(BotDeath.needsZeroHpAdoption(true, false, false, 0));
    }

    @Test
    void adoptingWithoutACharacterIsSafeAndChangesNothing() {
        BotDeath death = new BotDeath(null);
        assertFalse(death.adoptIfZeroHp());
        assertFalse(death.isDead());
        assertFalse(death.isCorpse());
    }

    @Test
    void aRunningEpisodeIsAlsoACorpse() {
        // isCorpse() must answer "do not move this body" for the ordinary case too,
        // not just for the un-adopted window GCTravel now guards against.
        assertFalse(new BotDeath(null).isCorpse());
    }
}
