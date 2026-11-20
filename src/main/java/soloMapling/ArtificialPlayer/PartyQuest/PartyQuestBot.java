package soloMapling.ArtificialPlayer.PartyQuest;

import org.gms.client.Character;
import org.gms.scripting.event.EventInstanceManager;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Portal;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAuraState;
import soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands;
import soloMapling.ArtificialPlayer.BotCommandsPack.WarpCommands;
import soloMapling.ArtificialPlayer.BotDialogueHandler;
import soloMapling.ArtificialPlayer.BotGrindSystem.GrindTickRegistry;
import soloMapling.ArtificialPlayer.BotHealthSystem.BotPotionSim;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotMessagingSystem.ChatMessage;
import soloMapling.ArtificialPlayer.BotPartySystem.BotPartyCommands;
import soloMapling.ArtificialPlayer.BotPartySystem.BotPartyLogic;
import soloMapling.ArtificialPlayer.BotPartySystem.BotPartyQueue;
import soloMapling.ArtificialPlayer.BotPartySystem.BotRecruitManager;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotTypes.OPQ.OPQBot;
import soloMapling.ArtificialPlayer.BotTypes.OPQ.OrbisPqData;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.BotLogger;
import soloMapling.Environment.BotMessages;
import soloMapling.Environment.PlatformPlacement;

import java.awt.Point;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;

import static soloMapling.ArtificialPlayer.BotHelpers.isBot;

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
public abstract class PartyQuestBot extends BotSM implements GrindTickRegistry.Participant {

    /** The event name this bot plays, used to look up its recruit line ("HenesysPQ", ...). */
    protected String questName;

    /**
     * How long between lobby recruit shouts. Matches the Orbis lobby's cadence (12s), which is
     * the only quest lobby that has ever been noisy - the rest were silent until this class
     * learned to spend the pre-run wait talking.
     */
    private static final long RECRUIT_SHOUT_INTERVAL_MS = 12_000L;

    /** Jitter on the shout cadence so a spawned cohort does not shout in lockstep. */
    private static final long RECRUIT_SHOUT_JITTER_MS = 4_000L;

    /** When this bot next shouts; re-armed with fresh jitter after each shout. */
    private long nextRecruitShoutAtMs;

    /** When this bot next strolls; re-armed after each stroll. */
    private long nextLobbyStrollAtMs;

    /** Quiet-until between two in-quest lines, so one beat never lands right after another. */
    private static final long QUEST_CHAT_GAP_MS = 20_000L;

    /** Jitter on the gap so a cohort in one room does not talk over each other in lockstep. */
    private static final long QUEST_CHAT_GAP_JITTER_MS = 10_000L;

    /** When this bot may say its next line inside the quest; 0 means the first beat is free. */
    private long nextQuestChatAtMs;

    /** Whether the previous tick saw this bot inside the quest - the run-start edge. */
    private boolean wasInsideQuest;

    /** The stage or room whose clear already celebrated this run, so one milestone speaks once. */
    private int celebratedClearKey = Integer.MIN_VALUE;

    /**
     * Lobby pacing: how often the idle shuffle picks a new spot.
     *
     * <p>Deliberately slower than the shout cadence, and slower than it looks like it should be.
     * The stroll is the visible half of the wait - a bot that re-spots every few seconds reads as
     * pacing, and a lobby holds several parties' worth of bots at once (see {@code PqBotSpawner}),
     * so every bot's own cadence is what the room's crowd multiplies into: fifteen bots each
     * re-spotting every ~13s put a move somewhere in the room every second, which is the "they
     * never stand still" report. A player assembling a party watches this spot for a minute or
     * two; a shuffle roughly every half minute, jittered per bot, is the rate that reads as
     * waiting.
     */
    private static final long LOBBY_STROLL_INTERVAL_MS = 25_000L;

    /** Jitter on the stroll cadence so a spawned cohort does not re-spot in lockstep. */
    private static final long LOBBY_STROLL_JITTER_MS = 10_000L;

    /**
     * Simulated potion drinking while the stage fight is going badly. Quest rooms are real
     * hostile maps: the moment the seek-and-attack chase works, its bots stand in the mob
     * pack and take real touch damage, and without this they die to the first pack that
     * notices them (the reported LPQ stage-1 wipe). Same model the grinders run.
     */
    private final BotPotionSim potionSim = new BotPotionSim();

    /** The town's stationary entry NPC is resolved once, not scanned for on every lobby tick. */
    private PqRecruitArea townRecruitArea;

    protected PartyQuestBot(Character character) {
        super(character);
    }

    /** How many rooms one catch-up tick may chain through (each hop is a bounded walk+portal). */
    private static final int MAX_CATCHUP_HOPS = 3;

    /** Read the room's instance: the character's membership can be transient during a warp. */
    @Override
    protected long lowPriorityDelayMs() {
        MapleMap map = getChr().getMap();
        if (map != null && isInsideQuest(map.getId()) && (map.getEventInstance() != null || runsOnOwnClock())) {
            return PqActions.inRunDelayMs();
        }
        return super.lowPriorityDelayMs();
    }

    /**
     * Whether this run lives in a map that carries no {@code EventInstance}, yet still needs the
     * fast in-run cadence.
     *
     * <p>Public lobbies stay slow; quests such as Monster Carnival or Dojo opt in for their
     * active rooms even though the host does not attach an event instance.
     */
    protected boolean runsOnOwnClock() {
        return false;
    }

