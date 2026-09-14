package soloMapling.server.EventMessageSystem;

import org.gms.client.Character;
import org.gms.client.inventory.Item;
import org.gms.extension.event.ChatType;
import org.gms.server.maps.MapleMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GameEvent {
    private static int nextId = 1;
    private static final Logger log = LoggerFactory.getLogger(GameEvent.class);

    private final int id;
    private final long timestamp;
    private final Character mapleCharacter;
    private final int world;
    private final int channel;
    private final MapleMap map;
    private final String playerName;
    private final int playerId;
    private final EventType type;
    private final String message;
    private final Item item;
    private final Boolean pass;
    // Directed-channel add-ons (CHAT_WHISPER/BUDDY/PARTY/GUILD/ALLIANCE). null for map-wide events.
    private final Character recipient;
    private final ChatType chatType;

    public GameEvent(Character mapleCharacter, EventType type, String message, Item item, Boolean pass) {
        this(mapleCharacter, type, message, item, pass, null, null);
    }

    public GameEvent(Character mapleCharacter, EventType type, String message, Item item, Boolean pass,
                     Character recipient, ChatType chatType) {
        this.id = nextId++;
        this.timestamp = System.currentTimeMillis();
        this.mapleCharacter = mapleCharacter;
        this.world = mapleCharacter.getWorld();
        this.channel = mapleCharacter.getMap().getChannelServer().getId();
        this.map = mapleCharacter.getMap();
        this.playerName = mapleCharacter.getName();
        this.playerId = mapleCharacter.getId();
        this.type = type;
        this.message = message;
        this.item = item;
        this.pass = pass;
        this.recipient = recipient;
        this.chatType = chatType;
    }

    /** The addressed bot for a directed-channel event; null for map-wide events. */
    public Character getRecipient() {
        return recipient;
    }

    /** The directed channel; null for map-wide events. */
    public ChatType getChatType() {
        return chatType;
    }

    public int getId() {
        return id;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public Character getMapleCharacter() {
        return mapleCharacter;
    }

    public int getWorld() {
        return world;
    }

    public int getChannel() {
        return channel;
    }

    public MapleMap getMap() {
        return map;
    }

    public String getPlayerName() {
        return playerName;
    }

    public int getPlayerId() {
        return playerId;
    }

    public EventType getType() {
        return type;
    }

    public String getMessage() {
        return message;
    }

    public Item getItem() {
        return item;
    }

    public Boolean getPass() {
        return pass;
    }

    public void printDescription() {
        log.debug("GameEvent: id={}, character={}, type={}, message={}, item={}, pass={}",
                id, mapleCharacter, type, message, item, pass);
    }
}
