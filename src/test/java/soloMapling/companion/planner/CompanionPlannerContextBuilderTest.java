package soloMapling.companion.planner;

import org.junit.jupiter.api.Test;
import soloMapling.companion.agent.CompanionStateSnapshot;
import soloMapling.companion.memory.MemoryRecord;
import soloMapling.companion.memory.MemoryScorer;
import soloMapling.companion.memory.MemoryType;
import soloMapling.companion.persistence.CompanionProfile;
import soloMapling.companion.persistence.CompanionRelationship;
import soloMapling.companion.persona.PersonaProfile;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The disclosure layer's authority set. A whisper reaches the companion from ANY map, so the
 * speaker's own memories and relationship row must survive the filter even when the speaker is
 * not standing next to it. Filtering by same-map alone answered cross-map whispers with
 * "Relevant memories: - none" - total amnesia about the person talking.
 */
class CompanionPlannerContextBuilderTest {

    private static final Instant NOW = Instant.parse("2026-08-20T12:00:00Z");

    @Test
    void crossMapSpeakerKeepsTheirOwnMemoriesAndRelationship() {
        CompanionPlannerContext context = new CompanionPlannerContextBuilder().build(
                input(Set.of(42), Set.of(42)));

        assertTrue(context.relevantMemories().stream()
                .anyMatch(memory -> memory.content().contains("Slime Tree")));
        assertTrue(context.relationships().stream()
                .anyMatch(relationship -> relationship.relatedCharacterId() == 42));
    }

    @Test
    void otherPlayersMemoriesStayFilteredOut() {
        // Widening the set for the speaker must not turn it into "everyone": a player who never
        // spoke to this companion is still not an allowed actor, and their rows stay hidden. The
        // stranger is NOT on this map (sameMapCharacterIds must stay a subset of
        // targetCharacterIds), which is the realistic shape - a cross-map whisper from someone the
        // companion has history with, while a different stranger is nowhere near it.
        CompanionPlannerContext context = new CompanionPlannerContextBuilder().build(
                input(Set.of(), Set.of(99)));

        assertFalse(context.relevantMemories().stream()
                .anyMatch(memory -> memory.content().contains("Slime Tree")));
        assertTrue(context.relationships().isEmpty());
    }

    private static CompanionPlannerInput input(
            Set<Integer> sameMapIds, Set<Integer> targetIds) {
        CompanionProfile profile = new CompanionProfile(
                7, 8, "Mira", "ACTIVE", true, 123,
                "persona", "system prompt", "hello", "UTC", "routine",
                "FRIEND", "TRAINING", null, null,
                NOW.minus(Duration.ofDays(30)), NOW);
        PersonaProfile persona = new PersonaProfile(
                123, List.of("careful"), "warm and concise",
                Map.of("training", "with friends"), List.of("never insult players"));
        CompanionStateSnapshot state = new CompanionStateSnapshot(
                100000000,
                sameMapIds,
                false,
                Set.of(100000000),
                targetIds,
                Set.of(),
                false);
        List<MemoryRecord> memories = List.of(memory("memory-1", "We cleared Slime Tree together."));
        List<CompanionRelationship> relationships = List.of(relationship(42));
        return new CompanionPlannerInput(
                profile, persona, state, memories,
                new MemoryScorer.Context(
                        NOW, Integer.toString(42), Integer.toString(100000000),
                        Set.of("conversation")),
                MemoryScorer.Parameters.defaults(),
                12, 0.05,
                relationships,
                "Want to train together?");
    }

    private static MemoryRecord memory(String id, String content) {
        return new MemoryRecord(
                id,
                MemoryType.EPISODIC,
                content,
                1.0,
                1.0,
                NOW.minus(Duration.ofHours(1)),
                null,
                "42",
                "100000000",
                Set.of("conversation"),
                false);
    }

    private static CompanionRelationship relationship(int relatedCharacterId) {
        return new CompanionRelationship(
                1L, 7, relatedCharacterId, "FRIEND", 10, 10, 10, 3L,
                "Reliable training partner.",
                NOW.minus(Duration.ofHours(1)), NOW, NOW);
    }
}