    @Override
    public synchronized void startScheduledTask(long initialDelayMs) {
        // Both engine sessions span the FSM's RUNNING lifetime, not the object's - the same rule
        // the sweep registration below follows, and for the same reason: a bot wrapped but never
        // started (the GM createAndSetBot paths) would otherwise hold a live driver thread and the
        // shared movement lock for good, because stopScheduledTask - the only release seam - never
        // runs for it. Enable BEFORE delegating (the same order the constructor used), so the walk
        // helpers in {@link PqActions} have a driver and the map-entry choreography already sees the
        // bot as dynamic-controlled and skips the recorded drop.
        //
        // Why the engine at all: the recorded-path engine replayed a Haste-speed player's packets
        // at 1:1 (quest bots walked ~40% too fast) and only ever had data for a handful of maps
        // (any other quest room left the bot unable to move).
        GCMovement.enable(getChr());
        // The fight rides the shared 250ms combat sweep, the same ticker the grind brains use.
        // {@link #grindTick} gates on "actually inside a quest room and in a party", so a bot in
        // the lobby, between rooms or waiting for a run pays only the cheap gate; the registration
        // never has to chase room changes. Unregistration happens once, at stop, where the other
        // per-bot state is released too.
        GrindTickRegistry.getInstance().register(this);
        super.startScheduledTask(initialDelayMs);
    }

    @Override
    public synchronized void stopScheduledTask() {
        // Release the dynamic engine (and the shared movement lock it holds) when this bot is
        // stopped or converted away, so the next bot type on this character starts clean.
        GrindTickRegistry.getInstance().unregister(this);
        GCMovement.disable(getChr());
        PqActions.clearSeekState(getChr().getId());
        PqActions.clearReactorBeat(getChr().getId());
        releaseRoomState(); // a stopped bot holds no wait spots or box claims
        super.stopScheduledTask();
    }

    // =========================================================================
    // Combat sweep (the 250ms beat, shared with the grind bots)
    // =========================================================================

    /**
     * One beat of the shared combat ticker: fight the room the bot is standing in.
     *
     * <p>Everything else - invites, following the leader, room transitions, ambient chatter, potion
     * sips - stays on the macro tick in {@link #tickPartyQuest}; this beat only ever does the
     * one thing the 2-6s macro cadence made the bot look dazed at: swing, and keep closing on
     * whatever it is fighting, at the cadence combat actually runs at. The gates mirror the
     * macro tick's own: a bot that is not running, is a corpse (isCorpse, not isDead - a bot
     * the host zeroed behind the damage layer's back has no episode yet but must still not
     * swing), frozen, outside a quest room or without a party has no fight to be in.
     *
     * <p>Three coordination gates keep the beat from fighting the macro tick, in priority
     * order:
     * <ul>
     *   <li>the stage-walk shield ({@code PqActions.movementShielded}): while the macro tick
     *       has the bot walking somewhere whose arrival it depends on (following the leader
     *       through a portal, holding a puzzle spot), the beat must not re-issue
     *       {@code GCMovement.move} at ANYTHING - it only swings through
     *       {@code PqActions.attack}, so a mob within reach on the way is fought rather
     *       than walked past. This outranks the armed box below: a critical position walk
     *       is the macro tick's to finish.</li>
     *   <li>an armed reactor beat ({@code PqActions.workReactorOnBeat}): a stage walking its
     *       bot to a box owns the steering until the approach lands - the beat carries the
     *       box work and RETURNS. Mixing it with the mob chase would re-issue a move at a
     *       mob every other beat and oscillate the bot between box and fight (stage 3 has
     *       both). While the approach is still in flight the beat swings at whatever is in
     *       reach (attack only turns, it never steers), so the walk to the box is not also
     *       a combat blackout.</li>
     *   <li>{@link #fightsOnSweep}: a stage whose contract forbids swinging (the frog room's
     *       catch-don't-kill mobs, Ludi's invincible guards, the pyramid's marked monsters)
     *       opts out entirely. Default is to fight.</li>
     * </ul>
     */
    @Override
    public void grindTick() {
        Character bot = getChr();
        if (bot == null || bot.getMap() == null
                || !getRunning()
                || death().isCorpse() || status().isFrozen()
                || !inRunGroup(bot)
                || !isInsideQuest(bot.getMapId())) {
            return;
        }
        // No graph yet: this sweep must not steer on the warmup heuristic, and must not build one
        // on this thread either (the shared 250ms sweep is one thread for every bot). The macro
        // tick does the building (GCMovement.ensureGraph) and will run the fight on its next beat.
        if (!GCMovement.hasGraph(bot)) {
            return;
        }
        combatSweep(bot);
    }

