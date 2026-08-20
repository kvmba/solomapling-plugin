package soloMapling.companion.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InviteTurnDeduplicatorTest {

    @Test
    void plansEachLiveInviteOnceAndRearmsAfterQueueClears() {
        InviteTurnDeduplicator deduplicator = new InviteTurnDeduplicator();

        assertTrue(deduplicator.shouldPlan("42:7"));
        assertFalse(deduplicator.shouldPlan("42:7"));
        assertTrue(deduplicator.shouldPlan("43:8"));
        assertFalse(deduplicator.shouldPlan("43:8"));

        deduplicator.clear();
        assertTrue(deduplicator.shouldPlan("43:8"));
    }
}
