package soloMapling.companion.execution;

import soloMapling.companion.agent.CompanionAction;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Engine-type-neutral dispatch core. The narrow adapter keeps static host APIs
 * out of policy tests while preserving one exception boundary per action.
 */
final class ActionExecutionRouter<C, B, T> {

    interface Resolver<T> {
        Optional<T> resolveCharacter(int characterId);

        default Optional<T> resolveCharacter(
                CompanionAction.ActionType actionType, int characterId) {
            return resolveCharacter(characterId);
        }

        boolean allowsMap(int mapId);
    }

    interface Adapter<C, B, T> {
        void say(C companion, String text);

        void emote(C companion, int emoteId);

        ActionExecutionResult acceptParty(C companion, B bot, T inviter, int inviterCharacterId);

        ActionExecutionResult inviteParty(C companion, B bot, T target);

        ActionExecutionResult follow(C companion, B bot, T target);

        void goTo(C companion, int mapId);

        ActionExecutionResult trainWith(C companion, B bot, T target);

        ActionExecutionResult rest(C companion, B bot);

        ActionExecutionResult goodbye(C companion, B bot);
    }

    private static final Map<String, Integer> EMOTES = Map.ofEntries(
            Map.entry("smile", 1),
            Map.entry("happy", 1),
            Map.entry("troubled", 2),
            Map.entry("cry", 3),
            Map.entry("angry", 4),
            Map.entry("bewildered", 5),
            Map.entry("stunned", 6),
            Map.entry("vomit", 7));

    private final Adapter<C, B, T> adapter;

    ActionExecutionRouter(Adapter<C, B, T> adapter) {
        this.adapter = java.util.Objects.requireNonNull(adapter, "adapter must not be null");
    }

    ActionExecutionResult execute(CompanionAction action, C companion, B bot, Resolver<T> resolver) {
        if (action == null) {
            return ActionExecutionResult.rejected("ACTION_MISSING", "Action must not be null");
        }
        if (resolver == null) {
            return ActionExecutionResult.rejected(
                    "RESOLVER_MISSING", "An explicit allowlist target resolver is required");
        }

        try {
            return switch (action) {
                case CompanionAction.Say say -> {
                    adapter.say(companion, say.text());
                    yield ActionExecutionResult.success("SAY_SENT", "Chat was dispatched");
                }
                case CompanionAction.Emote emote -> executeEmote(emote, companion);
                case CompanionAction.AcceptParty accept ->
                        resolveTarget(action.type(), accept.characterId(), resolver)
                                .map(inviter -> requireResult(
                                        adapter.acceptParty(
                                                companion, bot, inviter, accept.characterId()),
                                        action))
                                .orElseGet(() -> targetRejected(accept.characterId()));
                case CompanionAction.InviteParty invite ->
                        resolveTarget(action.type(), invite.characterId(), resolver)
                                .map(target -> requireResult(
                                        adapter.inviteParty(companion, bot, target), action))
                                .orElseGet(() -> targetRejected(invite.characterId()));
                case CompanionAction.Follow follow ->
                        resolveTarget(action.type(), follow.characterId(), resolver)
                                .map(target -> requireResult(
                                        adapter.follow(companion, bot, target), action))
                                .orElseGet(() -> targetRejected(follow.characterId()));
                case CompanionAction.GoTo goTo -> {
                    if (!resolver.allowsMap(goTo.mapId())) {
                        yield ActionExecutionResult.rejected(
                                "MAP_NOT_ALLOWED",
                                "Map " + goTo.mapId() + " is not authorized by the target resolver");
                    }
                    adapter.goTo(companion, goTo.mapId());
                    yield ActionExecutionResult.deferred(
                            "TRAVEL_STARTED", "Travel to map " + goTo.mapId() + " was started asynchronously");
                }
                case CompanionAction.TrainWith trainWith ->
                        resolveTarget(action.type(), trainWith.characterId(), resolver)
                                .map(target -> requireResult(
                                        adapter.trainWith(companion, bot, target), action))
                                .orElseGet(() -> targetRejected(trainWith.characterId()));
                case CompanionAction.Rest ignored ->
                        requireResult(adapter.rest(companion, bot), action);
                case CompanionAction.Goodbye ignored ->
                        requireResult(adapter.goodbye(companion, bot), action);
            };
        } catch (Throwable error) {
            return ActionExecutionResult.failed(
                    "ENGINE_ERROR",
                    "Action " + action.type() + " failed: " + safeError(error));
        }
    }

    static Optional<Integer> emoteId(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        Integer named = EMOTES.get(normalized);
        if (named != null) {
            return Optional.of(named);
        }
        try {
            int numeric = Integer.parseInt(normalized);
            return numeric >= 1 && numeric <= 22 ? Optional.of(numeric) : Optional.empty();
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    private ActionExecutionResult executeEmote(CompanionAction.Emote action, C companion) {
        Optional<Integer> emoteId = emoteId(action.emote());
        if (emoteId.isEmpty()) {
            return ActionExecutionResult.rejected(
                    "EMOTE_NOT_ALLOWED",
                    "Emote must be a supported name or a numeric id from 1 through 22");
        }
        adapter.emote(companion, emoteId.get());
        return ActionExecutionResult.success("EMOTE_SENT", "Emote was dispatched");
    }

    private static <T> Optional<T> resolveTarget(
            CompanionAction.ActionType actionType, int characterId, Resolver<T> resolver) {
        Optional<T> resolved = resolver.resolveCharacter(actionType, characterId);
        return resolved == null ? Optional.empty() : resolved.filter(java.util.Objects::nonNull);
    }

    private static ActionExecutionResult targetRejected(int characterId) {
        return ActionExecutionResult.rejected(
                "TARGET_NOT_ALLOWED",
                "Character " + characterId + " was not supplied by the target resolver");
    }

    private static ActionExecutionResult requireResult(
            ActionExecutionResult result, CompanionAction action) {
        if (result == null) {
            throw new IllegalStateException(
                    "Engine adapter returned no result for " + action.type());
        }
        return result;
    }

    private static String safeError(Throwable error) {
        String type = error.getClass().getSimpleName();
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return type;
        }
        String normalized = message.replace('\n', ' ').replace('\r', ' ').trim();
        if (normalized.length() > 160) {
            normalized = normalized.substring(0, 160);
        }
        return type + ": " + normalized;
    }
}
