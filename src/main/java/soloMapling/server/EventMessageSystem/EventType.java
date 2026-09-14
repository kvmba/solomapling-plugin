package soloMapling.server.EventMessageSystem;

public enum EventType {
    GACHAPON_REWARD,
    LEVEL_UP,
    SCROLLING,
    ITEM_MEGAPHONE,
    CHAT_MEGAPHONE,
    CHAT_GENERAL,
    MAP_ENTERED,
    // Directed player-to-player channels (host CharacterDirectChatEvent). Addressed per recipient,
    // so they ride the bot's own inbox rather than the map-wide Dispatcher.
    CHAT_WHISPER,
    CHAT_BUDDY,
    CHAT_PARTY,
    CHAT_GUILD,
    CHAT_ALLIANCE
}
