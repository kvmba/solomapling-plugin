package soloMapling.ArtificialPlayer;

import org.gms.client.Character;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotMessagingSystem.ChatMessage;

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class BotInteractorsHandler {

    // The Dispatcher's worker pool sets/removes interactors (on a player's chat thread) while the
    // bot's own tick reads them - a plain ArrayList there was a data race: a concurrent add/remove
    // can drop an entry or throw ArrayIndexOutOfBounds, so a player's line was silently answered by
    // the wrong bot or never attributed at all. CharacterStorage keeps its own respondant/inquirer
    // lists copy-on-write for exactly this reason; these per-bot copies were the ones left behind.
    private final List<Character> respondant = new CopyOnWriteArrayList<>();
    private final List<Character> inquirer = new CopyOnWriteArrayList<>();

    public List<Character> getListRespondants() {
        return respondant;
    }

    public List<Character> getListInquirer() {
        return inquirer;
    }

    public void setRespondant(Character respondant) {
        CharacterStorage.addPlayer(respondant);
        this.respondant.add(respondant);
    }

    public Character getRespondant() {
        return respondant.isEmpty() ? null : respondant.get(0);
    }

    public boolean isRespondant(Character character) {
        return this.respondant.contains(character);
    }

    public void removeRespondant(Character character) {
        CharacterStorage.removePlayer(character);
        this.respondant.remove(character);
    }

    public void resetRespondant() {
        for (Character character : this.respondant) {
            CharacterStorage.removePlayer(character);
        }
        this.respondant.clear();
    }

    public void setInquirer(Character inquirer) {
        CharacterStorage.addInquirer(inquirer);
        if (!this.inquirer.contains(inquirer)) {
            this.inquirer.add(inquirer);
        }
    }

    public Character getInquirer() {
        return inquirer.isEmpty() ? null : inquirer.get(0);
    }

    /**
     * Take the next pending inquirer, removing it in the same step.
     *
     * <p>The old "isEmpty() then get(0) then remove(...)" sequence in the host-bot transition was a
     * check-then-act race: two chat threads (or a chat thread and the tick) could both see a non-empty
     * list, and the second one then removed somebody else's entry - or read index 0 of a list the
     * other had just emptied. Callers that want "the player waiting for me" must use this instead.
     */
    public Character pollInquirer() {
        for (;;) {
            Iterator<Character> it = inquirer.iterator();
            if (!it.hasNext()) {
                return null;
            }
            Character first = it.next();
            if (inquirer.remove(first)) {
                return first;
            }
            // somebody else consumed it between the snapshot and the remove; retry
        }
    }

    public boolean isInquirer(Character character) {
        return this.inquirer.contains(character);
    }

    public void removeInquirer(Character character) {
        CharacterStorage.removeInquirer(character);
        this.inquirer.remove(character);
    }

    public void resetInquirer() {
        for (Character character : this.inquirer) {
            CharacterStorage.removeInquirer(character);
        }
        this.inquirer.clear();
    }

    public boolean isMessageFromRespondant(ChatMessage message) {
        return message.getSender() == getRespondant();
    }

}
