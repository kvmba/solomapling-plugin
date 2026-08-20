package soloMapling.companion.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Normalizes planner reply/actions into one non-duplicating execution list. */
public final class CompanionDecisionActions {

    private CompanionDecisionActions() {
    }

    public static List<CompanionAction> forExecution(AgentDecision decision) {
        Objects.requireNonNull(decision, "decision");
        List<CompanionAction> actions = decision.actions();
        boolean hasSay = actions.stream().anyMatch(CompanionAction.Say.class::isInstance);
        if (hasSay || decision.reply().isBlank()) {
            return actions;
        }
        List<CompanionAction> normalized = new ArrayList<>(actions.size() + 1);
        normalized.add(new CompanionAction.Say(decision.reply()));
        normalized.addAll(actions);
        return List.copyOf(normalized);
    }
}
