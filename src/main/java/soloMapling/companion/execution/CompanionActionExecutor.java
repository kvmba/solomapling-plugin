package soloMapling.companion.execution;

import org.gms.client.Character;
import soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands;
import soloMapling.ArtificialPlayer.BotPartySystem.BotPartyCommands;
import soloMapling.ArtificialPlayer.BotPartySystem.BotPartyQueue;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.companion.agent.CompanionAction;

import java.util.Optional;

/**
 * Strict engine boundary for actions already accepted by {@code ActionValidator}.
 *
 * <p>The supplied resolver is the only source of character and map authority.
 * This class deliberately performs no storage, channel, world, or map-factory
 * lookup. Each call has its own catch-all boundary so engine failures cannot
 * escape into a companion tick.</p>
 */
public final class CompanionActionExecutor {

    private static final long REST_HOLD_MS = 30_000L;
    private static final long GOODBYE_HOLD_MS = 15_000L;

    public interface EngineAdapter {
        void say(Character companion, String text);

        void emote(Character companion, int emoteId);

        ActionExecutionResult acceptParty(
                Character companion, BotSM bot, Character inviter, int inviterCharacterId);

        ActionExecutionResult inviteParty(Character companion, BotSM bot, Character target);

        ActionExecutionResult follow(Character companion, BotSM bot, Character target);

        void goTo(Character companion, int mapId);

        ActionExecutionResult trainWith(Character companion, BotSM bot, Character target);

        ActionExecutionResult dropGift(Character companion, BotSM bot, Character target, int itemId);

        ActionExecutionResult rest(Character companion, BotSM bot);

        ActionExecutionResult goodbye(Character companion, BotSM bot);
    }

    private final ActionExecutionRouter<Character, BotSM, Character> router;

    public CompanionActionExecutor() {
        this(new HostEngineAdapter());
    }

    public CompanionActionExecutor(EngineAdapter adapter) {
        if (adapter == null) {
            throw new NullPointerException("adapter must not be null");
        }
        this.router = new ActionExecutionRouter<>(new ActionExecutionRouter.Adapter<>() {
            @Override
            public void say(Character companion, String text) {
                adapter.say(companion, text);
            }

            @Override
            public void emote(Character companion, int emoteId) {
                adapter.emote(companion, emoteId);
            }

            @Override
            public ActionExecutionResult acceptParty(
                    Character companion, BotSM bot, Character inviter, int inviterCharacterId) {
                return adapter.acceptParty(companion, bot, inviter, inviterCharacterId);
            }

            @Override
            public ActionExecutionResult inviteParty(
                    Character companion, BotSM bot, Character target) {
                return adapter.inviteParty(companion, bot, target);
            }

            @Override
            public ActionExecutionResult follow(Character companion, BotSM bot, Character target) {
                return adapter.follow(companion, bot, target);
            }

            @Override
            public void goTo(Character companion, int mapId) {
                adapter.goTo(companion, mapId);
            }

            @Override
            public ActionExecutionResult trainWith(Character companion, BotSM bot, Character target) {
                return adapter.trainWith(companion, bot, target);
            }

            @Override
            public ActionExecutionResult dropGift(
                    Character companion, BotSM bot, Character target, int itemId) {
                return adapter.dropGift(companion, bot, target, itemId);
            }

            @Override
            public ActionExecutionResult rest(Character companion, BotSM bot) {
                return adapter.rest(companion, bot);
            }

            @Override
            public ActionExecutionResult goodbye(Character companion, BotSM bot) {
                return adapter.goodbye(companion, bot);
            }
        });
    }

    public ActionExecutionResult execute(
            CompanionAction action,
            Character companion,
            BotSM bot,
            CompanionTargetResolver resolver) {
        if (companion == null || bot == null) {
            return ActionExecutionResult.rejected(
                    "COMPANION_MISSING", "Current companion Character and BotSM are required");
        }
        if (resolver == null) {
            return ActionExecutionResult.rejected(
                    "RESOLVER_MISSING", "An explicit allowlist target resolver is required");
        }

        try {
            if (bot.getChr() != companion) {
                return ActionExecutionResult.rejected(
                        "COMPANION_MISMATCH", "BotSM does not own the supplied companion Character");
            }
            if (companion.getMap() == null) {
                return ActionExecutionResult.rejected(
                        "COMPANION_OFFLINE", "Companion has no current engine map");
            }
            if (!bot.getRunning() || bot.getState() == BotSM.BotState.FINISHED) {
                return ActionExecutionResult.rejected(
                        "COMPANION_INACTIVE", "Companion BotSM is not active");
            }
            if (bot.getState() == BotSM.BotState.TRADING) {
                return ActionExecutionResult.rejected(
                        "COMPANION_BUSY", "Companion is trading and cannot change actions");
            }

            ActionExecutionRouter.Resolver<Character> guardedResolver =
                    new ActionExecutionRouter.Resolver<>() {
                        @Override
                        public Optional<Character> resolveCharacter(int characterId) {
                            Optional<Character> resolved = resolver.resolveCharacter(characterId);
                            if (resolved == null) {
                                return Optional.empty();
                            }
                            return resolved.filter(target ->
                                    target != null && target.getId() == characterId);
                        }

                        @Override
                        public Optional<Character> resolveCharacter(
                                CompanionAction.ActionType actionType, int characterId) {
                            Optional<Character> resolved =
                                    resolver.resolveCharacterFor(actionType, characterId);
                            if (resolved == null) {
                                return Optional.empty();
                            }
                            return resolved.filter(target ->
                                    target != null && target.getId() == characterId);
                        }

                        @Override
                        public boolean allowsMap(int mapId) {
                            return resolver.allowsMap(mapId);
                        }
                    };
            return router.execute(action, companion, bot, guardedResolver);
        } catch (Throwable error) {
            String message = error.getMessage();
            String detail = error.getClass().getSimpleName()
                    + (message == null || message.isBlank() ? "" : ": " + message);
            return ActionExecutionResult.failed(
                    "ENGINE_CONTEXT_ERROR", "Could not inspect companion engine state: " + detail);
        }
    }

