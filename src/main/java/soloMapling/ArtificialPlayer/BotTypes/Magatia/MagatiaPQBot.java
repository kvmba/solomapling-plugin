package soloMapling.ArtificialPlayer.BotTypes.Magatia;

import org.gms.client.Character;
import org.gms.server.maps.Portal;
import soloMapling.ArtificialPlayer.BotGeneration;
import soloMapling.ArtificialPlayer.PartyQuest.PartyQuestBot;
import soloMapling.ArtificialPlayer.PartyQuest.PqActions;
import soloMapling.Environment.BotMessages;

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

    private static final int[] DOCUMENTS = {4001134, 4001135};

    /**
     * The town this bot was recruited from, captured at construction (it is built while standing
     * in its lobby). Returned to when a run ends, so an Alcadno bot goes back to Alcadno and a
     * Zenumist one to Zenumist rather than both piling into one town.
     */
    private final int lobbyMap;

    /** The stage-6 combination last spoken, so the once-per-tick read does not repeat it. */
    private String announcedCombination;

    public MagatiaPQBot(Character character) {
        super(character);
        dialoguePath = PARTY_QUEST_DIALOGUE_PATH;
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
    protected Portal forwardPortal(int leaderMapId) {
        var map = getChr().getMap();
        int here = map.getId();
        if (here / 10_000 != leaderMapId / 10_000) {
            return null; // the Alcadno and Zenumist maps are separate runs
        }
        int room = here % 10_000;
        int leaderRoom = leaderMapId % 10_000;
        // The four puzzle entrances are exclusive slots. Let the room scripts assign them;
        // following the leader's slot would try a door that's already taken.
        String door = switch (room) {
            case 0, 1, 100, 400 -> leaderRoom > room ? "pt00" : null;
            case 200 -> leaderRoom >= 203 ? "pt00" : null;
            case 201, 202 -> leaderRoom == 200 || leaderRoom >= 203 ? "out00" : null;
            case 203 -> leaderRoom >= 300 ? "out00" : null;
            case 301, 302, 303, 304 -> leaderRoom >= 400 ? "out00" : null;
            default -> null;
        };
        return door == null ? null : map.getPortal(door);
    }

    /**
     * Forget the spoken combination when the run ends.
     *
     * <p>The property is republished per run, and {@code stage6_comb1} can easily come out the same
     * two runs in a row; a stale value would silence the one line the party needs.
     */
    @Override
    protected void releaseRoomState() {
        super.releaseRoomState();
        announcedCombination = null;
    }

    @Override
    protected int lobbyMapId() {
        return lobbyMap;
    }

    @Override
    protected boolean fightsOnSweep() {
        return !collectionRoomCleared();
    }

    private boolean collectionRoomCleared() {
        Character bot = getChr();
        return switch (bot.getMapId()) {
            case MagatiaPqData.BEAKER_ROOM_A, MagatiaPqData.BEAKER_ROOM_Z ->
                    PqActions.readEimInt(bot, "statusStg3", 0) == 3;
            case MagatiaPqData.DOCUMENT_ROOM_A, MagatiaPqData.DOCUMENT_ROOM_Z ->
                    PqActions.readEimInt(bot, "statusStg4", 0) == 1 || documentsReady();
            default -> false;
        };
    }

    private boolean documentsReady() {
        return !PqActions.needsHuntForLeader(getChr(), DOCUMENTS[0], 1)
                && !PqActions.needsHuntForLeader(getChr(), DOCUMENTS[1], 1);
    }

    private void deliverDocuments() {
        Character bot = getChr();
        int npc = bot.getMapId() == MagatiaPqData.DOCUMENT_ROOM_A ? 2112005 : 2112006;
        for (int document : DOCUMENTS) {
            PqActions.deliverAndWait(bot, document, npc);
        }
        if (PqActions.readEimInt(bot, MagatiaPqData.LETTER_DELIVERED, 0) == 0) {
            int letter = bot.getMapId() == MagatiaPqData.DOCUMENT_ROOM_A
                    ? MagatiaPqData.ROMEO_LETTER : MagatiaPqData.JULIET_LETTER;
            PqActions.deliverAndWait(bot, letter, npc);
        }
    }

    @Override
    protected boolean workStage() {
        Character bot = getChr();
        // The escort's own switch: once Yulete has been dealt with, the run is decided, and
        // there is nothing for a bot to add until the exit.
        if (PqActions.readEimInt(bot, MagatiaPqData.YULETE_PASSED, 0) == 1) {
            return false;
        }
        if (collectionRoomCleared()) {
            if (bot.getMapId() == MagatiaPqData.DOCUMENT_ROOM_A
                    || bot.getMapId() == MagatiaPqData.DOCUMENT_ROOM_Z) {
                deliverDocuments();
            } else {
                PqActions.stopHunting(bot);
                PqActions.waitNearStageNpc(bot,
                        bot.getMapId() == MagatiaPqData.BEAKER_ROOM_A ? 2112003 : 2112004);
            }
            return false;
        }

        switch (currentStage()) {
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
     *
     * <p>Spoken once per combination, not once per tick: {@link #workStage} runs every 2.5s in a
     * run, so an unguarded {@code say} here repeated the same six strings forty times a minute -
     * the bot's single most spammy line in the whole quest family.
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
        String spoken = plan.toString();
        if (spoken.equals(announcedCombination)) {
            return; // same combination, same line - do not spam the room
        }
        announcedCombination = spoken;
        PqActions.say(getChr(), BotMessages.get("pq.stage_plan", 6, spoken));
    }

    /** Kill what is here and break what is breakable, which covers the early stages. */
    private void fightAndBreak() {
        Character bot = getChr();
        if ((bot.getMapId() == MagatiaPqData.DOCUMENT_ROOM_A
                || bot.getMapId() == MagatiaPqData.DOCUMENT_ROOM_Z)
                && (PqActions.countItem(bot, DOCUMENTS[0]) > 0 || PqActions.countItem(bot, DOCUMENTS[1]) > 0
                || (PqActions.readEimInt(bot, MagatiaPqData.LETTER_DELIVERED, 0) == 0
                        && PqActions.countItem(bot, bot.getMapId() == MagatiaPqData.DOCUMENT_ROOM_A
                                ? MagatiaPqData.ROMEO_LETTER : MagatiaPqData.JULIET_LETTER) > 0))) {
            deliverDocuments();
            return;
        }
        var map = getChr().getMap();
        if (!map.getMonsters().isEmpty()) {
            PqActions.seekAndAttack(getChr());
        }
        // The loot sweep must NAME the quest items: an empty filter matches nothing, and the
        // sweep is what fills the bot's inventory for the hand-off below.
        PqActions.loot(getChr(), getChr().getPosition(), 2_000, MagatiaPqData.QUEST_ITEMS);
        // Reagents feed the beakers, not an NPC. Letters and documents stay pocketed until
        // the party returns from the side rooms to Romeo/Juliet's delivery post.
        PqActions.handItemsToLeader(getChr(), 4001132);
        PqActions.handItemsToLeader(getChr(), 4001133);
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
