package soloMapling.ArtificialPlayer.BotTypes.Carnival;

import org.gms.client.Character;
import org.gms.server.maps.MapleMap;
import org.gms.util.Pair;
import soloMapling.ArtificialPlayer.BotGeneration;
import soloMapling.ArtificialPlayer.PartyQuest.PartyQuestBot;
import soloMapling.ArtificialPlayer.PartyQuest.PqActions;

import java.util.List;

/**
 * Plays Monster Carnival against a real party - the only bot here that is an opponent rather
 * than a teammate.
 *
 * <p>CPQ is two parties fighting over the same arena and the same clock. A bot on the opposing
 * side is not helping anyone; it is standing in for the team the player could not find, which is
 * a different job from every other bot in this package. What it should do is therefore not "help"
 * but "play": kill what it can reach, spend its Carnival Points on monsters when it has them, and
 * cast the opponent's debuffs when they are worth the points.
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
     * How long after arriving in a reward room the bot waits before walking itself home.
     *
     * <p>The reward rooms are ordinary maps with a carnival NPC; a player reads the result
     * message and talks to it. The bot has no reason to linger, but leaving on the arrival tick
     * would skip the win/lose effect entirely, which is the one moment the room exists for.
     */
    private static final long REWARD_ROOM_LINGER_MS = 4_000L;

    /** When this bot entered the reward room it is standing in now; 0 outside one. */
    private long rewardRoomSinceMs;

    /** The arena this bot last ran its in-arena setup for; -1 outside an arena. */
    private int lastArenaMapId = -1;

    public CarnivalPQBot(Character character) {
        super(character);
        dialoguePath = "CarnivalPQBotDialogue.yaml";
        botType = "CarnivalPQBot";
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
     * <p>The <b>lobby</b> is deliberately <em>not</em> "inside the quest". Being inside means the
     * stage work runs; the lobby is where the bot waits to be drafted, and the base class's lobby
     * behaviour (stroll, recruit shout, accept an invitation) is exactly what should happen there.
     * Counting it as inside would also break the run-edge bookkeeping: the bot would never see the
     * transition into a match as "entering", so the start-of-run beats would not fire.
     *
     * <p>The reward rooms are kept inside because the engine warps both teams there when the clock
     * expires; {@link #workStage()} reads the result for a moment and then walks the bot home.
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
     * Carnival bots never accept a party invite.
     *
     * <p>Every other quest bot is recruited by a player, so accepting is the whole point. These
     * are the opposing team: the arena needs six of them, and any bot pulled into a player's party
     * is skipped by the draft (it looks committed) - a player who invited four of them would
     * silently make the field unstaffable. Declining keeps the opponent roster intact.
     */
    @Override
    protected boolean acceptsPartyInvites(Character bot) {
        return false;
    }

    /**
     * The lobby this bot belongs to.
     *
     * <p>Three cases, in order of specificity:
     * <ul>
     *   <li>a <b>field lobby</b> ({@code 980000100+100*field} / {@code 980031000+1000*field}) -
     *       this is where a team waits for its match, and it must be recognised as home, or the
     *       base class's "outside the quest and outside the lobby" reclaim would warp the bot out
     *       of the very field it was placed in;</li>
     *   <li>a post-match reward room is <em>not</em> home - the bot leaves it for the hall;</li>
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
        if (CarnivalPqData.fieldOfLobby(mapId) >= 0) {
            return mapId; // a field lobby is home while the team is waiting there
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
            PqActions.seekAndAttack(getChr());
        }
        return false;
    }

    /**
     * Read the result, then walk home.
     *
     * <p>The reward room's NPC is the normal way out, but talking to NPCs is not something this
     * bot does - it warps out instead, the same way it leaves every other finished run.
     */
    private void workRewardRoom() {
        long now = System.currentTimeMillis();
        if (rewardRoomSinceMs == 0L) {
            rewardRoomSinceMs = now;
            return;
        }
        if (now - rewardRoomSinceMs < REWARD_ROOM_LINGER_MS) {
            return;
        }
        rewardRoomSinceMs = 0L;
        returnToLobby("carnival match finished");
    }

    @Override
    protected void releaseRoomState() {
        super.releaseRoomState();
        rewardRoomSinceMs = 0L;
        lastArenaMapId = -1;
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
