package soloMapling.companion.planner;

import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.LlmSystem.LlmClient;
import soloMapling.ArtificialPlayer.LlmSystem.LlmMessage;
import soloMapling.ArtificialPlayer.LlmSystem.LlmRequest;
import soloMapling.companion.agent.CompanionAction;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionPlannerServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-20T12:00:00Z");
    private static final String SUCCESS_JSON = """
            {"schemaVersion":1,"reply":"Let's go.","reason":"The player asked to train.",
            "actions":[{"schemaVersion":1,"type":"TRAIN_WITH","characterId":42}]}""";

    @Test
    void successUsesBoundedPromptAndReturnsValidatedDecision() {
        AtomicReference<LlmRequest> received = new AtomicReference<>();
        CompanionPlannerService service = service(request -> {
            received.set(request);
            return CompletableFuture.completedFuture(SUCCESS_JSON);
        });

        CompanionPlannerResult result = service.plan(input()).join();

        CompanionPlannerResult.Success success =
                assertInstanceOf(CompanionPlannerResult.Success.class, result);
        assertEquals("Let's go.", success.decision().reply());
        assertEquals(
                List.of(new CompanionAction.TrainWith(42)),
                success.decision().actions());

        LlmRequest request = received.get();
        assertEquals("fake-model", request.model());
        assertEquals(2, request.messages().size());
        assertEquals(LlmMessage.Role.SYSTEM, request.messages().getFirst().role());
        assertEquals(LlmMessage.Role.USER, request.messages().getLast().role());
        assertEquals("Want to train together?", request.messages().getLast().content());

        String prompt = request.messages().getFirst().content();
        assertTrue(prompt.contains("schema v1"));
        assertTrue(prompt.contains("displayName: Mira"));
        assertTrue(prompt.contains("careful"));
        assertTrue(prompt.contains("currentMapId: 100000000"));
        assertTrue(prompt.contains("targetCharacterIds: [42]"));
        assertTrue(prompt.contains("We cleared Slime Tree together."));
        assertTrue(prompt.contains("Reliable training partner."));
        assertFalse(prompt.contains("WEAK_MEMORY_MUST_NOT_APPEAR"));
        assertFalse(prompt.contains("PROFILE_SYSTEM_PROMPT_MUST_NOT_LEAK"));
        assertFalse(prompt.contains("PROFILE_PERSONA_BLOB_MUST_NOT_LEAK"));
        assertFalse(prompt.contains("999999999"));
    }

    @Test
    void excludesRelationshipSummariesForCharactersOutsideTargetAllowlist() {
        CompanionPlannerInput base = input();
        CompanionRelationship unauthorized = relationship(
                2, 777, "UNAUTHORIZED_RELATIONSHIP_MUST_NOT_APPEAR");
        CompanionPlannerInput request = copyInput(
                base,
                base.memoryCandidates(),
                List.of(base.relationships().getFirst(), unauthorized),
                base.playerMessage());

        String prompt = captureRequest(request).messages().getFirst().content();

        assertTrue(prompt.contains("Reliable training partner."));
        assertFalse(prompt.contains("UNAUTHORIZED_RELATIONSHIP_MUST_NOT_APPEAR"));
        assertFalse(prompt.contains("characterId=777"));
    }

    @Test
    void excludesMemoriesWithUnauthorizedActorsOrUnknownMaps() {
        CompanionPlannerInput base = input();
        List<MemoryRecord> candidates = List.of(
                memory("allowed-actor", "ALLOWED_ACTOR_MEMORY", "42", "100000000"),
                memory("self-memory", "SELF_MEMORY_WITH_CONTROL_\u0007_NORMALIZED", null,
                        "100000000"),
                memory("unauthorized-actor", "UNAUTHORIZED_ACTOR_MUST_NOT_APPEAR", "777",
                        "100000000"),
                memory("invalid-actor", "INVALID_ACTOR_MUST_NOT_APPEAR", "player:42",
                        "100000000"),
                memory("unknown-map", "UNKNOWN_MAP_MUST_NOT_APPEAR", null, "999999999"),
                memory("invalid-map", "INVALID_MAP_MUST_NOT_APPEAR", null, "map:100000000"));
        CompanionPlannerInput request = copyInput(
                base, candidates, base.relationships(), base.playerMessage());

        String prompt = captureRequest(request).messages().getFirst().content();

        assertTrue(prompt.contains("ALLOWED_ACTOR_MEMORY"));
        assertTrue(prompt.contains("SELF_MEMORY_WITH_CONTROL_ _NORMALIZED"));
        assertFalse(prompt.contains("UNAUTHORIZED_ACTOR_MUST_NOT_APPEAR"));
        assertFalse(prompt.contains("INVALID_ACTOR_MUST_NOT_APPEAR"));
        assertFalse(prompt.contains("UNKNOWN_MAP_MUST_NOT_APPEAR"));
        assertFalse(prompt.contains("INVALID_MAP_MUST_NOT_APPEAR"));
    }

    @Test
    void boundsPlayerMessageAndNormalizesPromptControlCharacters() {
        CompanionPlannerInput base = input();

        assertThrows(IllegalArgumentException.class, () -> copyInput(
                base,
                base.memoryCandidates(),
                base.relationships(),
                "x".repeat(CompanionPlannerInput.MAX_PLAYER_MESSAGE_LENGTH + 1)));
        assertThrows(IllegalArgumentException.class, () -> copyInput(
                base,
                base.memoryCandidates(),
                base.relationships(),
                "hello\0world"));

        CompanionPlannerInput request = copyInput(
                base,
                base.memoryCandidates(),
                base.relationships(),
                "hello\tworld\nnext\u200Bpart");
        String normalized = captureRequest(request).messages().getLast().content();

        assertEquals("hello world next part", normalized);
        assertFalse(normalized.codePoints().anyMatch(Character::isISOControl));
    }

    @Test
    void providerFailureReturnsTypedFailureWithoutExceptionalCompletion() {
        CompanionPlannerService service = service(request ->
                CompletableFuture.failedFuture(new IllegalStateException("provider unavailable")));

        CompanionPlannerResult.Failure failure = assertFailure(service.plan(input()).join());

        assertEquals(CompanionPlannerResult.FailureType.PROVIDER_FAILURE, failure.type());
    }

    @Test
    void badJsonReturnsTypedInvalidResponse() {
        CompanionPlannerService service =
                service(request -> CompletableFuture.completedFuture("not-json"));

        CompanionPlannerResult.Failure failure = assertFailure(service.plan(input()).join());

        assertEquals(CompanionPlannerResult.FailureType.INVALID_RESPONSE, failure.type());
    }

    @Test
    void validationRejectionReturnsViolations() {
        String rejected = """
                {"schemaVersion":1,"reply":"Okay.","reason":"Trying an unknown destination.",
                "actions":[{"schemaVersion":1,"type":"GO_TO","mapId":999999999}]}""";
        CompanionPlannerService service =
                service(request -> CompletableFuture.completedFuture(rejected));

        CompanionPlannerResult.Failure failure = assertFailure(service.plan(input()).join());

        assertEquals(CompanionPlannerResult.FailureType.VALIDATION_REJECTED, failure.type());
        assertEquals(1, failure.violations().size());
        assertEquals("MAP_NOT_ALLOWED", failure.violations().getFirst().code());
    }

    private static CompanionPlannerResult.Failure assertFailure(CompanionPlannerResult result) {
        return assertInstanceOf(CompanionPlannerResult.Failure.class, result);
    }

    private static CompanionPlannerService service(LlmClient client) {
        return new CompanionPlannerService(
                client,
                new CompanionPlannerService.Settings(
                        "fake-model", 256, 0.2, Duration.ofSeconds(1)));
    }

    private static LlmRequest captureRequest(CompanionPlannerInput input) {
        AtomicReference<LlmRequest> received = new AtomicReference<>();
        CompanionPlannerResult result = service(request -> {
            received.set(request);
            return CompletableFuture.completedFuture(SUCCESS_JSON);
        }).plan(input).join();
        assertInstanceOf(CompanionPlannerResult.Success.class, result);
        return received.get();
    }

    private static CompanionPlannerInput copyInput(
            CompanionPlannerInput base,
            List<MemoryRecord> memories,
            List<CompanionRelationship> relationships,
            String playerMessage) {
        return new CompanionPlannerInput(
                base.profile(),
                base.persona(),
                base.state(),
                memories,
                base.memoryContext(),
                base.memoryParameters(),
                10,
                base.minimumMemoryStrength(),
                relationships,
                playerMessage);
    }

    private static MemoryRecord memory(
            String id, String content, String actorKey, String mapKey) {
        return new MemoryRecord(
                id,
                MemoryType.EPISODIC,
                content,
                1.0,
                1.0,
                NOW.minus(Duration.ofHours(1)),
                null,
                actorKey,
                mapKey,
                Set.of("training"),
                false);
    }

    private static CompanionRelationship relationship(
            long id, int relatedCharacterId, String summary) {
        return new CompanionRelationship(
                id,
                7,
                relatedCharacterId,
                "FRIEND",
                10,
                10,
                10,
                3,
                summary,
                NOW.minus(Duration.ofHours(1)),
                NOW.minus(Duration.ofDays(7)),
                NOW);
    }

    private static CompanionPlannerInput input() {
        CompanionProfile profile = new CompanionProfile(
                7,
                8,
                "Mira",
                "ACTIVE",
                true,
                123,
                "PROFILE_PERSONA_BLOB_MUST_NOT_LEAK",
                "PROFILE_SYSTEM_PROMPT_MUST_NOT_LEAK",
                "hello",
                "UTC",
                "routine",
                "FRIEND",
                "TRAINING",
                null,
                null,
                NOW.minus(Duration.ofDays(30)),
                NOW);
        PersonaProfile persona = new PersonaProfile(
                123,
                List.of("careful", "loyal"),
                "warm and concise",
                Map.of("training", "with friends"),
                List.of("never insult players"));
        CompanionStateSnapshot state = new CompanionStateSnapshot(
                100000000,
                Set.of(42),
                false,
                Set.of(100000000, 101000000),
                Set.of(42),
                Set.of(),
                false);
        MemoryRecord relevantMemory = new MemoryRecord(
                "memory-1",
                MemoryType.EPISODIC,
                "We cleared Slime Tree together.",
                1.0,
                1.0,
                NOW.minus(Duration.ofHours(1)),
                null,
                "42",
                "100000000",
                Set.of("training"),
                false);
        MemoryRecord weakMemory = new MemoryRecord(
                "memory-2",
                MemoryType.SEMANTIC,
                "WEAK_MEMORY_MUST_NOT_APPEAR",
                1.0,
                0.1,
                NOW.minus(Duration.ofHours(1)),
                null,
                null,
                null,
                Set.of(),
                false);
        CompanionRelationship relationship =
                relationship(1, 42, "Reliable training partner.");
        return new CompanionPlannerInput(
                profile,
                persona,
                state,
                List.of(relevantMemory, weakMemory),
                new MemoryScorer.Context(
                        NOW, "42", "100000000", Set.of("training")),
                MemoryScorer.Parameters.defaults(),
                4,
                0.5,
                List.of(relationship),
                "Want to train together?");
    }
}
