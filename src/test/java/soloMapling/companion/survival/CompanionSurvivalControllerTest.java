package soloMapling.companion.survival;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionSurvivalControllerTest {

    @Test
    void scansRecoveryConsumablesWithoutParsingUnrelatedUseItems() {
        assertTrue(CompanionSurvivalController.isPotionCandidate(2000000));
        assertTrue(CompanionSurvivalController.isPotionCandidate(2020013));

        assertFalse(CompanionSurvivalController.isPotionCandidate(2048000));
        assertFalse(CompanionSurvivalController.isPotionCandidate(2060000));
        assertFalse(CompanionSurvivalController.isPotionCandidate(2070000));
        assertFalse(CompanionSurvivalController.isPotionCandidate(2330000));
    }
}
