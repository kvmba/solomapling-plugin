package soloMapling.companion.planner;

import soloMapling.companion.agent.CompanionStateSnapshot;
import soloMapling.companion.agent.CompanionBrain;
import soloMapling.companion.memory.MemoryRecord;
import soloMapling.companion.memory.MemoryScorer;
import soloMapling.companion.persistence.CompanionProfile;
import soloMapling.companion.persistence.CompanionRelationship;
import soloMapling.companion.persona.PersonaProfile;

import java.util.List;
import java.util.Objects;

/** Caller-supplied facts for one planning request. */
public record CompanionPlannerInput(
        CompanionProfile profile,
        PersonaProfile persona,
        CompanionStateSnapshot state,
        List<MemoryRecord> memoryCandidates,
        MemoryScorer.Context memoryContext,
        MemoryScorer.Parameters memoryParameters,
        int memoryLimit,
        double minimumMemoryStrength,
        List<CompanionRelationship> relationships,
        String playerMessage) {

    public static final int MAX_PLAYER_MESSAGE_LENGTH = CompanionBrain.MAX_PLAYER_MESSAGE_LENGTH;

    public CompanionPlannerInput {
        profile = Objects.requireNonNull(profile, "profile must not be null");
        persona = Objects.requireNonNull(persona, "persona must not be null");
        state = Objects.requireNonNull(state, "state must not be null");
        memoryCandidates = List.copyOf(
                Objects.requireNonNull(memoryCandidates, "memoryCandidates must not be null"));
        memoryContext = Objects.requireNonNull(memoryContext, "memoryContext must not be null");
        memoryParameters = Objects.requireNonNull(
                memoryParameters, "memoryParameters must not be null");
        if (memoryLimit < 0) {
            throw new IllegalArgumentException("memoryLimit must not be negative");
        }
        if (!Double.isFinite(minimumMemoryStrength)
                || minimumMemoryStrength < 0.0
                || minimumMemoryStrength > 1.0) {
            throw new IllegalArgumentException(
                    "minimumMemoryStrength must be finite and within [0, 1]");
        }
        relationships = List.copyOf(
                Objects.requireNonNull(relationships, "relationships must not be null"));
        playerMessage = Objects.requireNonNull(
                playerMessage, "playerMessage must not be null");
        if (playerMessage.length() > MAX_PLAYER_MESSAGE_LENGTH) {
            throw new IllegalArgumentException(
                    "playerMessage exceeds " + MAX_PLAYER_MESSAGE_LENGTH + " characters");
        }
        if (playerMessage.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("playerMessage must not contain NUL");
        }
        playerMessage = playerMessage.trim();
        if (playerMessage.isEmpty()) {
            throw new IllegalArgumentException("playerMessage must not be blank");
        }
    }
}
