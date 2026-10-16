package soloMapling.companion.execution;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static soloMapling.companion.execution.CompanionCombatRecoveryPolicy.Recovery.NONE;
import static soloMapling.companion.execution.CompanionCombatRecoveryPolicy.Recovery.REPATH;

class CompanionCombatRecoveryPolicyTest {

    @Test
    void initialEngagementNeverRepairs() {
        assertEquals(NONE, CompanionCombatRecoveryPolicy.choose(19_999, true));
        assertEquals(REPATH, CompanionCombatRecoveryPolicy.choose(20_000, true));
    }

    @Test
    void observedCombatAlwaysUsesPlayerVisibleRepathing() {
        assertEquals(REPATH, CompanionCombatRecoveryPolicy.choose(90_000, true));
        assertEquals(REPATH, CompanionCombatRecoveryPolicy.choose(300_000, true));
    }

    @Test
    void cooldownSuppressesEveryRecovery() {
        // The cooldown is what keeps a stalled companion from re-pathing every sweep.
        assertEquals(NONE, CompanionCombatRecoveryPolicy.choose(300_000, false));
        assertEquals(NONE, CompanionCombatRecoveryPolicy.choose(300_000, false));
    }

    @Test
    void unobservedStallRepathsRatherThanTeleporting() {
        // POSITION_REPAIR used to fire here, but its caller only reaches the recovery check
        // after asserting the target is on this map and online - and a real player on the map is
        // what makes the map observed. The branch was structurally unreachable, so the teleport
        // it guarded was removed rather than left as a latent glitch.
        assertEquals(REPATH, CompanionCombatRecoveryPolicy.choose(300_000, true));
    }
}
