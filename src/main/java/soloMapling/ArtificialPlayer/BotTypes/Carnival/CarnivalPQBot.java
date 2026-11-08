package soloMapling.ArtificialPlayer.BotTypes.Carnival;

import org.gms.client.Character;
import org.gms.net.server.world.PartyCharacter;
import org.gms.server.maps.Foothold;
import org.gms.server.maps.MapleMap;
import org.gms.util.Pair;
import soloMapling.ArtificialPlayer.BotGeneration;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.ArtificialPlayer.PartyQuest.PartyQuestBot;
import soloMapling.ArtificialPlayer.PartyQuest.PqActions;

import java.awt.Point;
import java.util.List;

/**
 * Plays Monster Carnival - either as the opposing team, or as a teammate a player recruited in the
 * hall.
 *
 * <p>CPQ is two parties fighting over the same arena and the same clock. When the arena fields a
 * team for a waiting player party, a bot on the opposing side is not helping anyone; it is standing
 * in for the team the player could not find. What it should do then is not "help" but "play": kill
 * what it can reach, spend its Carnival Points on monsters when it has them, and cast the
 * opponent's debuffs when they are worth the points.
 *
 * <p>The same brain also plays a bot the player invited in the hall, because CPQ's own rules make
 * that the other way a team gets filled: the party cap is six, a match is six against six, and the
 * player may bring fewer friends than that. Such a bot is on the player's side - the engine assigns
 * the side in the match, and everything below reads it from there - so the hunt filter and the
 * summons follow the team it actually got, not the one it was recruited for.
 *
 * <p>The engine does the work the bot would otherwise have to fake: CP is awarded on kills by
 * {@code Character.gainCP}, the arena adds the summoned monster itself once the request is
 * accepted, and the win is decided by the counters. The bot's whole part is to choose what to
 * spend CP on and keep fighting.
 *
 * <p><b>Which monsters it may hit.</b> The arena holds both sides' summons in one map object
 * list, and the engine's kill path awards CP for <em>any</em> monster that carries CP, with no
 * team check. Hunting the nearest mob would therefore farm the bot's own side's summons. The
 * bot installs a hunt filter ({@link PqActions#setHuntFilter}) that admits only the opposing
 * team's monsters, and both the chase and the attack driver honour it.
 *
 * <p><b>Why the summon choice is a real decision.</b> The arena lists ten monsters whose costs
 * run from 7 to 30 CP (WZ {@code monsterCarnival/mob}/{@code spendCP}); a bot that cycles the
 * list blindly spends a third of its budget on the 30-CP entry and ends the run with far fewer
 * bodies on the field than a player who bought the cheap ones. The pick is therefore the dearest
 * monster this side can afford, varied from purchase to purchase.
 */
public class CarnivalPQBot extends PartyQuestBot {

    /**
     * How long after arriving in a reward room the bot waits before following the player out.
     *
     * <p>The reward rooms are ordinary maps with a carnival NPC the player talks to; the bot has no
     * business running that conversation, so what it reproduces is the wait. Long enough to read
     * the result and watch the win/lose effect, short enough not to strand the hall empty.
     */
    private static final long REWARD_ROOM_LINGER_MS = 4_000L;

    /** How long the walk to the reward room's NPC may take before the bot reads in place. */
    private static final long REWARD_ROOM_WALK_TIMEOUT_MS = 8_000L;

    /**
     * How far from its feet the bot collects the props the arena's monsters drop.
     *
     * <p>Same neighbourhood as LPQ's {@code LOOT_RADIUS_PX}: a kill lands its drop at the mob's
     * feet, so a walk-up-and-take radius keeps a bot on its own share instead of racing the rest
     * of the team across the arena. Anything farther is left to the combat beat as the bot walks
     * on into the fight.
     */
    private static final int ARENA_LOOT_RADIUS_PX = 260;

    /** When this bot's reward-room walk started (0 = not walking); the walk's own deadline. */
    private long rewardWalkSinceMs;

    /** When this bot began reading the result (0 = not reading yet); the linger's start. */
    private long rewardRoomSinceMs;

    /** The arena this bot last ran its in-arena setup for; -1 outside an arena. */
    private int lastArenaMapId = -1;

    /**
     * Minimum gap between two follow warps. The field's own three-minute exit timer can bounce a
     * party back to the hall, and chasing that on the very next tick would make the bot flip
     * between the two maps instead of settling in either.
     */
    private static final long WARP_FOLLOW_GAP_MS = 5_000L;

    /** Earliest time this bot may follow its leader by warp again. */
    private long nextWarpFollowAtMs;

