package soloMapling.companion.execution;

import org.junit.jupiter.api.Test;
import soloMapling.companion.agent.CompanionAction;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionExecutionRouterTest {

    private final RecordingAdapter adapter = new RecordingAdapter();
    private final ActionExecutionRouter<String, String, String> router =
            new ActionExecutionRouter<>(adapter);

    @Test
    void dispatchesSafeActionsThroughTheInjectedBoundary() {
        Resolver resolver = new Resolver(Set.of(42), Set.of(100000000));
        adapter.trainingControllerAvailable = true;

        assertEquals(ActionExecutionResult.Status.SUCCESS,
                router.execute(new CompanionAction.Say("hello"), "companion", "bot", resolver).status());
        assertEquals(ActionExecutionResult.Status.SUCCESS,
                router.execute(new CompanionAction.Emote("cry"), "companion", "bot", resolver).status());
        assertEquals(ActionExecutionResult.Status.SUCCESS,
                router.execute(new CompanionAction.Follow(42), "companion", "bot", resolver).status());
        assertEquals(ActionExecutionResult.Status.SUCCESS,
                router.execute(new CompanionAction.TrainWith(42), "companion", "bot", resolver).status());
        assertEquals(ActionExecutionResult.Status.SUCCESS,
                router.execute(new CompanionAction.DropGift(42, 1002019),
                        "companion", "bot", resolver).status());
        assertEquals(ActionExecutionResult.Status.SUCCESS,
                router.execute(new CompanionAction.Rest(), "companion", "bot", resolver).status());
        assertEquals(ActionExecutionResult.Status.SUCCESS,
                router.execute(new CompanionAction.Goodbye(), "companion", "bot", resolver).status());

        assertEquals(
                List.of("say:hello", "emote:3", "follow:target-42",
                        "train:target-42", "gift:target-42:1002019", "rest", "goodbye"),
                adapter.calls);
    }

    @Test
    void followKeepsTheCurrentBotAndOnlyDispatchesMovementIntent() {
        String currentBot = new String("persistent-companion-bot");

        ActionExecutionResult result = router.execute(
                new CompanionAction.Follow(42),
                "companion",
                currentBot,
                new Resolver(Set.of(42), Set.of()));

        assertEquals(ActionExecutionResult.Status.SUCCESS, result.status());
        assertSame(currentBot, adapter.lastFollowBot);
        assertEquals(List.of("follow:target-42"), adapter.calls);
    }

    @Test
    void trainWithWithoutCompanionControllerIsDeferred() {
        ActionExecutionResult result = router.execute(
                new CompanionAction.TrainWith(42),
                "companion",
                "plain-bot",
                new Resolver(Set.of(42), Set.of()));

        assertEquals(ActionExecutionResult.Status.DEFERRED, result.status());
        assertEquals("COMPANION_TRAINING_CONTROLLER_REQUIRED", result.reasonCode());
        assertTrue(adapter.calls.isEmpty());
    }

    @Test
    void neverDispatchesTargetsOrMapsOutsideResolverAllowlist() {
        Resolver resolver = new Resolver(Set.of(), Set.of());

        ActionExecutionResult follow = router.execute(
                new CompanionAction.Follow(42), "companion", "bot", resolver);
        ActionExecutionResult goTo = router.execute(
                new CompanionAction.GoTo(100000000), "companion", "bot", resolver);

        assertEquals(ActionExecutionResult.Status.REJECTED, follow.status());
        assertEquals("TARGET_NOT_ALLOWED", follow.reasonCode());
        assertEquals(ActionExecutionResult.Status.REJECTED, goTo.status());
        assertEquals("MAP_NOT_ALLOWED", goTo.reasonCode());
        assertTrue(adapter.calls.isEmpty());
    }

    @Test
    void reportsAsynchronousTravelAsDeferredAfterDispatch() {
        Resolver resolver = new Resolver(Set.of(), Set.of(100000000));

        ActionExecutionResult result = router.execute(
                new CompanionAction.GoTo(100000000), "companion", "bot", resolver);

        assertEquals(ActionExecutionResult.Status.DEFERRED, result.status());
        assertEquals("TRAVEL_STARTED", result.reasonCode());
        assertEquals(List.of("goto:100000000"), adapter.calls);
    }

    @Test
    void partyActionsDispatchOnlyResolverAuthorizedTargets() {
        Resolver resolver = new Resolver(Set.of(42), Set.of());

        ActionExecutionResult accept = router.execute(
                new CompanionAction.AcceptParty(42), "companion", "bot", resolver);
        ActionExecutionResult invite = router.execute(
                new CompanionAction.InviteParty(42), "companion", "bot", resolver);

        assertEquals(ActionExecutionResult.Status.SUCCESS, accept.status());
        assertEquals(ActionExecutionResult.Status.SUCCESS, invite.status());
        assertEquals(List.of("accept:target-42", "invite:target-42"), adapter.calls);
    }

    @Test
    void partyAcceptRequiresPendingResolvedAndActionInviterToMatch() {
        assertTrue(CompanionActionExecutor.inviterIdsMatch(42, 42, 42));
        assertFalse(CompanionActionExecutor.inviterIdsMatch(41, 42, 42));
        assertFalse(CompanionActionExecutor.inviterIdsMatch(42, 41, 42));
        assertFalse(CompanionActionExecutor.inviterIdsMatch(42, 42, 41));
    }

    @Test
    void acceptOnlyResolverDoesNotAuthorizeCrossMapFollowOrTraining() {
        ActionExecutionRouter.Resolver<String> acceptOnly =
                new ActionExecutionRouter.Resolver<>() {
                    @Override
                    public Optional<String> resolveCharacter(int characterId) {
                        return Optional.empty();
                    }

                    @Override
                    public Optional<String> resolveCharacter(
                            CompanionAction.ActionType actionType, int characterId) {
                        return actionType == CompanionAction.ActionType.ACCEPT_PARTY
                                && characterId == 42
                                ? Optional.of("target-42") : Optional.empty();
                    }

                    @Override
                    public boolean allowsMap(int mapId) {
                        return false;
                    }
                };

        assertEquals(ActionExecutionResult.Status.SUCCESS, router.execute(
                new CompanionAction.AcceptParty(42), "companion", "bot", acceptOnly).status());
        assertEquals(ActionExecutionResult.Status.REJECTED, router.execute(
                new CompanionAction.Follow(42), "companion", "bot", acceptOnly).status());
        assertEquals(ActionExecutionResult.Status.REJECTED, router.execute(
                new CompanionAction.TrainWith(42), "companion", "bot", acceptOnly).status());
    }

    @Test
    void rejectsUnknownEmotesWithoutCallingEngine() {
        ActionExecutionResult result = router.execute(
                new CompanionAction.Emote("dance"),
                "companion",
                "bot",
                new Resolver(Set.of(), Set.of()));

        assertEquals(ActionExecutionResult.Status.REJECTED, result.status());
        assertEquals("EMOTE_NOT_ALLOWED", result.reasonCode());
        assertTrue(adapter.calls.isEmpty());
        assertEquals(Optional.of(22), ActionExecutionRouter.emoteId("22"));
        assertEquals(Optional.empty(), ActionExecutionRouter.emoteId("23"));
    }

    @Test
    void catchesEveryDispatchErrorAtTheActionBoundary() {
        adapter.failure = new AssertionError("engine exploded");

        ActionExecutionResult result = router.execute(
                new CompanionAction.Say("hello"),
                "companion",
                "bot",
                new Resolver(Set.of(), Set.of()));

        assertEquals(ActionExecutionResult.Status.FAILED, result.status());
        assertEquals("ENGINE_ERROR", result.reasonCode());
        assertTrue(result.reason().contains("AssertionError"));
    }

    private static final class Resolver implements ActionExecutionRouter.Resolver<String> {
        private final Set<Integer> characterIds;
        private final Set<Integer> mapIds;

        private Resolver(Set<Integer> characterIds, Set<Integer> mapIds) {
            this.characterIds = characterIds;
            this.mapIds = mapIds;
        }

        @Override
        public Optional<String> resolveCharacter(int characterId) {
            return characterIds.contains(characterId)
                    ? Optional.of("target-" + characterId)
                    : Optional.empty();
        }

        @Override
        public boolean allowsMap(int mapId) {
            return mapIds.contains(mapId);
        }
    }

    private static final class RecordingAdapter
            implements ActionExecutionRouter.Adapter<String, String, String> {
        private final List<String> calls = new ArrayList<>();
        private Error failure;
        private boolean trainingControllerAvailable;
        private String lastFollowBot;

        @Override
        public void say(String companion, String text) {
            failIfRequested();
            calls.add("say:" + text);
        }

        @Override
        public void emote(String companion, int emoteId) {
            calls.add("emote:" + emoteId);
        }

        @Override
        public ActionExecutionResult acceptParty(
                String companion, String bot, String inviter, int inviterCharacterId) {
            calls.add("accept:" + inviter);
            return ActionExecutionResult.success("PARTY_ACCEPTED", "accepted");
        }

        @Override
        public ActionExecutionResult inviteParty(String companion, String bot, String target) {
            calls.add("invite:" + target);
            return ActionExecutionResult.success("PARTY_INVITE_SENT", "sent");
        }

        @Override
        public ActionExecutionResult follow(String companion, String bot, String target) {
            lastFollowBot = bot;
            calls.add("follow:" + target);
            return ActionExecutionResult.success("FOLLOW_STARTED", "started");
        }

        @Override
        public void goTo(String companion, int mapId) {
            calls.add("goto:" + mapId);
        }

        @Override
        public ActionExecutionResult trainWith(String companion, String bot, String target) {
            if (!trainingControllerAvailable) {
                return ActionExecutionResult.deferred(
                        "COMPANION_TRAINING_CONTROLLER_REQUIRED", "controller required");
            }
            calls.add("train:" + target);
            return ActionExecutionResult.success("TRAINING_STARTED", "started");
        }

        @Override
        public ActionExecutionResult dropGift(
                String companion, String bot, String target, int itemId) {
            calls.add("gift:" + target + ":" + itemId);
            return ActionExecutionResult.success("GIFT_DROPPED", "dropped");
        }

        @Override
        public ActionExecutionResult rest(String companion, String bot) {
            calls.add("rest");
            return ActionExecutionResult.success("REST_STARTED", "started");
        }

        @Override
        public ActionExecutionResult goodbye(String companion, String bot) {
            calls.add("goodbye");
            return ActionExecutionResult.success("GOODBYE_APPLIED", "applied");
        }

        private void failIfRequested() {
            if (failure != null) {
                throw failure;
            }
        }
    }
}
