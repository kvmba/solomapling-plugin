package soloMapling.companion.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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

    /**
     * Preserves an explicit player request to train even if the language model
     * mistakenly proposes FOLLOW alone. Combat remains subject to the normal
     * same-map target validation in the execution router.
     */
    public static List<CompanionAction> forExecution(
            AgentDecision decision, String playerMessage, int playerCharacterId) {
        List<CompanionAction> actions = forExecution(decision);
        if (!requestsTraining(playerMessage)
                || actions.stream().anyMatch(CompanionAction.TrainWith.class::isInstance)) {
            return actions;
        }
        List<CompanionAction> normalized = new ArrayList<>(actions.size() + 1);
        normalized.addAll(actions);
        normalized.add(new CompanionAction.TrainWith(playerCharacterId));
        return List.copyOf(normalized);
    }

    private static boolean requestsTraining(String message) {
        String normalized = message == null ? "" : message.toLowerCase(Locale.ROOT);
        boolean chinese = (normalized.contains("练级") || normalized.contains("打怪"))
                && (normalized.contains("一起") || normalized.contains("跟我"));
        boolean chineseResume = normalized.contains("怎么不打")
                || normalized.contains("为什么不打")
                || normalized.contains("咋不打");
        boolean english = normalized.contains("train with me")
                || normalized.contains("grind with me")
                || normalized.contains("fight monsters with me")
                || normalized.contains("why aren't you fighting")
                || normalized.contains("why are you not fighting");
        return chinese || chineseResume || english;
    }
}
