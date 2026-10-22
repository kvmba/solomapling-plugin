package soloMapling.ArtificialPlayer.BotTypes.Papulatus;

import org.gms.client.Character;
import soloMapling.ArtificialPlayer.BotGeneration;
import soloMapling.ArtificialPlayer.PartyQuest.PartyQuestBot;
import soloMapling.ArtificialPlayer.PartyQuest.PqActions;

/**
 * Plays PapulatusBattle with a real player leading.
 *
 * <p>The whole run is "fight what the leader summoned, until the leader leaves". The summon
 * piece is the leader's (quest 7103 or the T-1337 shop; a bot can neither hold nor drop one)
 * and the rewards are the leader's too — the bot neither drops the piece on the Time Sphere
 * nor lets its pets sweep the boss's drops. There is no reactor-feed call, and the pet
 * follower checks this behaviour's live map policy at pickup time, including immediately
 * after map entry or a pet-system reload.
 *
 * <p>The exit is an NPC the leader talks to - the event never warps the team out on a clear.
 * So the bot's only real decision is when the run is over: the moment the leader is no longer
 * standing in the fight map. Every way that happens is terminal - he walked out through the
 * Machine (no exit door exists to come back through), died (respawn carries him to town and
 * the revive unregisters him), or dropped (his party slot reads {@code null}) - so there is no
 * grace period to wait out and no re-entry to chase.
 *
 * <p>While he IS here, the job is the same as every boss room: fight what is standing. The
 * 250ms combat sweep owns the swings; an empty map means the piece has not been dropped yet
 * (or the leader is looting) - stand by, never leave early.
 */
public class PapulatusPQBot extends PartyQuestBot {

    public PapulatusPQBot(Character character) {
        super(character);
        dialoguePath = "PapulatusPQBotDialogue.yaml";
        botType = "PapulatusPQBot";
        questName = "PapulatusBattle";
        // The event's scripts resolve their player through client.getPlayer() when they run,
        // so this bot needs a client of its own rather than the shared per-channel one.
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
    public boolean allowsPetPickup(int itemId) {
        return !PapulatusPqData.isQuestRoom(getChr().getMapId());
    }

    @Override
    protected boolean isInsideQuest(int mapId) {
        return PapulatusPqData.isQuestRoom(mapId);
    }

    @Override
    protected int lobbyMapId() {
        return PapulatusPqData.LOBBY_MAP;
    }

    @Override
    protected boolean workStage() {
        // The lobby does not count as inside the quest, so reaching here means the bot is in
        // the fight map. The leader gone means the run is over - see the class comment for why
        // none of the three ways he can vanish is recoverable.
        Character leader = partyLeader();
        if (leader == null || leader == getChr()
                || leader.getMapId() != PapulatusPqData.BOSS_MAP) {
            return true; // the base class warps the bot home, instance unregistered first
        }
        if (!getChr().getMap().getMonsters().isEmpty()) {
            PqActions.seekAndAttack(getChr());
        }
        // An empty map with the leader still here is the wait for the summon piece (or his
        // loot sweep) - the one state the bot must sit through rather than walk home in.
        return false;
    }
}
