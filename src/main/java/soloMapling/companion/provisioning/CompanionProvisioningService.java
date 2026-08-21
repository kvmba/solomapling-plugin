package soloMapling.companion.provisioning;

import soloMapling.companion.CompanionRoster;

import java.util.Arrays;
import java.util.Objects;

public final class CompanionProvisioningService {

    private final CompanionHostProvisioner hostProvisioner;
    private final SecureCompanionIdentityGenerator generator;

    public CompanionProvisioningService(
            CompanionHostProvisioner hostProvisioner,
            SecureCompanionIdentityGenerator generator
    ) {
        this.hostProvisioner = Objects.requireNonNull(hostProvisioner, "hostProvisioner");
        this.generator = Objects.requireNonNull(generator, "generator");
    }

    public CompanionProvisionResult provision(String characterName, String personaSeed) throws Exception {
        return provision(characterName, personaSeed, 0);
    }

    public CompanionProvisionResult provision(
            String characterName,
            String personaSeed,
            int worldId
    ) throws Exception {
        String validName = CompanionProvisioningInput.validateCharacterName(characterName);
        long seed = personaSeed == null
                ? generator.nextPersonaSeed()
                : CompanionProvisioningInput.parsePersonaSeed(personaSeed);
        if (!hostProvisioner.isAvailable()) {
            throw new ProvisioningUnavailableException(hostProvisioner.unavailableReason());
        }

        String accountName = generator.nextAccountName();
        char[] credential = generator.nextCredential();
        try {
            CompanionProvisionResult result = hostProvisioner.provision(
                    new CompanionProvisionRequest(validName, seed, worldId), accountName, credential);
            CompanionRoster.register(result.characterId());
            return result;
        } finally {
            Arrays.fill(credential, '\0');
        }
    }

    public String availabilityDescription() {
        return hostProvisioner.isAvailable() ? "available" : hostProvisioner.unavailableReason();
    }

    public static final class ProvisioningUnavailableException extends Exception {
        public ProvisioningUnavailableException(String message) {
            super(message);
        }
    }
}
