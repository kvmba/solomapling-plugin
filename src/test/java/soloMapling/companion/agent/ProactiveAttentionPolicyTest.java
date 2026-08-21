package soloMapling.companion.agent;

import org.junit.jupiter.api.Test;
import soloMapling.companion.persistence.CompanionRelationship;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProactiveAttentionPolicyTest {
    private static final Instant NOW = Instant.parse("2026-08-21T10:00:00Z");

    @Test
    void strangersAndBusyCompanionsNeverInitiate() {
        assertFalse(ProactiveAttentionPolicy.shouldInitiate(
                Optional.of(relationship(1, 0, 0, NOW.minusSeconds(7200))),
                NOW, null, true));
        assertFalse(ProactiveAttentionPolicy.shouldInitiate(
                Optional.of(relationship(20, 5, 5, NOW.minusSeconds(7200))),
                NOW, null, false));
    }

    @Test
    void closenessShortensCooldownWithoutSpamming() {
        CompanionRelationship close = relationship(20, 2, 2, NOW.minusSeconds(360));
        assertTrue(ProactiveAttentionPolicy.shouldInitiate(
                Optional.of(close), NOW, null, true));
        assertFalse(ProactiveAttentionPolicy.shouldInitiate(
                Optional.of(close), NOW, NOW.minusSeconds(60), true));
    }

    private static CompanionRelationship relationship(
            int familiarity, int trust, int affinity, Instant lastInteraction) {
        return new CompanionRelationship(
                1, 5, 7, "friend", familiarity, trust, affinity, 10,
                "", lastInteraction, NOW.minusSeconds(10_000), NOW);
    }
}
