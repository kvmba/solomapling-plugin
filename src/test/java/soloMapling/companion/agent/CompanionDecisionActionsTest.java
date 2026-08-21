package soloMapling.companion.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class CompanionDecisionActionsTest {

    @Test
    void actionSayWinsOverReplyWithoutDuplicateBubble() {
        AgentDecision decision = new AgentDecision(
                1,
                "reply bubble",
                "test",
                List.of(new CompanionAction.Emote("smile"), new CompanionAction.Say("action bubble")));

        List<CompanionAction> actions = CompanionDecisionActions.forExecution(decision);

        assertEquals(2, actions.size());
        assertEquals(1, actions.stream().filter(CompanionAction.Say.class::isInstance).count());
        assertEquals("action bubble", ((CompanionAction.Say) actions.get(1)).text());
    }

    @Test
    void replyBecomesSayOnlyWhenActionsContainNoSay() {
        AgentDecision decision = new AgentDecision(
                1, "reply bubble", "test", List.of(new CompanionAction.Rest()));

        List<CompanionAction> actions = CompanionDecisionActions.forExecution(decision);

        assertEquals(2, actions.size());
        assertInstanceOf(CompanionAction.Say.class, actions.getFirst());
        assertEquals("reply bubble", ((CompanionAction.Say) actions.getFirst()).text());
    }

    @Test
    void explicitTrainingRequestAddsTrainActionWhenPlannerOnlyFollows() {
        AgentDecision decision = new AgentDecision(
                1, "一起走吧", "test", List.of(new CompanionAction.Follow(42)));

        List<CompanionAction> actions =
                CompanionDecisionActions.forExecution(decision, "跟我一起练级去", 42);

        assertEquals(3, actions.size());
        assertInstanceOf(CompanionAction.TrainWith.class, actions.getLast());
        assertEquals(42, ((CompanionAction.TrainWith) actions.getLast()).characterId());
    }

    @Test
    void ordinaryFollowRequestDoesNotStartCombat() {
        AgentDecision decision = new AgentDecision(
                1, "跟上了", "test", List.of(new CompanionAction.Follow(42)));

        List<CompanionAction> actions =
                CompanionDecisionActions.forExecution(decision, "跟我走", 42);

        assertEquals(2, actions.size());
        assertEquals(0, actions.stream().filter(CompanionAction.TrainWith.class::isInstance).count());
    }

    @Test
    void askingWhyCompanionIsNotFightingResumesTraining() {
        AgentDecision decision = new AgentDecision(
                1, "没看到怪", "test", List.of(new CompanionAction.Say("没看到怪")));

        List<CompanionAction> actions =
                CompanionDecisionActions.forExecution(decision, "你怎么不打呢？", 42);

        assertInstanceOf(CompanionAction.TrainWith.class, actions.getLast());
    }
}
