package soloMapling.ArtificialPlayer.BotTypes.Ariant;

import org.gms.client.Character;
import org.gms.client.inventory.manipulator.InventoryManipulator;
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
 *
 * <p>Two structural differences from every other quest bot in this package:
 * <ul>
 *   <li>a match is run over an <em>expedition</em>, not a party, and the engine refuses to start
 *       while any competitor is in a party - so this bot is recruited by enrolment, never by a
 *       party invite (see {@link AriantExpedition});</li>
 *   <li>the arena's own NPC hands each competitor fifty Element Rocks on arrival, a
 *       conversation a bot cannot hold, so the same kit is granted once per run instead.</li>
 * </ul>
 */
public class AriantPQBot extends PartyQuestBot {

    /** Whether this bot has already been given the arena kit for the run it is standing in. */
    private boolean suppliedThisRun;

    public AriantPQBot(Character character) {
        super(character);
        dialoguePath = PARTY_QUEST_DIALOGUE_PATH;
        botType = "AriantPQBot";
        questName = "AriantPQ";
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
        if (!AriantPqData.isArena(getChr().getMapId())) {
            suppliedThisRun = false; // the kit is per run
        }
        tickPartyQuest();
    }

    /**
     * The run's maps: the three arenas, and the queue halls their expeditions wait in.
     *
     * <p>The public lobby (980010000) is deliberately NOT part of the run - it is where the
     * crowd stands to be recruited, so it has to read as "outside the quest" for the base
     * class's lobby beat (shout, stroll, answer a greeting) to run there.
     */
    @Override
    protected boolean isInsideQuest(int mapId) {
        return AriantPqData.isArena(mapId) || AriantPqData.isQueueLobby(mapId);
    }

    /**
     * The arena is a disposable map with no {@code EventInstance} - {@code AriantColiseum} is
     * built directly on a channel map, not through an event script - so the base class's in-run
     * cadence gate would leave this bot on the 36-48s background tick. A ten-minute catching
     * race at that pace is roughly a dozen decisions per match: the bot would rarely swing, and
     * almost never at the moment a scorpion entered catch range. Arena time therefore opts into
     * the fast cadence explicitly, the same reason {@code CarnivalPQBot} does.
     *
     * <p>Scoped to the arena, not the queue hall: waiting with the expedition needs no fast
     * decisions, and a hall full of bots on the 2.5s wheel would be pure load.
     */
    @Override
    protected boolean runsOnOwnClock() {
        return AriantPqData.isArena(getChr().getMapId());
    }

    /**
     * An Ariant competitor is never in a party - the engine forbids it - so the base class's
     * party test is replaced by expedition membership. Without this the bot would read its own
     * run as "no longer in a party" and walk home on the first tick.
     */
    @Override
    protected boolean inRunGroup(Character bot) {
        return AriantExpedition.enrolled(bot);
    }

    /**
     * Party invites are declined, not accepted.
     *
     * <p>This is the one quest where accepting would actively break the run: the engine refuses
     * to start a match while any competitor is in a party, so a lobby bot that accepted an invite
     * would be permanently ineligible - and the player who invited it would have taken the whole
     * arena down with them. The recruit path here is the expedition
     * ({@link AriantExpedition}), which the lobby's own NPC creates.
     */
    @Override
    protected boolean acceptsPartyInvites(Character bot) {
        return false;
    }

    /**
     * The shared skeleton's shout-recruit answer is disabled for the same reason as
     * {@link #acceptsPartyInvites}: a "yes, invite me" here would cost the player their match.
     * The bot still shouts its own Ariant line, which is what draws a player to the lobby NPC.
     */
    @Override
    public boolean offerRecruit(Character player, String content) {
        return false;
    }

    @Override
    protected int lobbyMapId() {
        return AriantPqData.LOBBY_MAP;
    }

