package soloMapling.companion.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionInteractionPolicyTest {

    @Test
    void acceptsOnlySameMapRealPlayerFromActiveSession() {
        assertTrue(CompanionInteractionPolicy.allowsContinuation(
                true, false, true, 100, 100));
        assertFalse(CompanionInteractionPolicy.allowsContinuation(
                true, false, true, 100, 200));
        assertFalse(CompanionInteractionPolicy.allowsContinuation(
                true, true, true, 100, 100));
        assertFalse(CompanionInteractionPolicy.allowsContinuation(
                true, false, false, 100, -1));
        assertFalse(CompanionInteractionPolicy.allowsContinuation(
                false, false, true, 100, 100));
    }
}
