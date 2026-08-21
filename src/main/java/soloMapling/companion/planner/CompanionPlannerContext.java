package soloMapling.companion.planner;

import soloMapling.companion.agent.CompanionStateSnapshot;
import soloMapling.companion.memory.MemoryRecord;
import soloMapling.companion.persistence.CompanionProfile;
import soloMapling.companion.persistence.CompanionRelationship;
import soloMapling.companion.persona.PersonaProfile;

import java.util.List;
import java.util.Objects;

/** Selected, engine-independent context that may be disclosed to the planner. */
public record CompanionPlannerContext(
        CompanionProfile profile,
        PersonaProfile persona,
        CompanionStateSnapshot state,
        List<MemoryRecord> relevantMemories,
        List<CompanionRelationship> relationships,
        List<String> gearAdvice) {

    public CompanionPlannerContext {
        profile = Objects.requireNonNull(profile, "profile must not be null");
        persona = Objects.requireNonNull(persona, "persona must not be null");
        state = Objects.requireNonNull(state, "state must not be null");
        relevantMemories = List.copyOf(
                Objects.requireNonNull(relevantMemories, "relevantMemories must not be null"));
        relationships = List.copyOf(
                Objects.requireNonNull(relationships, "relationships must not be null"));
        gearAdvice = List.copyOf(
                Objects.requireNonNull(gearAdvice, "gearAdvice must not be null"));
    }
}
