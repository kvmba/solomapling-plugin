package soloMapling.ArtificialPlayer.BotTypes.Dojo;

import org.gms.client.Character;
import org.gms.server.life.LifeFactory;
import org.gms.server.life.Monster;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Portal;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotTargetRules;
import soloMapling.ArtificialPlayer.BotGeneration;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.ArtificialPlayer.PartyQuest.PartyQuestBot;
import soloMapling.ArtificialPlayer.PartyQuest.PqActions;

import java.awt.Point;

/**
 * Coaches a party up the Mu Lung Dojo.
 *
 * <p>A tower of bosses and nothing else: each room is a fight, the door to the next opens when
 * the room is clear, and the party climbs until it runs out of time or people.
 *
 * <p>Two things about the tower are not what a plain "fight and walk the portal" bot assumes, and
 * both come from the host skipping map scripts for artificial characters:
 *
 * <ul>
 *   <li><b>The boss is raised by a map script.</b> {@code onFirstUserEnter/dojang_1st} spawns the
 *       room's boss, and {@code MapleMap.addPlayer} runs it only for {@code chrSize == 1} and only
 *       for a non-artificial body. A bot that enters first therefore raises nothing, and because
 *       the gate is {@code chrSize == 1} the human leader who follows it raises nothing either -
 *       the room stays empty for the rest of the run. {@link #raiseBoss} supplies that half.</li>
 *   <li><b>"Cleared" is not "no monsters".</b> Every boss revives into {@code 9300216}, a 2.09B-HP
 *       transparent marker ({@code Mob.wz} {@code revive/0}); it is what {@code dojang_up} and
 *       {@code dojang_1st} read as the clear signal, and it stays on the map. The marker is also
 *       flagged {@code invincible}, a WZ field this host never loads - so an unfiltered scan sees a
 *       boss-sized mob and swings at it forever. {@link BotTargetRules} excludes it from targeting;
 *       {@link #roomCleared} reads it as the positive signal.</li>
 * </ul>
 *
 * <p>The climb itself is a two-part contract, which is why neither portal alone can move a bot:
 * {@code out00/dojang_up} sends {@code DOJO_WARP_UP} (a CLIENT-side warp animation) and opens the
 * door reactor; {@code out001/dojang_next} is the server-side door on the top platform and warps
 * the body. The room has no rope, no ladder and 372px of wall between its floor and that
 * platform - far more than the ~77px a jump clears - so the bot opens the door the way the script
 * does and places itself on the platform, which is exactly what the client half would have done.
 */
public class DojoPQBot extends PartyQuestBot {