    /**
     * A queue hall is part of the run but has no work of its own: the match is started by the
     * player talking to Cesar. Standing with the team is the whole contribution.
     *
     * <p>An expedition that has gone away (the match disposed it, or the player abandoned the
     * registration) means the run is over. Reporting that through {@code true} - rather than
     * warping home here - is what lets the base class walk the bot out and say its clear line
     * through the one path every other quest uses.
     *
     * <p>Inside the arena, leaving is handled by the base class: the clock warps competitors to
     * the kings room when it expires, that room is outside this quest's maps, so the lobby
     * branch walks the finished bot back to the public lobby on its next tick.
     */
    @Override
    protected boolean workStage() {
        int mapId = getChr().getMapId();
        if (!AriantPqData.isArena(mapId)) {
            if (AriantExpedition.enrolled(getChr())) {
                return false; // queue hall: wait with the expedition for the player's NPC
            }
            // The expedition is gone (the match disposed it, or the player abandoned the
            // registration). Give up the seat for good - a stale roster entry would keep a slot
            // in a match this bot will never join - and report the run over so the base class
            // walks it home and says its clear line through the one path every quest uses.
            AriantExpedition.leave(getChr());
            return true;
        }
        grantArenaKitIfDue();
        if (!hasElementRock()) {
            // No rocks left means no more catches, and killing would only remove the scorpions
            // the other competitors could still be catching. Sit the round out.
            return false;
        }

        Monster target = bestCatchTarget();
        if (target != null) {
            if (isInCatchRange(target)) {
                // Worn down to where a catch will be offered: ask for one instead of hitting
                // again. The handler decides whether it succeeds.
                throwElementRock(target);
                return false;
            }
            PqActions.seekAndAttack(getChr());
        }
        return false;
    }

    /**
     * The kit the arena's NPC hands each competitor on arrival ({@code 2101017.js}: fifty
     * Element Rocks). A bot has no conversation with which to claim it, and without the rocks it
     * could never score, so the same quantity is granted once per run.
     *
     * <p>Once per run, not once per tick - but the flag is only raised on a kit that actually
     * landed. Setting it before the inventory check would burn the one attempt on a full
     * inventory and leave the bot without rocks for the rest of the match, which is exactly the
     * silent zero-score this grant exists to prevent.
     */
    private void grantArenaKitIfDue() {
        Character bot = getChr();
        if (suppliedThisRun || bot == null) {
            return;
        }
        if (!bot.canHold(AriantPqData.ELEMENT_ROCK, AriantPqData.SUPPLY_ROCKS)) {
            return; // no room for the rocks; retried next beat once a slot opens up
        }
        if (!InventoryManipulator.addById(bot.getClient(), AriantPqData.ELEMENT_ROCK,
                (short) AriantPqData.SUPPLY_ROCKS)) {
            return; // the add refused it (inventory changed under us); retried next beat
        }
        suppliedThisRun = true;
    }

    /**
     * Give up this bot's run membership when the base class stops or retypes it.
     *
     * <p>For every other quest the party is the membership record and the party machinery
     * maintains it. An Ariant seat lives on the channel's expedition instead, and nothing in the
     * engine removes a member except the arena's own {@code leaveArena} or the registration
     * timeout - neither of which runs for a bot that was stopped, converted, or walked home by
     * the plugin. Without this the roster accumulates seats in matches that will never happen.
     */
    @Override
    protected void releaseRoomState() {
        super.releaseRoomState();
        AriantExpedition.leave(getChr());
    }

    /** Whether the bot still has the item a catch spends. */
    private boolean hasElementRock() {
        return PqActions.countItem(getChr(), AriantPqData.ELEMENT_ROCK) > 0;
    }

    /**
     * The combat sweep may only swing in the arena while the macro tick's own catch contract
     * holds: rocks in hand and a scorpion worth wearing down still standing. The sweep cannot
     * pick catch targets, so where the contract is off (no rocks, or only catchable scorpions)
     * it must not fight at all - a killed scorpion is a catch some competitor can never make.
     */
    @Override
    protected boolean fightsOnSweep() {
        if (!AriantPqData.isArena(getChr().getMapId()) || !hasElementRock()) {
            return false;
        }
        Monster target = bestCatchTarget();
        return target != null && !isInCatchRange(target);
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
