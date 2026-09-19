package soloMapling.ArtificialPlayer.BotTypes.Amoria;

import org.gms.client.Character;
import soloMapling.ArtificialPlayer.BotGeneration;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.PartyQuest.PartyQuestBot;
import soloMapling.ArtificialPlayer.PartyQuest.PqActions;

/**
 * Plays Amoria PQ ("Amorian Challenge") with a real player leading.
 *
 * <p>The quest's stage number is derived from the map, using the same arithmetic the NPC does,
 * so the bot always names the stage correctly without tracking anything.
 *
 * <p>Stages 2 and 3 are area puzzles on different maps: each records how many bodies each of its
 * areas should hold and compares that against where the party stands. The bot reads the published
 * combination and takes a spot from the far end, leaving the near slots for the player the quest
 * also counts. Stage 2 indexes the three areas of its sub-room; stage 3 the nine ropes on
 * {@code 670010400}.
 *
 * <p>Stage 1 (the mirror fight) and the stages past the ropes are fights, which the attack path
 * already covers, so this bot only adds its damage there.
 */
public class AmoriaPQBot extends PartyQuestBot {

    public AmoriaPQBot(Character character) {
        super(character);
        dialoguePath = "AmoriaPQBotDialogue.yaml";
        botType = "AmoriaPQBot";
        // The stage NPC resolves its speaker through client.getPlayer() when it runs, so this
        // bot needs a client of its own rather than the shared per-channel one.
        BotGeneration.adoptPrivateClient(character);
    }

    @Override
    public void updateState() {
        super.updateState();
        if (checkIfNotRunningOrPaused()) {
            return;
        }
        tickPartyQuest();
    }

    @Override
    protected boolean isInsideQuest(int mapId) {
        return mapId >= AmoriaPqData.ENTRY_MAP && mapId <= AmoriaPqData.CLEAR_MAP;
    }

    @Override
    protected int lobbyMapId() {
        return AmoriaPqData.RECRUIT_MAP;
    }

    @Override
    protected boolean workStage() {
        int stage = AmoriaPqData.stageOf(getChr().getMapId());
        return switch (stage) {
            case 1 -> stageOne();
            case 2, 3 -> ropePuzzle(stage);
            default -> false; // later stages are fights; the attack path already covers them
        };
    }

    /**
     * Stage 1 is "shatter the mirror and bring the shard back" - the fight to get the shard is
     * the only part a bot can help with, and the turn-in wants the piece in the hand of
     * whoever talks to the NPC.
     */
    private boolean stageOne() {
        PqActions.attack(getChr());
        return false;
    }

    /**
     * Stand on a spot the quest's published combination asks for.
     *
     * <p>Each rope stage records how many bodies each of its areas should hold and compares that
     * against where the party stands. The bot reads that stage's counts and takes a spot from the
     * far end of the list - leaving the near slots for the player, whom the quest counts too.
     *
     * <p>Stage 3 is the nine-rope map {@code 670010400}; stage 2 is the three-area sub-room the
     * stage-1 gate opens onto (its combination generator draws from only three slots). Each stage
     * indexes its own area list, which {@link AmoriaPqData#spotsFor} supplies. (This replaced a
     * stage-3 branch that modelled the puzzle as item counts {@code 4000000+i} - a rule the stage
     * script never applies, which left the bot standing still on the real rope map.)
     */
    private boolean ropePuzzle(int stage) {
        String published = PqActions.readEimString(getChr(), AmoriaPqData.comboPropertyFor(stage));
        int[] counts = AmoriaPqData.chosenCounts(published);
        if (counts == null) {
            return false; // Amos has not picked yet; the party has to talk to him first
        }
        var spot = AmoriaPqData.mySpot(counts, AmoriaPqData.spotsFor(stage), botIndex());
        if (spot != null) {
            PqActions.holdArea(getChr(), spot, 1_200);
        }
        return false;
    }

    /** Which body this bot is, counted from character ids for a stable order. */
    private int botIndex() {
        var party = getChr().getParty();
        if (party == null) {
            return 0;
        }
        int mine = getChr().getId();
        int ahead = 0;
        for (var member : party.getMembers()) {
            int id = member.getId();
            if (id != mine && id < mine && CharacterStorage.getBotById(id) != null) {
                ahead++;
            }
        }
        return ahead;
    }
}