    public CarnivalPQBot(Character character) {
        super(character);
        dialoguePath = "CarnivalPQBotDialogue.yaml";
        botType = "CarnivalPQBot";
        questName = "CarnivalPQ";
        // The arena resolves its player through client.getPlayer() when awarding CP and when
        // adding a summoned monster, so this bot needs a client of its own.
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

    /**
     * A carnival map the bot may stand in: an arena, or a post-match reward room.
     *
     * <p>The <b>hall</b> and the <b>field lobby</b> are deliberately <em>not</em> "inside the
     * quest". Being inside means the stage work runs; both are places where the bot waits to be
     * drafted, and the base class's lobby behaviour (stroll, recruit shout, accept an invitation)
     * is exactly what should happen there. Counting them as inside would also break the run-edge
     * bookkeeping: the bot would never see the transition into a match as "entering", so the
     * start-of-run beats would not fire.
     *
     * <p>The reward rooms are kept inside because the engine warps the players there when the clock
     * expires; {@link #workStage()} reads the result for a moment and then walks home.
     */
    @Override
    protected boolean isInsideQuest(int mapId) {
        MapleMap map = getChr().getMap();
        return CarnivalPqData.isArena(map) || CarnivalPqData.isRewardMap(map);
    }

    /**
     * The arena is a disposable map with no {@code EventInstance}, so the base class's in-run
     * cadence gate would keep this bot at the 36-48s background tick - long enough to summon
     * twice in a ten-minute match. Carnival matches therefore opt into the fast cadence
     * explicitly.
     */
    @Override
    protected boolean runsOnOwnClock() {
        return true;
    }

    /**
     * Carnival bots accept a party invitation - in the hall and in the field lobby.
     *
     * <p>This is the "large recruit hall" half of the feature: a player who walks into the hall
     * with fewer than six finds bots that will join, exactly like every other quest lobby. The
     * arena's own draft only takes bots that are <em>not</em> in a party, so the two paths cannot
     * fight over the same body: a bot a player recruited is busy with that player, and the arena
     * fields its opponent team from the bots still free.
     */
    @Override
    protected boolean acceptsPartyInvites(Character bot) {
        return true;
    }

    /**
     * Follow a player's party into its field and its match.
     *
     * <p>The carnival is entered by a warp, not by a portal: nothing on the hall's map leads to a
     * field, and the field is entered by a script. A recruited bot standing in the hall therefore
     * has no walk of its own to take, and would otherwise wait there while its party fights - the
     * exact reason the previous cut forbade recruiting them at all.
     *
     * <p>Warping is only used when the party is somewhere this quest actually happens (the hall,
     * a field lobby, an arena, or a reward room). A leader who wanders off into town is not
     * followed: that is a player leaving the carnival, not a run moving on.
     *
     * <p>One warp per leader move, rate-limited: the field's own three-minute exit timer can pull
     * a party back to the hall, and without a floor between warps the bot would chase the leader
     * out and back on alternating ticks.
     */
    @Override
    protected boolean followLeaderByWarp() {
        Character leader = partyLeader();
        Character bot = getChr();
        if (leader == null || leader == bot || bot.getParty() == null) {
            return false;
        }
        int target = leader.getMapId();
        if (bot.getMapId() == target) {
            return false;
        }
        if (!CarnivalPqData.isCarnivalMap(target)) {
            return false; // the leader has left the carnival - this bot stays with its lobby
        }
        long now = System.currentTimeMillis();
        if (now < nextWarpFollowAtMs) {
            return false; // just followed: let the leader settle before chasing again
        }
        var map = soloMapling.ArtificialPlayer.BotCommandsPack.WarpCommands.mapForBot(bot, target);
        if (map == null) {
            return false;
        }
        bot.changeMap(map, map.getPortal(0));
        nextWarpFollowAtMs = now + WARP_FOLLOW_GAP_MS;
        return bot.getMapId() == target;
    }

    /**
     * The lobby this bot belongs to.
     *
     * <p>In order of specificity:
     * <ul>
     *   <li>a post-match reward room is <em>not</em> home - the bot leaves it for the hall;</li>
     *   <li>a <b>field lobby</b> ({@code 980000100+100*field} / {@code 980031000+1000*field}) is
     *       home while this bot still belongs to a party there. That covers both ways a bot
     *       reaches a field: the arena's own team (waiting for its match) and a player's teammate
     *       (following the player in). A bot left there with <em>no</em> party is not home - it is
     *       an orphan, and the reclaim path should carry it back to a hall rather than leave it
     *       standing in a field nobody is fighting in;</li>
     *   <li>otherwise the hall its own edition/level fits.</li>
     * </ul>
     */
    @Override
    protected int lobbyMapId() {
        int mapId = getChr().getMapId();
        if (CarnivalPqData.isRewardMap(getChr().getMap())) {
            return CarnivalPqData.isSecondEdition(mapId)
                    ? CarnivalPqData.LOBBY_MAP_CPQ2 : CarnivalPqData.LOBBY_MAP;
        }
        if (CarnivalPqData.fieldOfLobby(mapId) >= 0 && getChr().getParty() != null) {
            return mapId; // home while this bot's own party is waiting here
        }
        if (CarnivalPqData.isSecondEdition(mapId)) {
            return CarnivalPqData.LOBBY_MAP_CPQ2;
        }
        if (mapId >= 980000000 && mapId <= 980000999) {
            return CarnivalPqData.LOBBY_MAP;
        }
        return CarnivalPqData.lobbyForLevel(getChr().getLevel());
    }

    @Override
    protected boolean workStage() {
        MapleMap map = getChr().getMap();

        // The engine moves both teams to a reward room when the clock expires; that is the end
        // of this bot's participation, so read the result for a beat and then leave.
        if (CarnivalPqData.isRewardMap(map)) {
            workRewardRoom();
            return false;
        }

        if (!CarnivalPqData.isArena(map)) {
            // The lobby is "outside the quest" by isInsideQuest, so the base class's lobby tick
            // owns it; reaching here means the run left the bot on some other map.
            lastArenaMapId = -1;
            return false;
        }
        if (lastArenaMapId != map.getId()) {
            lastArenaMapId = map.getId();
            // A fresh arena: the purchase rotation restarts with it, so a re-match does not begin
            // halfway through the price list.
            CarnivalSummons.resetChoice(getChr().getId());
            // Only the opposing side's summons may be hit - the arena holds both teams' monsters
            // and the engine's kill path awards CP for any of them. Installed once per arena
            // rather than per beat: the side is fixed for the run.
            PqActions.setHuntFilter(getChr(), enemyMonsterOf(getChr()));
        }

        // Spend before fighting: CP is earned by kills, so the earliest purchase is the one that
        // pays for itself the longest.
        trySummon();
        tryDebuff();

        // Then fight, which is what the match is scored on.
        if (!map.getMonsters().isEmpty()) {
            PqActions.seekAndAttack(getChr(), false, CarnivalPqData.ARENA_CONSUMABLES);
        }
        // The arena's monsters drop consume-on-pickup props (Carnival Points, party cures, the
        // debuff cubes, potions) on every kill. They are this quest's equivalent of LPQ's passes:
        // the bot must walk over and take them instead of leaving them for the match clock to
        // expire - including the pile the LAST kill left behind, which is why the sweep is outside
        // the "room still has monsters" branch.
        PqActions.loot(getChr(), getChr().getPosition(), ARENA_LOOT_RADIUS_PX,
                CarnivalPqData.ARENA_CONSUMABLES);
        return false;
    }

    /**
     * The props the arena's monsters drop, scooped by the shared 250ms combat beat as the bot
     * walks over them.
     *
     * <p>Restricted to the arena: a reward room is an ordinary map with no match loot, and its
     * floor belongs to the party's humans (the same rule LPQ's bonus rooms follow). Returning the
     * ids only inside a live arena also keeps a bot that has just been carried out of the match
     * from hoovering a town map.
     */
    @Override
    protected int[] steppedOnLootIds() {
        return CarnivalPqData.isArena(getChr().getMap()) ? CarnivalPqData.ARENA_CONSUMABLES : new int[0];
    }

    /**
     * Read the result, then follow the players out - the same shape as every other quest's last
     * room: stand by the exit NPC for a beat, then leave once the party does.
     *
     * <p>The reward room's NPC is the normal way out, but talking to NPCs is not something this
     * bot does: {@code 2042002.js} hands out {@code gainExp} on the way through, which is a
     * player's reward. What the bot reproduces is the observable part - the walk to the NPC, the
     * pause a person spends reading the result, and then the hop back to the hall. On the two
     * CPQ2 fields whose rooms ship no NPC it simply waits out the pause where it landed.
     *
     * <p>The walk has its own deadline, separate from the reading pause. {@code walkTo} answers
     * false for a target it cannot path to (a missing foothold, a stripped WZ); sharing one
     * timestamp with the linger would either cut the pause short once the walk used it up, or let
     * a failed walk restart the pause forever. Two clocks, one for each beat.
     *
     * <p>The goal is the floor under the NPC, never the NPC's own sprite anchor, and a room whose
     * NPC stands on a surface this body cannot reach does not get a walk at all - see
     * {@link #rewardPost}.
     */
    private void workRewardRoom() {
        MapleMap map = getChr().getMap();
        long now = System.currentTimeMillis();
        int npcId = CarnivalPqData.rewardNpcId(getChr().getMapId());
        var npc = (map == null || npcId <= 0) ? null : map.getNPCById(npcId);
        Point post = rewardPost(getChr(), (npc == null) ? null : npc.getPosition());
        if (post != null && !PqActions.withinPickupReach(getChr(), post)) {
            if (rewardWalkSinceMs == 0L) {
                rewardWalkSinceMs = now;
            }
            if (now - rewardWalkSinceMs < REWARD_ROOM_WALK_TIMEOUT_MS) {
                PqActions.walkTo(getChr(), post);
                return;
            }
            // Unreachable (or wedged): give up on the NPC and read the result where it stands.
        }
        rewardWalkSinceMs = 0L;

        if (rewardRoomSinceMs == 0L) {
            rewardRoomSinceMs = now;
            return;
        }
        if (now - rewardRoomSinceMs < REWARD_ROOM_LINGER_MS) {
            return;
        }
        if (waitingForPlayerInRewardRoom()) {
            return; // the party is still reading its result; leave when they do
        }
        rewardRoomSinceMs = 0L;
        returnToLobby("carnival match finished");
    }

    /**
     * Where this bot stands to read the result: the first floor under the reward NPC this body can
     * WALK to, or null when there is none.
     *
     * <p>The NPC's authored position is a sprite anchor, which these rooms routinely place off the
     * walkable surface: on 98000013 it sits 8px above its ledge, on 98000033 13px above the floor,
     * and the CPQ1 field-4/5 and CPQ2 winner rooms put it on an island 142-192px above a floor
     * with no rope, no upward step and no walkable link. Aiming a walk at that pixel asked the
     * movement layer for a point no body can stand on, and every way it could answer was wrong:
     * the analytic (unobserved) path snapped the sprite straight onto the pixel, the dead-pit
     * guard hauled the bot onto the only livable surface above it (that island, i.e. beside the
     * NPC), and a mage blinked up. The human standing next to the bot could do none of those:
     * {@code NPCTalkHandler} never checks range, so for a player the NPC is a conversation across
     * the gap from the floor below.
     *
     * <p>So: take the highest floor on the NPC's own column that this body can WALK to - the stand
     * a player takes when the NPC is a conversation across a gap - and answer null when no floor on
     * that column is walkable. Deliberately on foot rather than {@code canPathTo}: a mage's blink
     * makes almost any island "reachable", and blinking up to read a result is exactly the
     * behaviour being removed. A null result is not a failure: the caller reads the result in
     * place, exactly like a room that ships no NPC.
     */
    private static Point rewardPost(Character bot, Point npc) {
        MapleMap map = bot == null ? null : bot.getMap();
        if (map == null || map.getFootholds() == null || npc == null) {
            return null;
        }
        // Floors that cross the NPC's column AND sit at or below the NPC's own row (y grows
        // downward). Highest first, so the nearest stand under the NPC wins: the floor that is
        // below the NPC is where a player stands when the NPC is a conversation across the gap,
        // and floors ABOVE the NPC are unrelated shelves on another storey.
        List<Foothold> column = new java.util.ArrayList<>();
        for (Foothold foothold : map.getFootholds().getAllFootholds()) {
            if (foothold.isWall()) {
                continue;
            }
            int lo = Math.min(foothold.getX1(), foothold.getX2());
            int hi = Math.max(foothold.getX1(), foothold.getX2());
            if (npc.x >= lo && npc.x <= hi && surfaceYAt(foothold, npc.x) >= npc.y) {
                column.add(foothold);
            }
        }
        column.sort(java.util.Comparator.comparingInt(
                (Foothold foothold) -> surfaceYAt(foothold, npc.x)));
        for (Foothold foothold : column) {
            Point stand = new Point(npc.x, surfaceYAt(foothold, npc.x));
            if (GCMovement.canReachOnFoot(bot, stand.x, stand.y)) {
                return stand;
            }
        }
        return null;
    }

    /** A foothold's real surface height at {@code x}, linear between its endpoints (as WZ authors it). */
    private static int surfaceYAt(Foothold foothold, int x) {
        int y1 = foothold.getY1();
        int y2 = foothold.getY2();
        int x1 = foothold.getX1();
        int x2 = foothold.getX2();
        if (y1 == y2 || x1 == x2) {
            return Math.min(y1, y2);
        }
        long dy = (long) (y2 - y1) * (x - x1);
        return y1 + (int) (dy / (x2 - x1));
    }

    /**
     * Whether a human on this bot's OWN side is still standing in the reward room.
     *
     * <p>Only a bot that played on a player's side should wait here. The opponent team is all bots
     * and has nobody to wait for: making it wait as well would park six bots in a reward room for
     * as long as any human happened to stand there - including the players it just beat.
     */
    private boolean waitingForPlayerInRewardRoom() {
        MapleMap map = getChr().getMap();
        if (map == null || getChr().getParty() == null) {
            return false;
        }
        for (PartyCharacter mpc : getChr().getParty().getPartyMembers()) {
            Character mc = mpc.getPlayer();
            if (mc != null && mc.getMap() == map
                    && !soloMapling.ArtificialPlayer.BotHelpers.isBot(mc)) {
                return true; // a player on my own side is still reading the result
            }
        }
        return false;
    }

    @Override
    protected void releaseRoomState() {
        super.releaseRoomState();
        rewardRoomSinceMs = 0L;
        rewardWalkSinceMs = 0L;
        lastArenaMapId = -1;
        nextWarpFollowAtMs = 0L;
        CarnivalSummons.release(getChr().getId());
        // The filter is only meaningful inside an arena; leaving it set would keep a bot that has
        // gone back to town from hitting plain map mobs.
        PqActions.setHuntFilter(getChr(), null);
        // A bot that is being stopped or re-typed mid-match must stop looking like a participant:
        // its side would otherwise keep a member that no longer ticks, and the final dispose()
        // would warp a character that was already removed from the server.
        detachFromCarnival();
    }

    /**
     * Drop this bot's own match state without ending the match for everybody else.
     *
     * <p>Deliberately NOT {@code MonsterCarnival.exit()}: that is {@code dispose()}, which settles
     * the result and warps both sides out. Retiring one bot must not do that - the other five and
     * the opposing party are still playing. Clearing the character's pointer is enough: the arena
     * skips a member whose {@code getPlayer()} is null, and CP awards stop routing here because
     * {@code gainCP} is gated on the pointer being set.
     */
    private void detachFromCarnival() {
        if (getChr().getMonsterCarnival() != null) {
            getChr().setMonsterCarnival(null);
            getChr().setTeam(-1);
        }
    }

    /**
     * Ask the arena for the dearest monster this side can afford.
     *
     * <p>Reads the arena's own list of summonable monsters and their costs - the same list the
     * client's request indexes into - rather than carrying ids, and checks the same two
     * conditions the server does.
     */
    private void trySummon() {
        List<Pair<Integer, Integer>> options = CarnivalPqData.summonableOn(getChr().getMap());
        if (options.isEmpty() || !sideHasSummonsLeft()) {
            return;
        }
        CarnivalSummons.requestBest(getChr(), options);
    }

    /**
     * Cast a debuff at the opposing party when one is affordable.
     *
     * <p>Debuffs are the half of CPQ a bot is most likely to skip, and they are the half that
     * makes a bot team feel like opponents rather than a monster dispenser: a slow or a darkness
     * on the other side costs the caster points but costs the target far more time.
     */
    private void tryDebuff() {
        CarnivalSummons.castDebuff(getChr(), CarnivalPqData.debuffsOn(getChr().getMap()));
    }

    /**
     * The side rule, exposed as a pure function so it can be pinned without a live character.
     *
     * <p>A monster with no side ({@code -1}) is always huntable, and otherwise only a different
     * side is.
     */
    static boolean sideRule(int mine, int theirs) {
        return theirs < 0 || theirs != mine;
    }

    /**
     * The predicate that restricts this bot to the opposing side's monsters.
     *
     * <p>The monster's {@code team} is the side that called it, so the bot may only hit the
     * <em>other</em> team's. The side is read once, at install time: a match assigns it in the
     * arena's constructor and it cannot change while the run lasts.
     */
    static java.util.function.Predicate<org.gms.server.life.Monster> enemyMonsterOf(
            Character bot) {
        int mine = bot == null ? -1 : bot.getTeam();
        return monster -> monster != null && sideRule(mine, monster.getTeam());
    }

    /**
     * Whether this side still has summons left.
     *
     * <p>The allowance is the arena's to track and it decrements as monsters are called, so the
     * bot does not keep its own count - it reads the run's.
     */
    private boolean sideHasSummonsLeft() {
        var carnival = getChr().getMonsterCarnival();
        if (carnival == null) {
            return false;
        }
        return getChr().getTeam() == 0 ? carnival.canSummonR() : carnival.canSummonB();
    }
}
