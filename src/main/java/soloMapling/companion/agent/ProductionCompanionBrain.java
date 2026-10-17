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
import soloMapling.companion.gear.CompanionGearAdvice;
import soloMapling.companion.planner.CompanionPlannerInput;
import soloMapling.companion.planner.CompanionPlannerResult;
import soloMapling.companion.planner.CompanionPlannerService;
import soloMapling.companion.execution.ActionExecutionResult;
import soloMapling.companion.execution.CompanionRuntimeCapabilities;
import soloMapling.server.ExecutorServiceManager;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Production composition of profile, persona, memory, relationship, perception,
 * planner, and best-effort persistence. Every JDBC operation is off the bot tick.
 */
public final class ProductionCompanionBrain implements CompanionBrain {

    /**
     * How many of a companion's most recent memories planning may recall.
     *
     * <p>This is a recall bound, not a forgetting point: MemorySelector then ranks the
     * candidates by salience/strength/recency and keeps the best {@code memoryLimit}, so raising
     * it widens the pool a long-lived companion can draw on. It was 100, which meant the
     * hundredth-most-recent episode was invisible to planning no matter how salient it was -
     * exactly the "the bot has forgotten everything about me" complaint on a companion that has
     * been talked to a lot.
     */
    private static final int MEMORY_RECALL_LIMIT = 200;

    /**
     * How long one episodic turn memory stays recallable.
     *
     * <p>Rows are inserted with this as {@code expires_at} (never null), so the repository's own
     * {@code expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP(3)} filter drops them and the
     * table stops growing without bound. Without a TTL every turn appended a permanent row and
     * nothing ever removed one.
     */
    private static final Duration EPISODIC_TTL = Duration.ofDays(30);

    private static volatile CompanionBrain sharedBrain;

    private static final Logger log = LoggerFactory.getLogger(ProductionCompanionBrain.class);

    private final CompanionPlannerService planner;
    private final CompanionProfileRepository profiles;
    private final CompanionMemoryRepository memories;
    private final CompanionRelationshipRepository relationships;
    private final CompanionActivityRepository activities;
    private final Executor executor;