    /** The room's combat work once the shared run and navigation checks have passed. */
    protected void combatSweep(Character bot) {
        // Sweep the drops the bot is standing on, whatever the stage's steering is doing this
        // beat: a kill lands its item at the mob's feet, and waiting for the macro tick to come
        // back for it is the reported "the pass is still lying there" delay. Narrow on purpose -
        // see PqActions.lootSteppedOn.
        int[] lootIds = steppedOnLootIds();
        if (lootIds.length > 0) {
            if (!fightsOnSweep(lootIds)) {
                PqActions.stopHunting(bot);
                return;
            }
            PqActions.lootSteppedOn(bot, lootIds);
        }
        if (!fightsOnSweep(lootIds)) {
            PqActions.stopHunting(bot);
            return; // the leader can meet the quota even when this bot picked up nothing
        }
        if (movementShielded()) {
            PqActions.attack(bot); // the walk owns the steering; the swing still lands
            return;
        }
        if (PqActions.reactorBeatArmed(bot)) {
            if (PqActions.workReactorOnBeat(bot)) {
                PqActions.attack(bot); // still travelling to the box: fight on the way
            }
            return;
        }
        // A pickup order the macro tick issued owns the steering until it lands. This beat runs
        // every 250ms and its chase goal would otherwise replace that order immediately: measured
        // on LPQ stage 1, the macro tick aimed at a pass at (100,-180) and the very next beat
        // retargeted the bot at a mob at (120,-572), so the pass was never collected while the
        // bot climbed away from it.
        //
        // Recognised from the DRIVER's own goal rather than from a flag set when the order was
        // issued: any other producer (walkTo, a park, a follow) replaces that goal and clears the
        // arrival callback, so a flag would go stale and, once stale, silently kill this bot's
        // chase for the whole run. "Is one of my quest items sitting at the goal I am walking to"
        // cannot go stale. Swinging still happens - attack() never steers.
        if (walkingAtALootableDrop(bot, lootIds)) {
            PqActions.attack(bot);
            return;
        }
        PqActions.seekAndAttack(bot, false, lootIds);
    }

    /**
     * Whether the movement driver's current goal is a drop this quest wants (see
     * {@link #steppedOnLootIds()}), and the bot has not reached it yet.
     *
     * <p>Cost: the floor scan walks the map's object table, and this runs on every 250ms beat.
     * That is accepted rather than optimised: only a quest that opts into
     * {@link #steppedOnLootIds()} ever reaches the scan (it early-returns on an empty list), and a
     * quest party is at most a handful of bots - the alternative, caching "is there loot at the
     * goal", is exactly the kind of second copy of state that goes stale and silently kills the
     * chase.
     *
     * <p>The reach test uses the driver's own arrival box: once the bot is standing on the goal
     * the walk is over, and letting it hold the beat at that point would freeze the bot on its
     * own feet.
     */
    private static boolean walkingAtALootableDrop(Character bot, int[] lootIds) {
        return PqActions.walkingToLoot(bot, lootIds);
    }

    /** Shared empty opt-in for {@link #steppedOnLootIds()}; never mutated. */
    private static final int[] EMPTY_LOOT_IDS = new int[0];

    /**
     * The items this quest's combat beat may scoop off the floor as the bot walks over them.
     *
     * <p>Empty by default: a quest opts in with the exact item ids its own stage work produces.
     * Keeping it declarative is what stops the sweep from eating another mechanic's ground item -
     * HPQ's seeds and OPQ's spring seeds are floor items too, and the reactors that consume them
     * match the pile by identity (see {@code PqActions.feedReactor}), so a generic "pick up
     * everything" beat would break those stages while fixing this one.
     */
    protected int[] steppedOnLootIds() {
        return EMPTY_LOOT_IDS;
    }

    /**
     * Whether the shared combat beat may fight in the room the bot is standing in. Stages
     * whose puzzles forbid swinging override this; the common case is to fight.
     */
    protected boolean fightsOnSweep() {
        return true;
    }

    /** Validate the beat's chosen loot for quests whose turn-in can change in the same room. */
    protected boolean fightsOnSweep(int[] lootIds) {
        return fightsOnSweep();
    }

    /**
     * Set while this bot's macro tick is driving a walk whose arrival the stage work depends
     * on (portal following, puzzle spots): the combat beat must not re-target the movement
     * engine mid-walk. The shield lives in {@code PqActions} - armed by every walk/hold
     * helper, expiring on its own after a few seconds so a wedged walk cannot pin the bot
     * forever.
     */
    private boolean movementShielded() {
        Character bot = getChr();
        return bot != null && PqActions.movementShielded(bot.getId());
    }

