package soloMapling.ArtificialPlayer.BotTypes.Ariant;

import org.gms.client.Character;
import org.gms.server.life.Monster;
import soloMapling.ArtificialPlayer.BotGeneration;
import soloMapling.ArtificialPlayer.PartyQuest.PartyQuestBot;
import soloMapling.ArtificialPlayer.PartyQuest.PqActions;

/**
 * Plays Ariant Coliseum with a real player leading.
 *
 * <p>A catching race, not a fight, and the difference decides how this bot has to behave. The
 * score is the number of Spirit Jewels in the inventory, and a jewel only appears when a
 * scorpion is caught - which requires the scorpion to be worn down below forty percent health
 * and then, half the time, takes an Element Rock. Killing a scorpion outright scores nothing.
 *
 * <p>So the bot does the one thing no other bot here does: it checks health before swinging and
 * stops when the target is in catching range. That means reading the monsters rather than
 * firing at whatever the attack driver picks, because the attack driver has no idea it is
 * supposed to leave something alive.
 */
public class AriantPQBot extends PartyQuestBot {

    /** How many swings to spend before letting the tick end. */
    private static final int SWING_PASSES = 15;

    public AriantPQBot(Character character) {
        super(character);
        dialoguePath = "AriantPQBotDialogue.yaml";
        botType = "AriantPQBot";
        // The arena awards its score through client.getPlayer() when a catch succeeds, so this
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
        return AriantPqData.isAriantMap(mapId);
    }

    @Override
    protected int lobbyMapId() {
        // The lobby is whichever Ariant lobby the run was started from; the arena hands the
        // party back to it on its own, so returning there is the right default.
        return getChr().getMapId();
    }

    @Override
    protected boolean workStage() {
        if (!AriantPqData.isArena(getChr().getMapId())) {
            return false; // in the lobby; the run warps the party in itself
        }
        if (!hasElementRock()) {
            // No rocks left means no more catches, and killing would only remove the scorpions
            // other party members could still be catching. Sit the round out.
            return false;
        }

        for (int pass = 0; pass < SWING_PASSES; pass++) {
            Monster target = bestCatchTarget();
            if (target == null) {
                // Nothing is in range yet, or everything nearby is already low enough that
                // another swing could kill it. Either way, do not swing.
                break;
            }
            if (isInCatchRange(target)) {
                // Worn down to where a catch will be offered: ask for one instead of hitting
                // again. The handler decides whether it succeeds.
                throwElementRock(target);
                break;
            }
            PqActions.attack(getChr());
        }
        return false;
    }

    /** Whether the bot still has the item a catch spends. */
    private boolean hasElementRock() {
        return PqActions.countItem(getChr(), AriantPqData.ELEMENT_ROCK) > 0;
    }

    /**
     * Whether a scorpion is low enough for the handler to accept a catch, using the same
     * arithmetic the handler uses so the bot and the server agree at the boundary.
     */
    private boolean isInCatchRange(Monster mob) {
        return AriantPqData.catchableAt(mob.getHp(), mob.getMaxHp());
    }

    /**
     * The scorpion worth working on: the healthiest one that is not yet in catch range, so the
     * bot is always bringing something down rather than hovering over a monster it should not
     * touch.
     */
    private Monster bestCatchTarget() {
        Monster best = null;
        for (var obj : getChr().getMap().getAllMonsters()) {
            if (!(obj instanceof Monster mob) || mob.getId() != AriantPqData.SCORPION) {
                continue;
            }
            if (mob.getHp() <= 0) {
                continue;
            }
            if (best == null || mob.getHp() > best.getHp()) {
                best = mob;
            }
        }
        return best;
    }

    /**
     * Use an Element Rock on a scorpion.
     *
     * <p>The server's catch path is a packet handler, so a socketless bot cannot send the
     * request the way a client does. What it can do is the part that decides the score: the
     * handler's own work is the 50% roll, the rock spent, and the jewel added; a bot that only
     * had the animation would catch nothing.
     */
    private void throwElementRock(Monster target) {
        AriantCatch.attempt(getChr(), target);
    }
}
