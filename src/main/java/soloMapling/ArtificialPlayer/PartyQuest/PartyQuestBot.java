package soloMapling.ArtificialPlayer.PartyQuest;

import org.gms.client.Character;
import soloMapling.ArtificialPlayer.BotPartySystem.BotPartyLogic;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.BotLogger;
import soloMapling.ArtificialPlayer.BotCommandsPack.WarpCommands;
import soloMapling.ArtificialPlayer.BotMovementSystem.MovementCommands;

import java.util.function.BooleanSupplier;

/**
 * Shared skeleton for a bot that plays a party quest alongside a real party leader.
 *
 * <p>Every quest bot needs the same four things and only differs in the middle: find the
 * run, stay with the leader, do the current stage's work, and leave when the run ends. This
 * class holds that shape so a quest supplies one method - {@link #workStage()} - and a small
 * amount of per-quest data, rather than re-implementing the lifecycle each time.
 *
 * <p>The leader is the anchor throughout: he is the one who talks to the recruit NPC, and
 * the quest's own gate is that the party leader is the one in the instance. A bot therefore
 * never tries to start the quest itself and never second-guesses which stage the party is
 * on beyond what the instance flags say.
 *
 * <p>Subclasses must call {@code super(character)} and, if they need their own client for
 * engine callbacks, {@code BotGeneration.adoptPrivateClient} (see the Orbis bot for why).
 */
public abstract class PartyQuestBot extends BotSM {

    protected PartyQuestBot(Character character) {
        super(character);
    }

    /** Where the run happens: the map that proves the bot is inside the quest. */
    protected abstract boolean isInsideQuest(int mapId);

    /** The lobby the party starts in, to return to when a run ends. */
    protected abstract int lobbyMapId();

    /**
     * Do one tick's worth of the current stage's work.
     *
     * <p>Called only while the bot is inside the quest and in a party. Return true when the
     * run is over, which sends the bot back to the lobby.
     */
    protected abstract boolean workStage();

    // =========================================================================
    // Lifecycle
    // =========================================================================

    /**
     * One tick of the shared lifecycle: take an invite if one is waiting, leave if the run is
     * over, otherwise work the stage.
     *
     * <p>Accepting invites happens here, before the "am I inside the quest" test, and that
     * ordering is the whole point: a bot waiting to be recruited is by definition *not* inside
     * the quest yet. Putting this after the check would leave every bot in this package unable
     * to join a party at all - it would sit in the lobby looking ready and ignore the player
     * standing in front of it.
     *
     * <p>What it still does not do is start the quest. A player invites the bot and the
     * leader starts the run, which is the quests' own rule, so the only decisions here are
     * whether to join and whether to keep working.
     */
    protected final void tickPartyQuest() {
        Character bot = getChr();
        if (bot == null || bot.getMap() == null) {
            return;
        }

        // A pending invitation is answered even outside the quest - that is when they arrive.
        if (bot.getParty() == null) {
            BotPartyLogic.checkPartyQueue(bot);
        }

        if (!isInsideQuest(bot.getMapId())) {
            return;
        }
        if (bot.getParty() == null) {
            returnToLobby("no longer in a party");
            return;
        }

        // Stay with the party. Most of these quests move between rooms by the players walking
        // through a portal, not by a script warping the team, so a bot that only ever works on
        // whatever room it happens to be standing in gets left behind at the first stage
        // transition - registered in the instance but standing in an empty room, where it
        // contributes nothing and, worse, is still counted by getPlayerCount for the puzzles
        // that care how many people are present.
        if (followLeaderIntoNextRoom()) {
            return; // moved rooms; work resumes from the new one next tick
        }

        if (workStage()) {
            returnToLobby("stage work reports the run is over");
        }
    }

    /**
     * Walk through the portal the leader took, when the party has moved on without this bot.
     *
     * <p>Quests advance in one of two ways: the event script warps the whole team, or the
     * players walk a portal whose script checks that the stage is clear. The second kind is
     * invisible to a bot that does not walk it, and the result is not a stalled bot but a
     * misleading one - it stays registered, so a stage that counts the party's numbers still
     * counts it, while its position is in the room the party already left.
     *
     * <p>Only follows within the quest: a leader who has gone back to town is not a stage
     * transition, and following him out would abandon the run.
     *
     * @return true when this bot changed rooms
     */
    protected final boolean followLeaderIntoNextRoom() {
        Character leader = partyLeader();
        if (leader == null || leader == getChr()) {
            return false;
        }
        int leaderMap = leader.getMapId();
        int here = getChr().getMapId();
        if (leaderMap == here || !isInsideQuest(leaderMap)) {
            return false;
        }
        BotLogger.log("PQ bot " + getChr().getName() + " following the leader from "
                + here + " to " + leaderMap);
        WarpCommands.botWarpMapOnPortal(getChr());
        return getChr().getMapId() != here;
    }

    /** The party's leader as a character, or null when there is nobody to follow. */
    protected final Character partyLeader() {
        var party = getChr().getParty();
        if (party == null || party.getLeader() == null) {
            return null; // the leader's character is null while he is offline
        }
        return party.getLeader().getPlayer();
    }

    /** Walk back to the lobby, which is where a finished run puts everyone. */
    protected final void returnToLobby(String reason) {
        if (getChr().getMapId() == lobbyMapId()) {
            return;
        }
        BotLogger.log("PQ bot " + getChr().getName() + " leaving the run: " + reason);
        WarpCommands.botWarpMapOnPortal(getChr());
    }

    // =========================================================================
    // Helpers a quest's stage work tends to want
    // =========================================================================

    /**
     * Wait for a condition, up to a limit, without blocking the tick forever.
     *
     * <p>Returning false on timeout rather than throwing means a stage that cannot be
     * finished - the party moved on, the leader gave up - degrades into "try something else
     * next tick" instead of a stuck bot.
     */
    protected static boolean waitUntil(BooleanSupplier condition, int attempts, long pauseMs) {
        for (int i = 0; i < attempts; i++) {
            if (condition.getAsBoolean()) {
                return true;
            }
            if (pauseMs > 0) {
                try {
                    Thread.sleep(pauseMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return condition.getAsBoolean();
                }
            }
        }
        return condition.getAsBoolean();
    }

    /** Walk to a point, then pause, which is what most stage steps look like. */
    protected void goTo(java.awt.Point spot, long settleMs) {
        MovementCommands.pathFinderBeta(getChr(), spot);
        if (settleMs > 0) {
            try {
                Thread.sleep(settleMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
