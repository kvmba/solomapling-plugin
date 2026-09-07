package soloMapling.companion.intake;

import org.junit.jupiter.api.Test;
import soloMapling.companion.lifecycle.CompanionLifecycleAccess;
import soloMapling.companion.provisioning.CompanionHostProvisioner;
import soloMapling.companion.provisioning.CompanionProvisionRequest;
import soloMapling.companion.provisioning.CompanionProvisionResult;
import soloMapling.companion.provisioning.CompanionProvisioningService;
import soloMapling.companion.provisioning.SecureCompanionIdentityGenerator;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The roster is a process-wide static that other tests register into, and the
 * intake reads it to learn how many companions the world already had — so each
 * test starts from a known-empty roster rather than inheriting someone else's.
 */
class CompanionIntakeServiceTest {

    @org.junit.jupiter.api.BeforeEach
    void clearRoster() {
        soloMapling.companion.CompanionRoster.clear();
    }

    private static final class FakeProvisioner implements CompanionHostProvisioner {
        final AtomicInteger calls = new AtomicInteger();
        volatile String rejectName;

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public String unavailableReason() {
            return "";
        }

        @Override
        public CompanionProvisionResult provision(
                CompanionProvisionRequest request,
                String accountName,
                char[] credential) {
            calls.incrementAndGet();
            if (request.characterName().equals(rejectName)) {
                throw new IllegalStateException("name is already in use");
            }
            return new CompanionProvisionResult(
                    calls.get(), 900 + calls.get(), request.characterName());
        }
    }

    private static CompanionIntakeService service(
            FakeProvisioner provisioner,
            CompanionIntakeService.NameSource names,
            int maxTotal) {
        return new CompanionIntakeService(
                new CompanionProvisioningService(
                        provisioner, new SecureCompanionIdentityGenerator()),
                new CompanionLifecycleAccess(),
                names,
                60_000L,
                maxTotal,
                0,
                CompanionProvisionRequest.DEFAULT_TIMEZONE);
    }

    @Test
    void registersOneCompanionPerIteration() {
        FakeProvisioner provisioner = new FakeProvisioner();
        CompanionIntakeService intake = service(provisioner, () -> "Wanderer", 10);

        intake.registerOne();

        assertEquals(1, intake.registered());
        assertEquals(0, intake.failed());
    }

    @Test
    void aTakenNameCostsOneRetryNotTheInterval() {
        FakeProvisioner provisioner = new FakeProvisioner();
        provisioner.rejectName = "First";
        AtomicInteger draw = new AtomicInteger();
        CompanionIntakeService intake = service(provisioner,
                () -> draw.getAndIncrement() == 0 ? "First" : "Second", 10);

        intake.registerOne();

        assertEquals(1, intake.registered());
        assertEquals(0, intake.failed(), "a name collision is ordinary and must not count");
    }

    @Test
    void givesUpAfterRepeatedFailuresAndStillCountsOneInterval() {
        FakeProvisioner provisioner = new FakeProvisioner();
        provisioner.rejectName = "Stuck";
        CompanionIntakeService intake = service(provisioner, () -> "Stuck", 10);

        intake.registerOne();

        assertEquals(0, intake.registered());
        assertEquals(1, intake.failed(), "one skipped interval, however many attempts it took");
    }

    @Test
    void stopsAtThePopulationCap() {
        FakeProvisioner provisioner = new FakeProvisioner();
        AtomicInteger draw = new AtomicInteger();
        CompanionIntakeService intake = service(provisioner,
                () -> "Bot" + draw.incrementAndGet(), 3);

        for (int i = 0; i < 6; i++) {
            intake.registerOne();
        }

        assertEquals(3, intake.registered(), "must not exceed the cap");
    }

    /**
     * The cap counts the companions the world already had. A restart must not
     * treat them as headroom and top a full world up to twice its size.
     */
    @Test
    void countsCompanionsTheWorldAlreadyHad() {
        FakeProvisioner provisioner = new FakeProvisioner();
        AtomicInteger draw = new AtomicInteger();
        soloMapling.companion.CompanionRoster.register(555_001);
        soloMapling.companion.CompanionRoster.register(555_002);
        try {
            CompanionIntakeService intake = service(provisioner,
                    () -> "Bot" + draw.incrementAndGet(), 3);

            for (int i = 0; i < 5; i++) {
                intake.registerOne();
            }

            assertEquals(1, intake.registered(),
                    "only one slot was free out of a cap of three");
        } finally {
            soloMapling.companion.CompanionRoster.unregister(555_001);
            soloMapling.companion.CompanionRoster.unregister(555_002);
        }
    }

    @Test
    void aBlankNameIsSkippedRatherThanSentToTheHost() {
        FakeProvisioner provisioner = new FakeProvisioner();
        AtomicInteger draw = new AtomicInteger();
        CompanionIntakeService intake = service(provisioner,
                () -> draw.getAndIncrement() == 0 ? "  " : "Real", 10);

        intake.registerOne();

        assertEquals(1, intake.registered());
        assertEquals(1, provisioner.calls.get(), "the blank draw was not provisioned");
    }

    @Test
    void rejectsAnIntervalThatIsNotPositive() {
        FakeProvisioner provisioner = new FakeProvisioner();
        assertThrows(IllegalArgumentException.class, () -> new CompanionIntakeService(
                new CompanionProvisioningService(
                        provisioner, new SecureCompanionIdentityGenerator()),
                new CompanionLifecycleAccess(),
                () -> "Wanderer",
                0L,
                10,
                0,
                CompanionProvisionRequest.DEFAULT_TIMEZONE));
    }
}
