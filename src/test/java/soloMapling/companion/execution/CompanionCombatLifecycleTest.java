package soloMapling.companion.execution;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionCombatLifecycleTest {

    @Test
    void pausesAcrossMapsAndSafelyRestartsAfterArrival() {
        CompanionCombatLifecycle lifecycle = new CompanionCombatLifecycle();
        List<String> calls = new ArrayList<>();

        lifecycle.start(() -> calls.add("start"));
        assertTrue(lifecycle.active());

        lifecycle.reconcile(
                true, false,
                () -> calls.add("release"),
                () -> calls.add("follow"),
                () -> calls.add("restart"));
        assertFalse(lifecycle.active());
        lifecycle.reconcile(
                true, false,
                () -> calls.add("release-again"),
                () -> calls.add("duplicate-follow"),
                () -> calls.add("restart"));

        lifecycle.reconcile(
                true, true,
                () -> calls.add("release-again"),
                () -> calls.add("position"),
                () -> calls.add("restart"));
        assertTrue(lifecycle.active());

        lifecycle.stop(() -> calls.add("stop-release"));
        assertFalse(lifecycle.active());
        assertEquals(
                List.of("start", "release", "follow", "restart", "stop-release"),
                calls);
    }

    @Test
    void offlineTargetPausesWithoutRestartingOrFollowing() {
        CompanionCombatLifecycle lifecycle = new CompanionCombatLifecycle();
        List<String> calls = new ArrayList<>();
        lifecycle.start(() -> calls.add("start"));

        lifecycle.reconcile(
                false, false,
                () -> calls.add("release"),
                () -> calls.add("follow"),
                () -> calls.add("restart"));

        assertFalse(lifecycle.active());
        assertEquals(List.of("start", "release"), calls);
    }
}
