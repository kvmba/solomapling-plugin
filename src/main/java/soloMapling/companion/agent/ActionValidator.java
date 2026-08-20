package soloMapling.companion.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Pure policy validation for proposed actions. Validation has no engine side
 * effects and grants no authority to execute accepted actions.
 */
public final class ActionValidator {

    public ValidationResult validate(
            AgentDecision decision, CompanionStateSnapshot snapshot) {
        Objects.requireNonNull(decision, "decision must not be null");
        Objects.requireNonNull(snapshot, "snapshot must not be null");

        List<Violation> violations = new ArrayList<>();
        for (int index = 0; index < decision.actions().size(); index++) {
            CompanionAction action = decision.actions().get(index);
            validateAction(action, snapshot, index, violations);
        }
        return new ValidationResult(violations);
    }

    private static void validateAction(
            CompanionAction action,
            CompanionStateSnapshot state,
            int index,
            List<Violation> violations) {
        if (state.cooldownActions().contains(action.type())) {
            add(violations, index, "ACTION_ON_COOLDOWN",
                    action.type() + " is on cooldown");
        }

        switch (action) {
            case CompanionAction.Say ignored -> {
                // Constructor-level bounds are sufficient.
            }
            case CompanionAction.Emote ignored -> {
                // Constructor-level bounds are sufficient.
            }
            case CompanionAction.AcceptParty accept -> {
                requireKnownTarget(accept.characterId(), state, index, violations);
                requireNotInParty(state, index, violations);
                requireNotEngaged(state, index, violations);
            }
            case CompanionAction.InviteParty invite -> {
                requireKnownTarget(invite.characterId(), state, index, violations);
                requireSameMap(invite.characterId(), state, index, violations);
                requireNotInParty(state, index, violations);
                requireNotEngaged(state, index, violations);
            }
            case CompanionAction.Follow follow -> {
                requireKnownTarget(follow.characterId(), state, index, violations);
                requireSameMap(follow.characterId(), state, index, violations);
                requireNotEngaged(state, index, violations);
            }
            case CompanionAction.GoTo goTo -> {
                if (!state.knownMapIds().contains(goTo.mapId())) {
                    add(violations, index, "MAP_NOT_ALLOWED",
                            "mapId is not in knownMapIds: " + goTo.mapId());
                }
                requireNotEngaged(state, index, violations);
            }
            case CompanionAction.TrainWith trainWith -> {
                requireKnownTarget(trainWith.characterId(), state, index, violations);
                requireSameMap(trainWith.characterId(), state, index, violations);
            }
            case CompanionAction.Rest ignored ->
                    requireNotEngaged(state, index, violations);
            case CompanionAction.Goodbye ignored -> {
                // Always safe as a planner-domain action.
            }
        }
    }

    private static void requireKnownTarget(
            int characterId,
            CompanionStateSnapshot state,
            int index,
            List<Violation> violations) {
        if (!state.targetCharacterIds().contains(characterId)) {
            add(violations, index, "TARGET_NOT_ALLOWED",
                    "characterId is not in targetCharacterIds: " + characterId);
        }
    }

    private static void requireSameMap(
            int characterId,
            CompanionStateSnapshot state,
            int index,
            List<Violation> violations) {
        if (!state.sameMapCharacterIds().contains(characterId)) {
            add(violations, index, "TARGET_NOT_ON_SAME_MAP",
                    "characterId is not on the same map: " + characterId);
        }
    }

    private static void requireNotInParty(
            CompanionStateSnapshot state, int index, List<Violation> violations) {
        if (state.inParty()) {
            add(violations, index, "ALREADY_IN_PARTY",
                    "party action is not allowed while already in a party");
        }
    }

    private static void requireNotEngaged(
            CompanionStateSnapshot state, int index, List<Violation> violations) {
        if (state.engaged()) {
            add(violations, index, "CURRENTLY_ENGAGED",
                    "action is not allowed while engaged");
        }
    }

    private static void add(
            List<Violation> violations, int index, String code, String message) {
        violations.add(new Violation(index, code, message));
    }

    public record ValidationResult(List<Violation> violations) {
        public ValidationResult {
            Objects.requireNonNull(violations, "violations must not be null");
            violations = List.copyOf(violations);
        }

        public boolean valid() {
            return violations.isEmpty();
        }
    }

    public record Violation(int actionIndex, String code, String message) {
        public Violation {
            if (actionIndex < 0) {
                throw new IllegalArgumentException("actionIndex must not be negative");
            }
            Objects.requireNonNull(code, "code must not be null");
            Objects.requireNonNull(message, "message must not be null");
        }
    }
}
