package soloMapling.ArtificialPlayer;

import org.gms.client.Character;
import org.gms.server.Trade;
import org.gms.server.maps.MapObject;
import org.gms.server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotAttackSystem.ThrowingStarSelector;
import soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands;
import soloMapling.ArtificialPlayer.BotHealthSystem.BotDeath;
import soloMapling.ArtificialPlayer.BotMessagingSystem.ChatMessage;
import soloMapling.ArtificialPlayer.BotMessagingSystem.MessageQueue;
import soloMapling.ArtificialPlayer.BotTradeSystem.BotTradeHandler;
import soloMapling.ArtificialPlayer.BotTradeSystem.BotTradeInventory;
import soloMapling.ArtificialPlayer.BotTradeSystem.BotTradeLogic;
import soloMapling.ArtificialPlayer.BotTradeSystem.BotTradeSM;
import soloMapling.ArtificialPlayer.BotTradeSystem.BotTradeWants;
import soloMapling.ArtificialPlayer.GCMoveSystem.LodCounts;
import soloMapling.server.EventMessageSystem.BotEventBuffer;
import soloMapling.server.EventMessageSystem.EventBus;
import soloMapling.server.EventMessageSystem.EventSubscriber;
import soloMapling.server.EventMessageSystem.GameEvent;

import soloMapling.server.BotTiming;
import soloMapling.server.BotTickService;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands.botClearChalkboard;
import static soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage.botLoggedIn;
import static soloMapling.ArtificialPlayer.BotHelpers.isBot;
import static soloMapling.ArtificialPlayer.BotMovementSystem.MovementCommands.BotIdleStandingUpdate;
import static soloMapling.ArtificialPlayer.BotTradeSystem.BotTradeLogic.clearTradeRequest;
import static soloMapling.BotLogger.log;
import static soloMapling.DebugUtilities.debugprint;
import static soloMapling.server.SoloMaplingUtilities.random;


public abstract class BotSM implements EventSubscriber {

    // BotSM - bot framework related stuff. setting/getting data

    public enum BotState {
        IDLE,
        RUNNING,
        PAUSE,
        TRADING,
        FINISHED;
    }

    private final Character character; // Reference to the existing Character object
    // Level-appropriate throwing star chosen once at creation for claw-thief bots (0 = not a claw
    // thrower). Cosmetic packet projectile only; lives here so we never touch the Cosmic Character.
    private final int chosenStarId;
    private boolean running;
    protected BotState state;
    private BotDebugHandler debugger;
    private BotInteractorsHandler interactors = new BotInteractorsHandler();
    private BotTradeHandler tradeHandler;

    protected String dialoguePath;
    protected String botType;

    protected void dprint(String msg) {
        boolean debugPrintConsole = false;
        if (!debugPrintConsole) {
            return;
        }
        debugprint("[" + getBotType() + ":" + getChr().getName() + "] " + msg);
    }

    private static MessageQueue messageQueue = MessageQueue.getInstance();

    // Per-bot inbox for directed channels (whisper/buddy/party/guild/alliance). Unlike the shared
    // primary/secondary queues this is addressed to exactly this bot, and unlike the Dispatcher it
    // needs no map proximity - a whisper or party line reaches a bot on any map or channel.
    // Bounded: a player can whisper any visible character, while a stopped bot never drains and an
    // unobserved grinder drains on a minutes-long cadence - an unbounded queue would let a spammer
    // grow it without limit. Full means the bot is far behind anyway; offer() then drops the newest.
    private static final int MAX_DIRECT_INBOX = 128;
    private final java.util.concurrent.BlockingQueue<ChatMessage> directInbox =
            new java.util.concurrent.ArrayBlockingQueue<>(MAX_DIRECT_INBOX);
    // Drain cap per tick: a burst of whispers must not starve the bot's own FSM.
    private static final int MAX_DIRECT_CHAT_PER_TICK = 8;

    /**
     * This bot's death episode — the one piece of state that suspends all the others.
     * Declared before the tick body below references it.
     */
    private final BotDeath death;

