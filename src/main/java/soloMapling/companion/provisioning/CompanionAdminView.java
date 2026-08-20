package soloMapling.companion.provisioning;

import java.time.Instant;

/** Non-secret provisioning metadata used by GM diagnostics. */
public record CompanionAdminView(
        int characterId,
        int accountId,
        String displayName,
        String status,
        boolean enabled,
        long personaSeed,
        String growthStage,
        String currentMode,
        Instant createdAt,
        Instant updatedAt,
        boolean accountPresent,
        boolean characterPresent,
        boolean ownershipMatches
) {
}
