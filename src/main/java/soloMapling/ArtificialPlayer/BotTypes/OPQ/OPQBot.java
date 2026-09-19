package soloMapling.ArtificialPlayer.BotTypes.OPQ;

import org.gms.client.Character;
import org.gms.server.maps.MapItem;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.MapObject;
import org.gms.server.maps.Reactor;
import soloMapling.ArtificialPlayer.BotCommandsPack.BotAttack;
import soloMapling.ArtificialPlayer.BotCommandsPack.DropCommands;
import soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands;
import soloMapling.ArtificialPlayer.BotGeneration;
import soloMapling.ArtificialPlayer.BotLogic;
import soloMapling.ArtificialPlayer.BotMessagingSystem.ChatMessage;
import soloMapling.ArtificialPlayer.BotMessagingSystem.MessageQueue;
import soloMapling.ArtificialPlayer.BotMovementSystem.MovementCommands;
import soloMapling.ArtificialPlayer.BotPartySystem.BotPartyLogic;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.PartyQuest.PqActions;
import soloMapling.ArtificialPlayer.BotTypes.OPQ.OPQSharedContext.OPQPhase;
import soloMapling.Environment.BotMessages;
import soloMapling.Environment.EnvironmentManager;
import soloMapling.Environment.PlatformPlacement;
import soloMapling.MapVFX.CustomReactor;
import soloMapling.server.BotTiming;

import java.awt.Point;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static soloMapling.ArtificialPlayer.BotCommandsPack.WarpCommands.botWarpMapOnPortal;
import static soloMapling.ArtificialPlayer.BotCommandsPack.WarpCommands.mapForBot;
import static soloMapling.ArtificialPlayer.BotGeneration.warpBotToLocation;
import static soloMapling.ArtificialPlayer.BotHelpers.blockingSleep;
import static soloMapling.ArtificialPlayer.BotTypes.OPQ.OPQConstants.STAGE_1_COMPLETE_TP;
import static soloMapling.ArtificialPlayer.BotTypes.OPQ.OPQConstants.STAGE_1_ENTRY_TP;
import static soloMapling.BotLogger.log;
import static soloMapling.DebugUtilities.debugprint;

/**
 * Orbis Party Quest rush-bot state machine.
 * <p>
 * Design:
 * - Perception-driven. The bot decides transitions from its own observable
 * state (current mapId, position, inventory) plus orchestrator blackboard
 * reads. It never accepts commands from other bots.
 * - Map change is authoritative for major phase swaps (lobby -> stage 1 ->
 * tower -> stage 2 -> exit lobby). When the game teleports the party, the
 * mapId flips and the bot rehomes itself via {@link #detectPhaseFromMap()}.
 * - Per-stage work is a short loop: navigate -> hit target -> loot -> return
 * -> drop -> wait. The wait state flips to TRANSITION once the orchestrator
 * (or a map change) signals the stage is done.
 * - All waits are time-boxed against {@link OPQConstants#STAGE_WAIT_TIMEOUT_MS}
 * so a stuck bot eventually falls back to LOOP_CHECK instead of hanging.
 */
public class OPQBot extends BotSM {

    // volatile: the swing chains run off-tick and read/write these
    private volatile OPQBotState opqBotState = OPQBotState.RESET;
    private final OPQOrchestrator orchestrator;
    private final OPQSharedContext sharedContext;
    private List<String> hint = Collections.singletonList(getChr().getName());

    // Per-state timers / scratch fields
    private long stageWaitStartTime;
    private long lastRecruitMessageAt;
    private volatile int reactorHitsThisTarget;

    /** Stage 5: which of the lounge's four sub-rooms to enter next (advances on each visit). */
    private int loungeVisit;

    private volatile int lootedRecordItemId = -1;

    public OPQBot(Character character) {
        super(character);
        dialoguePath = "OPQBotDialogue.yaml"; // TODO: add YAML or fall back gracefully
        botType = "OPQBot";
        // Move off the shared per-channel client before doing any engine work: the PQ's
        // reactor callbacks fire seconds after the call that arms them, on a timer thread,
        // and read client.getPlayer() when they do. On the shared client that character is
        // whatever bot last bound itself - or null - so the altar's spawnNpc would silently
        // do nothing. See BotGeneration.adoptPrivateClient.
        BotGeneration.adoptPrivateClient(character);
        this.orchestrator = OPQOrchestrator.getInstance();
        this.sharedContext = orchestrator.getSharedContext();
        orchestrator.registerBot(this);
    }

    // =========================================================================
    // State machine plumbing
    // =========================================================================

    private void setOPQBotState(OPQBotState state) {
        this.opqBotState = state;
    }

    /**
     * Dev-only setter used by the !opq forcestate command.
     */
    public void setStateForDebug(OPQBotState state) {
        OPQBotState prev = this.opqBotState;
        this.opqBotState = state;
        log(String.format("[OPQBot %s] DEBUG forced %s -> %s",
                getChr().getName(), prev, state));
    }

    /**
     * Read the current top-level state (for dev tooling / dump command).
     */
    public OPQBotState getOPQBotState() {
        return opqBotState;
    }

    /**
     * Logged state transition. Use this instead of setOPQBotState(...) for any
     * transition you want to see in BotLog.txt. The reason argument is the
     * single most useful field for tracing why a bot moved — write it as a
     * short clause: "arrived at platform m3", "stage1Complete flag flipped",
     * "wait timed out", etc.
     */
    private void transitionTo(OPQBotState next, String reason) {
        OPQBotState prev = this.opqBotState;
        if (prev == next) {
            return;
        }
        this.opqBotState = next;
        log(String.format("[OPQBot %s] %s -> %s | %s | map=%d pos=%s",
                getChr().getName(), prev, next, reason,
                getChr().getMapId(), getChr().getPosition()));
        debugprint("[OPQBot]", getChr().getName(), prev, "->", next, "|", reason);
    }

    public enum OPQBotState {
        RESET,
        RECRUITMENT,
        IN_PARTY_IDLE,
        STAGE_1_NAVIGATE,
        STAGE_1_HIT_REACTOR,
        STAGE_1_LOOT,
        STAGE_1_RETURN,
        STAGE_1_DROP_ITEMS,
        STAGE_1_WAIT,
        STAGE_1_TRANSITION,
        STAGE_1_TRANSITION_PT_2,
        MIDDLE_STAGE,
        STAGE_2_NAVIGATE,
        STAGE_2_HIT_BOX,
        STAGE_2_LOOT,
        STAGE_2_RETURN,
        STAGE_2_DROP_ITEMS,
        STAGE_2_WAIT,
        EXIT_DETECT,
        EXIT_LOBBY,
        LOOP_CHECK
    }

    private void resetOPQBotState() {
        setOPQBotState(OPQBotState.RESET);
        hint = Collections.singletonList(getChr().getName());
        stageWaitStartTime = 0;
        lastRecruitMessageAt = 0;
        reactorHitsThisTarget = 0;
        lootedRecordItemId = -1;
        loungeVisit = 0;
    }

    // =========================================================================
    // Main tick
    // =========================================================================

