package soloMapling.companion.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.ArtificialPlayer.LlmSystem.DeepSeekLlmClient;
import soloMapling.ArtificialPlayer.LlmSystem.SocialLlmConfig;
import soloMapling.companion.memory.MemoryRecord;
import soloMapling.companion.memory.MemoryScorer;
import soloMapling.companion.memory.MemoryType;
import soloMapling.companion.persistence.CompanionActivityRepository;
import soloMapling.companion.persistence.CompanionMemoryRepository;
import soloMapling.companion.persistence.CompanionProfile;
import soloMapling.companion.persistence.CompanionProfileRepository;
import soloMapling.companion.persistence.CompanionRelationship;
import soloMapling.companion.persistence.CompanionRelationshipRepository;
import soloMapling.companion.persistence.JdbcCompanionActivityRepository;
import soloMapling.companion.persistence.JdbcCompanionMemoryRepository;
import soloMapling.companion.persistence.JdbcCompanionProfileRepository;
import soloMapling.companion.persistence.JdbcCompanionRelationshipRepository;
import soloMapling.companion.persona.PersonaProfile;
import soloMapling.companion.planner.CompanionPlannerInput;
import soloMapling.companion.planner.CompanionPlannerResult;
import soloMapling.companion.planner.CompanionPlannerService;
import soloMapling.server.ExecutorServiceManager;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Production composition of profile, persona, memory, relationship, perception,
 * planner, and best-effort persistence. Every JDBC operation is off the bot tick.
 */
public final class ProductionCompanionBrain implements CompanionBrain {

    private static final Logger log = LoggerFactory.getLogger(ProductionCompanionBrain.class);

    private final CompanionPlannerService planner;
    private final CompanionProfileRepository profiles;
    private final CompanionMemoryRepository memories;
    private final CompanionRelationshipRepository relationships;
    private final CompanionActivityRepository activities;
    private final Executor executor;

    public static CompanionBrain createDefault() {
        if (!SocialLlmConfig.enabled()) {
            return request -> CompletableFuture.completedFuture(CompanionPlannerResult.Failure.of(
                    CompanionPlannerResult.FailureType.PROVIDER_FAILURE,
                    "Companion LLM is disabled"));
        }
        CompanionPlannerService planner = new CompanionPlannerService(
                DeepSeekLlmClient.create(SocialLlmConfig.apiKey()),
                new CompanionPlannerService.Settings(
                        SocialLlmConfig.model(),
                        Math.max(128, SocialLlmConfig.maxTokens()),
                        0.5,
                        Duration.ofMillis(SocialLlmConfig.timeoutMs())));
        return new ProductionCompanionBrain(
                planner,
                new JdbcCompanionProfileRepository(),
                new JdbcCompanionMemoryRepository(),
                new JdbcCompanionRelationshipRepository(),
                new JdbcCompanionActivityRepository(),
                ExecutorServiceManager.getExecutorService());
    }

    public ProductionCompanionBrain(
            CompanionPlannerService planner,
            CompanionProfileRepository profiles,
            CompanionMemoryRepository memories,
            CompanionRelationshipRepository relationships,
            CompanionActivityRepository activities,
            Executor executor) {
        this.planner = java.util.Objects.requireNonNull(planner, "planner");
        this.profiles = java.util.Objects.requireNonNull(profiles, "profiles");
        this.memories = java.util.Objects.requireNonNull(memories, "memories");
        this.relationships = java.util.Objects.requireNonNull(relationships, "relationships");
        this.activities = java.util.Objects.requireNonNull(activities, "activities");
        this.executor = java.util.Objects.requireNonNull(executor, "executor");
    }

    @Override
    public CompletableFuture<CompanionPlannerResult> plan(TurnRequest request) {
        long startedNanos = System.nanoTime();
        return CompletableFuture.supplyAsync(() -> buildInput(request), executor)
                .thenCompose(input -> input == null
                        ? CompletableFuture.completedFuture(contextFailure())
                        : planner.plan(input))
                .whenComplete((result, error) -> {
                    long elapsedMs = Math.max(
                            0L, (System.nanoTime() - startedNanos) / 1_000_000L);
                    if (error != null) {
                        log.warn("Companion brain failed cid={} playerCid={} latencyMs={}",
                                request.companionCharacterId(),
                                request.playerCharacterId(), elapsedMs, error);
                    } else {
                        log.debug("Companion brain completed cid={} playerCid={} latencyMs={} result={}",
                                request.companionCharacterId(),
                                request.playerCharacterId(), elapsedMs,
                                result == null ? "null" : result.getClass().getSimpleName());
                    }
                })
                .exceptionally(error -> contextFailure());
    }

