package soloMapling.companion.planner;

import soloMapling.companion.agent.ActionValidator;
import soloMapling.companion.agent.AgentDecision;

import java.util.List;
import java.util.Objects;

/** Typed outcome that keeps provider and model failures off the game tick thread. */
public sealed interface CompanionPlannerResult
        permits CompanionPlannerResult.Success, CompanionPlannerResult.Failure {

    record Success(AgentDecision decision) implements CompanionPlannerResult {
        public Success {
            decision = Objects.requireNonNull(decision, "decision must not be null");
        }
    }

    record Failure(
            FailureType type,
            String message,
            List<ActionValidator.Violation> violations) implements CompanionPlannerResult {
        public Failure {
            type = Objects.requireNonNull(type, "type must not be null");
            message = Objects.requireNonNull(message, "message must not be null");
            violations = List.copyOf(
                    Objects.requireNonNull(violations, "violations must not be null"));
        }

        public static Failure of(FailureType type, String message) {
            return new Failure(type, message, List.of());
        }
    }

    enum FailureType {
        CONTEXT_FAILURE,
        PROVIDER_FAILURE,
        TIMEOUT,
        EMPTY_RESPONSE,
        INVALID_RESPONSE,
        VALIDATION_REJECTED
    }
}