    @Override
    public void updateState() {
        super.updateState();
        if (checkIfNotRunningOrPaused()) {
            return;
        }
        getDebugger().debugLoggingFull(
                String.format("%s OPQBotState: %s", this.getChr().getName(), opqBotState),
                String.format("%s", opqBotState));

        if (isInsidePQ() && !isInParty()) {
            handlePQAbandoned();
            return;
        }

        // Authoritative re-home: if the game teleported us to a map we weren't
        // expecting, snap to the correct phase entry state.
        OPQBotState mapDerived = detectPhaseFromMap();
        if (mapDerived != null && mapDerived != opqBotState && !inSameStageFamily(mapDerived, opqBotState)) {
            transitionTo(mapDerived,
                    "map-derived rehome (mapId=" + getChr().getMapId() + ")");
        }

        switch (opqBotState) {
            case RESET:
                resetOPQBotState();
                transitionTo(OPQBotState.RECRUITMENT, "RESET completed, beginning recruitment");
                break;
            case RECRUITMENT:
                handleRecruitment();
                break;
            case IN_PARTY_IDLE:
                handleInPartyIdle();
                break;
            case STAGE_1_NAVIGATE:
                handleStage1Navigate();
                break;
            case STAGE_1_HIT_REACTOR:
                hitReactor4Times();
                break;
            case STAGE_1_LOOT:
                handleStage1Loot();
                break;
            case STAGE_1_RETURN:
                handleStage1Return();
                break;
            case STAGE_1_DROP_ITEMS:
                handleStage1DropItems();
                break;
            case STAGE_1_WAIT:
                handleStage1Wait();
                break;
            case STAGE_1_TRANSITION:
                handleStage1Transition();
                break;
            case STAGE_1_TRANSITION_PT_2:
                handleStage1TransitionPart2();
                break;
            case MIDDLE_STAGE:
                handleMiddleStage();
                break;
            case STAGE_2_NAVIGATE:
                handleStage2Navigate();
                break;
            case STAGE_2_HIT_BOX:
                hitBox4Times();
                break;
            case STAGE_2_LOOT:
                handleStage2Loot();
                break;
            case STAGE_2_RETURN:
                handleStage2Return();
                break;
            case STAGE_2_DROP_ITEMS:
                handleStage2DropItems();
                break;
            case STAGE_2_WAIT:
                handleStage2Wait();
                break;
            case EXIT_DETECT:
                handleExitDetect();
                break;
            case EXIT_LOBBY:
                handleExitLobby();
                break;
            case LOOP_CHECK:
                handleLoopCheck();
                break;
            default:
                log("Unexpected state: " + opqBotState);
                state = BotState.FINISHED;
                resetOPQBotState();
                throw new IllegalStateException("Unexpected state: " + state);
        }
    }

    @Override
    public void displayCommands(Character chr) {
        SocialCommands.displayPlayerChatCommands(chr, hint);
    }

