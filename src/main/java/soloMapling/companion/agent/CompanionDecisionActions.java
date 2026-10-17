package soloMapling.companion.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Normalizes planner reply/actions into one non-duplicating execution list.
 *
 * <p>{@link #forExecution(AgentDecision, String, int)} must run BEFORE
 * {@link ActionValidator#validate}: the training-request fallback APPENDS an
 * action, and an action appended after validation would bypass every gate the
 * validator enforces (the busy-actions cooldown above all) - a second player
 * could then take over a companion already training with someone else. The
 * planner service therefore validates the normalized list, and the play site
 * re-derives it with the SAME decision and the SAME message/speaker id - both
 * immutable - so the validated list is exactly the executed list. The one-arg
 * overload is the no-speaker degenerate case: it can never append (nobody to
 * train with) and is for tests and reply-folding only.
 */
public final class CompanionDecisionActions {

    private CompanionDecisionActions() {
    }

    public static List<CompanionAction> forExecution(AgentDecision decision) {
        return forExecution(decision, null, -1);
    }

    public static List<CompanionAction> forExecution(
            AgentDecision decision, String playerMessage, int playerCharacterId) {
        Objects.requireNonNull(decision, "decision");
        List<CompanionAction> actions = decision.actions();
        // Fold the reply into one Say first so the appended training action lands
        // after it and no caller can observe an un-normalized intermediate.
        boolean hasSay = actions.stream().anyMatch(CompanionAction.Say.class::isInstance);
        if (!hasSay && !decision.reply().isBlank()) {
            List<CompanionAction> withReply = new ArrayList<>(actions.size() + 1);
            withReply.add(new CompanionAction.Say(decision.reply()));
            withReply.addAll(actions);
            actions = List.copyOf(withReply);
        }
        // The explicit-request fallback. Kept here rather than at the play site so
        // the appended action is part of the list the validator judges. Requires a
        // real speaker id: the legacy no-speaker constructors pass -1, and -1 is
        // not a character id to train with.
        if (playerCharacterId > 0
                && playerMessage != null
                && requestsTraining(playerMessage)
                && actions.stream().noneMatch(CompanionAction.TrainWith.class::isInstance)) {
            List<CompanionAction> normalized = new ArrayList<>(actions.size() + 1);
            normalized.addAll(actions);
            normalized.add(new CompanionAction.TrainWith(playerCharacterId));
            actions = List.copyOf(normalized);
        }
        return actions;
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
