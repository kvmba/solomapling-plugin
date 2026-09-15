package soloMapling.ArtificialPlayer.BotTypes.Magatia;

import org.gms.client.Character;
import soloMapling.ArtificialPlayer.BotGeneration;
import soloMapling.ArtificialPlayer.PartyQuest.PartyQuestBot;
import soloMapling.ArtificialPlayer.PartyQuest.PqActions;

/**
 * Plays Magatia PQ ("Romeo and Juliet") with a real player leading.
 *
 * <p>Seven stages that change character as they go, so the bot reads the quest's own stage
 * flags to know which one it is in rather than assuming from the room - several rooms share
 * a stage, and the sub-rooms off {@code 926110200} belong to the same one.
 *
 * <p>The stage-6 combination is the part worth reading: the quest publishes each slot's answer
 * as its own property, so the bot fetches them instead of trying arrangements. The escort at
 * the end is driven by talking to Yulete and then surviving what follows, and the talking is
 * the leader's - a bot cannot start a conversation that decides the run's outcome.
 */
public class MagatiaPQBot extends PartyQuestBot {

    private static final int FIGHT_PASSES = 20;

    public MagatiaPQBot(Character character) {
        super(character);
        dialoguePath = "MagatiaPQBotDialogue.yaml";
        botType = "MagatiaPQBot";
        // The stage scripts resolve their speaker through client.getPlayer() when they run,
        // and the escort reads its own properties, so this bot needs its own client.
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
        return MagatiaPqData.isQuestRoom(mapId);
    }

    @Override
    protected int lobbyMapId() {
        return MagatiaPqData.RECRUIT_MAP_A;
    }

    @Override
    protected boolean workStage() {
        int stage = currentStage();

        // The escort's own switch: once Yulete has been dealt with, the run is decided, and
        // there is nothing for a bot to add until the exit.
        if (PqActions.readEimInt(getChr(), MagatiaPqData.YULETE_PASSED, 0) == 1) {
            return false;
        }

        switch (stage) {
            case 6 -> readCombination();
            default -> fightAndBreak();
        }
        return false;
    }

    /**
     * Stage 6's published per-slot combination.
     *
     * <p>Read rather than tried: the quest writes one property per slot before anyone
     * attempts the puzzle, which is the difference between a bot that helps and one that
     * makes the same guesses the party was already making.
     */
    private void readCombination() {
        StringBuilder plan = new StringBuilder();
        for (int slot = 0; slot < MagatiaPqData.STAGE_6_SLOTS; slot++) {
            int answer = PqActions.readEimInt(getChr(),
                    MagatiaPqData.stage6Key(slot), Integer.MIN_VALUE);
            if (answer == Integer.MIN_VALUE) {
                return; // not published yet; the party has not reached the prompt
            }
            if (slot > 0) {
                plan.append(", ");
            }
            plan.append(answer);
        }
        PqActions.say(getChr(), "Stage 6: " + plan);
    }

    /** Kill what is here and break what is breakable, which covers the early stages. */
    private void fightAndBreak() {
        var map = getChr().getMap();
        if (!map.getMonsters().isEmpty()) {
            for (int pass = 0; pass < FIGHT_PASSES && !map.getMonsters().isEmpty(); pass++) {
                PqActions.attack(getChr());
            }
        }
        PqActions.loot(getChr(), getChr().getPosition(), 2_000, new int[0]);
    }

    /**
     * Which stage is in play, from the quest's flags.
     *
     * <p>Read downwards from the last one, because the flags are set in order and the newest
     * outstanding one is the stage the party is on.
     */
    private int currentStage() {
        for (int stage = 7; stage >= 1; stage--) {
            if (PqActions.readEimInt(getChr(), "statusStg" + stage, 0) == 0) {
                return stage;
            }
        }
        return 7;
    }
}
