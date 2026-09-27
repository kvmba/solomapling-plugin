package soloMapling.ArtificialPlayer.PartyQuest;

import org.gms.client.Character;
import org.gms.server.maps.Portal;
import soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands;
import soloMapling.ArtificialPlayer.BotCommandsPack.WarpCommands;
import soloMapling.ArtificialPlayer.BotDialogueHandler;
import soloMapling.ArtificialPlayer.BotPartySystem.BotPartyLogic;
import soloMapling.ArtificialPlayer.BotPartySystem.BotRecruitManager;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotHealthSystem.BotPotionSim;
import soloMapling.ArtificialPlayer.BotMessagingSystem.ChatMessage;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.BotLogger;
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
public abstract class PartyQuestBot extends BotSM {

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

    protected PartyQuestBot(Character character) {
        super(character);
        // Drive every quest bot with the dynamic (WZ-terrain) engine for its whole life. The
        // recorded-path engine replayed a Haste-speed player's packets at 1:1 (quest bots walked
        // ~40% too fast) and only ever had data for a handful of maps (any other quest room left
        // the bot unable to move). Enabling here - before the FSM starts and before any arrival
        // warp - means the walk helpers in {@link PqActions} have a driver, and the map-entry
        // choreography already sees the bot as dynamic-controlled and skips the recorded drop.
        GCMovement.enable(character);
    }

    @Override
    public synchronized void stopScheduledTask() {
        // Release the dynamic engine (and the shared movement lock it holds) when this bot is
        // stopped or converted away, so the next bot type on this character starts clean.
        GCMovement.disable(getChr());
        PqActions.clearSeekState(getChr().getId());
        releaseRoomState(); // a stopped bot holds no wait spots or box claims
        super.stopScheduledTask();
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
            BotPartyLogic.checkPartyQueue(bot);
        }

        if (!isInsideQuest(bot.getMapId())) {
            // Outside the quest the run's edge flags reset, so a bot that leaves and comes
            // back gets its run-start line again - and its stage-clear milestones - on the
            // next run.
            wasInsideQuest = false;
            celebratedClearKey = Integer.MIN_VALUE;
            tickLobby(bot);
            return;
        }
        if (!wasInsideQuest) {
            wasInsideQuest = true;
            noteEnteredQuest();
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
            releaseRoomState(); // a new room means a new ring around its stage NPC
            return; // moved rooms; work resumes from the new one next tick
        }

        // Survive the fight: sip back whatever the room's mobs took off (no-op while full).
        potionSim.tick(bot);

        recoverStalledClimb(bot);

        maybeQuestChat(Beat.WORKING);

