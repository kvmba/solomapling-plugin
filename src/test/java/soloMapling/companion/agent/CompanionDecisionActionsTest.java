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
}
