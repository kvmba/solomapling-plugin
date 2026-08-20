package soloMapling.companion.planner;

import soloMapling.ArtificialPlayer.LlmSystem.LlmClient;
import soloMapling.ArtificialPlayer.LlmSystem.LlmRequest;
import soloMapling.companion.agent.ActionValidator;
import soloMapling.companion.agent.AgentDecision;
import soloMapling.companion.agent.AgentDecisionParseException;
import soloMapling.companion.agent.AgentDecisionParser;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Asynchronous orchestration boundary for one companion planning turn. */
public final class CompanionPlannerService {

    private final LlmClient client;
    private final AgentDecisionParser parser;
    private final ActionValidator validator;
    private final CompanionPlannerContextBuilder contextBuilder;
    private final CompanionPlannerPromptBuilder promptBuilder;
    private final Settings settings;

    public CompanionPlannerService(LlmClient client, Settings settings) {
        this(
                client,
                settings,
                new AgentDecisionParser(),
                new ActionValidator(),
                new CompanionPlannerContextBuilder(),
                new CompanionPlannerPromptBuilder());
    }

    CompanionPlannerService(
            LlmClient client,
            Settings settings,
            AgentDecisionParser parser,
            ActionValidator validator,
            CompanionPlannerContextBuilder contextBuilder,
            CompanionPlannerPromptBuilder promptBuilder) {
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.settings = Objects.requireNonNull(settings, "settings must not be null");
        this.parser = Objects.requireNonNull(parser, "parser must not be null");
        this.validator = Objects.requireNonNull(validator, "validator must not be null");
        this.contextBuilder = Objects.requireNonNull(contextBuilder, "contextBuilder must not be null");
        this.promptBuilder = Objects.requireNonNull(promptBuilder, "promptBuilder must not be null");
    }

    public CompletableFuture<CompanionPlannerResult> plan(CompanionPlannerInput input) {
        return plan(input, settings.timeout());
    }

    /** Allows the caller to override the service timeout for a particular turn. */
    public CompletableFuture<CompanionPlannerResult> plan(
            CompanionPlannerInput input, Duration timeout) {
        if (input == null) {
            return completedFailure(
                    CompanionPlannerResult.FailureType.CONTEXT_FAILURE,
                    "planner input must not be null");
        }

        final long timeoutMillis;
        try {
            timeoutMillis = positiveMillis(timeout);
        } catch (RuntimeException exception) {
            return completedFailure(
                    CompanionPlannerResult.FailureType.CONTEXT_FAILURE,
                    "invalid planner timeout");
        }

        final LlmRequest request;
        try {
            CompanionPlannerContext context = contextBuilder.build(input);
            request = new LlmRequest(
                    promptBuilder.build(context, input.playerMessage()),
                    settings.model(),
                    settings.maxTokens(),
                    settings.temperature());
        } catch (Throwable error) {
            return completedFailure(
                    CompanionPlannerResult.FailureType.CONTEXT_FAILURE,
                    "planner context could not be built");
        }

        final CompletableFuture<String> completion;
        try {
            completion = client.complete(request);
        } catch (Throwable error) {
            return completedFailure(
                    CompanionPlannerResult.FailureType.PROVIDER_FAILURE,
                    "LLM provider call failed");
        }
        if (completion == null) {
            return completedFailure(
                    CompanionPlannerResult.FailureType.PROVIDER_FAILURE,
                    "LLM provider returned no completion");
        }

        return completion.orTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                .handle((raw, error) -> finish(raw, error, input));
    }

    private CompanionPlannerResult finish(
            String raw, Throwable error, CompanionPlannerInput input) {
        try {
            if (error != null) {
                Throwable cause = unwrap(error);
                CompanionPlannerResult.FailureType type = cause instanceof TimeoutException
                        ? CompanionPlannerResult.FailureType.TIMEOUT
                        : CompanionPlannerResult.FailureType.PROVIDER_FAILURE;
                return CompanionPlannerResult.Failure.of(type, "LLM completion failed");
            }
            if (raw == null || raw.isBlank()) {
                return CompanionPlannerResult.Failure.of(
                        CompanionPlannerResult.FailureType.EMPTY_RESPONSE,
                        "LLM response was empty");
            }

            final AgentDecision decision;
            try {
                decision = parser.parse(raw);
            } catch (AgentDecisionParseException exception) {
                return CompanionPlannerResult.Failure.of(
                        CompanionPlannerResult.FailureType.INVALID_RESPONSE,
                        "LLM response was not valid schema v1 JSON");
            }

            ActionValidator.ValidationResult validation =
                    validator.validate(decision, input.state());
            if (!validation.valid()) {
                return new CompanionPlannerResult.Failure(
                        CompanionPlannerResult.FailureType.VALIDATION_REJECTED,
                        "LLM decision failed action validation",
                        validation.violations());
            }
            return new CompanionPlannerResult.Success(decision);
        } catch (Throwable errorWhileFinishing) {
            return CompanionPlannerResult.Failure.of(
                    CompanionPlannerResult.FailureType.INVALID_RESPONSE,
                    "LLM response could not be processed");
        }
    }

    private static CompletableFuture<CompanionPlannerResult> completedFailure(
            CompanionPlannerResult.FailureType type, String message) {
        return CompletableFuture.completedFuture(CompanionPlannerResult.Failure.of(type, message));
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static long positiveMillis(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout must not be null");
        long milliseconds = timeout.toMillis();
        if (timeout.isNegative() || timeout.isZero() || milliseconds <= 0) {
            throw new IllegalArgumentException("timeout must be at least one millisecond");
        }
        return milliseconds;
    }

    public record Settings(
            String model,
            int maxTokens,
            double temperature,
            Duration timeout) {

        public Settings {
            model = Objects.requireNonNull(model, "model must not be null").trim();
            if (model.isEmpty()) {
                throw new IllegalArgumentException("model must not be blank");
            }
            if (maxTokens <= 0) {
                throw new IllegalArgumentException("maxTokens must be positive");
            }
            if (!Double.isFinite(temperature) || temperature < 0.0) {
                throw new IllegalArgumentException("temperature must be finite and non-negative");
            }
            positiveMillis(timeout);
        }
    }
}