    // One shared tick body for every (re)schedule path - start / priority change / nudge.
    private final Runnable tickRunnable = () -> {
        try {
            // Mount reconciliation runs ahead of every gate so a bot is astride iff its pose
            // allows it: walking/standing → mount, anything else (skill, air, rope, swim,
            // chair, death) → dismount. Cheap no-op for the bots that own no mount.
            soloMapling.ArtificialPlayer.BotMountSystem.BotMount.tick(getChr());
            // Death outranks everything the bot would otherwise do: a corpse does not grind,
            // wander or follow. Checked here rather than in each of the twenty-odd bot types
            // so the rule is stated once. Trading is let through on purpose - a dead bot in a
            // trade window would strand the player's side of it.
            //
            // The tick is also re-paced while down: an unobserved grinder otherwise ticks on a
            // 4-8 minute cadence, which is right for its abstract grinding but would stretch a
            // half-minute death into a quarter of an hour.
            // A bot the host zeroed behind our back — a map's decHP field drains HP directly
            // (Aqua Road's breathing damage, El Nath's cold) without going through the damage
            // layer — has no episode of its own yet. Adopt it before the gate so this tick
            // already treats it as down instead of letting the FSM steer a corpse.
            death().adoptIfZeroHp();
            if (death().isDead() && state != BotState.TRADING) {
                if (death().tick()) {
                    updateScheduleDelay(death().tickDelayMs());
                    return;
                }
                // Just stood up: let its own cadence take over again. Our death pacing left
                // currentDelay at the corpse's few seconds, and updateScheduleDelay would
                // otherwise skip re-applying an identical value.
                currentDelay = 0;
            }
            if (isWaiting()) {
                return; // FSM-requested pause (waitFor) - skip the tick entirely
            }
            soloMapling.server.BotPerfStats.MACRO_TICKS.increment();
            drainDirectChat();
            updateState();
        } catch (Exception e) {
            e.printStackTrace(); // Handle exceptions to ensure the scheduler doesn't stop unexpectedly
        }
    };

    // ── Waiting without sleeping (Fable Phase 4) ─────────────────────────────
    // FSM code that needs a pause calls waitFor(ms) and RETURNS from its tick
    // instead of Thread.sleep. The tick gate above then skips updateState until
    // the wait passes - no thread is held anywhere, and the FSM resumes in
    // whatever state was set before returning.
    //
    //   old:  doThing(); blockingSleep(3000); doNext();
    //   new:  doThing(); waitFor(3000); setXxxState(NEXT); return;  // next tick runs doNext
    //
    // The wait also holds through nudges (a player walking in doesn't cut a pause
    // short - that's what the pause means), so keep waits short for anything that
    // should feel reactive.
    private volatile long waitUntilMs = 0;

    // public so collaborators driven from the tick (BotTradeSM) can pace the bot too
    public void waitFor(long ms) {
        waitUntilMs = System.currentTimeMillis() + ms;
    }

    protected void waitForRandom(long loMs, long hiMs) {
        waitFor(loMs + random.nextInt((int) Math.max(1, hiMs - loMs + 1)));
    }

    protected boolean isWaiting() {
        return System.currentTimeMillis() < waitUntilMs;
    }

    // Map-entry responsiveness (see BotMapEntryResponder): timestamp of the last nudgeSoon, used to
    // debounce repeated entries so the next tick isn't perpetually reset (which would starve the FSM).
    private volatile long lastNudgeMs = 0L;
    private static final long NUDGE_DEBOUNCE_MS = 1500;

    private BotDialogueHandler dialogueHandler;

    private BotTradeSM botTradeSM = null; // Initially null
    private BotTradeInventory tradeInventory = new BotTradeInventory();
    private BotTradeWants tradeWants = new BotTradeWants();
    private BotTradeSM.TradeMode currentTradeMode = BotTradeSM.TradeMode.NULL;
    private volatile long currentDelay = getRandomDelay(); // Store current delay
    private volatile boolean movementInterrupted = false;
    protected Trade.TradeResult lastTradeResult = null;
    protected Character lastTradedCharacter = null;

    private final BotEventBuffer eventBuffer;