    /** {@code GCMovement.enterPortal} refuses a body further than this from the door. */
    private static final int DOOR_ARRIVAL_PX = 8;

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
        MapleMap map = bot.getMap();
        // The room's work is whatever is left to kill. The clear marker is not a monster to fight
        // (BotTargetRules), so it must not be what "still fighting" means here.
        if (hasLivingTargets(map)) {
            PqActions.seekAndAttack(bot);
            return false;
        }
        // Below the party, a bot waits instead of climbing: the boss it would raise is the party's
        // fight, and climbing alone is what leaves the tower running without them.
        if (partyIsBelow(bot)) {
            return false;
        }
        if (!roomCleared(map, bot.getMapId())) {
            raiseBoss(bot, bot.getMapId());
            return false;
        }
        climb(bot, map);
        return false;
    }

    /** Whether the room still holds anything worth swinging at (the marker is not). */
    private static boolean hasLivingTargets(MapleMap map) {
        for (Monster m : map.getAllMonsters()) {
            if (m.isAlive() && BotTargetRules.isCombatTarget(m)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The script's own clear test: a rest floor is clear by design, and a fighting floor is clear
     * once its boss has died - which the host records by spawning the revive marker
     * ({@code dojang_up.js}: {@code getMonsterById(9300216) != null}).
     */
    private static boolean roomCleared(MapleMap map, int mapId) {
        return DojoPqData.isRestingFloor(mapId)
                || map.getMonsterById(DojoPqData.CLEAR_MARKER) != null;
    }

    /**
     * Raise this room's boss the way {@code dojang_1st} would have.
     *
     * <p>Two gates, both about THIS room:
     *
     * <ol>
     *   <li>nothing to raise on a resting floor - {@code dojang_1st} dismisses the timer there
     *       instead of spawning;</li>
     *   <li>the marker means the boss already died here; re-raising it would restart the fight
     *       after the room was cleared.</li>
     * </ol>
     *
     * <p>{@code setDojoProgress} is still called - it advances the tower's clock and stage record
     * ({@code dojang_1st} and {@code dojang_next} both depend on it) - but its return value is NOT
     * a spawn gate here. It is monotonic per tower SLOT, and a party's five rooms all share slot
     * {@code mapId % 100}, so it answers true only for the first room the party reaches. Gating on
     * it would leave every room above the first with no boss at all - the very stall this fix is
     * for. The script can use it because its caller is already scoped to "a body just entered this
     * one room, which was empty until now"; the bot's own scoping is the empty-room check above.
     */
    private void raiseBoss(Character bot, int mapId) {
        int bossId = DojoPqData.bossIdFor(mapId);
        if (bossId < 0) {
            return; // outside the tower, or a resting floor that has no boss
        }
        MapleMap map = bot.getMap();
        if (map.getMonsterById(DojoPqData.CLEAR_MARKER) != null) {
            return; // already cleared here - the marker is the record of that kill
        }
        var channel = map.getChannelServer();
        if (channel == null) {
            return; // an instance map with no channel: nothing to raise the room against
        }
        channel.setDojoProgress(mapId); // bookkeeping/clock, not a gate - see the note above
        Monster boss = LifeFactory.getMonster(bossId);
        if (boss == null) {
            return; // WZ without this row: nothing to spawn, the door's count check still passes
        }
        boss.setBoss(false); // dojang_1st does this too: the room boss carries no boss HP bar
        map.spawnDojoMonster(boss);
    }

    /**
     * Take the server-side door once the room is clear.
     *
     * <p>Two different rooms, two different paths, both taken from what the scripts actually do:
     *
     * <ul>
     *   <li>A <b>fighting floor</b> carries the {@code door} reactor and {@code out001}. Its script
     *       refuses while the reactor is shut ("The door is not open yet"), and only
     *       {@code dojang_up} opens it - so the bot calls {@code map.setReactorState()}, the
     *       script's own action. The part a bot cannot produce is the {@code DOJO_WARP_UP} client
     *       animation that carries a player to this platform, so the body is placed there; the
     *       observed result is then identical (same warp, same pixel).</li>
     *   <li>A <b>resting floor</b> has no {@code door} reactor and no {@code out00} at all
     *       ({@code 9250206xx}, {@code 9250212xx}, ... {@code 9250236xx}), so {@code dojang_next}
     *       returns false on {@code gate == null} before it reaches its warp. Its only advance is
     *       NPC {@code 2091005}'s "continue" menu ({@code isRestingSpot} branch:
     *       {@code warpParty(...)} to one index up), and that menu refuses everyone but the
     *       leader. A bot cannot drive it - and must not: the party continues together, on the
     *       leader's click. So a rest floor is a WAIT, never a self-warp.</li>
     * </ul>
     */
    private void climb(Character bot, MapleMap map) {
        if (DojoPqData.isRestingFloor(map.getId())) {
            return; // the leader's NPC turn advances the party; there is no door to take
        }
        Portal door = PartyQuestBot.scriptedPortalOf(map.getPortals(),
                DojoPqData.SERVER_DOOR, DojoPqData.SERVER_DOOR_SCRIPT);
        if (door == null) {
            return; // a room whose door was relinked - nowhere safe to go, and the run waits
        }
        map.setReactorState(); // dojang_up's own door-opening call, and a no-op once open
        Point at = door.getPosition();
        Point pos = bot.getPosition();
        if (pos == null) {
            return;
        }
        if (Math.abs(pos.x - at.x) > DOOR_ARRIVAL_PX || Math.abs(pos.y - at.y) > DOOR_ARRIVAL_PX) {
            // Aim at the door's own pixel: the ground probe drops it onto the platform the door
            // stands on, which is also the pixel enterPortal requires (DojoDoorReachabilityTest).
            GCMovement.teleportTo(bot, at.x, at.y);
        }
        PqActions.enterPortal(bot, door);
    }

    /** Whether this bot has climbed past the party - the leader is in a lower room. */
    private boolean partyIsBelow(Character bot) {
        Character leader = partyLeader();
        return leader != null && leader != bot && leader.getMapId() < bot.getMapId();
    }
}
