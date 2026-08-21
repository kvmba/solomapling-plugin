package soloMapling.companion.provisioning;

public record CompanionProvisionResult(int characterId, int accountId, String displayName) {

    public CompanionProvisionResult {
        if (characterId <= 0 || accountId <= 0) {
            throw new IllegalArgumentException("native identity ids must be positive");
        }
        displayName = CompanionProvisioningInput.validateCharacterName(displayName);
    }
}
