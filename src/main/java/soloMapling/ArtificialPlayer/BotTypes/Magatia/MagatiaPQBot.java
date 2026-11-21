package soloMapling.ArtificialPlayer.BotTypes.Magatia;

import org.gms.client.Character;
import org.gms.server.maps.Portal;
import org.gms.server.maps.Reactor;
import soloMapling.ArtificialPlayer.BotGeneration;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.ArtificialPlayer.PartyQuest.PartyQuestBot;
import soloMapling.ArtificialPlayer.PartyQuest.PqActions;
import soloMapling.ArtificialPlayer.PartyQuest.PqStageChat;
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
    private static final int[] BEAKER_LOOT = {4001132};
    private static final int[] DOOR_LOOT = {4001133};
    private static final int REWARD_NPC = 2112018;
    private static final int BOSS_TURN_IN_NPC = 2112005;

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
        return MagatiaPqData.isQuestRoom(mapId) || mapId == 926110700 || mapId == 926100700;
    }

    @Override
    protected boolean isFollowingLeader() {
        int room = getChr().getMapId() % 10_000;
        return room < 301 || room > 304; // every lane must advance through its own ten rungs
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
            case 200 -> switch (leaderRoom) {
                case 201 -> "in00";
                case 202 -> "in01";
                default -> leaderRoom >= 203 ? "pt00" : null;
            };
            case 201, 202 -> leaderRoom == 200 || leaderRoom >= 203 ? "out00" : null;
            case 203 -> leaderRoom >= 300 ? "out00" : null;
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
        var instance = getChr().getEventInstance();
        return getChr().getMapId() % 10_000 < 600 && (instance == null || !instance.isEventCleared())
                && !collectionRoomCleared();
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
        int room = bot.getMapId() % 10_000;
        if (room == 600) {
            settleRewardRoom(REWARD_NPC);
            return false;
        }
        if (room == 700) {
            settleExitPortal("out00");
            return false;
        }
        var instance = bot.getEventInstance();
        if (instance != null && instance.isEventCleared()) {
            if (room == 500) {
                int npc = bot.getMapId() == 926110500 ? 2112011 : 2112001;
                if (hasPlayerInRunRoom(bot.getMapId() + 100)) {
                    PqActions.talkTo(bot, npc, 1, 0);
                } else {
                    PqActions.waitNearStageNpc(bot, npc);
                }
                return false;
            }
            if (room == 401) {
                // The boss summons Juliet/Romeo here after the fight. The success path
                // takes two confirmations; the failed escort needs only the first.
                int npc = lobbyMap == MagatiaPqData.RECRUIT_MAP_A ? BOSS_TURN_IN_NPC : 2112006;
                if (hasPlayerInRunRoom(bot.getMapId() + 99) || hasPlayerInRunRoom(bot.getMapId() + 199)) {
                    if (PqActions.readEimInt(bot, MagatiaPqData.ESCORT_FAIL, 0) == 1) {
                        PqActions.talkTo(bot, npc, 0);
                    } else {
                        PqActions.talkTo(bot, npc, 0, 0);
                    }
                } else {
                    PqActions.waitNearStageNpc(bot, npc);
                }
                return false;
            }
        }
        switch (room) {
            case 0 -> {
                PqActions.waitNearStageNpc(bot, townNpc());
                return false;
            }
            case 300 -> {
                readCombination();
                takeFreeLane(bot);
                return false;
            }
            case 301, 302, 303, 304 -> {
                climbLane(bot, room - 300);
                return false;
            }
            default -> { }
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

        if (room == 100) {
            fillBeakers(bot);
        } else {
            fightAndBreak();
        }
        return false;
    }

    private int townNpc() {
        return lobbyMap == MagatiaPqData.RECRUIT_MAP_A ? 2112003 : 2112004;
    }

    private static void fillBeakers(Character bot) {
        Reactor beaker = null;
        for (Reactor reactor : bot.getMap().getAllReactors()) {
            if (reactor.getId() == 2618000 && reactor.isAlive() && reactor.getState() < 6
                    && (beaker == null || reactor.getState() > beaker.getState())) {
                beaker = reactor;
            }
        }
        if (beaker != null && PqActions.feedReactor(bot, beaker.getObjectId(), BEAKER_LOOT[0])) return;
        PqActions.seekAndAttack(bot, false, BEAKER_LOOT);
        PqActions.loot(bot, bot.getPosition(), 1_200, BEAKER_LOOT);
    }

    private static void takeFreeLane(Character bot) {
        int occupied = PqActions.readEimInt(bot, "statusStg5", 0);
        for (int slot = 0; slot < MagatiaPqData.STAGE_6_SLOTS; slot++) {
            if ((occupied & (1 << slot)) == 0) {
                Portal door = bot.getMap().getPortal("pt0" + slot);
                if (door != null) PqActions.walkThroughPortal(bot, door);
                return; // the game's own entrance script reserves the slot
            }
        }
    }

    private static void climbLane(Character bot, int lane) {
        var map = bot.getMap();
        int y = bot.getPosition().y;
        Portal exit = map.getPortal("out00");
        if (exit != null && Math.abs(exit.getPosition().y - y) < 80) {
            PqActions.walkThroughPortal(bot, exit);
            return;
        }
        String combination = PqActions.readEimString(bot, MagatiaPqData.stage6Key(lane - 1));
        if (combination == null || combination.length() != 10) return;
        Portal next = null;
        int nearestRow = Integer.MAX_VALUE;
        for (int row = 0; row < combination.length(); row++) {
            Portal portal = map.getPortal("pt" + row + combination.charAt(row));
            if (portal != null) {
                int height = Math.abs(portal.getPosition().y - y);
                if (height < nearestRow) {
                    nearestRow = height;
                    next = portal;
                }
            }
        }
        if (next != null) PqActions.walkThroughPortal(bot, next);
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
        if (!GCMovement.isMapObserved(getChr().getMap()) || !PqStageChat.isRoomSpeaker(this)) return;
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
        if (PqStageChat.claimRoomLine(getChr(), "magatia.combination", spoken)) {
            PqActions.say(getChr(), BotMessages.get("pq.stage_plan", 6, spoken));
        }
    }

    /** Keep the quest items and open the actual item-triggered doors, not their scenery. */
    private void fightAndBreak() {
        Character bot = getChr();
        int room = bot.getMapId() % 10_000;
        if (room == 200
                && (PqActions.countItem(bot, DOCUMENTS[0]) > 0 || PqActions.countItem(bot, DOCUMENTS[1]) > 0
                || (PqActions.readEimInt(bot, MagatiaPqData.LETTER_DELIVERED, 0) == 0
                        && PqActions.countItem(bot, bot.getMapId() == MagatiaPqData.DOCUMENT_ROOM_A
                                ? MagatiaPqData.ROMEO_LETTER : MagatiaPqData.JULIET_LETTER) > 0))) {
            deliverDocuments();
            return;
        }
        var map = getChr().getMap();
        if (room == 200) {
            for (String name : new String[]{"jnr3_out1", "jnr3_out2", "rnj3_out1", "rnj3_out2"}) {
                var door = map.getReactorByName(name);
                if (door != null && PqActions.feedReactor(bot, door.getObjectId(), DOOR_LOOT[0])) return;
            }
        } else if (room == 201 || room == 202) {
            for (var box : map.getAllReactors()) {
                if (box.isActive() && box.getReactorType() < 100
                        && PqActions.approachReactor(bot, box.getObjectId(), false, true) != PqActions.Approach.STUCK) {
                    return;
                }
            }
        }
        if (!map.getMonsters().isEmpty()) {
            PqActions.seekAndAttack(getChr());
        }
        // The loot sweep must NAME the quest items: an empty filter matches nothing, and the
        // sweep is what fills the bot's inventory for the hand-off below.
        PqActions.loot(getChr(), getChr().getPosition(), 2_000, MagatiaPqData.QUEST_ITEMS);
    }
}
