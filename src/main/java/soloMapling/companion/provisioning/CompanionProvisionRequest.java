package soloMapling.companion.provisioning;

/** Validated, non-secret input for an atomic host provisioning operation. */
public record CompanionProvisionRequest(String characterName, long personaSeed, int worldId) {

    public CompanionProvisionRequest {
        characterName = CompanionProvisioningInput.validateCharacterName(characterName);
        if (worldId < 0) {
            throw new IllegalArgumentException("worldId must not be negative");
        }
    }

    public CompanionProvisionRequest(String characterName, long personaSeed) {
        this(characterName, personaSeed, 0);
    }
}
