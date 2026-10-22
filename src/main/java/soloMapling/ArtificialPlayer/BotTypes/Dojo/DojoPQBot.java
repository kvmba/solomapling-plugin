package soloMapling.ArtificialPlayer.BotTypes.Dojo;

import org.gms.client.Character;
import org.gms.server.maps.Portal;
import soloMapling.ArtificialPlayer.BotGeneration;
import soloMapling.ArtificialPlayer.PartyQuest.PartyQuestBot;
import soloMapling.ArtificialPlayer.PartyQuest.PqActions;

/**
 * Coaches a party up the Mu Lung Dojo.
 *
 * <p>A tower of bosses and nothing else: each room is a fight, the door to the next opens when
 * the room is clear, and the party climbs until it runs out of time or people. So the bot adds
 * damage and stays with the group, and the interesting part of this quest is not in the bot at
 * all - it is in getting in.
 *
 * <p>Entry is the risky step, and this bot deliberately does not attempt it. The channel hands
 * out five party slots as a shared resource and a bot party would occupy one a player party
 * then cannot have; and the level rule is a spread of thirty rather than a range, so a bot
 * added carelessly can block an otherwise legal party from entering. Both are decisions for
 * whoever is assembling the party, not for a bot to take on its own.
 */
public class DojoPQBot extends PartyQuestBot {

    public DojoPQBot(Character character) {
        super(character);
        dialoguePath = "DojoPQBotDialogue.yaml";
        botType = "DojoPQBot";
        questName = "Dojo";
        // The tower's spawns and the party warps resolve their player through
        // client.getPlayer(), so this bot needs a client of its own.
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
        return DojoPqData.isPartyDojo(mapId);
    }

    @Override
    protected int lobbyMapId() {
        return DojoPqData.DOJO_HALL;
    }

    @Override
    protected boolean workStage() {
        Character bot = getChr();
        var map = bot.getMap();
        // The dojo's rooms are one boss each and the door opens when it is down, so there is
        // nothing to read - just fight while anything is standing.
        if (!map.getMonsters().isEmpty()) {
            PqActions.seekAndAttack(bot);
            return false;
        }
        // Room clear. Take the door the SERVER moves a body through. The obvious one
        // (out00/dojang_up) is a client animation: it only sends DOJO_WARP_UP and waits for the
        // client's reply, which a bot never sends - so a bot walking it stands at the door
        // forever. out001/dojang_next does the warp itself, and at a resting floor it moves the
        // whole party standing in the five rooms below.
        //
        // Only climb while the party is not BELOW us. A room spawns its boss from
        // onFirstUserEnter, which the host skips for artificial characters - so a bot that
        // reaches an empty room alone would see "cleared" and run the whole tower on its own,
        // leaving the party behind. Waiting there is the correct read: the leader's own entry
        // raises the boss, and the room is only genuinely cleared once he has.
        if (!partyIsBelow(bot)) {
            Portal door = PartyQuestBot.scriptedPortalOf(map.getPortals(),
                    DojoPqData.SERVER_DOOR, DojoPqData.SERVER_DOOR_SCRIPT);
            if (door != null && PqActions.walkTo(bot, door.getPosition())) {
                PqActions.enterPortal(bot, door);
            }
        }
        return false;
    }

    /** Whether this bot has climbed past the party - the leader is in a lower room. */
    private boolean partyIsBelow(Character bot) {
        Character leader = partyLeader();
        return leader != null && leader != bot && leader.getMapId() < bot.getMapId();
    }
}