    public BotSM(Character chr) {
        this.character = chr;
        this.running = false;
        this.state = BotState.IDLE;
        this.tradeHandler = new BotTradeHandler(chr);
        this.debugger = new BotDebugHandler(chr);
        this.dialogueHandler = new BotDialogueHandler(chr);
        this.eventBuffer = new BotEventBuffer(100);
        // Roll the throwing star now: the Character is fully decorated by the time a BotSM is built
        // (createBot decorates, setAndStartBots then constructs us), so weapon/level/job are set.
        this.chosenStarId = ThrowingStarSelector.selectFor(chr);
        this.death = new BotDeath(chr);
        debugprint(("Bot Initialized: " + this.character.getName() + ", " + this.character.getId()));
    }

    /**
     * This bot's death episode. Public so the damage layer (a different package) can end this
     * bot when a hit is more than it could drink through, and visible to bot types so the ones
     * with a second tick path outside this class can stand down too. {@code isDead()} is what
     * anyone merely asking "is it down?" wants.
     */
    public BotDeath death() {
        return death;
    }

    private void setState(BotState state) {
        this.state = state;
    }

    public BotState getState() {
        return this.state;
    }

    private boolean verifyState(BotState expectedState) {
        return this.state == expectedState;
    }

    public org.gms.client.Character getChr() {
        return this.character;
    }

    // The throwing star this claw-thief bot chose at creation, or 0 if it isn't a claw thrower.
    public int getChosenStarId() {
        return this.chosenStarId;
    }

    public String getBotType() {
        return this.botType;
    }

    /**
     * Whether this bot type may ride a mount. Opted in by every bot that moves about its
     * business: the four mobile families (SocialBot 站街, TrainingBot 打怪, TownWandererBot
     * 游走, CompanionBot 持久化) and the mobile Free-Market bots (buying/selling/NX merchants
     * and the walking FMBot browser). A shop/stall runner (the in-room hired-merchant /
     * player-shop owner) is a bare Character with no BotSM and so never mounts; gacha/blackjack/
     * game-zone hosts, dice, drop-game, OPQ and the tutorial/staging bots inherit {@code false}.
     * Checked by {@code BotMount} so a non-eligible bot never mounts.
     */
    public boolean allowsMount() {
        return false;
    }

    public void setRunning(boolean bool) {
        this.running = bool;
    }

    public boolean getRunning() {
        return running;
    }

    public void interruptMovement() {
        this.movementInterrupted = true;
    }

    public boolean isMovementInterrupted() {
        return this.movementInterrupted;
    }

    public void clearMovementInterrupt() {
        this.movementInterrupted = false;
    }

    public BotInteractorsHandler getInteractors() {
        return interactors;
    }

    /**
     * Accepts one directed line (whisper/buddy/party/guild/alliance) for this bot. Called by the
     * bridge on the sending player's packet thread, so it only enqueues - the bot's own tick drains
     * and acts on it. Safe on any thread.
     */
    public void postDirectChat(ChatMessage message) {
        if (message != null) {
            directInbox.offer(message);
        }
    }