        if (workStage()) {
            maybeQuestChat(Beat.CLEAR);
            returnToLobby("stage work reports the run is over");
            return;
        }
        // A working room keeps a tighter beat: the 2-6s gap between macro ticks reads as the
        // bot standing dumb between actions ("act, freeze, act"). The nudge only pulls the
        // NEXT tick forward to ~0.7s, and its own 1.5s debounce makes that the real working
        // floor - a live bot acts roughly every 1.5s instead of every 2-6s. The lobby branch
        // above returns before this, so waiting bots keep their calm cadence.
        nudgeSoon(700);
    }

    /**
     * Drop whatever room-scoped state this bot holds (stage-NPC wait spots, box claims).
     * Quests that keep none override this to a no-op; the Ludi rooms hold both.
     */
    protected void releaseRoomState() {
    }

    // Climb-stall recovery (the PQ twin of the grind brain's ClimbRecovery, which a quest
    // bot does not run): a committed nav climb that stops making real vertical progress is
    // a wedge (rope-top clamp, mid-rope rock) - the chase silently dies there and the bot
    // never reaches the mobs it picked. The numbers mirror ClimbRecovery's proven pair:
    // 6px sits just above one climb step (~5px), so a wriggle reads as a stall while any
    // genuine multi-tick climb counts as progress.
    private long climbStallSinceMs = 0L;
    private int lastClimbY = Integer.MIN_VALUE;
    private static final long CLIMB_STALL_MS = 2_500;
    private static final int CLIMB_PROGRESS_EPS_PX = 6;

    private void recoverStalledClimb(Character bot) {
        if (!GCMovement.isClimbing(bot)) {
            climbStallSinceMs = 0L;
            return;
        }
        Point pos = bot.getPosition();
        int y = (pos != null) ? pos.y : 0;
        if (climbStallSinceMs == 0L || Math.abs(y - lastClimbY) >= CLIMB_PROGRESS_EPS_PX) {
            climbStallSinceMs = System.currentTimeMillis();
            lastClimbY = y;
            return;
        }
        if (System.currentTimeMillis() - climbStallSinceMs >= CLIMB_STALL_MS) {
            climbStallSinceMs = 0L;
            GCMovement.dismountRope(bot, 0);
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
            if (bot.getParty() == null && lobbyHasListener(bot)) {
                sayRecruitLine(bot);
            }
        }
        if (now >= nextLobbyStrollAtMs) {
            nextLobbyStrollAtMs = now + LOBBY_STROLL_INTERVAL_MS + jitter(LOBBY_STROLL_JITTER_MS);
            // The dynamic engine owns the walk; a no-op while one is already in progress, so
            // this can be called every tick without thrashing the target.
            PlatformPlacement.botStrollOnMap(bot);
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
        return GCMovement.isMapObserved(bot.getMapId());
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
     * <p>Lines come from the message pack: {@code pq.chat.<questName>.<beat>.<n>} first,
     * the shared {@code pq.chat.generic.<beat>.<n>} pool otherwise, and a quest with neither
     * for a beat simply says nothing - which is why a bot that never set {@link #questName}
     * still works, riding the generic pool.
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
        if (!GCMovement.isMapObserved(bot.getMapId())) {
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

    /**
     * Walk through the portal the leader took, when the party has moved on without this bot.
     *
     * <p>Quests advance in one of two ways: the event script warps the whole team, or the
     * players walk a portal whose script checks that the stage is clear. The second kind is
     * invisible to a bot that does not walk it, and the result is not a stalled bot but a
     * misleading one - it stays registered, so a stage that counts the party's numbers still
     * counts it, while its position is in the room the party already left.
     *
     * <p>The portal is chosen by <em>destination</em>, not by proximity. A quest room's nearest
     * portal is usually its spawn point ({@code sp}, target {@code 999999999}) or a return
     * portal - walking onto either is at best a no-op and at worst a warp to a map that does not
     * exist. The one that matters is the one whose target is the room the leader is standing in,
     * and its own script (e.g. Kerning's {@code kpq0}) re-checks the stage gate, so a bot that
     * arrives early is simply refused instead of skipping ahead.
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
        Portal exit = portalTo(leaderMap);
        if (exit == null) {
            // No door from here to there: the script warped the leader (or the rooms are not
            // directly connected). Leave it to the quest's own team warp next tick.
            return false;
        }
        BotLogger.log("PQ bot " + getChr().getName() + " following the leader from "
                + here + " to " + leaderMap + " through portal " + exit.getName());
        sayDoorLine();
        PqActions.walkTo(getChr(), exit.getPosition());
        PqActions.enterPortal(getChr(), exit);
        return getChr().getMapId() != here;
    }

    /** The portal on this bot's map that leads to {@code targetMapId}, or null when there is none. */
    private Portal portalTo(int targetMapId) {
        var map = getChr().getMap();
        return map == null ? null : portalTo(map.getPortals(), targetMapId);
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
        // Drop the instance before warping: changeMap would otherwise resolve the lobby through
        // the instance's own map cache and put the bot in an empty copy of the town.
        leaveInstance();
        var lobby = WarpCommands.mapForBot(bot, lobbyMapId());
        if (lobby == null) {
            return; // a lobby id that does not resolve is a data error, not a reason to wander
        }
        bot.changeMap(lobby, lobby.getPortal(0));
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