    /**
     * The shared production brain.
     *
     * <p>Built once and reused: every companion used to get its own DeepSeek HTTP client and its
     * own four JDBC repositories, and those objects are stateless - the planner is thread-safe,
     * the repositories open a pooled connection per call and hold no fields, and the executor is
     * the shared pool. So N companions meant N HTTP clients and N sets of needless wrappers for
     * no behaviour difference.
     *
     * <p>The config is read once here, at construction, exactly like SocialLlmService's own
     * client - {@code SocialLlmConfig.configure} runs only from plugin onLoad, so there is no
     * runtime re-configuration path that a cached brain could go stale against.
     */
    public static synchronized CompanionBrain createDefault() {
        if (!SocialLlmConfig.enabled()) {
            return request -> CompletableFuture.completedFuture(CompanionPlannerResult.Failure.of(
                    CompanionPlannerResult.FailureType.PROVIDER_FAILURE,
                    "Companion LLM is disabled"));
        }
        if (sharedBrain == null) {
            CompanionPlannerService planner = new CompanionPlannerService(
                    DeepSeekLlmClient.create(SocialLlmConfig.apiKey(), SocialLlmConfig.baseUrl()),
                    new CompanionPlannerService.Settings(
                            SocialLlmConfig.model(),
                            Math.max(128, SocialLlmConfig.maxTokens()),
                            0.5,
                            Duration.ofMillis(SocialLlmConfig.timeoutMs())));
            sharedBrain = new ProductionCompanionBrain(
                    planner,
                    new JdbcCompanionProfileRepository(),
                    new JdbcCompanionMemoryRepository(),
                    new JdbcCompanionRelationshipRepository(),
                    new JdbcCompanionActivityRepository(),
                    ExecutorServiceManager.getExecutorService());
        }
        return sharedBrain;
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

    @Override
    public CompletableFuture<Optional<CompanionRelationship>> relationship(
            int companionCharacterId, int playerCharacterId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return relationships.get(companionCharacterId, playerCharacterId);
            } catch (Throwable error) {
                log.warn("Companion relationship lookup failed cid={} playerCid={}",
                        companionCharacterId, playerCharacterId, error);
                return Optional.empty();
            }
        }, executor);
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
                    null, null, MEMORY_RECALL_LIMIT);
            List<CompanionRelationship> related = relationships
                    .get(request.companionCharacterId(), request.playerCharacterId())
                    .map(List::of)
                    .orElseGet(List::of);
            PersonaProfile persona = persona(profile);
            CompanionStateSnapshot perception = request.perception().withGiftableItemIds(
                    CompanionGiftPolicy.allowedItemIds(
                            request.perception().inventoryItems(),
                            request.perception().gearGoal(),
                            persona,
                            related.stream().findFirst()));
            Instant now = Instant.now();
            log.debug("Companion context built cid={} playerCid={} memories={} relationships={} map={}",
                    request.companionCharacterId(), request.playerCharacterId(),
                    candidates.size(), related.size(), request.perception().currentMapId());
            return new CompanionPlannerInput(
                    profile,
                    persona,
                    perception,
                    candidates,
                    new MemoryScorer.Context(
                            now,
                            Integer.toString(request.playerCharacterId()),
                            Integer.toString(perception.currentMapId()),
                            Set.of("conversation")),
                    MemoryScorer.Parameters.defaults(),
                    12,
                    0.05,
                    related,
                    CompanionGearAdvice.forQuestion(
                            request.playerMessage(), perception.currentMapId(),
                            CompanionRuntimeCapabilities.dropSources()),
                    request.playerMessage(),
                    request.playerCharacterId());
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
                    "actions=" + turn.executions().size() + ";codes="
                            + turn.executions().stream()
                            .map(ActionExecutionResult::reasonCode)
                            .limit(8)
                            .toList()
                            + giftAudit(turn),
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
                    // A TTL, not null: this row is one turn's small talk, and an unbounded
                    // episodic table is what made the fixed 100-row recall window bite. The
                    // repository's own active-memory filter already honours expires_at.
                    now.plus(EPISODIC_TTL),
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
        persistRelationship(turn, summary, now);
    }

    private void persistRelationship(CompletedTurn turn, String summary, Instant now) {
        boolean successfulTurn = turn.result() instanceof CompanionPlannerResult.Success;
        if (!successfulTurn) {
            return;
        }
        int familiarity = 1;
        int trust = hasSuccessfulExecution(turn, "PARTY_ACCEPTED", "PARTY_INVITE_SENT",
                "FOLLOW_STARTED", "TRAINING_STARTED") ? 1 : 0;
        int affinity = hasSuccessfulExecution(turn, "GIFT_DROPPED") ? 1 : 0;
        try {
            relationships.upsertInteraction(
                    turn.request().companionCharacterId(),
                    turn.request().playerCharacterId(),
                    affinity > 0 ? "friend" : trust > 0 ? "companion" : "acquaintance",
                    familiarity,
                    trust,
                    affinity,
                    summary,
                    now);
        } catch (Throwable error) {
            log.warn("Companion relationship persistence failed cid={} playerCid={}",
                    turn.request().companionCharacterId(),
                    turn.request().playerCharacterId(), error);
        }
    }

    private static boolean hasSuccessfulExecution(CompletedTurn turn, String... reasonCodes) {
        Set<String> expected = Set.of(reasonCodes);
        for (ActionExecutionResult execution : turn.executions()) {
            if ((execution.status() == ActionExecutionResult.Status.SUCCESS
                    || execution.status() == ActionExecutionResult.Status.DEFERRED)
                    && expected.contains(execution.reasonCode())) {
                return true;
            }
        }
        return false;
    }

    private static String giftAudit(CompletedTurn turn) {
        if (!(turn.result() instanceof CompanionPlannerResult.Success success)
                || !hasSuccessfulExecution(turn, "GIFT_DROPPED")) {
            return "";
        }
        return success.decision().actions().stream()
                .filter(CompanionAction.DropGift.class::isInstance)
                .map(CompanionAction.DropGift.class::cast)
                .findFirst()
                .map(gift -> ";giftItemId=" + gift.itemId()
                        + ";giftTargetId=" + gift.characterId())
                .orElse("");
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
