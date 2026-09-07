package soloMapling.companion.provisioning;

/** Validated, non-secret input for an atomic host provisioning operation. */
public record CompanionProvisionRequest(
        String characterName,
        long personaSeed,
        int worldId,
        String timezone) {

    /** Zone the companion's generated routine is written in. */
    public static final String DEFAULT_TIMEZONE = "Asia/Shanghai";

    public CompanionProvisionRequest {
        characterName = CompanionProvisioningInput.validateCharacterName(characterName);
        if (worldId < 0) {
            throw new IllegalArgumentException("worldId must not be negative");
        }
        timezone = CompanionProvisioningInput.validateTimezone(timezone);
    }

    public CompanionProvisionRequest(String characterName, long personaSeed) {
        this(characterName, personaSeed, 0, DEFAULT_TIMEZONE);
    }

    public CompanionProvisionRequest(String characterName, long personaSeed, int worldId) {
        this(characterName, personaSeed, worldId, DEFAULT_TIMEZONE);
    }
}