    private static final class HostEngineAdapter implements EngineAdapter {

        @Override
        public void say(Character companion, String text) {
            SocialCommands.BotSpeak(companion, text);
        }

        @Override
        public void emote(Character companion, int emoteId) {
            SocialCommands.BotEmote(companion, emoteId);
        }

        @Override
        public ActionExecutionResult acceptParty(
                Character companion, BotSM bot, Character inviter, int inviterCharacterId) {
            BotPartyQueue.PartyInviteEntry pending =
                    BotPartyQueue.getInstance().getPartyInvite(companion);
            if (pending == null || pending.getInviter() == null
                    || inviter == null
                    || !inviterIdsMatch(
                            pending.getInviter().getId(), inviter.getId(), inviterCharacterId)) {
                return ActionExecutionResult.rejected(
                        "PARTY_INVITER_MISMATCH",
                        "Pending party inviter does not match the resolver-authorized action target");
            }
            boolean accepted = BotPartyCommands.botAcceptPartyInvite(
                    companion, inviterCharacterId);
            return accepted
                    ? ActionExecutionResult.success(
                            "PARTY_ACCEPTED", "Companion joined the authorized inviter's party")
                    : ActionExecutionResult.failed(
                            "PARTY_ACCEPT_FAILED", "The authorized party invite expired or could not be joined");
        }

        @Override
        public ActionExecutionResult inviteParty(
                Character companion, BotSM bot, Character target) {
            return BotPartyCommands.botInvitePlayer(companion, target)
                    ? ActionExecutionResult.success(
                            "PARTY_INVITE_SENT", "Party invite was sent to the authorized target")
                    : ActionExecutionResult.rejected(
                            "PARTY_INVITE_REJECTED", "The authorized target could not be invited");
        }

        @Override
        public ActionExecutionResult follow(Character companion, BotSM bot, Character target) {
            ActionExecutionResult sameMap = requireSameMap(companion, target);
            if (sameMap != null) {
                return sameMap;
            }
            GCMovement.follow(companion, target);
            return ActionExecutionResult.success(
                    "FOLLOW_STARTED",
                    "GCMovement follow was started without replacing the companion BotSM");
        }

        @Override
        public void goTo(Character companion, int mapId) {
            GCMovement.travel(companion, mapId);
        }

        @Override
        public ActionExecutionResult trainWith(Character companion, BotSM bot, Character target) {
            ActionExecutionResult sameMap = requireSameMap(companion, target);
            if (sameMap != null) {
                return sameMap;
            }
            if (!(bot instanceof CompanionTrainingController trainingController)) {
                return ActionExecutionResult.deferred(
                        "COMPANION_TRAINING_CONTROLLER_REQUIRED",
                        "The current BotSM does not provide companion-owned training execution");
            }
            ActionExecutionResult result = trainingController.beginTrainingWith(target);
            return result == null
                    ? ActionExecutionResult.failed(
                            "TRAINING_CONTROLLER_FAILED",
                            "Companion training controller returned no result")
                    : result;
        }

        @Override
        public ActionExecutionResult dropGift(
                Character companion, BotSM bot, Character target, int itemId) {
            ActionExecutionResult sameMap = requireSameMap(companion, target);
            if (sameMap != null) {
                return sameMap;
            }
            CompanionRuntimeCapabilities.GiftResult result =
                    CompanionRuntimeCapabilities.dropGift(companion, target, itemId);
            return result.success()
                    ? ActionExecutionResult.success(
                            "GIFT_DROPPED",
                            "The original inventory item was dropped for the authorized player")
                    : ActionExecutionResult.rejected(
                            result.code(),
                            "The requested inventory item could not be dropped: " + result.code());
        }

        @Override
        public ActionExecutionResult rest(Character companion, BotSM bot) {
            stopForRest(bot);
            GCMovement.stop(companion);
            bot.waitFor(REST_HOLD_MS);
            return ActionExecutionResult.success(
                    "REST_STARTED", "Movement stopped and the BotSM was held for 30 seconds");
        }

        @Override
        public ActionExecutionResult goodbye(Character companion, BotSM bot) {
            stopForRest(bot);
            GCMovement.stop(companion);
            if (BotPartyQueue.getInstance().hasPendingInvite(companion)) {
                BotPartyCommands.botRejectPartyInvite(companion);
            }
            bot.waitFor(GOODBYE_HOLD_MS);
            return ActionExecutionResult.success(
                    "GOODBYE_APPLIED", "Current movement and interaction were ended");
        }

        private static ActionExecutionResult requireSameMap(Character companion, Character target) {
            if (target.getMap() == null || companion.getMapId() != target.getMapId()) {
                return ActionExecutionResult.rejected(
                        "TARGET_NOT_ON_SAME_MAP",
                        "Resolver target is no longer on the companion's map");
            }
            return null;
        }

        private static void stopForRest(BotSM bot) {
            if (bot instanceof CompanionTrainingController trainingController) {
                trainingController.stopForRest();
            }
        }
    }

    static boolean inviterIdsMatch(
            int pendingInviterId, int resolvedInviterId, int actionCharacterId) {
        return pendingInviterId > 0
                && pendingInviterId == resolvedInviterId
                && resolvedInviterId == actionCharacterId;
    }
}
