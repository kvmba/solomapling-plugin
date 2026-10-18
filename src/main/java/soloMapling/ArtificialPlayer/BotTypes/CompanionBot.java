package soloMapling.ArtificialPlayer.BotTypes;

import org.gms.client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.ArtificialPlayer.BotGrindSystem.GrindBrain;
import soloMapling.ArtificialPlayer.BotGrindSystem.GrindStyle;
import soloMapling.ArtificialPlayer.BotGrindSystem.GrindTickRegistry;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotMessagingSystem.ChatMessage;
import soloMapling.ArtificialPlayer.BotPartySystem.BotPartyQueue;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotTypeManager;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.ArtificialPlayer.LlmSystem.SocialLlmConfig;
import soloMapling.companion.CompanionRoster;
import soloMapling.companion.agent.AgentDecision;
import soloMapling.companion.agent.CompanionAction;
import soloMapling.companion.agent.CompanionBrain;
import soloMapling.companion.agent.CompanionDecisionActions;
import soloMapling.companion.agent.CompanionInteractionPolicy;
import soloMapling.companion.agent.CompanionInventoryPerception;
import soloMapling.companion.agent.CompanionStateSnapshot;
import soloMapling.companion.agent.CompanionUpdateGate;
import soloMapling.companion.agent.InviteTurnDeduplicator;
import soloMapling.companion.agent.ProductionCompanionBrain;
import soloMapling.companion.agent.ProactiveAttentionPolicy;
import soloMapling.companion.agent.TurnCoordinator;
import soloMapling.companion.execution.ActionExecutionResult;
import soloMapling.companion.execution.CompanionActionExecutor;
import soloMapling.companion.execution.CompanionCombatLifecycle;
import soloMapling.companion.execution.CompanionCombatRecoveryPolicy;
import soloMapling.companion.execution.CompanionRuntimeCapabilities;
import soloMapling.companion.execution.CompanionTargetResolver;
import soloMapling.companion.execution.CompanionTrainingController;
import soloMapling.companion.execution.SoloGrindController;
import soloMapling.companion.gear.CompanionGearController;
import soloMapling.companion.planner.CompanionPlannerResult;
import soloMapling.companion.survival.CompanionSurvivalController;
import soloMapling.server.BotTiming;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import soloMapling.Environment.BotMessages;

import static soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands.BotSpeak;
import static soloMapling.ArtificialPlayer.BotHelpers.isBot;

/**
 * Persistent companion controller. It remains the Character's BotSM while planner
 * actions compose movement and positioning capabilities around it.
 */
