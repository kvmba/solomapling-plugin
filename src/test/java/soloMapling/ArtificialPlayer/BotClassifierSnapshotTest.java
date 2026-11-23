package soloMapling.ArtificialPlayer;

import org.gms.extension.api.ArtificialCharacters;
import org.gms.extension.api.CharacterClassifier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;

class BotClassifierSnapshotTest {
    @Test
    void scansWithOneRegistryReadAndKeepsClassifierStateLive() {
        var companion = new AtomicBoolean();
        List<CharacterClassifier> classifiers = List.of(
                id -> id == 42 && companion.get(), id -> id == 20_001);
        try (var registry = mockStatic(ArtificialCharacters.class)) {
            registry.when(ArtificialCharacters::classifiers).thenReturn(classifiers);
            var snapshot = BotHelpers.botClassifierSnapshot();
            assertFalse(snapshot.test(42));
            companion.set(true);
            assertTrue(snapshot.test(42));
            assertTrue(snapshot.test(20_001));
            assertFalse(snapshot.test(20_002)); // registered classifiers, not the legacy range, decide
            assertTrue(snapshot.test(999));
            registry.verify(ArtificialCharacters::classifiers, times(1));

            registry.when(ArtificialCharacters::classifiers).thenReturn(List.of());
            assertFalse(snapshot.test(20_002)); // this scan keeps its snapshot
            assertTrue(BotHelpers.botClassifierSnapshot().test(20_002)); // the next scan sees the new registry
        }
    }

    @Test
    void singleLookupAndScanAgreeOnTheLegacyFallback() {
        try (var registry = mockStatic(ArtificialCharacters.class)) {
            registry.when(ArtificialCharacters::classifiers).thenReturn(List.of());
            var snapshot = BotHelpers.botClassifierSnapshot();
            assertTrue(snapshot.test(999));
            assertTrue(BotHelpers.isBot(999));
            assertFalse(snapshot.test(20_000));
            assertFalse(BotHelpers.isBot(20_000));
            assertTrue(snapshot.test(20_001));
            assertTrue(BotHelpers.isBot(20_001));
        }
    }
}
