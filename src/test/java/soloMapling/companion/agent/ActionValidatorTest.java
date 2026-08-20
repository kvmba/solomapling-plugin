package soloMapling.companion.agent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionValidatorTest {

    private static final int CURRENT_MAP = 100000000;
    private static final int ALLOWED_MAP = 101000000;
    private static final int TARGET = 42;
    private static final int OTHER_TARGET = 84;

    private final ActionValidator validator = new ActionValidator();

    @Test
    void acceptsActionsAuthorizedBySnapshotWithoutExecutingAnything() {
        AgentDecision decision = decision(List.of(
                new CompanionAction.Say("Ready."),
                new CompanionAction.Follow(TARGET),
                new CompanionAction.GoTo(ALLOWED_MAP),
                new CompanionAction.TrainWith(TARGET)));

        ActionValidator.ValidationResult result =
                validator.validate(decision, state(Set.of(TARGET), false, false, Set.of()));

        assertTrue(result.valid());
        assertEquals(List.of(), result.violations());
    }

    @Test
    void dialoguePlanningDoesNotMakeFollowOrGoToEngaged() {
        CompanionStateSnapshot planningConversation = new CompanionStateSnapshot(
                CURRENT_MAP,
                Set.of(TARGET),
                false,
                Set.of(CURRENT_MAP, ALLOWED_MAP),
                Set.of(TARGET),
                Set.of(),
                false);
        AgentDecision decision = decision(List.of(
                new CompanionAction.Follow(TARGET),
                new CompanionAction.GoTo(ALLOWED_MAP)));

        ActionValidator.ValidationResult result =
                validator.validate(decision, planningConversation);

        assertTrue(result.valid());
        assertTrue(result.violations().isEmpty());
    }

    @Test
    void rejectsMapOutsideSnapshotAllowlist() {
        ActionValidator.ValidationResult result = validator.validate(
                decision(List.of(new CompanionAction.GoTo(999999999))),
                state(Set.of(TARGET), false, false, Set.of()));

        assertFalse(result.valid());
        assertEquals("MAP_NOT_ALLOWED", result.violations().getFirst().code());
        assertEquals(0, result.violations().getFirst().actionIndex());
    }

    @Test
    void rejectsUnknownTargetAndTargetOnAnotherMap() {
        AgentDecision decision = decision(List.of(
                new CompanionAction.Follow(777),
                new CompanionAction.TrainWith(TARGET)));

        ActionValidator.ValidationResult result =
                validator.validate(decision, state(Set.of(), false, false, Set.of()));

        assertEquals(
                List.of("TARGET_NOT_ALLOWED", "TARGET_NOT_ON_SAME_MAP",
                        "TARGET_NOT_ON_SAME_MAP"),
                result.violations().stream().map(ActionValidator.Violation::code).toList());
    }

    @Test
    void rejectsTheKnownTargetThatIsNotOnTheSameMap() {
        CompanionStateSnapshot state = new CompanionStateSnapshot(
                CURRENT_MAP,
                Set.of(TARGET),
                false,
                Set.of(CURRENT_MAP),
                Set.of(TARGET, OTHER_TARGET),
                Set.of(),
                false);
        AgentDecision decision = decision(List.of(
                new CompanionAction.Follow(TARGET),
                new CompanionAction.Follow(OTHER_TARGET)));

        ActionValidator.ValidationResult result = validator.validate(decision, state);

        assertEquals(1, result.violations().size());
        assertEquals(1, result.violations().getFirst().actionIndex());
        assertEquals("TARGET_NOT_ON_SAME_MAP", result.violations().getFirst().code());
    }

    @Test
    void representsMapZeroAsCurrentKnownAndGoToMap() {
        CompanionStateSnapshot state = new CompanionStateSnapshot(
                0,
                Set.of(),
                false,
                Set.of(0),
                Set.of(),
                Set.of(),
                false);

        ActionValidator.ValidationResult result = validator.validate(
                decision(List.of(new CompanionAction.GoTo(0))), state);

        assertTrue(result.valid());
        assertEquals(0, state.currentMapId());
        assertEquals(Set.of(0), state.knownMapIds());
    }

    @Test
    void requiresSameMapCharactersToBeKnownTargets() {
        assertThrows(IllegalArgumentException.class, () -> new CompanionStateSnapshot(
                CURRENT_MAP,
                Set.of(OTHER_TARGET),
                false,
                Set.of(CURRENT_MAP),
                Set.of(TARGET),
                Set.of(),
                false));
    }

    @Test
    void rejectsPartyStateCooldownAndEngagedPolicyViolationsDeterministically() {
        AgentDecision decision = decision(List.of(
                new CompanionAction.InviteParty(TARGET),
                new CompanionAction.Rest()));

        ActionValidator.ValidationResult result = validator.validate(
                decision,
                state(Set.of(TARGET), true, true,
                        Set.of(CompanionAction.ActionType.INVITE_PARTY)));

        assertEquals(
                List.of("ACTION_ON_COOLDOWN", "ALREADY_IN_PARTY", "CURRENTLY_ENGAGED",
                        "CURRENTLY_ENGAGED"),
                result.violations().stream().map(ActionValidator.Violation::code).toList());
    }

    private static AgentDecision decision(List<CompanionAction> actions) {
        return new AgentDecision(1, "", "test", actions);
    }

    private static CompanionStateSnapshot state(
            Set<Integer> sameMapCharacterIds,
            boolean inParty,
            boolean engaged,
            Set<CompanionAction.ActionType> cooldowns) {
        return new CompanionStateSnapshot(
                CURRENT_MAP,
                sameMapCharacterIds,
                inParty,
                Set.of(CURRENT_MAP, ALLOWED_MAP),
                Set.of(TARGET),
                cooldowns,
                engaged);
    }
}
