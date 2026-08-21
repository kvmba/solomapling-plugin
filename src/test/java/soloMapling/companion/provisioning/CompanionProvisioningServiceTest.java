package soloMapling.companion.provisioning;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.companion.CompanionRoster;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionProvisioningServiceTest {

    @AfterEach
    void clearRoster() {
        CompanionRoster.clear();
    }

    @Test
    void registersOnlyAfterHostReportsAtomicSuccessAndErasesCredential() throws Exception {
        CapturingProvisioner host = new CapturingProvisioner();
        CompanionProvisioningService service = new CompanionProvisioningService(
                host, new SecureCompanionIdentityGenerator());

        CompanionProvisionResult result = service.provision("Mira", "42");

        assertEquals(700, result.characterId());
        assertEquals(42L, host.request.personaSeed());
        assertTrue(host.accountName.matches("cmp_[a-z2-9]{9}"));
        assertTrue(CompanionRoster.isCompanion(700));
        assertTrue(allZero(host.credential));
    }

    private static boolean allZero(char[] value) {
        char[] zeros = new char[value.length];
        return Arrays.equals(zeros, value);
    }

    private static final class CapturingProvisioner implements CompanionHostProvisioner {
        private CompanionProvisionRequest request;
        private String accountName;
        private char[] credential;

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
                char[] credential
        ) {
            this.request = request;
            this.accountName = accountName;
            this.credential = credential;
            return new CompanionProvisionResult(700, 800, request.characterName());
        }
    }
}
