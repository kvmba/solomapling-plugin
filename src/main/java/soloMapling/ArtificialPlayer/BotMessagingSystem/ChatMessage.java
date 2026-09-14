package soloMapling.ArtificialPlayer.BotMessagingSystem;

import org.gms.client.Character;
import org.gms.extension.event.ChatType;
import org.gms.server.maps.MapleMap;

public class ChatMessage {
    private final Character sender;
    private final String content;
    private MapleMap map;
    private final long timestamp;
    // Directed-channel origin (whisper/buddy/party/guild/alliance). null for map-general chatter,
    // which is what the reply path uses to decide between a map bubble and a channel-scoped send.
    private final ChatType type;

    public ChatMessage(Character sender, String content) {
        this(sender, content, null);
    }

    public ChatMessage(Character sender, String content, ChatType type) {
        this.sender = sender;
        this.content = content;
        this.map = this.sender.getMap();
        this.timestamp = System.currentTimeMillis();
        this.type = type;
    }

    public Character getSender() {
        return sender;
    }

    public String getContent() {
        return content;
    }

    /** The directed channel this line arrived on, or null for map-general chat. */
    public ChatType getChatType() {
        return type;
    }

    protected MapleMap getMap() {
        return map;
    }

    protected long getTimestamp() {
        return timestamp;
    }
}
