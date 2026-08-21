package soloMapling.companion.agent;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionUpdateGateTest {

    @Test
    void inactiveTickRunsNoTrainingInviteOrPlanningWork() {
        AtomicInteger activeWork = new AtomicInteger();

        boolean ran = CompanionUpdateGate.runUnlessInactive(
                true,
                () -> {
                    activeWork.incrementAndGet(); // maintain training
                    activeWork.incrementAndGet(); // observe/enqueue invite
                    activeWork.incrementAndGet(); // planning tick
                });

        assertFalse(ran);
        assertEquals(0, activeWork.get());
    }

    @Test
    void activeTickRunsItsWorkExactlyOnce() {
        AtomicInteger activeWork = new AtomicInteger();

        assertTrue(CompanionUpdateGate.runUnlessInactive(
                false, activeWork::incrementAndGet));
        assertEquals(1, activeWork.get());
    }
}
