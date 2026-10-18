package soloMapling.ArtificialPlayer;

import org.gms.extension.api.ArtificialCharacters;
import org.gms.extension.api.CharacterClassifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Truth table for {@link BotHelpers#isBot(int)}.
 *
 * <p>The method has two inputs: the character id and whether the host has any
 * {@link CharacterClassifier} registered. The performance pass reordered its two registry reads
 * (classifier walk first, "is any bound?" second) to keep the common bot answer allocation-free;
 * this test pins that the reorder changed nothing observable in steady state, for both a
 * classifier that recognises the id and one that does not.</p>
 *
 * <p>{@code ArtificialCharacters} is a plain static registry in the extension API, so no host
 * context is needed - but it is process-wide, hence the {@code clear()} in teardown.</p>
 */
class BotHelpersIsBotTest {

    /** The plugin's own classifier shape: any id above 20000 is artificial. */
    private static final CharacterClassifier WIDE = id -> id > 20000;

    /** A third-party classifier that owns a narrow range and does NOT claim 20001. */
    private static final CharacterClassifier NARROW = id -> id == 7;

    @AfterEach
    void clearRegistry() {
        ArtificialCharacters.clear();
    }

    @Test
    void sentinelIdIsAlwaysArtificial() {
        ArtificialCharacters.clear();
        assertTrue(BotHelpers.isBot(999), "999 is the console bot, artificial even with no classifier");
        ArtificialCharacters.register(NARROW);
        assertTrue(BotHelpers.isBot(999), "999 stays artificial whatever a classifier claims");
    }

    @Test
    void noClassifierFallsBackToTheLegacyIdConvention() {
        ArtificialCharacters.clear();
        assertTrue(BotHelpers.isBot(20001), "no classifier bound: ids above 20000 are bots");
        assertTrue(BotHelpers.isBot(Integer.MAX_VALUE));
        assertFalse(BotHelpers.isBot(20000), "20000 itself is a real-player id");
        assertFalse(BotHelpers.isBot(1));
    }

    @Test
    void aBoundClassifierDecidesForIdsItClaims() {
        ArtificialCharacters.register(WIDE);
        assertTrue(BotHelpers.isBot(20001), "the registered classifier says bot");
        assertFalse(BotHelpers.isBot(7), "a bound classifier's miss must NOT fall back to the id rule");
        assertFalse(BotHelpers.isBot(20000));
    }

    @Test
    void aBoundClassifierThatDoesNotClaimTheIdStillSuppressesTheLegacyFallback() {
        // The regression this pins: with a classifier bound, the old code returned the
        // classifier's verdict outright. So a high id the classifier does not claim is NOT a bot.
        ArtificialCharacters.register(NARROW);
        assertTrue(BotHelpers.isBot(7), "claimed by the classifier");
        assertFalse(BotHelpers.isBot(20001),
                "a bound classifier's miss must not be overridden by 'id > 20000'");
        assertFalse(BotHelpers.isBot(123456));
    }

    @Test
    void clearingTheRegistryRestoresTheLegacyFallback() {
        ArtificialCharacters.register(WIDE);
        assertFalse(BotHelpers.isBot(12345));
        ArtificialCharacters.clear();
        assertFalse(BotHelpers.isBot(12345), "still a real-player id under the fallback");
        assertTrue(BotHelpers.isBot(20001), "unloaded plugin: the historical id rule is back");
    }
}