    private CompanionPlannerInput buildInput(TurnRequest request) {
        try {
            CompanionProfile profile = profiles.findByCharacterId(request.companionCharacterId())
                    .filter(CompanionProfile::enabled)
                    .orElse(null);
            if (profile == null) {
                return null;
            }
            List<MemoryRecord> candidates = memories.findCandidates(
                    request.companionCharacterId(), null, null, null, false,
                    null, null, 100);
            List<CompanionRelationship> related = relationships
                    .get(request.companionCharacterId(), request.playerCharacterId())
                    .map(List::of)
                    .orElseGet(List::of);
            Instant now = Instant.now();
            log.debug("Companion context built cid={} playerCid={} memories={} relationships={} map={}",
                    request.companionCharacterId(), request.playerCharacterId(),
                    candidates.size(), related.size(), request.perception().currentMapId());
            return new CompanionPlannerInput(
                    profile,
                    persona(profile),
                    request.perception(),
                    candidates,
                    new MemoryScorer.Context(
                            now,
                            Integer.toString(request.playerCharacterId()),
                            Integer.toString(request.perception().currentMapId()),
                            Set.of("conversation")),
                    MemoryScorer.Parameters.defaults(),
                    12,
                    0.05,
                    related,
                    request.playerMessage());
        } catch (Throwable error) {
            log.warn("Companion context build failed cid={} playerCid={}",
                    request.companionCharacterId(), request.playerCharacterId(), error);
            return null;
        }
    }

    @Override
    public void record(CompletedTurn turn) {
        CompletableFuture.runAsync(() -> persist(turn), executor)
                .exceptionally(error -> null);
    }

    private void persist(CompletedTurn turn) {
        Instant now = Instant.now();
        String outcome = turn.result() instanceof CompanionPlannerResult.Success
                ? "success" : "fallback";
        String summary = turn.result() instanceof CompanionPlannerResult.Success success
                ? safeSummary(success.decision().reply(), "Companion turn completed")
                : "Companion planner fallback";
        try {
            activities.append(
                    turn.request().companionCharacterId(),
                    "conversation",
                    outcome,
                    "player",
                    turn.request().playerCharacterId(),
                    summary,
                    "actions=" + turn.executions().size(),
                    now);
        } catch (Throwable ignored) {
            log.warn("Companion activity persistence failed cid={} playerCid={}",
                    turn.request().companionCharacterId(),
                    turn.request().playerCharacterId(), ignored);
        }
        try {
            memories.insert(
                    turn.request().companionCharacterId(),
                    "companion",
                    1,
                    null,
                    new MemoryRecord(
                            "pending-" + now.toEpochMilli(),
                            MemoryType.EPISODIC,
                            safeSummary(
                                    "Player said: " + turn.request().playerMessage()
                                            + "; companion outcome: " + summary,
                                    "Companion conversation"),
                            0.45,
                            0.5,
                            now,
                            null,
                            Integer.toString(turn.request().playerCharacterId()),
                            Integer.toString(turn.request().perception().currentMapId()),
                            Set.of("conversation"),
                            false));
        } catch (Throwable ignored) {
            log.warn("Companion memory persistence failed cid={} playerCid={}",
                    turn.request().companionCharacterId(),
                    turn.request().playerCharacterId(), ignored);
        }
    }

    private static PersonaProfile persona(CompanionProfile profile) {
        String raw = oneLine(profile.persona());
        String trait = raw.isBlank() ? "loyal" : raw;
        String voice = oneLine(profile.systemPrompt());
        if (voice.isBlank()) {
            voice = "warm, concise adventuring companion";
        }
        return new PersonaProfile(
                Math.max(0, profile.personaSeed()),
                List.of(trait),
                voice,
                Map.of("companionship", "stay helpful and grounded in current perception"),
                List.of("never invent unseen players or unknown maps"));
    }

    private static String oneLine(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.replace('\n', ' ').replace('\r', ' ').trim();
        return normalized.length() <= 240 ? normalized : normalized.substring(0, 240);
    }

    private static String safeSummary(String value, String fallback) {
        String normalized = oneLine(value);
        if (normalized.isBlank()) {
            normalized = fallback;
        }
        return normalized.length() <= 500 ? normalized : normalized.substring(0, 500);
    }

    private static CompanionPlannerResult contextFailure() {
        return CompanionPlannerResult.Failure.of(
                CompanionPlannerResult.FailureType.CONTEXT_FAILURE,
                "Companion context unavailable");
    }
}