    // Bounded drain, run from the tick ahead of the FSM so a whisper reaches the bot promptly.
    private void drainDirectChat() {
        for (int i = 0; i < MAX_DIRECT_CHAT_PER_TICK && !directInbox.isEmpty(); i++) {
            ChatMessage message = directInbox.poll();
            if (message == null) {
                return;
            }
            try {
                onDirectChat(message);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Handles one directed line. Default no-op: most bot types (shop runners, game-zone hosts, the
     * frozen set pieces) are not conversational off their own map, so an undirected whisper to them
     * is silently ignored - opting in is each type's choice. Overridden by the conversational types
     * (SocialBot, CompanionBot, FollowerBot).
     */
    protected void onDirectChat(ChatMessage message) {
    }

    // --- Reply channel (set when a conversation is opened by a directed line) --------------------
    // null type means the conversation came from map-wide chat, so replies keep the map bubble.
    // A directed type makes replies go back on that channel, which is the only way a line reaches a
    // player on another map or channel. Written off the tick (the packet thread) and read on it.
    protected volatile org.gms.extension.event.ChatType replyType = null;
    protected volatile Character replyTarget = null;

    protected void enterReplyChannel(org.gms.extension.event.ChatType type, Character target) {
        replyType = type;
        replyTarget = target;
    }

    protected void leaveReplyChannel() {
        replyType = null;
        replyTarget = null;
    }

    // Same map (and both maps present). Directed-channel handlers use this to tell a same-map
    // speaker (a bubble reply reaches them) from a distant one (only a channel-scoped reply does).
    protected boolean isSameMap(Character other) {
        Character self = getChr();
        return other != null && self != null && self.getMap() != null && other.getMap() != null
                && other.getMapId() == self.getMapId();
    }

    /** Speaks one line on the current reply channel (map bubble when none is set). */
    public void sayReply(String line) {
        SocialCommands.BotReply(getChr(), replyChannel(), replyChannelTarget(), line);
    }

    /**
     * Answers a player's social line (greeting / praise / joke / insult...) with this bot's own
     * dialogue - its pack's node if it has one, else the shared social pool. The reply goes back on
     * exactly the channel it arrived on: a directed line (whisper / party / guild / buddy) is
     * answered there, never downgraded to a map bubble a private line's reply would leak; a null
     * {@code type} means the line came from map chat, so the bubble is correct.
     *
     * @param type the channel the line arrived on (null = map chat)
     */
    public void respondSocial(Character player, String content, org.gms.extension.event.ChatType type) {
        if (player == null || content == null || content.isBlank() || !getRunning()) {
            return;
        }
        Character chr = getChr();
        if (chr == null || chr.getMap() == null) {
            return;
        }
        String intent = SocialIntent.classifyNode(content);
        if (intent == null) {
            return;
        }

        // Rate-limit per player: a spammed line ("hi hi hi") gets one reply, not a burst.
        long now = System.currentTimeMillis();
        Long until = socialReplyUntil.get(player.getId());
        if (until != null && until > now) {
            return;
        }
        socialReplyUntil.put(player.getId(), now + SOCIAL_REPLY_COOLDOWN_MS);
        if (socialReplyUntil.size() > 256) {
            socialReplyUntil.entrySet().removeIf(e -> e.getValue() <= now);
        }

        // Prefer this bot's own node for the intent (e.g. a TrainingBot's own Greeting); fall back to
        // the shared social pool when this type has no such node, so every type can answer in kind.
        String line = BotDialogueHandler.getRandomResolvedLine(dialoguePath, botType, intent, chr, player);
        if (line == null) {
            line = BotDialogueHandler.getRandomResolvedLine(SOCIAL_DIALOGUE_PATH, "SocialBot", intent, chr, player);
        }
        if (line == null) {
            return;
        }
        final String spoken = line;
        // Answer on the channel the line arrived on: a directed line (whisper/party/guild/buddy) is
        // answered on THAT channel, never downgraded to a map bubble - a whisper is private, and a
        // same-map bubble would expose its reply to everyone standing around. A null type means the
        // line came from map chat, so the bubble is correct there.
        final org.gms.extension.event.ChatType replyChannelType = type;
        final Character target = player;
        BotTiming.chain()
                .stopUnless(() -> getRunning() && chr.getMap() != null)
                .pause(BotTiming.typingPauseFor(spoken))
                .run(() -> SocialCommands.BotReply(chr, replyChannelType, target, spoken))
                .start();
    }

    /** True for bot types that answer a player's social chat directly (Phase D: Training/Follower). */
    public boolean respondsToSocialChat() {
        return false;
    }

    /**
     * A name call carrying a social line ("小花 你好"): answer it instead of opening a conversational
     * menu. Off by default; the Dispatcher routes this only for types that opt in.
     */
    public boolean handleSocialNameCall(Character player, String content) {
        if (!respondsToSocialChat() || !getRunning() || SocialIntent.classifyNode(content) == null) {
            return false;
        }
        respondSocial(player, content, null);
        return true;
    }

    /** A no-name map line that is a social gesture: answer it (see {@link #handleSocialNameCall}). */
    public boolean offerSocial(Character player, String content) {
        if (!respondsToSocialChat() || !getRunning() || SocialIntent.classifyNode(content) == null) {
            return false;
        }
        respondSocial(player, content, null);
        return true;
    }

    private static final String SOCIAL_DIALOGUE_PATH = "SocialBotDialogue.yaml";

    // Per-player cooldown so one player cannot make this bot (and the handful of others near them)
    // answer the same spam line over and over. Cleaned lazily when it grows.
    private final Map<Integer, Long> socialReplyUntil = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long SOCIAL_REPLY_COOLDOWN_MS = 2000;

    // The channel the next sayReply uses. Read through these rather than the fields so a bot whose
    // replies are produced on concurrent per-turn threads (CompanionBot) can scope them per turn.
    protected org.gms.extension.event.ChatType replyChannel() {
        return replyType;
    }

    protected Character replyChannelTarget() {
        return replyTarget;
    }

    public BotTradeHandler getTradeHandler() {
        return tradeHandler;
    }

    protected BotDebugHandler getDebugger() {
        return debugger;
    }

//    public static void getMessageQueue() {
//        messageQueue = MessageQueue.getInstance();
//    }


    public boolean checkRunningOnline() {
        return getRunning() && botLoggedIn(this.getChr().getId());
    }

    // "Is a real player on my map?" - answered by the LOD observer tracker in O(1)
    // (Fable Phase 1, F3) instead of scanning the map's character list per tick.
    // Falls back to the scan only when the tracker poll isn't running yet
    // (no GC-movement bot enabled, e.g. bare dev spawns).
    public boolean checkMainPlayersOnMap() {
        if (LodCounts.trackerRunning()) {
            return LodCounts.isMapFull(character.getMapId());
        }
        Collection<Character> charsOnMap = character.getMap().getCharacters();
        for (Character chrs : charsOnMap) {
            if (!isBot(chrs)) {
                return true;
            }
        }
        return false;
    }

    public void updateState() {
        debugger.handleDebugPrints(this);
        switch (state) {
            case IDLE:
                if (checkRunningOnline()) {
                    setState(BotState.RUNNING);
                    log("Moving to RUNNING: " + getChr().getName());
                }
                break;
            case RUNNING:
                checkPrioritySpeed();
                if (!checkRunningOnline()) {
                    setState(BotState.FINISHED);
                    log("Moving to FINISHED: " + getChr().getName());
                    break;
                }
                BotIdleStandingUpdate(getChr());
//                if (!checkMainPlayersOnMap()) {
//                    state = BotState.PAUSE;
//                    log("Moving to PAUSE: " + getChr().getName());
//                    break;
//                }
                if (tradeHandler.verifyTradePartner()) {
                    debugprint("verifyTradePartner");
                    tradeInitialized(getTradeMode());
                    setState(BotState.TRADING);
                    break;
                }
                break;
            case PAUSE:
                if (checkMainPlayersOnMap()) {
                    setState(BotState.RUNNING);
                    log("Resuming to RUNNING: " + getChr().getName());
                }
                break;
            case TRADING:
                /*
                1. completed, has trade partner = should not be possible
                2. not completed, has trade partner = still trading continuously - TRADING

                3. completed, no trade partner = successfully finished trade. go to running
                4. not completed, no partner = canceled / trade declined = go to running - RUNNING
                 */
                if (botTradeSM.isTradeComplete() && !tradeHandler.verifyTradePartner() ||
                        !botTradeSM.isTradeComplete() && !tradeHandler.verifyTradePartner() && !botTradeSM.isOfferAccepted()) {
                    cleanupTradeState();
                    waitFor(2000); // settle beat after the trade closes (gated, no thread held)
                    setState(BotState.RUNNING);
                    break;
                }
                try {
                    updateTradeSM();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }

                break;
            case FINISHED:
                this.setRunning(false);
                getInteractors().resetRespondant();
                stopScheduledTask();
                setState(BotState.IDLE);
                break;
            default:
                throw new IllegalStateException("Unexpected state: " + state);
        }
    }

    // Method to start the scheduled task
    public synchronized void startScheduledTask() {
        startScheduledTask(0);
    }

    // Fable Phase 2: macro ticks ride the shared BotTickService wheel - no per-bot
    // scheduler thread. Registration is keep-if-present (the old code left a live
    // task alone), the period is measured from tick completion (no pileups), and
    // ticks never overlap for one bot (the wheel's per-entry guard).
    public synchronized void startScheduledTask(long initialDelayMs) {
        BotTickService.register(getChr().getId(), tickRunnable, initialDelayMs, getRandomDelay());
    }

    public synchronized void updateScheduleDelay(long newDelayMs) {
        if (this.currentDelay == newDelayMs) {
            return; // No change needed
        }
        this.currentDelay = newDelayMs;
        BotTickService.reschedule(getChr().getId(), newDelayMs);
    }

    // Pull the next macro tick forward to ~initialDelayMs from now, then resume the normal 2-6s
    // cadence. Lets a bot act promptly the instant it shares a map with a real player (a player walked
    // into the bot's map, or the bot walked into the player's map) instead of waiting out its slow
    // wheel. Debounced so a pacing player / repeated entries can't keep resetting the next tick and
    // starve the FSM. Steady state is unchanged - only the next tick moves; checkPrioritySpeed settles
    // the cadence on the following tick. Only moves this bot's due time on the shared wheel, never
    // calls updateState directly, so the no-overlapping-ticks invariant holds.
    public synchronized void nudgeSoon(long initialDelayMs) {
        if (!getRunning() || state == BotState.TRADING || state == BotState.FINISHED) {
            return; // don't disrupt a trade or a shutting-down bot
        }
        if (!BotTickService.isRegistered(getChr().getId())) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastNudgeMs < NUDGE_DEBOUNCE_MS) {
            return;
        }
        lastNudgeMs = now;
        soloMapling.server.BotPerfStats.NUDGES.increment();

        long period = getRandomDelay(); // steady state stays 2-6s; only the next tick is pulled forward
        this.currentDelay = period;
        this.cadenceObserved = true; // the nudge re-established the normal cadence; next tick re-evaluates
        BotTickService.nudge(getChr().getId(), initialDelayMs, period);
    }

    // Cadence tier tracking: reschedule the observed cadence only on a tier flip, but
    // re-assert the low cadence every unobserved tick - a reschedule is two field
    // writes on the tick wheel now, and re-asserting lets a phase-dependent low delay
    // (TrainingBot deepens to 240-480s while GRINDING) take effect one tick after the
    // bot's phase changes.
    private volatile boolean cadenceObserved = true; // startScheduledTask begins at the normal 2-6s cadence

    public void checkPrioritySpeed() {
        boolean observed = checkMainPlayersOnMap();
        if (observed) {
            if (!cadenceObserved) {
                cadenceObserved = true;
                setPriorityNormal();
            }
            return;
        }
        cadenceObserved = false;
        setPriorityLow();
    }

    // Unobserved macro cadence for this bot type. Jittered so cohorts that flipped to
    // low together don't stay tick-aligned forever (Fable Phase 3: lockstep clumps CPU
    // into bursts and aliases short !env perf windows). Deep-background types override
    // this (TrainingBot returns 240-480s while GRINDING - abstract EXP accrues by
    // elapsed time and the grind watchdog skips unobserved bots, so nothing is lost).
    protected long lowPriorityDelayMs() {
        return 36_000 + random.nextInt(12_000); // 36-48s
    }

    // Convenience methods for common adjustments
    public void setPriorityLow() {
        updateScheduleDelay(lowPriorityDelayMs());
    }

    public void setPriorityHigh() {
        updateScheduleDelay(2000); // 2 seconds
    }

    public void setPriorityNormal() {
        updateScheduleDelay(getRandomDelay()); // Your original random delay
    }

    private long getRandomDelay() {
        return 2000 + random.nextInt(4000); // 2000 to 3000 ms
    }

    // Method to stop the scheduled task. An in-flight tick is allowed to finish
    // (the old cancel(true) interrupt is gone); the wheel entry is simply removed.
    public synchronized void stopScheduledTask() {
        log("Shutting down scheduler: " + this.getChr().getName());
        EventBus.getInstance().unsubscribeAll(this);
        botClearChalkboard(this.getChr());
        BotTickService.unregister(getChr().getId());
    }

    // todo
    // At the moment this is not used as far as I know.
    protected void processMessages() {
        System.out.println("BotSM processMessages");
        try {
            ChatMessage message = messageQueue.getMessageNonBlocking("secondary");
            if (message.getSender() == getInteractors().getRespondant()) {
                log("This Message is from Respondant: " + getInteractors().getRespondant().getName() + ", Msg: " + message);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public List<MapObject> detectItems() {
        return null;
    }

    protected boolean checkIfNotRunningOrPaused() {
        if (!this.getRunning()) {
            return true;
        }
        if (verifyState(BotState.PAUSE)) {
            return true;
        }
        return false;
    }

    public void displayCommands(Character chr) {
        List<String> hint = List.of(getChr().getName());
        SocialCommands.talkCygnusGuideCommands(chr, hint);
    }

    // Addressed without being named: a player in a party with this bot typed a line and the
    // Dispatcher is handing it to every same-party, same-map bot. Returns true when this bot
    // recognises the line as one of its own options (it then acts on its next tick, silently -
    // no hint balloon); false when it does not, so the Dispatcher may show a menu to one bot.
    // Bot types with an option menu override this; the rest ignore the line.
    public boolean offerKeyword(Character player, String content) {
        return false;
    }

    // Shouted party offer with nobody named: the player is a stranger (no shared party) standing
    // within viewport range and BotRecruitManager is broadcasting the line to every nearby bot.
    // Returns true when this bot answered, which counts against the per-shout reply cap; false
    // when it stayed quiet, so the slot is free for a bot that did speak.
    //
    // Default is silence: only bot types that actually recruit override this, so every other type
    // (merchants, dealers, guides...) is untouched by a shout.
    public boolean offerRecruit(Character player, String content) {
        return false;
    }

    //

    protected void checkForTrades() {
        boolean acceptedTrade = BotTradeLogic.checkTradeQueue(getChr());
        if (acceptedTrade) {
            tradeHandler.setTradePartner(tradeHandler.getTradePartnerRaw());
            debugprint("Accepted Trade");
        }
    }

    public void setTradeMode(BotTradeSM.TradeMode tradeMode) {
        this.currentTradeMode = tradeMode;
    }

    protected BotTradeSM.TradeMode getTradeMode() {
        return this.currentTradeMode;
    }

    protected void tradeInitialized(BotTradeSM.TradeMode tradeMode) {
        startTradeSM(tradeMode);
    }

    protected void startTradeSM() {
        if (botTradeSM == null) {
            botTradeSM = new BotTradeSM(this); // Create only when entering TRADING
        }
    }

    protected void startTradeSM(BotTradeSM.TradeMode mode) {
        botTradeSM = new BotTradeSM(this, mode);
    }

    protected void updateTradeSM() {
        botTradeSM.update(); // Continue trading logic
    }

    protected void discardTradeSM() {
        botTradeSM = null;
    }

    protected void cleanupTradeState() {
        botTradeSM = null;
        clearTradeRequest(getChr());
        tradeHandler.resetTradePartner();
        discardTradeSM();
    }

    public BotDialogueHandler getDialogueHandler() {
        return dialogueHandler;
    }

    public BotTradeInventory getTradeInventory() {
        return tradeInventory;
    }

    public BotTradeWants getTradeWants() {
        return tradeWants;
    }

    public void resetLastTradeResult() {
        lastTradeResult = null;
    }

    public void setLastTradeResult(Trade.TradeResult result) {
        lastTradeResult = result;
    }

    public Trade.TradeResult getLastTradeResult() {
        return lastTradeResult;
    }

    public void setLastTradedCharacter(Character character) {
        lastTradedCharacter = character;
    }

    public Character getLastTradedCharacter() {
        return lastTradedCharacter;
    }

    public void resetLastTradedCharacter() {
        lastTradedCharacter = null;
    }

    @Override
    public void onEvent(GameEvent event) {
        // Queue the event for processing later
        eventBuffer.add(event);
    }

    @Override
    public boolean matchesFilter(GameEvent event) {
        // Check if this event is relevant to this bot
        int targetWorld = getChr().getWorld();
        int targetChannel = getChr().getMap().getChannelServer().getId();
        MapleMap targetMap = getChr().getMap();
        if (event.getWorld() != targetWorld) {
            return false;
        }
        if (event.getChannel() != targetChannel) {
            return false;
        }
        if (targetMap != null && event.getMap() != targetMap) {
            return false;
        }
        return true;
    }

    // Call this from your event processing state
    public void processQueuedEvents() {
        GameEvent event = eventBuffer.poll();
        if (event != null) {
            handleEvent(event);
        }
    }

    public void handleEvent(GameEvent event) {
        // Process based on event type
        System.out.println("BotSM handleEvent");
        return;
    }

    public boolean isAvailableForAmbientActions() {
        return false;
    }

    public boolean hasQueuedEvents() {
        return !eventBuffer.isEmpty();
    }

}
