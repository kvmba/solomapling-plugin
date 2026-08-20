package soloMapling.companion.persistence;

import soloMapling.companion.progression.CompanionCareerBuild;

import java.time.Instant;
import java.util.Objects;

/**
 * Persistent metadata layered on top of a native BeiDou account/character.
 * Game state remains owned by the host's character tables.
 */
public record CompanionProfile(
        int characterId,
        int accountId,
        String displayName,
        String status,
        boolean enabled,
        long personaSeed,
        String careerBuild,
        String persona,
        String systemPrompt,
        String greeting,
        String routineTimezone,
        String routineProfile,
        String growthStage,
        String currentMode,
        Instant lastOnlineAt,
        Instant lastSettledAt,
        Instant createdAt,
        Instant updatedAt
) {

    public CompanionProfile {
        if (characterId <= 0) {
            throw new IllegalArgumentException("characterId must be positive");
        }
        if (accountId <= 0) {
            throw new IllegalArgumentException("accountId must be positive");
        }
        displayName = requireText(displayName, "displayName");
        status = requireText(status, "status");
        routineTimezone = requireText(routineTimezone, "routineTimezone");
        growthStage = requireText(growthStage, "growthStage");
        currentMode = requireText(currentMode, "currentMode");
        careerBuild = requireText(careerBuild, "careerBuild");
        CompanionCareerBuild.parse(careerBuild);
        persona = Objects.requireNonNullElse(persona, "");
        systemPrompt = Objects.requireNonNullElse(systemPrompt, "");
        greeting = Objects.requireNonNullElse(greeting, "");
        routineProfile = Objects.requireNonNullElse(routineProfile, "");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public CompanionProfile(
            int characterId,
            int accountId,
            String displayName,
            String status,
            boolean enabled,
            long personaSeed,
            String persona,
            String systemPrompt,
            String greeting,
            String routineTimezone,
            String routineProfile,
            String growthStage,
            String currentMode,
            Instant lastOnlineAt,
            Instant lastSettledAt,
            Instant createdAt,
            Instant updatedAt
    ) {
        this(
                characterId,
                accountId,
                displayName,
                status,
                enabled,
                personaSeed,
                CompanionCareerBuild.fromSeed(personaSeed).id(),
                persona,
                systemPrompt,
                greeting,
                routineTimezone,
                routineProfile,
                growthStage,
                currentMode,
                lastOnlineAt,
                lastSettledAt,
                createdAt,
                updatedAt);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