    /**
     * Whether this bot is a member of the run it is standing in.
     *
     * <p>Almost every quest is entered by a party, so the default asks for one. The exception is
     * Ariant Coliseum, which the engine runs as an <em>expedition</em> and refuses to start while
     * any competitor is in a party - a bot there is a member of the expedition and never has a
     * party, so the party test alone would walk it out of a run it is playing.
     */
    protected boolean inRunGroup(Character bot) {
        return bot.getParty() != null;
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
     * One tick of the shared lifecycle: take an invite if one is waiting, work the lobby while
     * waiting for the run to start, leave if the run is over, otherwise work the stage.
     *
     * <p>Accepting invites happens here, before the "am I inside the quest" test, and that
     * ordering is the whole point: a bot waiting to be recruited is by definition *not* inside
     * the quest yet. Putting this after the check would leave every bot in this package unable
     * to join a party at all - it would sit in the lobby looking ready and ignore the player
     * standing in front of it.
     *
     * <p>The lobby branch is the second half of that thought. A recruited bot spends the whole
     * wait between "invited" and "the leader starts the run" standing in town with nothing to
     * do, and a crowd that never shuffles and never speaks reads as scenery rather than people
     * - it is the state a player looks at while assembling the party. So the lobby tick also
     * shouts a recruit line and strolls to a fresh spot on the floor (see
     * {@link #tickLobby}).
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
            if (acceptsPartyInvites(bot)) {
                BotPartyLogic.checkPartyQueue(bot);
            } else if (BotPartyQueue.getInstance().hasPendingInvite(bot)) {
                // Answer and clear rather than ignore: an unanswered invite would be re-read on
                // every tick, and the inviter would never learn it was refused. Gated on there
                // being one, so a bot that declines by policy pays nothing on an idle tick.
                BotPartyCommands.botRejectPartyInvite(bot);
            }
        }

        if (!isInsideQuest(bot.getMapId())) {
            // Outside the quest the run's edge flags reset, so a bot that leaves and comes
            // back gets its run-start line again - and its stage-clear milestones - on the
            // next run.
            wasInsideQuest = false;
            celebratedClearKey = Integer.MIN_VALUE;
            // A party that moved by a warp rather than by a door is out of this bot's walking
            // reach. Checked before the lobby beat: Monster Carnival's hall -> field chain has no
            // portal between them at all, so a teammate recruited in the hall would otherwise
            // stand there for the whole run.
            if (followLeaderByWarp()) {
                return; // moved; the lobby beat resumes from the new map next tick
            }
            tickLobby(bot);
            return;
        }
        if (!wasInsideQuest) {
            wasInsideQuest = true;
            noteEnteredQuest();
        }
        if (!inRunGroup(bot)) {
            returnToLobby("no longer in a party");
            return;
        }

        // Quest work runs with the real graph, or not at all. The warmup heuristic has no A*, so
        // it cannot take a shelf whose x sits inside the bot's own platform (LPQ stage 1: the
        // -450 mob row's first Ratz is the only one it can ever reach, and the -506/-572 rungs
        // that lead on up the tower are never even attempted). Building costs one map on this
        // tick's own (virtual) thread; a party entering together may build it once per member,
        // which is bounded and never waits on the async warm queue. A build failure throws and the
        // tick wheel logs it - it must never fall through to the heuristic.
        GCMovement.ensureGraph(bot);

        // Once a door has the steering, do not run an old-room park or loot pass over it.
        boolean moved = false;
        for (int hop = 0; hop < MAX_CATCHUP_HOPS; hop++) {
            FollowResult result = followLeaderIntoNextRoom();
            if (result == FollowResult.WALKING) {
                return;
            }
            if (result == FollowResult.NONE) {
                break;
            }
            moved = true;
        }
        if (moved) {
            return; // moved rooms; work resumes from the new one next tick
        }

        // Survive the fight: sip back whatever the room's mobs took off (no-op while full).
        potionSim.tick(bot);

        maybeQuestChat(Beat.WORKING);

        if (workStage()) {
            maybeQuestChat(Beat.CLEAR);
            returnToLobby("stage work reports the run is over");
            return;
        }
    }

    /** The map-entry event only sees the new room; also wake this party's bots still behind. */
    public static void wakeForLeaderEntry(Character leader, MapleMap destination) {
        var party = leader.getParty();
        if (party == null || party.getLeaderId() != leader.getId() || leader.getMap() != destination) {
            return;
        }
        EventInstanceManager instance = destination.getEventInstance();
        int destinationId = destination.getId();
        for (var member : party.getMembers()) {
            BotSM bot = CharacterStorage.getBotById(member.getId());
            if (!(bot instanceof PartyQuestBot || bot instanceof OPQBot) || !bot.getRunning()) {
                continue;
            }
            BotState state = bot.getState();
            if (state == BotState.PAUSE || state == BotState.TRADING || state == BotState.FINISHED
                    || bot.death().isCorpse()) {
                continue;
            }
            Character teammate = bot.getChr();
            var team = teammate.getParty();
            MapleMap from = teammate.getMap();
            if (team == null || team.getId() != party.getId() || from == null || from == destination
                    || from.getEventInstance() != instance) {
                continue;
            }
            if ((bot instanceof PartyQuestBot pq
                    && pq.isInsideQuest(from.getId()) && pq.isInsideQuest(destinationId))
                    || (bot instanceof OPQBot && OrbisPqData.isOrbisRoom(from.getId())
                    && OrbisPqData.isOrbisRoom(destinationId))) {
                bot.requestTickWithin(ThreadLocalRandom.current().nextLong(150, 701));
            }
        }
    }

    /**
     * Drop whatever room-scoped state this bot holds (stage-NPC wait spots, box claims).
     *
     * <p>The placement-puzzle ledgers are released here for every quest, not just the ones that
     * override: a search belongs to one run of one room, and carrying a stale "this pattern was
     * already judged wrong" into the next run would silently skip a candidate that was never
     * tried. Subclasses that keep their own state must call {@code super.releaseRoomState()}.
     */
    protected void releaseRoomState() {
        Character bot = getChr();
        if (bot != null && bot.getMap() != null) {
            PositionCombination.release(System.identityHashCode(bot.getMap()));
        }
    }

    /**
     * What a quest bot does while it is waiting for a run: shout for a party, and shuffle about.
     *
     * <p>Two beats, both timed rather than per-tick so a crowd reads as idle people instead of
     * a metronome: a recruit shout into the map (the line names the quest, drawn from
     * {@link PqRecruitMessages}), and a stroll to another reachable spot on the lobby floor.
     * The stroll is what makes a recruitable bot look alive rather than parked; the shout is
     * what tells a player who has just walked in what this crowd is for.
     *
     * <p>A bot found somewhere else entirely - the quest's exit map, a GM warp, a stray script
     * - is walked home rather than left standing where nobody can recruit it. That case only
     * became reachable once bots started being registered in instances; before that a bot could
     * not be moved by a quest at all.
     */
    protected void tickLobby(Character bot) {
        if (bot.getMapId() != lobbyMapId()) {
            returnToLobby("outside the quest and outside the lobby");
            return;
        }

        if (townRecruitArea == null) {
            townRecruitArea = PqRecruitArea.forMap(bot.getMap());
        }
        long now = System.currentTimeMillis();
        // Both beats are deadline-driven (not "last ran at + interval"): the next due time is
        // rolled once when a beat fires, so the jitter is real jitter instead of a fresh roll
        // on every tick, which would fire the moment the interval alone had elapsed.
        //
        // The stroll's first beat is armed at the FULL interval (plus jitter) rather than at jitter
        // alone: rolling the first due time anywhere in [0, interval) puts a freshly spawned
        // cohort's first moves inside the first second or two of its life, so a lobby being
        // populated scatters before a player has even had time to read the room.
        if (nextRecruitShoutAtMs == 0L) {
            nextRecruitShoutAtMs = now + jitter(RECRUIT_SHOUT_INTERVAL_MS);
        }
        if (nextLobbyStrollAtMs == 0L) {
            nextLobbyStrollAtMs = now + LOBBY_STROLL_INTERVAL_MS + jitter(LOBBY_STROLL_JITTER_MS);
        }
        // A bot that is already in a party stops advertising itself: the wait between "invited"
        // and "the leader starts the run" is short, and shouting into the lobby for a group it is
        // already in reads as noise.
        if (now >= nextRecruitShoutAtMs) {
            nextRecruitShoutAtMs = now + RECRUIT_SHOUT_INTERVAL_MS + jitter(RECRUIT_SHOUT_JITTER_MS);
            if (!inRunGroup(bot) && lobbyHasListener(bot)) {
                sayRecruitLine(bot);
            }
        }
        if (now >= nextLobbyStrollAtMs
                || townRecruitArea != null && !townRecruitArea.contains(bot.getPosition())) {
            nextLobbyStrollAtMs = now + LOBBY_STROLL_INTERVAL_MS + jitter(LOBBY_STROLL_JITTER_MS);
            // The dynamic engine owns the walk; a no-op while one is already in progress, so
            // this can be called every tick without thrashing the target.
            if (townRecruitArea == null) {
                PlatformPlacement.botStrollOnMap(bot);
            } else {
                townRecruitArea.stroll(bot);
            }
        }
    }

    /**
     * Whether this bot's recruit shout is worth a packet: the lobby has to have somebody in it.
     *
     * <p>The shout is a broadcast, and a lobby with only bots in it is the normal case for most
     * of the day, so the gate is the same one every other speak path uses ({@link
     * GCMovement#isMapObserved}). A player walking in promotes the map and the next shout fires,
     * so nothing is lost by staying quiet while nobody is there.
     */
    private boolean lobbyHasListener(Character bot) {
        return GCMovement.isMapObserved(bot.getMap());
    }

    /** Per-bot jitter so a cohort does not shout and shuffle on the same frame. */
    private static long jitter(long spanMs) {
        return ThreadLocalRandom.current().nextLong(spanMs);
    }

    /** One recruit line, named after this bot's quest. */
    private void sayRecruitLine(Character bot) {
        if (questName == null) {
            return; // a subclass that never set the name has no line to shout
        }
        String line = PqRecruitMessages.generateRecruitMessage(
                questName, bot.getLevel(), bot.getJob() == null ? null : bot.getJob().getName());
        SocialCommands.BotSpeak(bot, line);
    }

    // =========================================================================
    // In-quest chatter
    // =========================================================================

    /**
     * The moments inside a run a bot may break its working silence for. A run's rooms are
     * real maps full of real players, and a crowd that fights and loots without a word reads
     * as machinery - the same judgment the lobby's recruit shout already made - but the
     * opposite failure is a bot that narrates every swing. So each beat fires only through
     * two gates: a per-beat roll (the beat's own personality - entering is worth a remark,
     * walking through an opened door only sometimes), and one shared quiet-until (see
     * {@link #maybeQuestChat}) so no two beats ever land back to back.
     */
    enum Beat {
        /** First tick inside the quest - the run is starting. */
        START(0.60),
        /** Mid-stage, while the work is actually going on. */
        WORKING(0.25),
        /** The stage's work reported done (this stage, not necessarily the run). */
        CLEAR(0.70),
        /** The party is moving to the next room through an opened portal. */
        DOOR(0.50);

        /** Odds this beat produces a line at all when it fires. */
        final double chance;

        Beat(double chance) {
            this.chance = chance;
        }
    }

    /**
     * Maybe say one line for a beat of the run, on the bot's current map.
     *
     * <p>Three gates before any packet: the shared quiet-until (armed at
     * {@code 20s + jitter(10s)} after every line, whichever beat it came from), the beat's
     * own roll, and {@link GCMovement#isMapObserved} - a broadcast nobody hears is a packet
     * paid for nothing, the same economy the lobby shout and the speak emote already use.
     * The gate order matters: the quiet-until is consumed first, so a beat silenced by it
     * stays silent rather than being deferred into the next one.
     *
     * <p>Lines come from the message pack: {@code pq.chat.<questName>.<beat>.<n>} and the
     * shared {@code pq.chat.generic.<beat>.<n>}, merged into ONE pool and drawn from
     * together ({@link PqStageChat#line}). A quest may cover only the beats it has
     * something to say about, and a bot that never set {@link #questName} rides the generic
     * pool alone - which is why the generic lines must stay true for every quest's rooms.
     */
    private void maybeQuestChat(Beat beat) {
        Character bot = getChr();
        if (bot == null || bot.getMap() == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now < nextQuestChatAtMs) {
            return;
        }
        nextQuestChatAtMs = now + QUEST_CHAT_GAP_MS + jitter(QUEST_CHAT_GAP_JITTER_MS);
        if (ThreadLocalRandom.current().nextDouble() >= beat.chance) {
            return;
        }
        if (!GCMovement.isMapObserved(bot.getMap())) {
            return;
        }
        String line = PqStageChat.line(questName, beat);
        if (line != null) {
            SocialCommands.BotSpeak(bot, line);
        }
    }

    /**
     * A subclass's report that the stage it is working is done - the ladder crossed, the
     * flowers all bloomed, the crates broken - at the moment it happens. The base class
     * fires {@link Beat#CLEAR} only for a whole run ending; the intermediate moments are
     * visible only to the quest's own logic, so they are marked here.
     */
    /**
     * A subclass's report that the stage it is working is done - the ladder crossed, the
     * flowers all bloomed, the crates broken - at the moment it happens. The base class
     * fires {@link Beat#CLEAR} only for a whole run ending; the intermediate moments are
     * visible only to the quest's own logic, so they are marked here. One milestone speaks
     * once per run: the key makes repeated calls no-ops, and leaving the run clears it, so
     * the next run celebrates again.
     */
    protected void sayStageClearOnce(int key) {
        if (celebratedClearKey == key) {
            return;
        }
        celebratedClearKey = key;
        maybeQuestChat(Beat.CLEAR);
    }

    /**
     * First tick inside the quest: the run is starting. Fired on the false-to-true edge of
     * {@link #isInsideQuest}, before the stage work, so the line lands while the party is
     * still gathering itself in the entry room.
     */
    private void noteEnteredQuest() {
        maybeQuestChat(Beat.START);
    }

    /**
     * The party is walking into the next room. Spoken just before the portal is entered -
     * once through, the line would land in the room behind, where the party no longer is.
     */
    private void sayDoorLine() {
        maybeQuestChat(Beat.DOOR);
    }

    /** Speak once on the actual clear event, while the leader can still hear the room. */
    public static void sayRearguardLine(EventInstanceManager instance, MapleMap map) {
        Character leader = instance.getLeader();
        if (leader == null || leader.getMap() != map || leader.getParty() == null) {
            return;
        }
        int mapId = map.getId();
        Character speaker = null;
        for (var member : leader.getParty().getMembers()) {
            if (!(CharacterStorage.getBotById(member.getId()) instanceof PartyQuestBot pq)
                    || !pq.getRunning() || pq.getState() == BotState.PAUSE
                    || pq.death().isCorpse() || !pq.isInsideQuest(mapId)) {
                continue;
            }
            Character bot = pq.getChr();
            if (bot.getMap() != map) {
                continue;
            }
            if (speaker == null || bot.getLevel() > speaker.getLevel()
                    || (bot.getLevel() == speaker.getLevel() && bot.getId() < speaker.getId())) {
                speaker = bot;
            }
        }
        // The instance owns the claim: repeated effects and concurrent callbacks share it,
        // while a new run gets a fresh claim even when it reuses the same map id.
        if (speaker != null
                && instance.setProperty("solomapling.rearguard." + mapId, "true", true) == null) {
            SocialCommands.BotSpeak(speaker, BotMessages.get("pq.rearguard"));
        }
    }

    /**
     * Whether this bot is available to be recruited: outside every room of the run.
     *
     * <p>Deliberately "not inside the quest" rather than "exactly in the lobby map": a bot that
     * drifted to the exit map (or was moved by a script) is still recruitable, and is on its way
     * home regardless. Inside a run the answer is no - the bot is busy, and it already has a
     * party, which is what {@link #offerRecruit} also checks for itself.
     */
    protected final boolean awaitingRecruit() {
        Character bot = getChr();
        return bot != null && bot.getMap() != null && !isInsideQuest(bot.getMapId());
    }

    /**
     * Answer a greeting from a player while waiting to be recruited.
     *
     * <p>The lobby is a social space, and a crowd of silent statues is the thing that reads as
     * artificial. {@link BotSM#respondSocial} already owns the reply (this quest's own line if
     * it ships one, else the shared social pool), the per-player cooldown, and the read-and-type
     * beat. During a run this is off: the bot is busy, and chatter mid-puzzle is noise rather
     * than life.
     */
    @Override
    public boolean respondsToSocialChat() {
        return getRunning() && awaitingRecruit();
    }

    /**
     * A directed line (party / whisper / ...) addressed to this bot. The lobby bots answer the
     * same social lines they answer over map chat, and on the channel the line arrived on - a
     * partied player addressing their quest teammate across maps hears the reply on the party
     * channel instead of nothing. Reuses {@link #respondsToSocialChat()} unchanged, so a bot
     * mid-run stays silent exactly as it does for same-map chatter; {@link BotSM#respondSocial}
     * owns the intent match, the per-player cooldown, and the typing beat.
     */
    @Override
    protected void onDirectChat(ChatMessage message) {
        Character player = message.getSender();
        if (player == null || isBot(player)) {
            return;
        }
        respondSocial(player, message.getContent(), message.getChatType());
    }

    /**
     * Answer a stranger's open recruit shout ("anyone want to party?").
     *
     * <p>Same shape as the training / social bots' answer: roll the shared accept/decline, speak
     * the matching line, and let the reply itself count against {@link BotRecruitManager}'s
     * per-shout cap. The accept window the roll arms is not consulted here - a quest bot accepts
     * whatever invite reaches it (see {@link #tickPartyQuest}) - so a yes mainly means "yes,
     * invite me", which is exactly what a recruiting player is looking for.
     */
    @Override
    public boolean offerRecruit(Character player, String content) {
        Character bot = getChr();
        if (bot == null || !getRunning() || bot.getParty() != null || !awaitingRecruit()) {
            return false; // already committed, or inside a run - the shout is not for us
        }
        BotRecruitManager.RecruitAnswer answer = BotRecruitManager.rollPartyAsk(
                bot, player, BotRecruitManager.SOCIAL_ACCEPT_CHANCE, false);
        if (answer == BotRecruitManager.RecruitAnswer.ON_COOLDOWN
                || answer == BotRecruitManager.RecruitAnswer.FOLLOWERS_FULL) {
            return false; // said no to this player recently; leave the slot to another bot
        }
        // The quest's own pack first (a couple of quests ship accept/decline lines), the shared
        // social pool otherwise. This is the same node pair the other recruit-enabled bots use.
        speakNode(bot, answer == BotRecruitManager.RecruitAnswer.ACCEPTED ? "PartyAccept" : "PartyDecline");
        return true;
    }

    /**
     * Speak one dialogue node, falling back to the shared social pool when this quest's pack has
     * no such node.
     *
     * <p>The quest packs are optional - most quests ship none at all - so every lobby line has to
     * survive their absence, which is exactly the state these bots shipped in before this class
     * learned to talk.
     */
    private void speakNode(Character bot, String node) {
        String line = BotDialogueHandler.getRandomResolvedLine(dialoguePath, botType, node, bot, null);
        if (line == null) {
            line = BotDialogueHandler.getRandomResolvedLine(
                    SOCIAL_DIALOGUE_PATH, "SocialBot", node, bot, null);
        }
        if (line != null) {
            SocialCommands.BotSpeak(bot, line);
        }
    }

    private enum FollowResult { NONE, WALKING, MOVED }

    /**
     * A walk that outlives its blocking window still owns the tick. If the portal refuses entry,
     * hand control back to this room's work so it can satisfy the gate.
     */
    private FollowResult followLeaderIntoNextRoom() {
        Character bot = getChr();
        MapleMap here = bot.getMap();
        Character leader = partyLeader();
        MapleMap target = leader == null ? null : leader.getMap();
        if (here == null || target == null || target == here || !isInsideQuest(target.getId())
                || !isInsideQuest(here.getId()) || here.getEventInstance() != target.getEventInstance()
                || !isFollowingLeader()) {
            return FollowResult.NONE;
        }
        Portal exit = portalTo(here.getPortals(), target.getId());
        if (exit == null) {
            exit = forwardPortal(target.getId());
        }
        if (exit == null || !exit.getPortalStatus()) {
            return FollowResult.NONE;
        }
        if (!PqActions.walkToPortal(bot, exit)) {
            if (bot.getMap() != here) {
                return FollowResult.MOVED;
            }
            return GCMovement.hasMoveTarget(bot) ? FollowResult.WALKING : FollowResult.NONE;
        }
        sayDoorLine();
        PqActions.enterPortal(bot, exit);
        if (bot.getMap() == here) {
            return FollowResult.NONE;
        }
        BotLogger.log("PQ bot " + bot.getName() + " followed the leader from "
                + here.getId() + " through portal " + exit.getName());
        return FollowResult.MOVED;
    }

    /**
     * A known door towards the leader when there is no direct WZ link. Linear quests have a
     * {@code next00} chain; a quest with script-only links knows which one leads forward.
     */
    protected Portal forwardPortal(int leaderMapId) {
        Portal door = forwardPortalOf(getChr().getMap().getPortals());
        return door != null && door.getTargetMapId() <= leaderMapId ? door : null;
    }

    /**
     * The {@code next00} door with a declared target. A script-only gate cannot be inferred
     * from its name alone, so the quest that owns it supplies the route.
     */
    static Portal forwardPortalOf(java.util.Collection<Portal> portals) {
        if (portals == null) {
            return null;
        }
        for (Portal portal : portals) {
            if (portal != null && "next00".equals(portal.getName())
                    && portal.getTargetMapId() > 0 && portal.getTargetMapId() != NO_MAP_SENTINEL) {
                return portal;
            }
        }
        return null;
    }

    /** The engine's "no map" portal target ({@code 999999999}): spawns and script-only gates. */
    private static final int NO_MAP_SENTINEL = 999999999;

    /**
     * Whether this bot should trail the leader's room changes. The default is always:
     * most quests move by the party walking through a portal together, and a bot that
     * stays behind contributes nothing. A bot on a self-directed errand (the Ludi door
     * rooms) overrides this to say no while its own loop is still driving it, so the
     * follow beat does not yank it back out mid-exploration.
     */
    protected boolean isFollowingLeader() {
        return true;
    }

    /**
     * Whether a pending party invitation may be accepted.
     *
     * <p>Default is yes: being recruited is the point of every quest bot. A content type that
     * fields its own team may say no instead, and then the pending invite is answered explicitly
     * ({@link BotPartyCommands#botRejectPartyInvite}) so the inviter is told and the queue entry
     * is cleared - leaving it unanswered would make the bot re-evaluate the same invite every tick
     * for its whole lifetime.
     */
    protected boolean acceptsPartyInvites(Character bot) {
        return true;
    }

    /**
     * Rejoin a party that moved by warp instead of by a door, in one hop.
     *
     * <p>{@link #followLeaderIntoNextRoom} walks a portal whose target is the leader's map, which
     * is how quests advance. A few maps are not connected that way at all - Monster Carnival's
     * lobby chain (hall -> field lobby -> arena) is entered by a script, so no portal leads from
     * the hall to the field. A bot that joined the party in the hall would otherwise stand there
     * for the whole run while its team fights.
     *
     * <p>Default off: warping is a bigger hammer than walking, and only the quest that needs it
     * should opt in.
     *
     * @return true when this bot changed rooms
     */
    protected boolean followLeaderByWarp() {
        return false;
    }

    /**
     * Pick, out of a map's portals, the one that leads to {@code targetMapId}.
     *
     * <p>Static and collection-taking so the rule can be tested against the engine's own portal
     * type - the bug this replaced (walking onto the <em>nearest</em> portal, which in a quest
     * room is usually the spawn point) was invisible to every existing test and only showed up
     * when the real WZ portal data was read.
     */
    static Portal portalTo(java.util.Collection<Portal> portals, int targetMapId) {
        if (portals == null) {
            return null;
        }
        for (Portal portal : portals) {
            if (portal != null && portal.getTargetMapId() == targetMapId) {
                return portal;
            }
        }
        return null;
    }

    /** The party's leader as a character, or null when there is nobody to follow. */
    protected final Character partyLeader() {
        var party = getChr().getParty();
        if (party == null || party.getLeader() == null) {
            return null; // the leader's character is null while he is offline
        }
        return party.getLeader().getPlayer();
    }

    /**
     * Put the bot back in the lobby, which is where a finished run leaves the party.
     *
     * <p>Warped directly rather than by walking a portal. Inside a quest's rooms the portals lead
     * to the next room, not out - Kerning's {@code next00} opens onto stage 3 - so following the
     * nearest one on the way home would walk the bot deeper into a run it is trying to leave.
     * The quest's own exit for a finished run is a plain warp to the lobby, and this is that.
     *
     * <p>Two engine details the warp has to get past, both only reachable now that a bot is
     * actually registered in the instance:
     * <ul>
     *   <li>{@code Character.changeMap} re-resolves its target through
     *       {@code getWarpMap}, which answers with the <em>instance's</em> copy of a map while an
     *       event instance is set. The lobby is not part of the run, so that copy is entered by
     *       nobody and a bot left there is invisible to every player in town. Unregistering
     *       first (the way the quests' own {@code playerExit} does) drops the instance, so the
     *       warp lands on the channel's real lobby.</li>
     *   <li>The lobby map must be resolved on the bot's OWN channel - a map is a per-channel
     *       object, and a bot placed in channel 1's town while living on another channel would
     *       broadcast to the wrong players.</li>
     * </ul>
     */
    protected final void returnToLobby(String reason) {
        Character bot = getChr();
        if (bot == null) {
            return;
        }
        releaseRoomState(); // the run is over - give up the room's wait spots and claims
        if (bot.getMapId() == lobbyMapId()) {
            leaveInstance();
            return;
        }
        BotLogger.log("PQ bot " + bot.getName() + " leaving the run: " + reason);
        // A hide aura (LPQ stage-5 隐身术/橡木伪装) has no expiry of its own - only an attack,
        // a mount or a death retires it, and none of those happen on the way home. Send the
        // cancelForeignBuff before the warp, so the bot doesn't arrive at the lobby invisible
        // (the on-arrival aura replay would even keep re-drawing it to every new arrival).
        BotAuraState.cancelHidesForAction(bot);
        // Drop the instance before warping: changeMap would otherwise resolve the lobby through
        // the instance's own map cache and put the bot in an empty copy of the town.
        leaveInstance();
        var lobby = WarpCommands.mapForBot(bot, lobbyMapId());
        if (lobby == null) {
            return; // a lobby id that does not resolve is a data error, not a reason to wander
        }
        // A null answer (a lobby whose WZ data names no spawn door) is fine: Character.changeMap
        // resolves a null portal to the map's portal 0.
        GCMovement.changeMap(bot, lobby, lobbyArrivalPortal(bot, lobby));
    }

    /**
     * The door this bot arrives on when the run sends it back to its lobby.
     *
     * <p>Default is portal 0, the arrival every quest shipped with (and what
     * {@code Character.changeMap} resolves a null portal to). A quest whose lobby ships several
     * spawn doors can answer with another one so a returning party does not land six bodies on one
     * pixel - see {@code CarnivalPQBot}.
     */
    protected Portal lobbyArrivalPortal(Character bot, MapleMap lobby) {
        return lobby.getPortal(0);
    }

    /**
     * Take this bot out of its event instance, if it is in one.
     *
     * <p>Written as the quests' own {@code playerExit} does it: unregister first (which clears
     * {@code Character.eventInstance}), then leave the map. Without this the bot stays counted by
     * {@code getPlayerCount()} for the rest of the instance's life - a puzzle that wants an exact
     * number of bodies in a rectangle would still see a bot that has gone home.
     */
    private void leaveInstance() {
        var eim = getChr().getEventInstance();
        if (eim != null) {
            eim.unregisterPlayer(getChr());
        }
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
        PqActions.walkTo(getChr(), spot);
        if (settleMs > 0) {
            try {
                Thread.sleep(settleMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
