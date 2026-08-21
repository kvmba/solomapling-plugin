package soloMapling.companion.execution;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static soloMapling.companion.execution.CompanionCombatRecoveryPolicy.Recovery.NONE;
import static soloMapling.companion.execution.CompanionCombatRecoveryPolicy.Recovery.POSITION_REPAIR;
import static soloMapling.companion.execution.CompanionCombatRecoveryPolicy.Recovery.REPATH;

class CompanionCombatRecoveryPolicyTest {

    @Test
    void initialEngagementNeverPositionRepairs() {
        assertEquals(NONE, CompanionCombatRecoveryPolicy.choose(19_999, true, true));
        assertEquals(REPATH, CompanionCombatRecoveryPolicy.choose(20_000, true, true));
    }

    @Test
    void observedCombatAlwaysUsesPlayerVisibleRepathing() {
        assertEquals(REPATH, CompanionCombatRecoveryPolicy.choose(90_000, true, true));
        assertEquals(REPATH, CompanionCombatRecoveryPolicy.choose(300_000, true, true));
    }

    @Test
    void positionRepairRequiresLongUnobservedStallAndReadyCooldown() {
        assertEquals(REPATH, CompanionCombatRecoveryPolicy.choose(89_999, false, true));
        assertEquals(POSITION_REPAIR,
                CompanionCombatRecoveryPolicy.choose(90_000, false, true));
        assertEquals(NONE, CompanionCombatRecoveryPolicy.choose(90_000, false, false));
    }
}
