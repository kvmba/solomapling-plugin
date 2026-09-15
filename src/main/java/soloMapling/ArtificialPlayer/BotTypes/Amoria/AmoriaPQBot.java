package soloMapling.ArtificialPlayer.BotTypes.Amoria;

import org.gms.client.Character;
import soloMapling.ArtificialPlayer.BotGeneration;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.PartyQuest.PartyQuestBot;
import soloMapling.ArtificialPlayer.PartyQuest.PqActions;

/**
 * Plays Amoria PQ ("Amorian Challenge") with a real player leading.
 *
 * <p>The quest's stage number is derived from the map, which is the same arithmetic the NPC
 * uses, so the bot always names the stage correctly without tracking anything.
 *
 * <p>Stage 2 is the rope puzzle: nine ropes, five bodies, and a combination the quest stores
 * as nine counts. The bot reads it and takes a rope, choosing from the far end of the list so
 * the player has somewhere to stand - the quest wants exactly five bodies on ropes and counts
 * him too.
 *
 * <p>Stage 3 looks identical and is not. Its map has no areas at all, and the check is on the
 * party's <em>item</em> counts instead: each slot wants so many of {@code 4000000 + i}. A bot
 * that stood on a rope here would contribute nothing, and the party would keep failing while
 * the feedback suggested the positions were wrong.
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
            case 2 -> stageTwo();
            case 3 -> stageThree();
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

    /** Stage 2: take a rope the quest's combination asks for. */
    private boolean stageTwo() {
        String published = PqActions.readEimString(getChr(), AmoriaPqData.STAGE_2_COMBO);
        int[] counts = AmoriaPqData.chosenCounts(published);
        if (counts == null) {
            return false; // Amos has not picked yet; the party has to talk to him first
        }
        var rope = AmoriaPqData.myRope(counts, botIndex());
        if (rope != null) {
            PqActions.holdArea(getChr(), rope, 1_200);
        }
        return false;
    }

    /**
     * Stage 3: the same room shape, a different rule.
     *
     * <p>Because this stage counts inventory rather than positions, the bot can do nothing
     * useful by standing anywhere - it reports what the slot it would have taken wants, and
     * gathers the item if it is one it can get. That is an honest limit: the two stages look
     * alike enough that silently treating them the same would be worse than saying so.
     */
    private boolean stageThree() {
        String published = PqActions.readEimString(getChr(), AmoriaPqData.STAGE_3_COMBO);
        int[] counts = AmoriaPqData.chosenCounts(published);
        if (counts == null) {
            return false;
        }
        for (int slot = 0; slot < counts.length; slot++) {
            if (counts[slot] <= 0) {
                continue;
            }
            int item = AmoriaPqData.stageThreeItemFor(slot);
            if (PqActions.countItem(getChr(), item) < counts[slot]) {
                // The item is what the stage checks, and it is a quest item the room provides.
                PqActions.attack(getChr());
                PqActions.loot(getChr(), getChr().getPosition(), 2_000, new int[]{item});
                break;
            }
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