public final class CompanionBot extends BotSM implements
        CompanionTrainingController, GrindTickRegistry.Participant {

    private static final Logger log = LoggerFactory.getLogger(CompanionBot.class);
    private static final Duration SESSION_TIMEOUT = Duration.ofSeconds(45);
    // Planning is abandoned at the LLM's own timeout: a companion whose provider call is
    // configured at 5s would otherwise hold the turn (and refuse every other player) for the
    // remaining 7s while a result nobody is waiting for sits in the queue. The floor keeps a
    // misconfigured sub-second timeout from starving the turn before it starts.
    private static final Duration PLANNING_TIMEOUT =
            Duration.ofMillis(Math.max(2_000L, SocialLlmConfig.timeoutMs()));
    private static final Duration TURN_COOLDOWN = Duration.ofSeconds(2);
    private static final long COMBAT_REPAIR_COOLDOWN_MS = 15_000L;
    // Actions ActionValidator's cooldown rule holds back while a player owns this companion's
    // attention: it is already walking and fighting WITH that player, so the planner must not
    // also issue a follow / go-to that would compete for the same movement. Planning itself is
    // interruptible (the engine busy states are checked at execution), so SAY/EMOTE and the
    // party actions are not gated.
    private static final Set<CompanionAction.ActionType> BUSY_ACTIONS =
            Set.of(CompanionAction.ActionType.FOLLOW,
                    CompanionAction.ActionType.GO_TO,
                    CompanionAction.ActionType.TRAIN_WITH);
    // Resolved per use, not cached: a language switch invalidates the message pack, and a
    // companion spawned before it would otherwise keep answering in the old language.
    private static String fallbackReply() {
        return BotMessages.get("companion.fallback_reply");
    }

    private final CompanionBrain brain;
    private final CompanionActionExecutor actionExecutor;
    private final TurnCoordinator turns;
    private TurnContext activeContext;
    private long lastPlayedTurnId;

    // Reply channel for a directed line. It rides the queued message itself (TurnCoordinator.Message
    // .replyType), so it belongs to exactly that line's turn: it cannot leak into another player's
    // reply, survive a rejected line, or need cross-thread bookkeeping and cleanup. The turn body
    // runs on its own chain virtual thread, so a ThreadLocal carries the value to that turn's Say.
    private final ThreadLocal<org.gms.extension.event.ChatType> activeTurnReplyType =
            new ThreadLocal<>();
    private final ThreadLocal<Character> activeTurnReplyTarget = new ThreadLocal<>();

    private final GrindBrain grind = new GrindBrain(message -> { });
    private final SoloGrindController soloGrind = new SoloGrindController(grind);
    /** Whether this companion is currently on the shared combat sweep for solo grinding.
     *  Written from the state machine and read by the 250ms sweep, hence volatile. */
    private volatile boolean soloFightRegistered;
    private final CompanionCombatLifecycle combatLifecycle = new CompanionCombatLifecycle();
    private final CompanionSurvivalController survival =
            new CompanionSurvivalController();
    private final CompanionGearController gear = new CompanionGearController();
    private volatile Character trainingTarget;
    private volatile Boolean lastTrainingSameMap;
    private volatile String pendingInviteKey;
    private volatile long nextCombatDiagnosticAt;
    private volatile long nextCombatRepairAt;
    private volatile long nextProactiveScanAt;
    private volatile boolean proactiveLookupPending;
    private int proactiveCursor;
    private final Map<Integer, Instant> lastProactiveByPlayer = new java.util.concurrent.ConcurrentHashMap<>();
    private final InviteTurnDeduplicator inviteTurns = new InviteTurnDeduplicator();

    public CompanionBot(Character character) {
        this(character, ProductionCompanionBrain.createDefault());
    }

    public CompanionBot(Character character, CompanionBrain brain) {
        this(character, brain, new CompanionActionExecutor(),
                new TurnCoordinator(SESSION_TIMEOUT, PLANNING_TIMEOUT, TURN_COOLDOWN));
    }

    CompanionBot(
            Character character,
            CompanionBrain brain,
            CompanionActionExecutor actionExecutor,
            TurnCoordinator turns) {
        super(character);
        this.brain = java.util.Objects.requireNonNull(brain, "brain");
        this.actionExecutor = java.util.Objects.requireNonNull(actionExecutor, "actionExecutor");
        this.turns = java.util.Objects.requireNonNull(turns, "turns");
        // A companion trains around its player rather than owning a stationary
        // TrainingBot camp. CAMP can starve combat on crowded maps while it
        // repeatedly contests occupied or unreachable spots.
        this.grind.forceStyle(GrindStyle.ROAM);
        botType = "CompanionBot";
    }

    /** Explicit provisioning hook; profiles are never auto-started by roster discovery. */
    public static CompanionBot attach(Character character, CompanionBrain brain) {
        CompanionBot bot = new CompanionBot(character, brain);
        CompanionRoster.register(character.getId());
        CharacterStorage.addActiveBot(character.getId(), bot);
        return bot;
    }

    /** Explicit attach/start hook for a command or future lifecycle coordinator. */
    public static CompanionBot attachAndStart(Character character, CompanionBrain brain) {
        CompanionBot bot = attach(character, brain);
        BotTypeManager.manuallyStartBot(character);
        return bot;
    }

    /** Dispatcher entry point. This method only enqueues immutable message data. */
    public boolean enqueuePlayerMessage(Character player, String content) {
        return enqueuePlayerMessage(player, content, null);
    }

    /**
     * Enqueues one player line with the channel its reply must go back on (null = map bubble). The
     * channel rides the queued message, so it is answered on exactly that line's turn.
     */
    public boolean enqueuePlayerMessage(Character player, String content,
                                        org.gms.extension.event.ChatType replyType) {
        if (player == null || content == null || content.isBlank()
                || content.trim().length() > CompanionBrain.MAX_PLAYER_MESSAGE_LENGTH) {
            log.debug("Companion chat enqueue rejected cid={} playerCid={} length={}",
                    getChr() == null ? -1 : getChr().getId(),
                    player == null ? -1 : player.getId(),
                    content == null ? -1 : content.trim().length());
            return false;
        }
        boolean accepted =
                turns.enqueue(new TurnCoordinator.Message(player.getId(), content, replyType));
        log.info("Companion chat enqueue cid={} playerCid={} accepted={} state={}",
                getChr() == null ? -1 : getChr().getId(), player.getId(), accepted, turns.state());
        log.debug("Companion chat input cid={} playerCid={} message={}",
                getChr() == null ? -1 : getChr().getId(), player.getId(), content.trim());
        return accepted;
    }

    public boolean acceptsContinuation(Character player) {
        Character companion = getChr();
        return player != null
                && companion != null
                && companion.getMap() != null
                && CompanionInteractionPolicy.allowsContinuation(
                        turns.acceptsContinuation(player.getId()),
                        isBot(player),
                        player.getMap() != null,
                        companion.getMapId(),
                        player.getMapId());
    }

    // A whisper / party / guild line addressed straight to the companion. Feeds the same turn
    // pipeline as a map line - the brain already perceives cross-map players, so a directed line
    // is one more input. The map-proximity gate that governs ambient continuation is deliberately
    // NOT applied here: the player chose to address the companion directly.
    //
    // The reply channel rides the queued line itself (enqueuePlayerMessage's replyType). It is
    // ALWAYS the channel the line arrived on - never nulled for a same-map speaker: a whisper is
    // private to its two participants by definition, and the host publishes artificial-recipient
    // chat events regardless of whether sender and bot share a map (WhisperHandler /
    // MultiChatHandler publishToArtificialRecipients have no same-map gate), so a same-map whisper
    // is as reachable - and as private - as a cross-map one. Nulling it here made the answer fall
    // back to the map bubble (SocialCommands.BotReply with type == null), broadcasting the
    // player's private line to everyone on the map. Carrying it on the message is what makes it
    // exact - it applies to that line's turn only, cannot leak into another player's reply, and
    // needs no per-player bookkeeping or cleanup.
    @Override
    protected void onDirectChat(ChatMessage message) {
        if (message == null || !getRunning()) {
            return;
        }
        enqueuePlayerMessage(message.getSender(), message.getContent(), message.getChatType());
    }

    // The channel this turn answers on. Armed in playDecision from the planned turn's own message
    // (never bot-wide state), so concurrent turns - two chats, or a chat plus a party-invite turn
    // that bypasses session ownership - can never cross wires.
    @Override
    protected org.gms.extension.event.ChatType replyChannel() {
        return activeTurnReplyType.get();
    }

    @Override
    protected Character replyChannelTarget() {
        return activeTurnReplyTarget.get();
    }

    public TurnCoordinator.State companionState() {
        return turns.state();
    }

    @Override
    public void updateState() {
        super.updateState();
        CompanionUpdateGate.runUnlessInactive(
                checkIfNotRunningOrPaused(), this::updateActiveState);
    }

    private void updateActiveState() {
        if (getChr() == null || getChr().getMap() == null) {
            return;
        }
        // Player turns remain responsive while supply/gear controllers own movement.
        turns.tick(this::plan, this::execute);
        if (survival.tick(getChr())) {
            return;
        }
        boolean allowShopStart = trainingTarget == null
                || getChr().getLevel() > 40
                || gear.gearRunActive();
        if (gear.tick(
                getChr(), CompanionRuntimeCapabilities.dropSources(), allowShopStart)) {
            return;
        }
        maintainTraining();
        maintainSoloGrind();
        observePendingInvite();
        maybeInitiateConversation();
    }

    /**
     * Training the companion does for itself, when no player asked it to train
     * with them. Runs behind the player-authorized path: a companion that is
     * somebody's tag-along stays their tag-along.
     */
    private void maintainSoloGrind() {
        if (trainingTarget != null) {
            // A player owns its attention; the solo claim is released by
            // beginTrainingWith's sequenced hand-off, so nothing to do here —
            // and touch NOTHING below: soloGrind.stop() would release the
            // GrindBrain claim the live training episode now owns.
            return;
        }
        soloGrind.tick(getChr());
        // The combat sweep is shared, and registering is what makes real
        // fighting happen on an observed map. Register only for the phase that
        // actually fights: a companion walking to its hunting ground, or resting
        // between sessions, has nothing for the sweep to do and would be woken
        // four times a second to decline.
        boolean fights = getChr() != null
                && soloGrind.isGrindingOn(getChr().getMapId());
        if (fights != soloFightRegistered) {
            soloFightRegistered = fights;
            if (fights) {
                GrindTickRegistry.getInstance().register(this);
            } else {
                GrindTickRegistry.getInstance().unregister(this);
            }
        }
    }

    private void maybeInitiateConversation() {
        long nowMillis = System.currentTimeMillis();
        if (nowMillis < nextProactiveScanAt || proactiveLookupPending
                || turns.state() != TurnCoordinator.State.ROUTINE
                || trainingTarget != null || survival.supplyRunActive()
                || getState() == BotState.TRADING) {
            return;
        }
        nextProactiveScanAt = nowMillis + 30_000L;
        List<Character> players = getChr().getMap().getAllPlayers().stream() // snapshot, not the live set
                .filter(candidate -> candidate != null && !isBot(candidate)
                        && candidate.getMapId() == getChr().getMapId())
                .sorted(java.util.Comparator.comparingInt(Character::getId))
                .toList();
        if (players.isEmpty()) {
            // Nobody to greet, so every recorded "last proactive" is stale by definition - the map
            // it belonged to has no audience. Clearing here bounds the map by the players actually
            // present instead of by every id that ever walked by. Safe: shouldInitiate takes the
            // LATER of lastInteractionAt and lastProactiveAt as its baseline, so dropping the
            // proactive timestamp can only make the cooldown stricter, never looser.
            lastProactiveByPlayer.clear();
            return;
        }
        Character candidate = players.get(Math.floorMod(proactiveCursor++, players.size()));
        int companionId = getChr().getId();
        int playerId = candidate.getId();
        int mapId = getChr().getMapId();
        Instant now = Instant.ofEpochMilli(nowMillis);
        proactiveLookupPending = true;
        brain.relationship(companionId, playerId).whenComplete((relationship, error) -> {
            proactiveLookupPending = false;
            if (error != null || relationship == null
                    || !ProactiveAttentionPolicy.shouldInitiate(
                            relationship, now, lastProactiveByPlayer.get(playerId), true)) {
                return;
            }
            Character companion = getChr();
            if (companion == null || companion.getMap() == null || companion.getMapId() != mapId
                    || candidate.getMap() == null || candidate.getMapId() != mapId
                    || turns.state() != TurnCoordinator.State.ROUTINE
                    || trainingTarget != null || survival.supplyRunActive()) {
                return;
            }
            lastProactiveByPlayer.put(playerId, Instant.now());
            turns.enqueueEvent(new TurnCoordinator.Message(
                    playerId,
                    "Proactive social moment with character " + playerId
                            + ". Start one brief, natural conversation grounded in shared memories "
                            + "or invite them to help with the current equipment goal. Do not claim they spoke first."));
            nudgeSoon(0);
            log.info("Companion proactive conversation queued cid={} playerCid={} map={}",
                    companionId, playerId, mapId);
        });
    }

    private java.util.concurrent.CompletionStage<CompanionPlannerResult> plan(
            TurnCoordinator.Message message) {
        Perception perception = snapshotPerception(message.playerCharacterId());
        CompanionBrain.TurnRequest request = new CompanionBrain.TurnRequest(
                getChr().getId(),
                message.playerCharacterId(),
                message.content(),
                perception.state());
        activeContext = new TurnContext(request, perception.resolver(), System.nanoTime());
        log.info("Companion planning started cid={} playerCid={} map={} sameMapPlayers={}",
                request.companionCharacterId(), request.playerCharacterId(),
                perception.state().currentMapId(), perception.state().sameMapCharacterIds().size());
        log.debug("Companion perception cid={} snapshot={}",
                request.companionCharacterId(), perception.state());
        return brain.plan(request);
    }

    private void execute(TurnCoordinator.PlannedTurn planned) {
        TurnContext context = activeContext;
        activeContext = null;
        if (context == null
                || context.request().playerCharacterId() != planned.message().playerCharacterId()) {
            BotSpeak(getChr(), fallbackReply());
            return;
        }
        // A companion that answers the instant planning returns sounds like a script, so the whole
        // decision plays after a human read-and-type beat. It runs off the tick on one chain: the
        // actions stay in decision order, and the perception snapshot stays the authority boundary.
        // Latency is measured here rather than after the pause: it is provider latency, and folding
        // the typing beat into it would report every turn 3-15s slower than the brain really was.
        long planningLatencyMs =
                Math.max(0L, (System.nanoTime() - context.startedNanos()) / 1_000_000L);
        // The beat scales with what the companion is about to SAY, not with what the player typed:
        // a human's reply time is set by their own line. Using the player's text had it backwards -
        // a long player message made the bot slower, a one-word "hi" made it answer instantly.
        long typingBeatMs = BotTiming.typingPauseFor(plannedReply(planned));
        // The gate must also cover the bot being stopped or converted to another type: the Character
        // keeps its map through both, so a map-only check would let a dead CompanionBot keep
        // executing its old decision (including ACCEPT_PARTY) seconds after it was replaced.
        BotTiming.chain()
                .stopUnless(() -> getChr() != null && getChr().getMap() != null && getRunning())
                .pause(typingBeatMs)
                .run(() -> playDecision(context, planned, planningLatencyMs))
                .start();
    }

    /** The line this turn will speak, for pacing the reply; the fallback when planning failed. */
    private static String plannedReply(TurnCoordinator.PlannedTurn planned) {
        if (planned.result() instanceof CompanionPlannerResult.Success success) {
            String reply = success.decision().reply();
            return reply == null ? fallbackReply() : reply;
        }
        return fallbackReply();
    }

    // Plays one planned turn. The delay is random and longer than the turn cooldown, so a second
    // message from the same player can be planned and played while this beat is still pending -
    // its turn id is the tiebreak that keeps the bot from answering the older line last. A real
    // player catching up on two lines answers the newer one anyway, so the stale turn is dropped.
    //
    // Deliberately NOT synchronized as a whole: the engine actions below can call back into this
    // bot's own synchronized methods (beginTrainingWith via TrainWith), and holding the monitor
    // across them would block the tick thread's stopScheduledTask/stopTraining for the whole
    // action - which, with the reply now delayed seconds, is a long time to hold it. Only the
    // claim itself needs to be atomic.
    private void playDecision(
            TurnContext context, TurnCoordinator.PlannedTurn planned, long elapsedMs) {
        if (!claimTurn(planned)) {
            return;
        }
        // Arm this turn's reply channel from its own queued line (null = map bubble), and resolve the
        // speaker the same way the resolver would. Nothing bot-wide is written, so a concurrent turn
        // cannot inherit this one's channel.
        //
        // A directed line whose speaker is gone is dropped whole, never spoken on the map bubble:
        // a reply to a vanished whisperer is private by the same rule the line itself was (see
        // onDirectChat), and falling back to the map bubble would broadcast it to everyone here.
        // That fallback existed once before and was the exact leak the message-carried channel
        // was introduced to close - resolveSpeaker returning null must not reopen it.
        org.gms.extension.event.ChatType replyType = planned.message().replyType();
        Character replyTarget = replyType == null ? null
                : resolveSpeaker(planned.message().playerCharacterId());
        if (replyType != null && replyTarget == null) {
            log.info("Companion reply dropped: directed speaker vanished cid={} playerCid={} channel={}",
                    getChr() == null ? -1 : getChr().getId(),
                    planned.message().playerCharacterId(), replyType);
            brain.record(new CompanionBrain.CompletedTurn(
                    context.request(), planned.result(), List.of()));
            return;
        }
        if (replyType != null) {
            activeTurnReplyType.set(replyType);
            activeTurnReplyTarget.set(replyTarget);
        }
        try {
            List<ActionExecutionResult> executions = new ArrayList<>();
            CompanionPlannerResult result = planned.result();
            if (result instanceof CompanionPlannerResult.Success success) {
                AgentDecision decision = success.decision();
                // Same call, same inputs as the planner service's validation: the request's
                // message and speaker id are immutable, so this list IS the validated list.
                List<CompanionAction> executed = CompanionDecisionActions.forExecution(
                        decision,
                        context.request().playerMessage(),
                        context.request().playerCharacterId());
                log.info("Companion planning succeeded cid={} playerCid={} latencyMs={} actions={}",
                        context.request().companionCharacterId(),
                        context.request().playerCharacterId(), elapsedMs, executed.size());
                log.debug("Companion decision cid={} reason={} reply={} actions={}",
                        context.request().companionCharacterId(),
                        decision.reason(), decision.reply(), decision.actions());
                for (CompanionAction action : executed) {
                    ActionExecutionResult execution =
                            actionExecutor.execute(action, getChr(), this, context.resolver());
                    executions.add(execution);
                    if (execution.status() == ActionExecutionResult.Status.SUCCESS
                            || execution.status() == ActionExecutionResult.Status.DEFERRED) {
                        log.info("Companion action cid={} type={} status={} code={}",
                                context.request().companionCharacterId(), action.type(),
                                execution.status(), execution.reasonCode());
                    } else {
                        log.warn("Companion action cid={} type={} status={} code={} reason={}",
                                context.request().companionCharacterId(), action.type(),
                                execution.status(), execution.reasonCode(), execution.reason());
                    }
                }
            } else if (result instanceof CompanionPlannerResult.Failure failure) {
                log.warn("Companion planning fallback cid={} playerCid={} latencyMs={} type={} message={} violations={}",
                        context.request().companionCharacterId(),
                        context.request().playerCharacterId(), elapsedMs,
                        failure.type(), failure.message(), failure.violations().size());
                ActionExecutionResult execution = actionExecutor.execute(
                        new CompanionAction.Say(fallbackReply()),
                        getChr(),
                        this,
                        context.resolver());
                executions.add(execution);
            }
            brain.record(new CompanionBrain.CompletedTurn(context.request(), result, executions));
        } finally {
            activeTurnReplyType.remove();
            activeTurnReplyTarget.remove();
        }
    }

    // The speaker a directed line came from, resolved live from the bot's own world (the player may
    // have walked off since). Null when the character is gone - the caller then drops the whole
    // reply rather than leaking a private-conversation line onto the map bubble.
    private Character resolveSpeaker(int playerCharacterId) {
        Character self = getChr();
        return self == null || self.getWorldServer() == null
                ? null
                : self.getWorldServer().getPlayerStorage().getCharacterById(playerCharacterId);
    }

    // Atomically claims this turn as the newest one played. False when a newer turn has already
    // played, which drops the stale reply instead of letting it land after the newer answer.
    private synchronized boolean claimTurn(TurnCoordinator.PlannedTurn planned) {
        if (planned.turnId() < lastPlayedTurnId) {
            log.info("Companion stale turn dropped playerCid={} turnId={} supersededBy={}",
                    planned.message().playerCharacterId(), planned.turnId(), lastPlayedTurnId);
            return false;
        }
        lastPlayedTurnId = planned.turnId();
        return true;
    }

    @Override
    public synchronized ActionExecutionResult beginTrainingWith(Character target) {
        if (target == null || target.getMap() == null || target.getMapId() != getChr().getMapId()) {
            return ActionExecutionResult.rejected(
                    "TRAINING_TARGET_MOVED", "Training target is no longer on the companion's map");
        }
        stopTraining();
        // Ownership hand-off, sequenced: the solo grind shares this bot's ONE GrindBrain and ONE
        // combat-sweep seat with the training episode, so the solo claim must be released BEFORE
        // the training episode starts. Stopping it afterwards (the next macro tick's job, before
        // this reorder) released the freshly-started training claim and flipped the registration
        // mirror against a sweep the training combat still needed - the training episode then had
        // no driver at all until the target re-crossed a map.
        soloGrind.stop(getChr());
        trainingTarget = target;
        lastTrainingSameMap = true;
        nextCombatRepairAt = 0L;
        GCMovement.follow(getChr(), target);
        combatLifecycle.start(this::activateTrainingCombat);
        log.info("Companion training started cid={} targetCid={} map={} following={}",
                getChr().getId(), target.getId(), getChr().getMapId(),
                GCMovement.isFollowing(getChr()));
        return ActionExecutionResult.success(
                "TRAINING_STARTED",
                "Companion-owned GrindBrain combat started with the authorized target");
    }

    @Override
    public synchronized void stopTraining() {
        Character previousTarget = trainingTarget;
        boolean wasActive = combatLifecycle.active();
        combatLifecycle.stop(this::deactivateTrainingCombat);
        trainingTarget = null;
        lastTrainingSameMap = null;
        nextCombatRepairAt = 0L;
        if (previousTarget != null || wasActive) {
            log.info("Companion training stopped cid={} targetCid={} wasActive={}",
                    getChr() == null ? -1 : getChr().getId(),
                    previousTarget == null ? -1 : previousTarget.getId(), wasActive);
        }
    }

    @Override
    public synchronized void stopForRest() {
        stopTraining();
        // The solo grind is a movement-resuming activity like the others, so the interface
        // contract ("stops every high-level activity that would resume movement after REST")
        // covers it too. Stopping only training left a resting companion's GrindBrain swinging:
        // the 250ms combat sweep is driven by GrindTickRegistry, not by the FSM tick that
        // waitFor() suspends, so REST/GOODBYE alone never reached it.
        soloGrind.stop(getChr());
        survival.cancel();
        gear.cancel();
    }

    @Override
    public void grindTick() {
        Character companion = getChr();
        Character target = trainingTarget;
        if (checkIfNotRunningOrPaused() || companion == null
                || companion.getMap() == null
                || survival.supplyRunActive() || gear.gearRunActive()) {
            return;
        }
        if (target == null) {
            // Nobody asked it to train with them: this is the solo case, where
            // the companion fights for itself. Only on observed maps — when no
            // player can see it, SoloGrindController credits the same time
            // arithmetically instead of paying for combat nobody watches.
            // isGrindingOn also carries the phase check: a companion walking to
            // its ground, or resting between sessions, is not fighting here.
            if (!soloGrind.isGrindingOn(companion.getMapId())
                    || !GCMovement.isMapObserved(companion.getMapId())) {
                // The sweep runs at 4Hz while the state machine that would
                // unregister us runs every few seconds, so drop off here rather
                // than waiting a whole tick to be told we are done.
                if (soloFightRegistered) {
                    soloFightRegistered = false;
                    GrindTickRegistry.getInstance().unregister(this);
                }
                return;
            }
            grind.tick(companion);
            return;
        }
        if (!combatLifecycle.active() || target.getMap() == null
                || companion.getMapId() != target.getMapId()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (nextCombatDiagnosticAt <= now) {
            nextCombatDiagnosticAt = now + 5_000L;
            log.info("Companion combat tick cid={} map={} position={} monsters={} observed={} style={} spot={} progressAgeMs={}",
                    companion.getId(), companion.getMapId(), companion.getPosition(),
                    companion.getMap().getAllMonsters().size(),
                    GCMovement.isMapObserved(companion.getMapId()),
                    grind.activeStyle(), grind.spotLabel(), grind.msSinceProgress());
        }
        long progressAgeMs = grind.msSinceProgress();
        boolean mapObserved = GCMovement.isMapObserved(companion.getMapId());
        CompanionCombatRecoveryPolicy.Recovery recovery =
                CompanionCombatRecoveryPolicy.choose(
                        progressAgeMs, now >= nextCombatRepairAt);
        if (recovery == CompanionCombatRecoveryPolicy.Recovery.REPATH
                && !status().isFrozen() // a frozen bot lands no hits; that stall is the debuff, not a wedge
                && !companion.getMap().getAllMonsters().isEmpty()) {
            nextCombatRepairAt = now + COMBAT_REPAIR_COOLDOWN_MS;
            GCMovement.stop(companion);
            log.info("Companion combat repathing cid={} targetCid={} map={} progressAgeMs={}",
                    companion.getId(), target.getId(), companion.getMapId(), progressAgeMs);
            grind.resetAfterStall(companion);
            return;
        }
        grind.tick(companion);
    }

    @Override
    public synchronized void stopScheduledTask() {
        stopTraining();
        soloGrind.stop(getChr());
        soloFightRegistered = false;
        GrindTickRegistry.getInstance().unregister(this);
        survival.cancel();
        gear.cancel();
        GCMovement.disable(getChr());
        super.stopScheduledTask();
    }

    /** Called by the party invite bridge; updateState performs the actual planning enqueue. */
    public void onPartyInvite(Character inviter, int partyId) {
        if (inviter != null) {
            pendingInviteKey = inviteKey(inviter.getId(), partyId);
            nudgeSoon(0);
        }
    }

    private void maintainTraining() {
        Character companion = getChr();
        Character target = trainingTarget;
        if (target == null) {
            return;
        }
        if (survival.supplyRunActive() || gear.gearRunActive()) {
            return;
        }
        boolean online = target.getMap() != null;
        boolean sameMap = online && companion.getMapId() == target.getMapId();
        Boolean previousSameMap = lastTrainingSameMap;
        if (previousSameMap == null || previousSameMap != sameMap) {
            log.info("Companion training target transition cid={} targetCid={} online={} sameMap={} companionMap={} targetMap={}",
                    companion.getId(), target.getId(), online, sameMap,
                    companion.getMapId(), online ? target.getMapId() : -1);
            lastTrainingSameMap = sameMap;
        }
        combatLifecycle.reconcile(
                online,
                sameMap,
                this::deactivateTrainingCombat,
                () -> GCMovement.follow(companion, target),
                this::activateTrainingCombat);
    }

    private void activateTrainingCombat() {
        Character companion = getChr();
        Character target = trainingTarget;
        if (companion == null || target == null
                || companion.getMap() == null || target.getMap() == null
                || companion.getMapId() != target.getMapId()) {
            throw new IllegalStateException("Training combat activation requires a live same-map target");
        }
        GCMovement.setGrinding(companion, true);
        grind.start(companion);
        nextCombatDiagnosticAt = 0L;
        GrindTickRegistry.getInstance().register(this);
        log.debug("Companion combat activated cid={} targetCid={} map={}",
                companion.getId(), target.getId(), companion.getMapId());
    }

    private void deactivateTrainingCombat() {
        GrindTickRegistry.getInstance().unregister(this);
        Character companion = getChr();
        if (companion != null) {
            grind.release(companion);
            GCMovement.setGrinding(companion, false);
            log.debug("Companion combat deactivated cid={} map={}",
                    companion.getId(), companion.getMapId());
        }
    }

    private void observePendingInvite() {
        BotPartyQueue.PartyInviteEntry pending =
                BotPartyQueue.getInstance().getPartyInvite(getChr());
        if (pending == null || pending.getInviter() == null) {
            pendingInviteKey = null;
            inviteTurns.clear();
            return;
        }
        Character inviter = pending.getInviter();
        String key = inviteKey(inviter.getId(), pending.getPartyId());
        pendingInviteKey = key;
        if (!inviteTurns.shouldPlan(key)) {
            return;
        }
        String prompt = "Party invite event from character " + inviter.getId()
                + ". Decide whether to ACCEPT_PARTY from that character or say GOODBYE.";
        turns.enqueueEvent(new TurnCoordinator.Message(inviter.getId(), prompt));
        log.info("Companion party invite queued cid={} inviterCid={} partyId={}",
                getChr().getId(), inviter.getId(), pending.getPartyId());
    }

    private Perception snapshotPerception(int planningPlayerId) {
        Character companion = getChr();
        int mapId = companion.getMapId();
        Map<Integer, Character> allowedPlayers = new HashMap<>();
        for (Character candidate : companion.getMap().getAllPlayers()) { // snapshot: safe on the plan thread
            if (candidate != null && !isBot(candidate) && candidate.getMapId() == mapId) {
                allowedPlayers.put(candidate.getId(), candidate);
            }
        }
        Set<Integer> sameMapPlayerIds = Set.copyOf(allowedPlayers.keySet());
        BotPartyQueue.PartyInviteEntry pending =
                BotPartyQueue.getInstance().getPartyInvite(companion);
        Character pendingInviter = pending == null ? null : pending.getInviter();
        boolean planningPendingInvite = pendingInviter != null
                && pendingInviter.getId() == planningPlayerId
                && inviteKey(pendingInviter.getId(), pending.getPartyId()).equals(pendingInviteKey);
        // Who this turn may talk about. sameMapCharacterIds stays strictly same-map
        // (ActionValidator.requireSameMap gates the movement actions on it), while the memory and
        // relationship disclosure layer is deliberately wider: a whisper reaches the companion
        // from ANY map, so the speaker's own history must survive the turn or a cross-map whisper
        // is answered with "Relevant memories: - none" and no relationship row - total amnesia
        // about the very person addressing it.
        java.util.Set<Integer> targetIds = new java.util.HashSet<>(sameMapPlayerIds);
        if (planningPendingInvite) {
            targetIds.add(pendingInviter.getId());
        }
        targetIds.add(planningPlayerId);
        CompanionStateSnapshot state = new CompanionStateSnapshot(
                mapId,
                sameMapPlayerIds,
                companion.getParty() != null,
                Set.of(mapId), // No knowledge repository yet: current map is the complete allowlist.
                Set.copyOf(targetIds),
                trainingTarget != null ? BUSY_ACTIONS : Set.of(),
                soloGrind.engaged(),
                CompanionInventoryPerception.snapshot(companion),
                gear.cachedGoal().map(goal -> new soloMapling.companion.agent.CompanionGearGoal(
                        goal.itemId(),
                        goal.itemName(),
                        goal.slot().name(),
                        goal.requiredLevel(),
                        goal.mobId(),
                        goal.mobName(),
                        goal.mapId(),
                        goal.chance(),
                        goal.boss())),
                Set.of());
        CompanionTargetResolver resolver = new SnapshotResolver(
                mapId,
                Map.copyOf(allowedPlayers),
                state.knownMapIds(),
                planningPendingInvite ? pendingInviter : null);
        return new Perception(state, resolver);
    }

    private record Perception(
            CompanionStateSnapshot state,
            CompanionTargetResolver resolver) {
    }

    private record TurnContext(
            CompanionBrain.TurnRequest request,
            CompanionTargetResolver resolver,
            long startedNanos) {
    }

    private record SnapshotResolver(
            int sourceMapId,
            Map<Integer, Character> characters,
            Set<Integer> maps,
            Character pendingInviter) implements CompanionTargetResolver {
        @Override
        public Optional<Character> resolveCharacter(int characterId) {
            Character target = characters.get(characterId);
            if (target == null || target.getMap() == null || target.getMapId() != sourceMapId
                    || isBot(target)) {
                return Optional.empty();
            }
            return Optional.of(target);
        }

        @Override
        public Optional<Character> resolveCharacterFor(
                CompanionAction.ActionType actionType, int characterId) {
            // The captured inviter is exempt from the same-map rule (an invite outlives the
            // inviter walking away) but NOT from the artificial-character rule every other
            // resolver path applies: a party invite's originator is a real player by the host's
            // own contract (InviteCoordinator.createInvite(type, from, partyId, target)), so a
            // bot target here can only be a stale queue entry - rejecting it keeps this
            // exception consistent with resolveCharacter instead of silently widening it.
            if (actionType == CompanionAction.ActionType.ACCEPT_PARTY
                    && pendingInviter != null && pendingInviter.getId() == characterId
                    && !isBot(pendingInviter)) {
                // The executor independently verifies the live queue entry. This resolver
                // only grants the captured inviter for ACCEPT_PARTY.
                return Optional.of(pendingInviter);
            }
            return resolveCharacter(characterId);
        }

        @Override
        public boolean allowsMap(int mapId) {
            return maps.contains(mapId);
        }
    }

    private static String inviteKey(int inviterId, int partyId) {
        return inviterId + ":" + partyId;
    }

    @Override
    public boolean allowsMount() {
        return true; // 持久化
    }
}