    @Override
    public void processMessages() {
        try {
            ChatMessage message = MessageQueue.getInstance().getMessageWithTimeout("secondary", 1, TimeUnit.SECONDS);
            if (message == null) {
                return;
            }
            // OPQ bots don't need to react to player chat during a run, but the
            // hook is here for future extensions (e.g. leader shouting "go").
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // =========================================================================
    // Phase: Recruitment (lobby)
    // =========================================================================

    private void handleRecruitment() {
        debugLogf("handleRecruitment: mapId=" + getChr().getMapId()
                + " inParty=" + isInParty()
                + " sinceLastChat=" + (System.currentTimeMillis() - lastRecruitMessageAt) + "ms");

        // Auto-accept any pending party invite first; if accepted we'll flip
        // to IN_PARTY_IDLE on the next tick via the isInParty() check below.
        boolean accepted = BotPartyLogic.checkPartyQueue(getChr());
        if (accepted) {
            debugLogf("Accepted a pending party invite.");
        }

        if (isInParty()) {
            orchestrator.noteLeaderFromBot(this);
            sharedContext_trySetPhase(OPQPhase.IN_PARTY_IDLE);
            transitionTo(OPQBotState.IN_PARTY_IDLE, "joined a party");
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastRecruitMessageAt >= OPQConstants.RECRUIT_MESSAGE_INTERVAL_MS) {
            String msg = OPQRecruitMessages.generateRecruitMessage(getChr());
            SocialCommands.BotSpeak(getChr(), msg);
            lastRecruitMessageAt = now;
            debugLogf("Recruit chat sent: \"" + msg + "\"");

            List<String> platforms = PlatformPlacement.getMainPlatformIds(getChr().getMapId());
            if (!platforms.isEmpty()) {
                String target = platforms.get(new Random().nextInt(platforms.size()));
                PlatformPlacement.botMoveToPlatformAnyUnoccupiedSpot(getChr(), target);
            }
        }
    }

    // =========================================================================
    // Phase: In party, pre-start idle
    // =========================================================================

    private void handleInPartyIdle() {
        debugLogf("handleInPartyIdle: mapId=" + getChr().getMapId()
                + " inParty=" + isInParty()
                + " phase=" + sharedContext.getCurrentPhase());

        if (!isInParty()) {
            // Disbanded before PQ started — back to lobby chat.
            transitionTo(OPQBotState.RECRUITMENT, "party disbanded before PQ started");
            return;
        }

        // Refresh leader id every tick — covers leader change / re-invite.
//        orchestrator.noteLeaderFromBot(this);
        // The orchestrator's tick polls leader.getMapId() and warps us into
        // Stage 1 server-side once the leader enters. detectPhaseFromMap() at
        // the top of updateState() then flips us into STAGE_1_NAVIGATE.

        if (getPartyLeader().getMapId() == OPQConstants.OPQ_STAGE_1) {
            lootedRecordItemId = -1;
            // deliberate synchronous warp: warpBotToLocation blocks through the
            // arrival choreography (up to ~7s) and nothing may overlap it
            blockingSleep(3000);
            OPQOrchestrator.getInstance().followLeaderWarp(getChr(), STAGE_1_ENTRY_TP);
            transitionTo(OPQBotState.STAGE_1_NAVIGATE, "Warp to Stage 1 with Leader");
        }
    }

    // =========================================================================
    // Phase: Stage 1
    // =========================================================================

    // Stage 1 has 20 cloud reactors; one cycle per bot is not enough.
    // If any unbroken clouds remain (and not already claimed by another
    // bot), drop our now-dead assignment and loop back to NAVIGATE so
    // assignCloudReactor picks a fresh closest one. Only when no clouds
    // are left do we mark task complete and wait.

    private void handleStage1Navigate() {
        sharedContext_trySetPhase(OPQPhase.STAGE_1);

        Integer reactorOid = orchestrator.assignCloudReactor(this);
        debugLogf("handleStage1Navigate: reactorOid=" + reactorOid
                + "Char pos=" + getChr().getPosition());


        boolean moreCloudsLeft = orchestrator.hasUnclaimedLiveCloudReactor(
                getChr().getMap(), getChr().getId());
        if (!moreCloudsLeft) {
            sharedContext.putCloudAssignment(getChr().getId(), null);
            transitionTo(OPQBotState.STAGE_1_RETURN,
                    "no clouds left -> going return state");
            return;
        }
//        if (reactorOid == null) {
//            // No live reactor available — every cloud is taken or already broken.
//            startStageWaitTimer();
//            transitionTo(OPQBotState.STAGE_1_WAIT, "no cloud reactor available");
//            return;
//        }

        Reactor reactor = getChr().getMap().getReactorByOid(reactorOid);
        if (reactor == null || !reactor.isAlive() || reactor.getState() >= 4) {
            // Stale assignment — clear and retry next tick.
            sharedContext.putCloudAssignment(getChr().getId(), null);
            debugLogf("Reactor oid=" + reactorOid + " is stale (null/dead/state>=4) — re-requesting next tick");
            return;
        }

        // ensures its in range to hit it, could potentially loop if not in range based on reactor hit range px
        Point reactorPos = reactor.getPosition();
        double dx = Math.abs(getChr().getPosition().getX() - reactorPos.getX());
        if (dx <= OPQConstants.REACTOR_HIT_RANGE_PX) {
            reactorHitsThisTarget = 0;
            transitionTo(OPQBotState.STAGE_1_HIT_REACTOR,
                    "arrived within range of reactor oid=" + reactorOid + " (dx=" + dx + "px)");
            return;
        }
        MovementCommands.pathFinderBetaAerial(getChr(), reactorPos);
        waitFor(OPQConstants.NAVIGATE_SETTLE_MS); // let the walk land; range check re-runs next tick
        debugLogf("Stage1Navigate walking: dx=" + dx + " target=" + reactorPos);
    }

    private void hitReactor4Times() {
        // swings play out on a chain; the gate kills leftover swings once a hit
        // transitions us to LOOT, and waitFor holds ticks until the chain is done
        BotTiming.Chain chain = BotTiming.chain()
                .stopUnless(() -> opqBotState == OPQBotState.STAGE_1_HIT_REACTOR);
        for (int x = 0; x < 4; x++) {
            chain.run(this::handleStage1HitReactor).pause(OPQConstants.SWING_INTERVAL_MS);
        }
        chain.start();
        waitFor(OPQConstants.SWING_INTERVAL_MS * 4 + 200);
    }

    private void handleStage1HitReactor() {
        Integer reactorOid = sharedContext.getMyCloudAssignment(getChr().getId());
        if (reactorOid == null) {
            // Lost our assignment somehow — go back to navigate to re-request.
            transitionTo(OPQBotState.STAGE_1_NAVIGATE, "no reactor assignment in HIT state");
            return;
        }
        Reactor reactor = getChr().getMap().getReactorByOid(reactorOid);
        if (reactor == null || !reactor.isAlive() || reactor.getState() >= 4) {
            debugLogf("Reactor oid=" + reactorOid + " already dead — skipping to LOOT");
            transitionTo(OPQBotState.STAGE_1_LOOT, "reactor already broken on arrival");
            return;
        }

        debugLogf("handleStage1HitReactor: hit#" + (reactorHitsThisTarget + 1)
                + "/" + OPQConstants.MAX_REACTOR_HITS
                + " reactorOid=" + reactorOid + " state=" + reactor.getState());

        // Defensive: re-check state immediately before swinging. The top-of-tick
        // check above can go stale if a real player hit the reactor in the same
        // tick window — overshooting state 4 crashes the map.
        if (reactor.getState() >= 4) {
            debugLogf("Pre-hit guard tripped: state=" + reactor.getState() + " — skipping to LOOT");
            transitionTo(OPQBotState.STAGE_1_LOOT, "pre-hit state guard");
            return;
        }

        BotAttack.basicSwing(getChr());
        CustomReactor.hitReactorWithScript(getChr().getMap(), reactorOid, getChr());
        reactorHitsThisTarget++;

        // The engine drops the cloud piece itself now (2002001.js act() -> rm.dropItems()),
        // so nothing is spawned by hand. Whether THIS swing was the one that broke it is not
        // knowable from the caller's side - act() fires inside the engine's state walk - so
        // the loot step that follows simply sweeps the floor either way.
        byte stateAfter = reactor.getState();

        if (stateAfter >= 4 || reactorHitsThisTarget >= OPQConstants.MAX_REACTOR_HITS) {
            transitionTo(OPQBotState.STAGE_1_LOOT,
                    "reactor broken (state=" + stateAfter + ", hits=" + reactorHitsThisTarget + ")");
        }
    }

    private void handleStage1Loot() {
        Point botPos = getChr().getPosition();
        Integer reactorOid = sharedContext.getMyCloudAssignment(getChr().getId());
        Reactor reactor = (reactorOid != null) ? getChr().getMap().getReactorByOid(reactorOid) : null;
        Point reactorPos = (reactor != null) ? reactor.getPosition() : null;
        int[] cloudFilter = {OPQConstants.CLOUD_PIECE};

        // 1) Primary scan: at the bot's feet (where the just-broken reactor stood).
        List<MapObject> found = BotLogic.checkForItemsOnFloor(
                getChr(), botPos, OPQConstants.STAGE_1_LOOT_SCAN_RANGE_PX, cloudFilter);
        debugLogf("handleStage1Loot: primary @" + botPos + " hits=" + found.size());

        // 2) Fallback: cloud may have fallen through one or more footholds
        //    below the reactor anchor. Step straight down from the reactor's
        //    x in fixed increments and stop on the first hit.
        if (found.isEmpty() && reactorPos != null) {
            for (int step = 1; step <= OPQConstants.STAGE_1_LOOT_FALLBACK_STEPS; step++) {
                Point probe = new Point(
                        reactorPos.x,
                        reactorPos.y + step * OPQConstants.STAGE_1_LOOT_FALLBACK_STEP_PX);
                List<MapObject> hits = BotLogic.checkForItemsOnFloor(
                        getChr(), probe, OPQConstants.STAGE_1_LOOT_SCAN_RANGE_PX, cloudFilter);
                debugLogf("handleStage1Loot: fallback step " + step
                        + " @" + probe + " hits=" + hits.size());
                if (!hits.isEmpty()) {
                    found = hits;
                    break;
                }
            }
        }

        List<MapObject> loot = dropLoneItemsOnly(found);
        if (!loot.isEmpty()) {
            DropCommands.lootItemListOnFloor(getChr(), loot);
            SocialCommands.BotChatbubble(getChr(),
                    BotMessages.get("opq.cloud_pieces", cloudPiecesCount()));
        }
        waitFor(800); // loot beat before NAVIGATE ticks

        debugLogf("handleStage1Loot: scannedHits=" + found.size()
                + " held=" + cloudPiecesCount());

        transitionTo(OPQBotState.STAGE_1_NAVIGATE,
                "loot pass complete, searching for next cloud reactor");
    }

    private void handleStage1Return() {
        // Walk to the altar, not to the leader's old spot: the drop has to land inside the
        // altar's trigger box, and the altar sits at x=377 (the old target, x=497, was 20px
        // outside it to the right - which is why the altar never fired).
        MovementCommands.pathFinderBeta(getChr(), OPQConstants.STAGE_1_DROP_POS);
        waitFor(OPQConstants.NAVIGATE_SETTLE_MS); // settle before DROP_ITEMS ticks
        transitionTo(OPQBotState.STAGE_1_DROP_ITEMS, "return state done.");
    }

    private void handleStage1DropItems() {
        // The altar needs ONE stack of exactly CLOUD_REQUIRED pieces: MapleMap#activateItemReactors
        // compares the stack's quantity against the required 20, so twenty loose singles never
        // fire it. The bot layer's throws are synthetic (see DropCommands), which is what makes
        // this workable at all - the twenty pieces are spread across whoever broke which cloud,
        // so a bot throwing only what it personally holds would deadlock any party of two or
        // more. A bot reaches this state only once no cloud reactor is left standing, which is
        // the party-level equivalent of "the 20 clouds have been gathered".
        //
        // Exactly one bot throws (orchestrator election): the altar consumes the first
        // qualifying stack, and extra stacks would only litter the floor - each one a candidate
        // for another bot's loot sweep to pick up inside the engine's five-second window, which
        // cancels the trigger.
        boolean thrower = orchestrator.isAltarThrower(getChr().getId());
        boolean throwing = thrower && altarStillArmed();
        debugLogf("handleStage1DropItems: held=" + cloudPiecesCount()
                + " thrower=" + thrower + " throwing=" + throwing
                + " pos=" + getChr().getPosition());

        if (throwing) {
            SocialCommands.BotSpeak(getChr(), BotMessages.get("opq.dropping_clouds",
                    OPQConstants.CLOUD_REQUIRED, BotMessages.get("opq.cloud_plural")));
            BotTiming.after(400, () ->
                    DropCommands.botThrowItemQty(getChr(), OPQConstants.CLOUD_PIECE,
                            OPQConstants.CLOUD_REQUIRED, OPQConstants.STAGE_1_DROP_POS));
            waitFor(800); // hold WAIT until the throw lands
        }

        sharedContext.markTaskComplete(getChr().getId());
        startStageWaitTimer();
        transitionTo(OPQBotState.STAGE_1_WAIT,
                "all cloud reactors broken, waiting for stage-1 clear");
    }

    /**
     * How many cloud pieces this bot is actually carrying. Picked-up drops merge into one
     * stack per slot (InventoryManipulator.addFromDrop tops up existing slots), so the
     * inventory is the authoritative count - a running per-drop tally drifts whenever a
     * stack is bigger than one piece.
     */
    private int cloudPiecesCount() {
        return getChr().getItemQuantity(OPQConstants.CLOUD_PIECE, false);
    }

    /**
     * Drop multi-piece stacks from a loot list, keeping only the lone items a broken reactor
     * leaves behind.
     *
     * <p>A reactor drops one piece at a time, so a stack bigger than that can only be
     * something a party member laid down as an offering - and both of this PQ's item
     * triggers read their stack by identity five seconds after the drop, so picking one up
     * cancels the trigger silently. The loot scan reaches several thousand pixels, which is
     * most of either stage map, so this cannot be left to "the bot is probably not adjacent".
     */
    private static List<MapObject> dropLoneItemsOnly(List<MapObject> found) {
        return found.stream()
                .filter(obj -> !(obj instanceof MapItem drop) || drop.getItem().getQuantity() <= 1)
                .toList();
    }

    /**
     * Whether the altar will still react to a drop. Its type reads 100 only while its event
     * state still names an item condition; once it has fired, the state walk moves past the
     * declared WZ entries and the type reads -1. Same test MapleMap#activateItemReactors
     * applies, so this asks the question the engine itself will ask.
     */
    private boolean altarStillArmed() {
        for (Reactor r : getChr().getMap().getAllReactors()) {
            if (r.getId() == OPQConstants.STAGE_1_ALTAR_REACTOR_ID) {
                return r.getReactorType() == 100;
            }
        }
        return false;
    }

    private void handleStage1Wait() {
        if (sharedContext.isStage1Complete() && orchestrator.isChamberlainSpawned()) {
            OPQOrchestrator.getInstance().followLeaderWarp(getChr(), STAGE_1_COMPLETE_TP);
            // Walk to Portal
            MovementCommands.moveToPortal(getChr(), 4);
            transitionTo(OPQBotState.STAGE_1_TRANSITION, "stage1Complete flag flipped by orchestrator");
            return;
        }

        // Same recovery the stage-2 wait has, for the same reason: the altar only fires if a
        // stack of exactly 20 lands in its box, so a run where that never happened (the
        // thrower died, the stack was looted inside the engine's five-second window, the drop
        // landed outside the box) must be able to give up rather than sit here forever. The
        // leader leaving is the other end of the same rope - he may have exited the PQ or
        // walked back to the lobby, and the run is over either way.
        int leaderMap = getPartyLeader().getMapId();
        if (leaderMap == OPQConstants.OPQ_EXIT_LOBBY) {
            blockingSleep(1000); // deliberate: blocking follow-warp below
            OPQOrchestrator.getInstance().followLeaderWarp(getChr(), new Point(-161, 323));
            transitionTo(OPQBotState.EXIT_LOBBY, "leader left stage 1 — following to exit lobby");
            return;
        }
        if (leaderMap == OPQConstants.OPQ_LOBBY) {
            blockingSleep(1000); // deliberate: blocking follow-warp below
            OPQOrchestrator.getInstance().followLeaderWarp(getChr(), new Point(-233, 174));
            transitionTo(OPQBotState.LOOP_CHECK, "leader already in OPQ lobby");
            return;
        }

        if (waitTimedOut()) {
            transitionTo(OPQBotState.LOOP_CHECK,
                    "stage-1 wait timed out after "
                            + OPQConstants.STAGE_WAIT_TIMEOUT_MS + "ms");
        }
    }

    private void handleStage1Transition() {
        debugLogf("handleStage1Transition: mapId=" + getChr().getMapId()
                + " awaiting teleport to central tower (" + OPQConstants.OPQ_TOWER + ")");

        // I'm not sure how to have the bots enter the proper portal which is to get them to the map of OPQ_TOWER
        // Because technically its a PQ instance, so it's gotta be the correct MapleMap, not just generic map.
        if (getPartyLeader().getMapId() == OPQConstants.OPQ_TOWER) {
            // deliberate synchronous warp sequence (blocking arrival choreography)
            blockingSleep(1000);
            OPQOrchestrator.getInstance().followLeaderWarp(getChr(), new Point(-260,-32)); // Spawn point for OPQ tower [x=-260,y=-32]
            blockingSleep(1000);
            MovementCommands.pathFinderBeta(getChr(), new Point(159, -32)); // Walk to Portal [x=159,y=-32]
            transitionTo(OPQBotState.STAGE_1_TRANSITION_PT_2, "Waiting for leader to enter stage 2");
        }
    }

    private void handleStage1TransitionPart2() {
        // The tower is the hub; the quest's middle stages are all reached from here, so hand
        // over to the generic middle-stage driver instead of jumping straight at the music
        // box and skipping five rooms. If the party has already worked its way to the music
        // box room, that driver routes there on its own.
        if (getChr().getMapId() == OPQConstants.OPQ_TOWER) {
            transitionTo(OPQBotState.MIDDLE_STAGE, "tower reached, working the middle stages");
        }
    }

    // =========================================================================
    // Phase: the stages between the clouds and the music box
    // =========================================================================

    /**
     * Work whatever middle stage the party is on.
     *
     * <p>Orbis runs nine stages and the bot's original two (clouds, then the music box) left
     * seven untouched, so a party that got past the clouds had nowhere to go. This drives the
     * rest: the walkway, the storage room, the sealed room's platforms, the lounge and the
     * levers, plus the tower work (the six scar reactors and the statue base) in between.
     *
     * <p>Which stage is current is read from the instance's own flags rather than guessed
     * from the map, because several stages share a map and the flags are the only thing that
     * says what is still outstanding. The bot stays with the leader: if the leader is not in
     * the room this stage lives in, the bot walks back to the tower and over, which is how a
     * player moves between rooms too.
     */
    private void handleMiddleStage() {
        sharedContext_trySetPhase(OPQPhase.STAGE_2);

        int stage = currentMiddleStage();
        if (stage < 0) {
            // Nothing outstanding that the bot can see: hand back to the exit logic, which
            // follows the leader out when the run is over.
            transitionTo(OPQBotState.EXIT_DETECT, "no middle stage outstanding");
            return;
        }

        // The tower work needs no room: the scars are in the tower itself, and the statue
        // base is the tower's own pedestal.
        if (stage == OrbisStages.SCARS_STAGE) {
            if (getChr().getMapId() != OrbisPqData.TOWER_MAP) {
                walkToTower();
                return;
            }
            OrbisStages.breakScars(getChr());
            waitFor(OPQConstants.NAVIGATE_SETTLE_MS);
            return;
        }
        if (stage == OrbisStages.STATUE_STAGE) {
            if (getChr().getMapId() != OrbisPqData.TOWER_MAP) {
                walkToTower();
                return;
            }
            OrbisStages.placeFinalPiece(getChr());
            waitFor(OPQConstants.NAVIGATE_SETTLE_MS);
            return;
        }
        // Papa Pixie's room is not on the tower's portal list - Eak warps the party there -
        // so the bot rides along with the leader and works the room once it arrives.
        if (stage == OrbisStages.PAPA_STAGE) {
            if (getChr().getMapId() != OrbisPqData.STAGE_PAPA) {
                followLeaderIntoPapaRoom();
                return;
            }
            OrbisStages.settlePapaRoom(getChr());
            waitFor(OPQConstants.NAVIGATE_SETTLE_MS);
            return;
        }

        OrbisStages.StageRoom room = OrbisStages.roomFor(stage);
        if (room == null) {
            transitionTo(OPQBotState.EXIT_DETECT, "stage " + stage + " has no bot room");
            return;
        }

        // The lounge's forty pieces come from its four sub-rooms, so the bot rotates through
        // them rather than standing in the empty main room.
        if (stage == 5) {
            workLounge();
            return;
        }

        // Not in the room yet: go there through the tower (that is how the quest routes
        // between rooms - the tower is the hub, and rooms do not connect to each other).
        if (getChr().getMapId() != room.mapId()) {
            walkToRoom(room);
            return;
        }

        doRoomWork(stage, room);
    }

    /**
     * Work stage 5: the lounge's statue pieces are in its four sub-rooms, so the bot walks into
     * one, clears what is there, and steps back out; each entry advances the rotation so it
     * covers all four (three hold the piece-dropping mobs, the fourth the reactor that drops the
     * same item).
     */
    private void workLounge() {
        int here = getChr().getMapId();
        if (OrbisPqData.LOUNGE_ROOMS.contains(here)) {
            OrbisStages.gatherLounge(getChr());
            // Step back to the lounge so the next entry takes the following sub-room.
            walkOutOfCurrentRoom();
            loungeVisit++;
            return;
        }
        if (here != OrbisPqData.STAGE_LOUNGE) {
            walkToRoom(OrbisStages.roomFor(5));
            return;
        }
        String door = OrbisPqData.loungeEntryPortal(loungeVisit);
        Point doorPos = portalPos(door);
        if (doorPos == null) {
            return;
        }
        PqActions.walkTo(getChr(), doorPos);
        PqActions.enterPortalHere(getChr());
        waitFor(OPQConstants.NAVIGATE_SETTLE_MS);
    }

    /**
     * Which stage of the run after the clouds is still outstanding, from the instance flags.
     *
     * <p>Delegates the decision to {@link OrbisStages#middleStage}, which documents the flag
     * semantics (including stage 3's non-standard cleared value) and is unit-tested. This method
     * only gathers the flags and the one piece of map state the decision needs.
     */
    private int currentMiddleStage() {
        int[] stg = new int[9]; // index 0 unused; statusStg1..8
        for (int stage = 1; stage <= 8; stage++) {
            stg[stage] = readEimInt("statusStg" + stage, -1);
        }
        return OrbisStages.middleStage(stg, scarsComplete(getChr()));
    }

    /**
     * Whether the six scar reactors in the tower are all lit, which is Eak's own test
     * ({@code isStatueComplete}) and the gate on his sending the party to Papa Pixie.
     *
     * <p>There is no flag for this - no script sets {@code statusStg7} except the spring - so the
     * reactors are read directly, exactly as Eak reads them. Only meaningful in the tower, where
     * the scars live; a bot in another room reads false.
     */
    private static boolean scarsComplete(Character bot) {
        if (bot.getMap() == null || bot.getMapId() != OrbisPqData.TOWER_MAP) {
            return false;
        }
        for (var reactor : bot.getMap().getAllReactors()) {
            if (reactor.getId() >= OrbisPqData.SCAR_FIRST && reactor.getId() <= OrbisPqData.SCAR_LAST) {
                if (reactor.getState() < 1) {
                    return false;
                }
            }
        }
        return true;
    }

    private int readEimInt(String key, int fallback) {
        return soloMapling.ArtificialPlayer.PartyQuest.PqActions.readEimInt(getChr(), key, fallback);
    }

    /** Head back to the tower from whatever room the bot is standing in. */
    private void walkToTower() {
        if (getChr().getMapId() == OrbisPqData.TOWER_MAP) {
            return;
        }
        walkOutOfCurrentRoom();
    }

    /**
     * Walk onto the current room's exit portal and step through it (back to the tower or lounge).
     *
     * <p>The exit belongs to the room the bot is IN, not the room it wants. Most rooms publish it
     * as {@code st00} (entered by {@code party3_roomout}, whose script switches on the map id);
     * the lounge's sub-rooms, the jail and the prize room use {@code out00}. Papa Pixie's room has
     * no walk-out (it only lets the leader through), so there the bot stays put and is moved by
     * the leader-follow or the stage wait instead.
     */
    private void walkOutOfCurrentRoom() {
        Point exit = portalPos(OrbisPqData.ROOM_EXIT_PORTAL_NAME);
        if (exit == null) {
            exit = portalPos(OrbisPqData.LOUNGE_EXIT_PORTAL);
        }
        if (exit == null) {
            waitFor(OPQConstants.NAVIGATE_SETTLE_MS);
            return;
        }
        PqActions.walkTo(getChr(), exit);
        PqActions.enterPortalHere(getChr());
        waitFor(OPQConstants.NAVIGATE_SETTLE_MS);
    }

    /** The position of the named portal on the bot's current map, or null. */
    private Point portalPos(String portalName) {
        var portal = getChr().getMap().getPortal(portalName);
        return portal == null ? null : portal.getPosition();
    }

    /** Papa Pixie's room is entered by Eak's warp, so the bot simply stays with the leader. */
    private void followLeaderIntoPapaRoom() {
        if (getPartyLeader().getMapId() == OrbisPqData.STAGE_PAPA) {
            OPQOrchestrator.getInstance().followLeaderWarp(getChr(),
                    OrbisPqData.PAPA_SPRING_SPOT);
        } else {
            walkToTower();
        }
        waitFor(OPQConstants.NAVIGATE_SETTLE_MS);
    }

    /**
     * Walk onto the room's tower portal and step through it.
     *
     * <p>The tower portals ({@code in00..in06}) are script portals: walking onto one only moves
     * the bot, and the script that actually warps to the room only runs on entering. The tower
     * spot a room declares is that portal's position, so this walks there and then enters the
     * portal the bot is now standing on.
     */
    private void walkToRoom(OrbisStages.StageRoom room) {
        if (getChr().getMapId() != OPQConstants.OPQ_TOWER) {
            // Somewhere else (a sub-room, a room left behind): walk out to the tower first.
            walkToTower();
            return;
        }
        Point spot = room.towerSpot();
        if (spot == null) {
            return;
        }
        PqActions.walkTo(getChr(), spot);
        PqActions.enterPortalHere(getChr());
        waitFor(OPQConstants.NAVIGATE_SETTLE_MS);
    }

    private void doRoomWork(int stage, OrbisStages.StageRoom room) {
        switch (stage) {
            case 1 -> OrbisStages.huntWalkway(getChr());
            case 2 -> OrbisStages.clearStorage(getChr());
            case 3 -> {
                // The music box is the one stage the bot already knew how to finish.
                transitionTo(OPQBotState.STAGE_2_NAVIGATE, "music box room reached");
                return;
            }
            case 4 -> {
                if (!OrbisStages.standOnSealedPlatform(getChr(), sharedContext.mySealedSlot(getChr().getId()))) {
                    debugLogf("sealed room: no published layout yet");
                }
            }
            case 5 -> { /* handled by workLounge, which rotates the sub-rooms */ }
            case 6 -> OrbisStages.pullCorrectLevers(getChr());
            default -> { /* nothing to do */ }
        }
        waitFor(OPQConstants.NAVIGATE_SETTLE_MS);
    }

    // =========================================================================
    // Phase: Stage 2
    // =========================================================================

    private void handleStage2Navigate() {
        sharedContext_trySetPhase(OPQPhase.STAGE_2);

        if (leaderLeftStage2()) {
            sharedContext.putBoxAssignment(getChr().getId(), null);
            followLeaderOut();
            return;
        }

        // Debug: dump all reactors on the map on first entry
        debugLogf("handleStage2Navigate: dumping all reactors on map " + getChr().getMapId());
//        for (Reactor r : getChr().getMap().getAllReactors()) {
//            debugLogf("  reactor oid=" + r.getObjectId()
//                    + " dataId=" + r.getId()
//                    + " state=" + r.getState()
//                    + " alive=" + r.isAlive()
//                    + " pos=" + r.getPosition());
//        }

        Integer reactorOid = orchestrator.assignBoxReactor(this);
        debugLogf("handleStage2Navigate: reactorOid=" + reactorOid
                + " pos=" + getChr().getPosition());

        boolean moreBoxesLeft = orchestrator.hasUnclaimedLiveBoxReactor(
                getChr().getMap(), getChr().getId());
        if (!moreBoxesLeft && reactorOid == null) {
            // All boxes broken or claimed — if we have an item, go drop it first
            if (lootedRecordItemId > 0) {
                transitionTo(OPQBotState.STAGE_2_RETURN,
                        "no boxes left, returning to drop looted item");
            } else {
                sharedContext.markTaskComplete(getChr().getId());
                startStageWaitTimer();
                transitionTo(OPQBotState.STAGE_2_WAIT,
                        "no boxes left and nothing to drop — waiting for stage clear");
            }
            return;
        }

        if (reactorOid == null) {
            debugLogf("Stage2Navigate: no assignment yet, waiting for one to free up");
            return;
        }

        Reactor reactor = getChr().getMap().getReactorByOid(reactorOid);
        if (reactor == null || !reactor.isAlive() || reactor.getState() >= 4) {
            sharedContext.putBoxAssignment(getChr().getId(), null);
            debugLogf("Box reactor oid=" + reactorOid + " is stale (null/dead/state>=4) — re-requesting next tick");
            return;
        }

        // Chat which box we're going for (ordinal based on sorted position right-to-left)
        String ordinal = orchestrator.getBoxOrdinal(reactorOid);
        SocialCommands.BotSpeak(getChr(), BotMessages.get("opq.taking_box", localizeOrdinal(ordinal)));

        Point reactorPos = reactor.getPosition();
        double dx = Math.abs(getChr().getPosition().getX() - reactorPos.getX());
        if (dx <= OPQConstants.REACTOR_HIT_RANGE_PX) {
            reactorHitsThisTarget = 0;
            transitionTo(OPQBotState.STAGE_2_HIT_BOX,
                    "arrived at " + ordinal + " box (oid=" + reactorOid + ")");
            return;
        }
        MovementCommands.pathFinderBetaAerial(getChr(), reactorPos);
        waitFor(OPQConstants.NAVIGATE_SETTLE_MS); // let the walk land; range check re-runs next tick
        debugLogf("Stage2Navigate walking: dx=" + dx + " target=" + reactorPos);
    }

    private void hitBox4Times() {
        // Face toward the box reactor before swinging
        Integer reactorOid = sharedContext.getMyBoxAssignment(getChr().getId());
        if (reactorOid != null) {
            Reactor reactor = getChr().getMap().getReactorByOid(reactorOid);
            if (reactor != null) {
                boolean boxIsLeft = reactor.getPosition().x < getChr().getPosition().x;
                if (boxIsLeft && !MovementCommands.facingLeft(getChr())) {
                    MovementCommands.microTurnAroundToLeft(getChr());
                } else if (!boxIsLeft && MovementCommands.facingLeft(getChr())) {
                    MovementCommands.microTurnAroundToRight(getChr());
                }
            }
        }

        BotTiming.Chain chain = BotTiming.chain()
                .stopUnless(() -> opqBotState == OPQBotState.STAGE_2_HIT_BOX);
        for (int x = 0; x < 4; x++) {
            chain.run(this::handleStage2HitBox).pause(OPQConstants.SWING_INTERVAL_MS);
        }
        chain.start();
        waitFor(OPQConstants.SWING_INTERVAL_MS * 4 + 200);
    }

    private void handleStage2HitBox() {
        Integer reactorOid = sharedContext.getMyBoxAssignment(getChr().getId());
        if (reactorOid == null) {
            transitionTo(OPQBotState.STAGE_2_NAVIGATE, "no box assignment in HIT state");
            return;
        }
        Reactor reactor = getChr().getMap().getReactorByOid(reactorOid);
        if (reactor == null || !reactor.isAlive() || reactor.getState() >= 4) {
            debugLogf("Box reactor oid=" + reactorOid + " already broken — skipping to LOOT");
            transitionTo(OPQBotState.STAGE_2_LOOT, "box already broken on arrival");
            return;
        }

        debugLogf("handleStage2HitBox: hit#" + (reactorHitsThisTarget + 1)
                + "/" + OPQConstants.MAX_REACTOR_HITS
                + " reactorOid=" + reactorOid + " state=" + reactor.getState());

        if (reactor.getState() >= 4) {
            debugLogf("Pre-hit guard tripped: state=" + reactor.getState() + " — skipping to LOOT");
            transitionTo(OPQBotState.STAGE_2_LOOT, "pre-hit state guard");
            return;
        }

        BotAttack.basicSwing(getChr());
        CustomReactor.hitReactorWithScript(getChr().getMap(), reactorOid, getChr());
        reactorHitsThisTarget++;

        // As in stage 1: the engine drops the record itself (2002004..2002010 -> act() ->
        // rm.dropItems()), so nothing is spawned by hand. Which record this box yields is
        // reactordrops' business (2002004 -> 4001056, ... in data-id order, not screen
        // order); the loot step reads the inventory afterwards to find out.
        byte stateAfter = reactor.getState();

        if (stateAfter >= 4 || reactorHitsThisTarget >= OPQConstants.MAX_REACTOR_HITS) {
            transitionTo(OPQBotState.STAGE_2_LOOT,
                    "box broken (state=" + stateAfter + ", hits=" + reactorHitsThisTarget + ")");
        }
    }

    private void handleStage2Loot() {
        Point botPos = getChr().getPosition();
        Integer reactorOid = sharedContext.getMyBoxAssignment(getChr().getId());
        Reactor reactor = (reactorOid != null) ? getChr().getMap().getReactorByOid(reactorOid) : null;
        Point scanCenter = (reactor != null) ? reactor.getPosition() : botPos;

        // Seven boxes drop seven different records and the music box accepts only today's
        // (OrbisPQ.js sets its event state to the weekday), so pick up that one and leave the
        // rest - a player does the same, and hoarding the other six would only clutter the
        // inventory and make the bot carry records it has no use for.
        int today = OPQOrchestrator.getTodayRecordItemId();
        List<MapObject> found = BotLogic.checkForItemsOnFloor(
                getChr(), scanCenter, OPQConstants.STAGE_1_LOOT_SCAN_RANGE_PX,
                new int[]{today});
        // Same guard as stage 1: records drop as single items, so a stack is somebody's
        // offering already sitting on the music box, and taking it cancels their trigger.
        List<MapObject> loot = dropLoneItemsOnly(found);
        if (!loot.isEmpty()) {
            DropCommands.lootItemListOnFloor(getChr(), loot);
        }

        // Which record the bot ended up holding is read back off the inventory rather than
        // predicted from the box: the engine's own reactordrops decides what each box yields.
        boolean carryingToday = getChr().getItemQuantity(today, false) > 0;
        lootedRecordItemId = carryingToday ? today : -1;

        debugLogf("handleStage2Loot: holdingToday=" + carryingToday
                + " today=" + today + " floorHits=" + found.size());
        if (carryingToday) {
            SocialCommands.BotChatbubble(getChr(), BotMessages.get("opq.got_record"));
        }
        waitFor(800); // loot beat before RETURN ticks

        // Clear box assignment so we can pick a new one
        sharedContext.putBoxAssignment(getChr().getId(), null);

        if (carryingToday) {
            // Nothing more to hunt for - take it to the music box now instead of breaking
            // boxes the leader may still want.
            transitionTo(OPQBotState.STAGE_2_RETURN, "holding today's record, returning to music box");
        } else {
            transitionTo(OPQBotState.STAGE_2_NAVIGATE,
                    "this box did not hold today's record, trying another");
        }
    }

    /**
     * Box ordinals reach us as English words ("1st".."7th") because OPQConstants doubles as
     * debug-log output. For the spoken line, map them onto the localized ordinals so a Chinese
     * client doesn't hear "我去开第1st个箱子". Unrecognized values pass through unchanged.
     */
    private static String localizeOrdinal(String englishOrdinal) {
        for (int i = 0; i < OPQConstants.STAGE_2_BOX_ORDINALS.length; i++) {
            if (OPQConstants.STAGE_2_BOX_ORDINALS[i].equals(englishOrdinal)) {
                return BotMessages.get("opq.box_ordinal." + (i + 1));
            }
        }
        return englishOrdinal;
    }

    private void handleStage2Return() {
        // Walk to the music box's drop cell rather than the platform the leader happens to
        // be standing on: the box only reacts to a record landing inside
        // x∈[-1758,-1666) y∈[-304,-161), and the old target (-1588,-127) was 78px outside it
        // on x. The landing point already accounts for the drop re-seating itself 85px down
        // onto the floor below the box - see OPQConstants.STAGE_2_DROP_POS.
        MovementCommands.pathFinderBeta(getChr(), OPQConstants.STAGE_2_DROP_POS);
        waitFor(OPQConstants.NAVIGATE_SETTLE_MS); // settle before DROP_ITEMS ticks
        transitionTo(OPQBotState.STAGE_2_DROP_ITEMS,
                "arrived at music box drop zone");
    }

    private void handleStage2DropItems() {
        if (lootedRecordItemId > 0) {
            SocialCommands.BotSpeak(getChr(), BotMessages.get("opq.dropping_record"));
            int recordId = lootedRecordItemId;
            // Throw at the box's cell, not at the bot's feet: the landing cell caps how far
            // this can travel, and the bot is only near the target here, not on it.
            BotTiming.after(400, () ->
                    DropCommands.botThrowItem(getChr(), recordId, OPQConstants.STAGE_2_DROP_POS));
            debugLogf("handleStage2DropItems: dropping itemId=" + recordId);
            lootedRecordItemId = -1;
        } else {
            debugLogf("handleStage2DropItems: no record to drop (lootedId=" + lootedRecordItemId + ")");
        }
        waitFor(1000); // replaces the old 400+600ms drop beats

        // If leader already cleared and left, don't bother with remaining boxes
        if (leaderLeftStage2()) {
            followLeaderOut();
            return;
        }

        // Check if more boxes remain — loop back if so
        boolean moreBoxes = orchestrator.hasUnclaimedLiveBoxReactor(
                getChr().getMap(), getChr().getId());
        if (moreBoxes) {
            transitionTo(OPQBotState.STAGE_2_NAVIGATE,
                    "more boxes remaining, looping back for another");
        } else {
            sharedContext.markTaskComplete(getChr().getId());
            startStageWaitTimer();
            transitionTo(OPQBotState.STAGE_2_WAIT,
                    "all boxes broken, waiting for stage-2 clear");
        }
    }

    private void handleStage2Wait() {
        long waitedMs = stageWaitStartTime > 0
                ? (System.currentTimeMillis() - stageWaitStartTime) : 0;
        debugLogf("handleStage2Wait: waited=" + waitedMs + "ms"
                + " stage2Complete=" + sharedContext.isStage2Complete());

        // Detect leader leaving Stage 2 (via NPC exit or fast-exit through lobby)
        int leaderMap = getPartyLeader().getMapId();
        if (leaderMap == OPQConstants.OPQ_EXIT_LOBBY) {
            blockingSleep(1000); // deliberate: blocking follow-warp below
            OPQOrchestrator.getInstance().followLeaderWarp(getChr(), new Point(-161, 323));
            transitionTo(OPQBotState.EXIT_LOBBY, "followed leader to exit lobby");
            return;
        }
        if (leaderMap == OPQConstants.OPQ_LOBBY) {
            blockingSleep(1000); // deliberate: blocking follow-warp below
            OPQOrchestrator.getInstance().followLeaderWarp(getChr(), new Point(-233, 174));
            transitionTo(OPQBotState.LOOP_CHECK, "leader already in OPQ lobby");
            return;
        }

        if (sharedContext.isStage2Complete()) {
            transitionTo(OPQBotState.EXIT_DETECT,
                    "stage2Complete flag flipped by orchestrator");
            return;
        }

        if (waitTimedOut()) {
            transitionTo(OPQBotState.LOOP_CHECK,
                    "stage-2 wait timed out after "
                            + OPQConstants.STAGE_WAIT_TIMEOUT_MS + "ms");
        }
    }

    // =========================================================================
    // Phase: Exit
    // =========================================================================

    private void handleExitDetect() {
        debugLogf("handleExitDetect: mapId=" + getChr().getMapId()
                + " leaderMap=" + getPartyLeader().getMapId());
        sharedContext_trySetPhase(OPQPhase.EXIT);

        if (getChr().getMapId() == OPQConstants.OPQ_EXIT_LOBBY) {
            transitionTo(OPQBotState.EXIT_LOBBY, "arrived at exit lobby");
            return;
        }

        // Actively follow leader to exit lobby or recruitment lobby
        int leaderMap = getPartyLeader().getMapId();
        if (leaderMap == OPQConstants.OPQ_EXIT_LOBBY) {
            blockingSleep(1000); // deliberate: blocking follow-warp below
            OPQOrchestrator.getInstance().followLeaderWarp(getChr(), new Point(-161, 323));
            transitionTo(OPQBotState.EXIT_LOBBY, "followed leader to exit lobby");
        } else if (leaderMap == OPQConstants.OPQ_LOBBY) {
            blockingSleep(1000); // deliberate: blocking follow-warp below
            OPQOrchestrator.getInstance().followLeaderWarp(getChr(), new Point(-233, 174));
            transitionTo(OPQBotState.LOOP_CHECK, "followed leader to OPQ lobby");
        }
    }

    private void handleExitLobby() {
        debugLogf("handleExitLobby: mapId=" + getChr().getMapId()
                + " leaderMap=" + getPartyLeader().getMapId());

        // If leader has already moved to recruitment lobby, follow them
        // (deliberate synchronous warps: arrival choreography blocks and must not overlap)
        int leaderMap = getPartyLeader().getMapId();
        if (leaderMap == OPQConstants.OPQ_LOBBY) {
            blockingSleep(300);
            MapleMap lobbyMap = mapForBot(getChr(), OPQConstants.OPQ_LOBBY);
            warpBotToLocation(getChr(), new Point(-233, 174), lobbyMap);
            transitionTo(OPQBotState.LOOP_CHECK, "followed leader to recruitment lobby");
            return;
        }

        // Otherwise warp ourselves to lobby after a short wait
        blockingSleep(500);
        MapleMap lobbyMap = mapForBot(getChr(), OPQConstants.OPQ_LOBBY);
        warpBotToLocation(getChr(), new Point(-233, 174), lobbyMap);
        blockingSleep(2000);
        List<String> platforms = PlatformPlacement.getMainPlatformIds(getChr().getMapId());
        if (!platforms.isEmpty()) {
            String target = platforms.get(new Random().nextInt(platforms.size()));
            PlatformPlacement.botMoveToPlatformAnyUnoccupiedSpot(getChr(), target);
        }

        transitionTo(OPQBotState.LOOP_CHECK, "exit-lobby complete, warped to recruitment lobby");
    }

    private void handleLoopCheck() {
        debugLogf("handleLoopCheck: inParty=" + isInParty()
                + " mapId=" + getChr().getMapId());
        // Clear ALL per-run scratch so next run starts completely clean.
        sharedContext.clearTaskComplete(getChr().getId());
        sharedContext.putCloudAssignment(getChr().getId(), null);
        sharedContext.putBoxAssignment(getChr().getId(), null);
        sharedContext.putPlatformAssignment(getChr().getId(), null);
        reactorHitsThisTarget = 0;
        stageWaitStartTime = 0;
        lootedRecordItemId = -1;
        loungeVisit = 0;

        if (isInParty()) {
            transitionTo(OPQBotState.IN_PARTY_IDLE,
                    "still partied, ready for next PQ run");
        } else {
            transitionTo(OPQBotState.RECRUITMENT,
                    "no party, returning to lobby chat");
        }
    }

    // =========================================================================
    // PQ abandonment
    // =========================================================================

    private boolean isInsidePQ() {
        return switch (opqBotState) {
            case RESET, RECRUITMENT, IN_PARTY_IDLE, LOOP_CHECK -> false;
            default -> true;
        };
    }

    private void handlePQAbandoned() {
        debugLogf("Party lost — PQ abandoned. Cleaning up and warping out.");

        // Clear this bot's shared context entries
        int botId = getChr().getId();
        sharedContext.putCloudAssignment(botId, null);
        sharedContext.putBoxAssignment(botId, null);
        sharedContext.putPlatformAssignment(botId, null);
        sharedContext.clearTaskComplete(botId);

        // deliberate synchronous warps (blocking arrival choreography)
        MapleMap exitMap = mapForBot(getChr(), OPQConstants.OPQ_EXIT_LOBBY);
        warpBotToLocation(getChr(), new Point(-161, 323), exitMap);
        blockingSleep(1500);

        MapleMap lobbyMap = mapForBot(getChr(), OPQConstants.OPQ_LOBBY);
        warpBotToLocation(getChr(), new Point(-233, 174), lobbyMap);

        resetOPQBotState();
        transitionTo(OPQBotState.RECRUITMENT, "party lost — PQ abandoned, returned to lobby");
    }

    // =========================================================================
    // Perception helpers
    // =========================================================================

    /**
     * Translate the current mapId into the state the bot should be in, if any.
     */
    private OPQBotState detectPhaseFromMap() {
        int mapId = getChr().getMapId();
        if (mapId == OPQConstants.OPQ_LOBBY) {
            return isInParty() ? OPQBotState.IN_PARTY_IDLE : OPQBotState.RECRUITMENT;
        }
        if (mapId == OPQConstants.OPQ_STAGE_1) return OPQBotState.STAGE_1_NAVIGATE;
        // The tower is the hub every middle room is reached from. Standing in it means the party
        // is between rooms, so the middle-stage driver routes the next one - re-homing it to
        // STAGE_1_TRANSITION deadlocked, because that state only advances while the leader is
        // also in the tower, so a bot waiting there for a leader already in a room bounced
        // forever and never worked a stage.
        if (mapId == OPQConstants.OPQ_TOWER) return OPQBotState.MIDDLE_STAGE;
        if (mapId == OPQConstants.OPQ_STAGE_2) return OPQBotState.STAGE_2_NAVIGATE;
        if (mapId == OPQConstants.OPQ_EXIT_LOBBY) return OPQBotState.EXIT_DETECT;
        // Every other room in the tower is one of the quest's middle stages, and they share
        // the driver rather than each having a state of its own. Without this the bot would
        // be "somewhere unexpected" in any room but the two it originally knew, and would
        // never re-home itself back onto the party.
        if (OrbisPqData.isOrbisRoom(mapId)) return OPQBotState.MIDDLE_STAGE;
        return null;
    }

    /**
     * Avoid re-homing mid-stage: if we're already in a STAGE_1_* state and the
     * map-derived state is also a STAGE_1_* state, don't rewind us to NAVIGATE.
     */
    private boolean inSameStageFamily(OPQBotState a, OPQBotState b) {
        return family(a) != null && family(a).equals(family(b));
    }

    private String family(OPQBotState s) {
        if (s == null) return null;
        String n = s.name();
        if (n.startsWith("STAGE_1")) return "S1";
        if (n.startsWith("STAGE_2")) return "S2";
        if (n.startsWith("EXIT")) return "EX";
        if (n.equals("RECRUITMENT") || n.equals("IN_PARTY_IDLE")) return "LOBBY";
        return null;
    }

    private boolean isInParty() {
        return getChr().getParty() != null;
    }

    private Character getPartyLeader() {
        return getChr().getParty().getLeader().getPlayer();
    }

    private boolean leaderLeftStage2() {
        int leaderMap = getPartyLeader().getMapId();
        return leaderMap == OPQConstants.OPQ_EXIT_LOBBY || leaderMap == OPQConstants.OPQ_LOBBY;
    }

    private void followLeaderOut() {
        int leaderMap = getPartyLeader().getMapId();
        blockingSleep(1000); // deliberate: blocking follow-warps below
        if (leaderMap == OPQConstants.OPQ_EXIT_LOBBY) {
            OPQOrchestrator.getInstance().followLeaderWarp(getChr(), new Point(-161, 323));
            transitionTo(OPQBotState.EXIT_LOBBY, "leader already left stage 2 — following to exit lobby");
        } else {
            OPQOrchestrator.getInstance().followLeaderWarp(getChr(), new Point(-233, 174));
            transitionTo(OPQBotState.LOOP_CHECK, "leader already in OPQ lobby — skipping exit");
        }
    }

    private void startStageWaitTimer() {
        stageWaitStartTime = System.currentTimeMillis();
    }

    private boolean waitTimedOut() {
        return stageWaitStartTime > 0
                && (System.currentTimeMillis() - stageWaitStartTime) > OPQConstants.STAGE_WAIT_TIMEOUT_MS;
    }

    private void sharedContext_trySetPhase(OPQPhase phase) {
        // Bots observe their own mapId and ask the orchestrator to mirror the
        // derived phase into the blackboard. This keeps OPQSharedContext's
        // writers package-private while letting subscribers read a single
        // source of truth instead of polling mapIds themselves.
        if (sharedContext.getCurrentPhase() != phase) {
            orchestrator.mirrorPhase(phase);
        }
    }

    /**
     * Drop this bot's shared-context footprint when its FSM is torn down.
     *
     * <p>Without this the orchestrator kept every bot it had ever seen: registeredBots grew
     * for the process lifetime, its tick never stopped once one bot had registered, and the
     * completion checks kept counting bots that were long gone - so "all registered bots
     * reported done" and its stage-complete flags could never fire again. Releasing the
     * assignments here also frees their reactors for whichever bot takes over.
     */
    @Override
    public synchronized void stopScheduledTask() {
        orchestrator.unregisterBot(this);
        super.stopScheduledTask();
    }

    private void debugLogf(String msg) {
        // On by default: the OPQ FSM is all edge-triggered waits and stage flags, so a run
        // that stalls is unreadable without its transition log (which is how the altar-drop
        // and wrong-record bugs were found). Kept as a switch rather than deleted so it can
        // be turned off once the stage specs are stable.
        boolean opqDebug = true;
        if (!opqDebug) {
            return;
        }
        String line = "[OPQBot " + getChr().getName() + " " + opqBotState + "] " + msg;
        log(line);
        debugprint(line);
    }
}
