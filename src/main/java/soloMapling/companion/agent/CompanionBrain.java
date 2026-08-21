package soloMapling.companion.agent;

import soloMapling.companion.execution.ActionExecutionResult;
import soloMapling.companion.persistence.CompanionRelationship;
import soloMapling.companion.planner.CompanionPlannerResult;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Injectable asynchronous boundary between the game-facing bot and companion cognition.
 * Implementations must never perform blocking persistence or provider work before returning.
 */
public interface CompanionBrain {

    int MAX_PLAYER_MESSAGE_LENGTH = 500;

    CompletionStage<CompanionPlannerResult> plan(TurnRequest request);

    default void record(CompletedTurn turn) {
        // Optional best-effort persistence hook. Production implementations offload before returning.
    }

    default CompletionStage<Optional<CompanionRelationship>> relationship(
            int companionCharacterId, int playerCharacterId) {
        return CompletableFuture.completedFuture(Optional.empty());
    }

    record TurnRequest(
            int companionCharacterId,
            int playerCharacterId,
            String playerMessage,
            CompanionStateSnapshot perception) {
        public TurnRequest {
            if (companionCharacterId <= 0 || playerCharacterId <= 0) {
                throw new IllegalArgumentException("character ids must be positive");
            }
            playerMessage = Objects.requireNonNull(playerMessage, "playerMessage").trim();
            if (playerMessage.isEmpty()) {
                throw new IllegalArgumentException("playerMessage must not be blank");
            }
            if (playerMessage.length() > MAX_PLAYER_MESSAGE_LENGTH) {
                throw new IllegalArgumentException(
                        "playerMessage exceeds " + MAX_PLAYER_MESSAGE_LENGTH + " characters");
            }
            perception = Objects.requireNonNull(perception, "perception");
        }
    }

    record CompletedTurn(
            TurnRequest request,
            CompanionPlannerResult result,
            List<ActionExecutionResult> executions) {
        public CompletedTurn {
            request = Objects.requireNonNull(request, "request");
            result = Objects.requireNonNull(result, "result");
            executions = List.copyOf(Objects.requireNonNull(executions, "executions"));
        }
    }
}
