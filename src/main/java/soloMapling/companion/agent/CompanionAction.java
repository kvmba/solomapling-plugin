package soloMapling.companion.agent;

import java.util.Objects;

/**
 * Versioned, engine-independent commands that a companion planner may request.
 *
 * <p>The hierarchy is deliberately closed: adding a command requires a code
 * change in both the parser and validator. No command in this model executes
 * game-engine behavior.</p>
 */
public sealed interface CompanionAction permits
        CompanionAction.Say,
        CompanionAction.Emote,
        CompanionAction.AcceptParty,
        CompanionAction.InviteParty,
        CompanionAction.Follow,
        CompanionAction.GoTo,
        CompanionAction.TrainWith,
        CompanionAction.Rest,
        CompanionAction.Goodbye {

    int SCHEMA_VERSION = 1;
    int MAX_CHAT_LENGTH = 240;
    int MAX_EMOTE_LENGTH = 48;

    ActionType type();

    default int schemaVersion() {
        return SCHEMA_VERSION;
    }

    enum ActionType {
        SAY,
        EMOTE,
        ACCEPT_PARTY,
        INVITE_PARTY,
        FOLLOW,
        GO_TO,
        TRAIN_WITH,
        REST,
        GOODBYE
    }

    record Say(String text) implements CompanionAction {
        public Say {
            text = requireText(text, "text", MAX_CHAT_LENGTH);
        }

        @Override
        public ActionType type() {
            return ActionType.SAY;
        }
    }

    record Emote(String emote) implements CompanionAction {
        public Emote {
            emote = requireText(emote, "emote", MAX_EMOTE_LENGTH);
        }

        @Override
        public ActionType type() {
            return ActionType.EMOTE;
        }
    }

    record AcceptParty(int characterId) implements CompanionAction {
        public AcceptParty {
            requirePositiveCharacterId(characterId);
        }

        @Override
        public ActionType type() {
            return ActionType.ACCEPT_PARTY;
        }
    }

    record InviteParty(int characterId) implements CompanionAction {
        public InviteParty {
            requirePositiveCharacterId(characterId);
        }

        @Override
        public ActionType type() {
            return ActionType.INVITE_PARTY;
        }
    }

    record Follow(int characterId) implements CompanionAction {
        public Follow {
            requirePositiveCharacterId(characterId);
        }

        @Override
        public ActionType type() {
            return ActionType.FOLLOW;
        }
    }

    record GoTo(int mapId) implements CompanionAction {
        public GoTo {
            if (mapId < 0) {
                throw new IllegalArgumentException("mapId must not be negative");
            }
        }

        @Override
        public ActionType type() {
            return ActionType.GO_TO;
        }
    }

    record TrainWith(int characterId) implements CompanionAction {
        public TrainWith {
            requirePositiveCharacterId(characterId);
        }

        @Override
        public ActionType type() {
            return ActionType.TRAIN_WITH;
        }
    }

    record Rest() implements CompanionAction {
        @Override
        public ActionType type() {
            return ActionType.REST;
        }
    }

    record Goodbye() implements CompanionAction {
        @Override
        public ActionType type() {
            return ActionType.GOODBYE;
        }
    }

    private static void requirePositiveCharacterId(int characterId) {
        if (characterId <= 0) {
            throw new IllegalArgumentException("characterId must be positive");
        }
    }

    private static String requireText(String value, String field, int maxLength) {
        Objects.requireNonNull(value, field + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " exceeds " + maxLength + " characters");
        }
        return normalized;
    }
}
