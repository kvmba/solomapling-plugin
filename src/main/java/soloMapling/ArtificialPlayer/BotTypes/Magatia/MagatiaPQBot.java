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
 *
 * <p>Both versions run the same mechanics in parallel map ranges (Alcadno {@code 92611xxxx},
 * Zenumist {@code 92610xxxx}) and recruit in different towns, so the bot is spawned in and
 * works out of whichever lobby it was placed in.
 */
public class MagatiaPQBot extends PartyQuestBot {

    private static final int FIGHT_PASSES = 20;

    /**
     * The town this bot was recruited from, captured at construction (it is built while standing
     * in its lobby). Returned to when a run ends, so an Alcadno bot goes back to Alcadno and a
     * Zenumist one to Zenumist rather than both piling into one town.
     */
    private final int lobbyMap;

    public MagatiaPQBot(Character character) {
        super(character);
        dialoguePath = "MagatiaPQBotDialogue.yaml";
        botType = "MagatiaPQBot";
        questName = "MagatiaPQ";
        // The stage scripts resolve their speaker through client.getPlayer() when they run,
        // and the escort reads its own properties, so this bot needs its own client.
        BotGeneration.adoptPrivateClient(character);
        this.lobbyMap = character.getMapId() == MagatiaPqData.RECRUIT_MAP_Z
                ? MagatiaPqData.RECRUIT_MAP_Z
                : MagatiaPqData.RECRUIT_MAP_A;
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
        return lobbyMap;
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
     * Stage 6's published combination.
     *
     * <p>Each {@code stage6_combN} property is a ten-digit string of digits 0-3, not a number -
     * the stage's climbing puzzle reads one digit per row. So it is read as a string and spoken
     * back as one, and only the properties the quest actually writes are read.
     */
    private void readCombination() {
        StringBuilder plan = new StringBuilder();
        for (int slot = 0; slot < MagatiaPqData.STAGE_6_SLOTS; slot++) {
            String digits = PqActions.readEimString(getChr(), MagatiaPqData.stage6Key(slot));
            if (digits == null) {
                return; // not published yet; the party has not reached the prompt
            }
            if (slot > 0) {
                plan.append(", ");
            }
            plan.append(digits);
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
        // Whatever quest items the loot sweep picked up (the run's exclusive set: letters,
        // genes and their kin) belongs with the leader - the stage NPCs grade his pockets.
        for (int itemId : MagatiaPqData.QUEST_ITEMS) {
            PqActions.handItemsToLeader(getChr(), itemId);
        }
    }

    /**
     * Which stage is in play, from the quest's flags.
     *
     * <p>Read upwards from the first one: the quest runs the stages in order and sets each
     * {@code statusStgN} to 1 as it clears, so the lowest flag still at 0 is the one in play.
     * (Reading downwards reported the highest unfinished stage - usually 7 - and so never
     * surfaced the stage-6 combination the bot is here to help with.)
     */
    private int currentStage() {
        for (int stage = 1; stage <= 7; stage++) {
            if (PqActions.readEimInt(getChr(), "statusStg" + stage, 0) == 0) {
                return stage;
            }
        }
        return 7;
    }
}
