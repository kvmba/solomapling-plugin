package soloMapling.ArtificialPlayer.BotTypes.OPQ;

import org.gms.client.Character;
import org.gms.scripting.event.EventInstanceManager;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Reactor;
import soloMapling.ArtificialPlayer.BotCommandsPack.DropCommands;
import soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands;
import soloMapling.ArtificialPlayer.BotDialogueHandler;
import soloMapling.ArtificialPlayer.BotGeneration;
import soloMapling.ArtificialPlayer.BotGrindSystem.GrindTickRegistry;
import soloMapling.ArtificialPlayer.BotHealthSystem.BotPotionSim;
import soloMapling.ArtificialPlayer.BotMessagingSystem.ChatMessage;
import soloMapling.ArtificialPlayer.BotMessagingSystem.MessageQueue;
import soloMapling.ArtificialPlayer.BotPartySystem.BotPartyLogic;
import soloMapling.ArtificialPlayer.BotPartySystem.BotRecruitManager;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.ArtificialPlayer.PartyQuest.PositionCombination;
import soloMapling.ArtificialPlayer.PartyQuest.PqActions;
import soloMapling.ArtificialPlayer.PartyQuest.PqStageChat;
import soloMapling.ArtificialPlayer.BotTypes.OPQ.OPQSharedContext.OPQPhase;
import soloMapling.Environment.BotMessages;
import soloMapling.Environment.EnvironmentManager;
import soloMapling.Environment.PlatformPlacement;
import soloMapling.server.BotTiming;

import java.awt.Point;
import java.util.Collections;
import java.util.List;

import static soloMapling.ArtificialPlayer.BotCommandsPack.WarpCommands.botWarpMapOnPortal;
import static soloMapling.ArtificialPlayer.BotCommandsPack.WarpCommands.mapForBot;
import static soloMapling.ArtificialPlayer.BotGeneration.warpBotToLocation;
import static soloMapling.ArtificialPlayer.BotHelpers.blockingSleep;
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
public class OPQBot extends BotSM implements GrindTickRegistry.Participant {

    private static final int[] CLOUD_LOOT = {OPQConstants.CLOUD_PIECE};

    private final BotPotionSim potionSim = new BotPotionSim();

    // The combat sweep reads the phase while the macro tick advances it.
    private volatile OPQBotState opqBotState = OPQBotState.RESET;
    private volatile OPQOrchestrator orchestrator;
    private volatile OPQSharedContext sharedContext = new OPQSharedContext();
    private List<String> hint = Collections.singletonList(getChr().getName());

    // Per-state timers / scratch fields
    private long stageWaitStartTime;
    private long lastRecruitMessageAt;

    private final OrbisLounge lounge = new OrbisLounge();
    private volatile MapleMap leavingLoungeRoom;
    private final OrbisUpwardPassage upwardPassage = new OrbisUpwardPassage();

    private volatile int lootedRecordItemId = -1;

    public OPQBot(Character character) {
        super(character);
        dialoguePath = PARTY_QUEST_DIALOGUE_PATH;
        botType = "OPQBot";
        // Move off the shared per-channel client before doing any engine work: the PQ's
        // reactor callbacks fire seconds after the call that arms them, on a timer thread,
        // and read client.getPlayer() when they do. On the shared client that character is
        // whatever bot last bound itself - or null - so the altar's spawnNpc would silently
        // do nothing. See BotGeneration.adoptPrivateClient.
        BotGeneration.adoptPrivateClient(character);
        // The dynamic engine and the orchestrator registration both span the FSM's RUNNING
        // lifetime, so both live in startScheduledTask (see there) - a bot wrapped but never
        // started (the GM createAndSetBot paths) must not hold a live driver thread, the shared
        // movement lock, or an orchestrator slot that only stopScheduledTask would release.
    }

    /**
     * Start this bot's FSM. OPQBot extends BotSM directly (not PartyQuestBot), so it wires the
     * dynamic engine and its orchestrator slot here itself, in the same register-when-active
     * shape the base class uses - neither may be armed by the constructor, because the only
     * release seam is {@link #stopScheduledTask}.
     */
    @Override
    public synchronized void startScheduledTask(long initialDelayMs) {
        potionSim.tick(getChr());
        // Enable before the FSM starts and before any arrival warp, so the map-entry choreography
        // sees the bot as dynamic-controlled and skips the recorded portal drop.
        GCMovement.enable(getChr());
        bindRun();
        GrindTickRegistry.getInstance().register(this);
        super.startScheduledTask(initialDelayMs);
    }

    @Override
    public void grindTick() {
        Character bot = getChr();
        MapleMap room = bot.getMap();
        if (room == null || checkIfNotRunningOrPaused() || bot.getParty() == null || death().isCorpse()
                || status().isFrozen() || !GCMovement.hasGraph(bot)) return;
        var instance = room.getEventInstance();
        if (instance == null || bot.getMap() != room) return;
        int mapId = room.getId();
        if (instance.isEventCleared()) {
            if (mapId == OrbisPqData.STAGE_PRIZE && PqActions.findHumanPartyRoom(bot, map -> map == room) == room) {
                PqActions.seekAndAttack(bot);
            } else if (mapId == OrbisPqData.TREASURE_MAP || OrbisPqData.isJailRoom(mapId)) {
                PqActions.workReactorOnBeat(bot);
            }
            return;
        }
        switch (mapId) {
            case OPQConstants.OPQ_STAGE_1 -> {
                if (opqBotState == OPQBotState.STAGE_1_HIT_REACTOR && instance.getIntProperty("statusStg0") != 1) {
                    sweepAssignedReactor(bot);
                }
            }
            case OPQConstants.OPQ_STAGE_2 -> {
                if (opqBotState == OPQBotState.STAGE_2_HIT_BOX && instance.getIntProperty("statusStg3") < 0) {
                    sweepAssignedReactor(bot);
                }
            }
            default -> {
                int[] loot = combatLootIds(room, instance);
                if (loot == null) return;
                if ((loot == OrbisStages.WALKWAY_LOOT && !needsWalkwayPieces())
                        || (loot == OrbisStages.LOUNGE_LOOT && !needsLoungePieces())) {
                    PqActions.stopPartyLoot(bot);
                    PqActions.stopHunting(bot);
                } else if (loot == OrbisStages.LOUNGE_LOOT && PqActions.reactorBeatArmed(bot)) {
                    PqActions.workReactorOnBeat(bot);
                } else {
                    OrbisStages.fightAndCollect(bot, loot);
                }
            }
        }
    }

    private void sweepAssignedReactor(Character bot) {
        PqActions.workReactorOnBeat(bot);
        // A broken/stalled target needs a new stage action, not another wait on the macro cadence.
        if (!PqActions.reactorBeatArmed(bot)) requestTickWithin(0);
    }

    private int[] combatLootIds(MapleMap room, EventInstanceManager instance) {
        return switch (room.getId()) {
            case OrbisPqData.STAGE_WALKWAY -> instance.getIntProperty("statusStg1") != 1
                    ? OrbisStages.WALKWAY_LOOT : null;
            case OrbisPqData.STAGE_STORAGE -> instance.getIntProperty("statusStg2") != 1
                    ? OrbisStages.STORAGE_LOOT : null;
            case OrbisPqData.STAGE_PAPA -> instance.getIntProperty("statusStg7") == -1
                    ? OrbisStages.PAPA_LOOT : null;
            default -> OrbisPqData.LOUNGE_ROOMS.contains(room.getId()) && leavingLoungeRoom != room
                    && instance.getIntProperty("statusStg5") != 1 ? OrbisStages.LOUNGE_LOOT : null;
        };
    }

    // =========================================================================
    // State machine plumbing
    // =========================================================================

    private void bindRun() {
        OPQOrchestrator run = OPQOrchestrator.bind(this, orchestrator);
        if (run != orchestrator) {
            orchestrator = run;
            sharedContext = run.getSharedContext();
            resetRoomProgress();
        }
        orchestrator.noteLeaderFromBot(this);
    }

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

    public boolean wantsQuestLoot(int itemId) {
        Character bot = getChr();
        MapleMap room = bot.getMap();
        if (room == null) return false;
        var instance = room.getEventInstance();
        if (instance == null || instance.isEventCleared()) return false;
        if (room.getId() == OPQConstants.OPQ_STAGE_1) {
            return opqBotState == OPQBotState.STAGE_1_LOOT && itemId == OPQConstants.CLOUD_PIECE
                    && instance.getIntProperty("statusStg0") != 1;
        }
        if (room.getId() == OPQConstants.OPQ_STAGE_2) {
            return opqBotState == OPQBotState.STAGE_2_LOOT && instance.getIntProperty("statusStg3") < 0
                    && itemId == wantedRecord(room) && PqActions.countItem(bot, itemId) == 0;
        }
        int[] loot = combatLootIds(room, instance);
        if (loot != null) {
            for (int id : loot) if (itemId == id) return true;
        }
        return false;
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
        if (prev == OPQBotState.STAGE_1_HIT_REACTOR || prev == OPQBotState.STAGE_2_HIT_BOX) {
            PqActions.armReactorBeat(getChr(), -1);
        }
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
        lootedRecordItemId = -1;
        resetRoomProgress();
    }

    // =========================================================================
    // Main tick
    // =========================================================================

    @Override
    protected long lowPriorityDelayMs() {
        MapleMap map = getChr().getMap();
        return map != null && OrbisPqData.isOrbisRoom(map.getId()) && map.getEventInstance() != null
                ? PqActions.inRunDelayMs() : super.lowPriorityDelayMs();
    }

    @Override
    public void updateState() {
        super.updateState();
        if (checkIfNotRunningOrPaused()) {
            return;
        }
        bindRun();
        getDebugger().debugLoggingFull(
                String.format("%s OPQBotState: %s", this.getChr().getName(), opqBotState),
                String.format("%s", opqBotState));

        if (isInsidePQ() && !isInParty()) {
            handlePQAbandoned();
            return;
        }

        MapleMap map = getChr().getMap();
        if (map == null) return;
        if (map.getId() != OrbisPqData.STAGE_UP) upwardPassage.stopWalking(getChr());
        var instance = map.getEventInstance();
        if (map.getId() == OPQConstants.OPQ_LOBBY) {
            if (instance != null) {
                returnToLobby();
                return;
            }
            if (isInsidePQ() || opqBotState == OPQBotState.LOOP_CHECK) {
                handleLoopCheck();
                return;
            }
        }
        if (instance != null && OrbisPqData.isOrbisRoom(map.getId())) {
            potionSim.tick(getChr());
            if (!GCMovement.hasGraph(getChr())) GCMovement.ensureGraph(getChr());
            if (getChr().getMap() != map) return;
            if (instance.isEventCleared()) {
                transitionTo(OPQBotState.EXIT_DETECT, "settling the cleared run");
                workSettlement(map);
                return;
            }
        }

        // Authoritative re-home: if the game teleported us to a map we weren't
        // expecting, snap to the correct phase entry state.
        OPQBotState mapDerived = detectPhaseFromMap();
        if (mapDerived != null && mapDerived != opqBotState && !inSameStageFamily(mapDerived, opqBotState)) {
            transitionTo(mapDerived,
                    "map-derived rehome (mapId=" + getChr().getMapId() + ")");
        }

        // Eak can finish either collection room while this bot is still gathering or returning.
        int mapId = getChr().getMapId();
        if (mapId == OPQConstants.OPQ_STAGE_1 || mapId == OPQConstants.OPQ_STAGE_2) {
            int leaderMap = partyLeaderMapId();
            if (leaderMap == OPQConstants.OPQ_EXIT_LOBBY || leaderMap == OPQConstants.OPQ_LOBBY) {
                stopRoomWork();
                followLeaderOut();
                return;
            }
            if (mapId == OPQConstants.OPQ_STAGE_1 && readEimInt("statusStg0", -1) == 1) {
                stopRoomWork();
                transitionTo(OPQBotState.STAGE_1_TRANSITION, "Eak opened the tower route");
                handleStage1Transition();
                return;
            }
            if (mapId == OPQConstants.OPQ_STAGE_2) {
                int musicStatus = readEimInt("statusStg3", -1);
                if (musicStatus >= 0) {
                    stopRoomWork();
                    sharedContext.putBoxAssignment(getChr().getId(), null);
                    transitionTo(OPQBotState.STAGE_2_WAIT, "the music box has accepted its record");
                    if (musicStatus >= 1 && leaderMap != mapId && OrbisPqData.isOrbisRoom(leaderMap)) {
                        walkOutOfCurrentRoom();
                    } else {
                        PqActions.waitNearStageNpc(getChr(), OrbisPqData.EAK);
                    }
                    return;
                }
            }
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
                handleStage1Navigate(map);
                break;
            case STAGE_1_HIT_REACTOR:
                workAssignedReactor(map, true);
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
            case MIDDLE_STAGE:
                handleMiddleStage();
                break;
            case STAGE_2_NAVIGATE:
                handleStage2Navigate(map);
                break;
            case STAGE_2_HIT_BOX:
                workAssignedReactor(map, false);
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

    // =========================================================================
    // Shout recruiting
    // =========================================================================

    /**
     * Answer a stranger's open recruit shout ("anyone want to party?").
     *
     * <p>This bot extends {@link BotSM} rather than {@code PartyQuestBot} - its FSM predates the
     * shared quest skeleton - so it never inherited that class's recruit answer and the default
     * in {@code BotSM} is silence. In practice the Orbis lobby was the one quest lobby a player
     * could not recruit by shouting: the crowd stood there, shouted its own lines, and ignored
     * the player.
     *
     * <p>Only while waiting for a run. {@code RECRUITMENT} is the bot's own "standing in the
     * lobby, no party yet" state, so a bot idling in {@code IN_PARTY_IDLE} (already recruited)
     * or working a stage stays quiet, exactly like the shared skeleton's {@code offerRecruit}.
     * The accept window the roll arms is what the existing {@code checkPartyQueue} poll answers,
     * so a yes here is immediately actionable.
     */
    @Override
    public boolean offerRecruit(Character player, String content) {
        if (opqBotState != OPQBotState.RECRUITMENT || isInParty()) {
            return false; // already committed, or inside a run - the shout is not for us
        }
        BotRecruitManager.RecruitAnswer answer = BotRecruitManager.rollPartyAsk(
                getChr(), player, BotRecruitManager.SOCIAL_ACCEPT_CHANCE, false);
        if (answer == BotRecruitManager.RecruitAnswer.ON_COOLDOWN
                || answer == BotRecruitManager.RecruitAnswer.FOLLOWERS_FULL) {
            return false; // said no to this player recently; leave the slot to another bot
        }
        String node = answer == BotRecruitManager.RecruitAnswer.ACCEPTED
                ? "PartyAccept" : "PartyDecline";
        String line = BotDialogueHandler.getRandomResolvedLine(
                dialoguePath, botType, node, getChr(), null);
        if (line == null) {
            line = BotDialogueHandler.getRandomResolvedLine(
                    SOCIAL_DIALOGUE_PATH, "SocialBot", node, getChr(), null);
        }
        if (line != null) {
            SocialCommands.BotSpeak(getChr(), line);
        }
        return true;
    }

    @Override
    public void processMessages() {
        try {
            ChatMessage message = pollAddressedMessage(1000L);
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

            // Stroll to a fresh spot on the lobby floor between shouts (dynamic engine - the
            // lobby moves are no longer replayed from a recording). No-op while a stroll is
            // already running, so the shout cadence can't thrash the target mid-walk.
            PlatformPlacement.botStrollOnMap(getChr());
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

        if (partyLeaderMapId() == OPQConstants.OPQ_STAGE_1) {
            lootedRecordItemId = -1;
            // deliberate synchronous warp: warpBotToLocation blocks through the
            // arrival choreography (up to ~7s) and nothing may overlap it
            blockingSleep(3000);
            orchestrator.followLeaderWarp(getChr(), STAGE_1_ENTRY_TP);
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

    private void handleStage1Navigate(MapleMap room) {
        sharedContext_trySetPhase(OPQPhase.STAGE_1);

        Integer reactorOid = orchestrator.assignCloudReactor(this);
        debugLogf("handleStage1Navigate: reactorOid=" + reactorOid
                + "Char pos=" + getChr().getPosition());
        if (reactorOid == null) {
            if (collectClouds()) return;
            if (room.getAllReactors().stream().noneMatch(r -> r.getId() == OPQConstants.STAGE_1_CLOUD_REACTOR_ID
                    && PqActions.isStrikeable(r))) {
                transitionTo(OPQBotState.STAGE_1_RETURN,
                        "all cloud reactors are broken");
                handleStage1Return();
            }
            return;
        }

        transitionTo(OPQBotState.STAGE_1_HIT_REACTOR, "assigned cloud reactor oid=" + reactorOid);
        workAssignedReactor(room, true);
    }

    private void workAssignedReactor(MapleMap room, boolean cloud) {
        Character bot = getChr();
        if (bot.getMap() != room) return;
        int botId = bot.getId();
        OPQBotState navigate = cloud ? OPQBotState.STAGE_1_NAVIGATE : OPQBotState.STAGE_2_NAVIGATE;
        Integer reactorOid = cloud ? sharedContext.getMyCloudAssignment(botId) : sharedContext.getMyBoxAssignment(botId);
        if (reactorOid == null) {
            transitionTo(navigate, "no reactor assignment");
            return;
        }

        Reactor reactor = room.getReactorByOid(reactorOid);
        if (!PqActions.isStrikeable(reactor)) {
            transitionTo(cloud ? OPQBotState.STAGE_1_LOOT : OPQBotState.STAGE_2_LOOT,
                    "reactor broken oid=" + reactorOid);
            requestTickWithin(0);
            return;
        }
        // Once armed, the sweep owns the walk and swings as soon as it reaches the reactor.
        if (PqActions.reactorBeatArmed(bot)
                || PqActions.approachReactor(bot, room, reactorOid, false, true) != PqActions.Approach.STUCK
                || bot.getMap() != room) return;
        PqActions.deferReactorApproach(bot, reactorOid);
        if (cloud) sharedContext.putCloudAssignment(botId, null);
        else sharedContext.putBoxAssignment(botId, null);
        transitionTo(navigate, "reactor approach stalled oid=" + reactorOid);
    }

    private void handleStage1Loot() {
        if (collectClouds()) return;
        transitionTo(OPQBotState.STAGE_1_NAVIGATE,
                "loot pass complete, searching for next cloud reactor");
        requestTickWithin(0);
    }

    private boolean collectClouds() {
        PqActions.LootBeat loot = PqActions.collectReactorLoot(getChr(), CLOUD_LOOT);
        if (loot == PqActions.LootBeat.NONE) return false;
        if (loot == PqActions.LootBeat.PICKED_UP) {
            SocialCommands.BotChatbubble(getChr(), BotMessages.get("opq.cloud_pieces", cloudPiecesCount()));
        }
        requestTickWithin(GrindTickRegistry.TICK_MS);
        return true;
    }

    private void handleStage1Return() {
        // Walk to the altar, not to the leader's old spot: the drop has to land inside the
        // altar's trigger box, and the altar sits at x=377 (the old target, x=497, was 20px
        // outside it to the right - which is why the altar never fired).
        if (!PqActions.walkTo(getChr(), OPQConstants.STAGE_1_DROP_POS)) return;
        transitionTo(OPQBotState.STAGE_1_DROP_ITEMS, "return state done.");
        requestTickWithin(0);
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
        boolean thrower = orchestrator.isAltarThrower(this);
        boolean throwing = thrower && altarStillArmed();
        debugLogf("handleStage1DropItems: held=" + cloudPiecesCount()
                + " thrower=" + thrower + " throwing=" + throwing
                + " pos=" + getChr().getPosition());

        if (throwing) {
            Character bot = getChr();
            MapleMap clouds = bot.getMap();
            BotTiming.after(400, () -> {
                if (bot.getMap() == clouds && PqActions.readEimInt(bot, "statusStg0", -1) != 1) {
                    SocialCommands.BotSpeak(bot, BotMessages.get("opq.dropping_clouds",
                            OPQConstants.CLOUD_REQUIRED, BotMessages.get("opq.cloud_plural")));
                    DropCommands.botThrowItemQty(bot, OPQConstants.CLOUD_PIECE,
                            OPQConstants.CLOUD_REQUIRED, OPQConstants.STAGE_1_DROP_POS);
                }
            });
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
        if (getChr().getMap().getNPCById(OrbisPqData.EAK) != null) {
            PqActions.waitNearStageNpc(getChr(), OrbisPqData.EAK);
            return;
        }
        if (orchestrator.hasUnclaimedLiveCloudReactor(getChr().getMap(), getChr().getId())) {
            transitionTo(OPQBotState.STAGE_1_NAVIGATE, "a cloud reactor is available again");
            return;
        }
        // An altar that never fires (the offering was looted, missed its box, etc.) may time out.
        if (waitTimedOut()) {
            transitionTo(OPQBotState.LOOP_CHECK,
                    "stage-1 wait timed out after "
                            + OPQConstants.STAGE_WAIT_TIMEOUT_MS + "ms");
        }
    }

    private void handleStage1Transition() {
        Character bot = getChr();
        Character leader = PqActions.partyLeader(bot);
        MapleMap here = bot.getMap();
        MapleMap target = leader == null ? null : leader.getMap();
        if (target == null || here.getEventInstance() != target.getEventInstance()
                || !OrbisPqData.isOrbisRoom(target.getId())) {
            return;
        }
        // Eak moves the team to the top platform; the tower is reached via in00 even when
        // the leader has already left it for another room.
        var door = here.getPortal("in00");
        if (door != null) {
            if (target == here) PqActions.walkToPortal(bot, door);
            else if (target.getId() != OPQConstants.OPQ_STAGE_1) PqActions.walkThroughPortal(bot, door);
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
        if (rallyInClearedRoom()) return;

        int stage = currentMiddleStage();
        int here = getChr().getMapId();
        if ((stage != 5 && OrbisPqData.LOUNGE_ROOMS.contains(here))
                || (stage != 6 && here == OrbisPqData.STAGE_UP)) {
            stopRoomWork();
        }
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
            OrbisStages.feedPapaReactors(getChr());
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

        doRoomWork(stage);
    }

    /** Like LPQ, regroup at the turn-in post before following the leader out of a cleared room. */
    private boolean rallyInClearedRoom() {
        Character bot = getChr();
        int stage = switch (bot.getMapId()) {
            case OrbisPqData.STAGE_WALKWAY -> 1;
            case OrbisPqData.STAGE_STORAGE -> 2;
            case OrbisPqData.STAGE_SEALED -> 4;
            case OrbisPqData.STAGE_LOUNGE -> 5;
            case OrbisPqData.STAGE_UP -> 6;
            case OrbisPqData.STAGE_PAPA -> 7;
            default -> 0;
        };
        if (stage == 0 || readEimInt("statusStg" + stage, -1) != 1) return false;
        Character leader = PqActions.partyLeader(bot);
        if (leader == null || leader.getMap() != bot.getMap()) return false;
        stopRoomWork();
        if (stage == 4) PqActions.waitNearPuzzleNpc(bot, OrbisPqData.EAK);
        else PqActions.waitNearStageNpc(bot, OrbisPqData.EAK);
        return true;
    }

    private void stopRoomWork() {
        Character bot = getChr();
        upwardPassage.stopWalking(bot);
        PqActions.armReactorBeat(bot, -1);
        PqActions.stopPartyLoot(bot);
        PqActions.stopHunting(bot);
    }

    /** Gather the lounge's pieces, then bring them back to Eak in the hall. */
    private void workLounge() {
        Character bot = getChr();
        MapleMap room = bot.getMap();
        int here = room.getId();
        if (OrbisPqData.LOUNGE_ROOMS.contains(here)) {
            if (leavingLoungeRoom != room && needsLoungePieces()) {
                boolean quiet = OrbisStages.gatherLounge(bot);
                if (!lounge.roomClear(room, quiet)) {
                    if (quiet) requestTickWithin(OrbisLounge.ROOM_DROP_SETTLE_MS);
                    return;
                }
                lounge.finishedRoom(here);
            }
            // A portal walk can span ticks; finish it without starting another loot pass.
            leavingLoungeRoom = room;
            stopRoomWork();
            walkOutOfCurrentRoom();
            if (bot.getMap() != room) {
                leavingLoungeRoom = null;
                lounge.resetHallWalks();
            }
            return;
        }
        leavingLoungeRoom = null;
        if (here != OrbisPqData.STAGE_LOUNGE) {
            walkToRoom(OrbisStages.roomFor(5));
            return;
        }
        if (needsLoungePieces()) {
            var beat = lounge.step(bot, sharedContext.loungePuzzle());
            if (beat == OrbisLounge.Beat.ADVANCED) requestTickWithin(0);
            if (beat != OrbisLounge.Beat.NO_WORK) return;
        }
        lounge.stopWalking(bot);
        PqActions.deliverAndWait(bot, OrbisPqData.STATUE_PIECE_5, OrbisPqData.EAK);
    }

    private void resetRoomProgress() {
        lounge.reset();
        leavingLoungeRoom = null;
        upwardPassage.reset(getChr());
    }

    private boolean needsLoungePieces() {
        return PqActions.needsHuntForLeader(getChr(), OrbisPqData.STATUE_PIECE_5, OrbisPqData.LOUNGE_COUNT);
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
     * Whether the six scar reactors in the tower are already lit, which is Eak's own test
     * ({@code isStatueComplete}) and the gate on his sending the party to Papa Pixie.
     *
     * <p>There is no flag for this - no script sets {@code statusStg7} except the spring - so the
     * reactors are read directly, exactly as Eak reads them. The scars live only in the tower,
     * so a bot in another room cannot see them; but standing in Papa Pixie's room means Eak has
     * already sent the party there, and he only does that once the statue is complete - so that
     * room counts as "done" rather than bouncing the bot back to the tower it cannot reach.
     */
    private static boolean scarsComplete(Character bot) {
        if (bot.getMap() == null) {
            return false;
        }
        if (bot.getMapId() == OrbisPqData.STAGE_PAPA) {
            return true; // Eak only warps here once isStatueComplete()
        }
        if (bot.getMapId() != OrbisPqData.TOWER_MAP) {
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
        var exit = getChr().getMap().getPortal(OrbisPqData.ROOM_EXIT_PORTAL_NAME);
        if (exit == null) {
            exit = getChr().getMap().getPortal(OrbisPqData.LOUNGE_EXIT_PORTAL);
        }
        if (exit != null) PqActions.walkThroughPortal(getChr(), exit);
    }

    /** Papa Pixie's room is entered by Eak's warp, so the bot simply stays with the leader. */
    private void followLeaderIntoPapaRoom() {
        if (partyLeaderMapId() == OrbisPqData.STAGE_PAPA) {
            orchestrator.followLeaderWarp(getChr(),
                    OrbisPqData.PAPA_SPRING_SPOT);
        } else if (getChr().getMapId() == OrbisPqData.TOWER_MAP) {
            PqActions.waitNearStageNpc(getChr(), OrbisPqData.EAK);
        } else {
            walkToTower();
        }
    }

    /**
     * Walk onto the room's tower portal and step through it.
     *
     * <p>The tower portals ({@code in00..in06}) are script portals: the script that warps to the
     * room only runs on entering the named door.
     */
    private void walkToRoom(OrbisStages.StageRoom room) {
        if (getChr().getMapId() != OPQConstants.OPQ_TOWER) {
            // Somewhere else (a sub-room, a room left behind): walk out to the tower first.
            walkToTower();
            return;
        }
        var portal = getChr().getMap().getPortal(OrbisPqData.towerPortalName(room.portalInTower()));
        if (portal != null) {
            PqActions.walkThroughPortal(getChr(), portal);
        }
    }

    private boolean needsWalkwayPieces() {
        return PqActions.needsHuntForLeader(getChr(), OrbisPqData.STATUE_PIECE_1, OrbisPqData.WALKWAY_COUNT);
    }

    private void doRoomWork(int stage) {
        Character bot = getChr();
        switch (stage) {
            case 1 -> {
                if (!needsWalkwayPieces() || (bot.getMap().countMonsters() == 0
                        && PqActions.collectPartyQuestLoot(bot, OrbisStages.WALKWAY_LOOT) == PqActions.LootBeat.NONE)) {
                    PqActions.stopPartyLoot(bot);
                    PqActions.deliverAndWait(bot, OrbisPqData.STATUE_PIECE_1, OrbisPqData.EAK);
                }
            }
            case 2 -> {
                if (bot.getMap().countMonsters() == 0 && bot.getMap().countItems() == 0) {
                    PqActions.stopHunting(bot);
                    PqActions.waitNearStageNpc(bot, OrbisPqData.EAK);
                }
            }
            case 3 -> {
                // The music box is the one stage the bot already knew how to finish.
                transitionTo(OPQBotState.STAGE_2_NAVIGATE, "music box room reached");
                requestTickWithin(0);
            }
            case 4 -> {
                var plan = OrbisStages.standOnSealedPlatform(getChr(), PositionCombination.botIndex(getChr()));
                PqStageChat.announcePositionPlan(this, OrbisPqData.STAGE_4, plan, OrbisPqData.SEALED_AREA_RECTS);
            }
            case 6 -> {
                if (upwardPassage.step(bot, sharedContext.upwardPuzzle())) requestTickWithin(0);
            }
            default -> { /* nothing to do */ }
        }
    }

    // =========================================================================
    // Phase: Stage 2
    // =========================================================================

    private void handleStage2Navigate(MapleMap room) {
        sharedContext_trySetPhase(OPQPhase.STAGE_2);

        if (leaderLeftStage2()) {
            sharedContext.putBoxAssignment(getChr().getId(), null);
            followLeaderOut();
            return;
        }
        if (collectMissingRecord(wantedRecord(room))) return;

        Integer reactorOid = orchestrator.assignBoxReactor(this);
        debugLogf("handleStage2Navigate: reactorOid=" + reactorOid
                + " pos=" + getChr().getPosition());

        if (reactorOid == null && !orchestrator.hasUnclaimedLiveBoxReactor(room, getChr().getId())) {
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

        transitionTo(OPQBotState.STAGE_2_HIT_BOX, "assigned record box oid=" + reactorOid);
        workAssignedReactor(room, false);
        if (opqBotState == OPQBotState.STAGE_2_HIT_BOX) {
            String ordinal = orchestrator.getBoxOrdinal(reactorOid);
            SocialCommands.BotSpeak(getChr(), BotMessages.get("opq.taking_box", localizeOrdinal(ordinal)));
        }
    }

    private void handleStage2Loot() {
        int wanted = wantedRecord(getChr().getMap());
        if (collectMissingRecord(wanted)) return;

        // Which record the bot ended up holding is read back off the inventory rather than
        // predicted from the box: the engine's own reactordrops decides what each box yields.
        boolean carryingRecord = wanted > 0 && PqActions.countItem(getChr(), wanted) > 0;
        lootedRecordItemId = carryingRecord ? wanted : -1;

        debugLogf("handleStage2Loot: carryingRecord=" + carryingRecord + " wanted=" + wanted);
        if (carryingRecord) {
            SocialCommands.BotChatbubble(getChr(), BotMessages.get("opq.got_record"));
        }
        // Clear box assignment so we can pick a new one
        sharedContext.putBoxAssignment(getChr().getId(), null);

        if (carryingRecord) {
            // Nothing more to hunt for - take it to the music box now instead of breaking
            // boxes the leader may still want.
            transitionTo(OPQBotState.STAGE_2_RETURN, "holding today's record, returning to music box");
        } else {
            transitionTo(OPQBotState.STAGE_2_NAVIGATE,
                    "this box did not hold today's record, trying another");
        }
        requestTickWithin(0);
    }

    /** A teammate may have already broken the right box, including while we were waiting. */
    private boolean collectMissingRecord(int wanted) {
        Character bot = getChr();
        if (wanted <= 0 || PqActions.countItem(bot, wanted) > 0) return false;
        PqActions.LootBeat loot = PqActions.collectReactorLoot(bot, new int[]{wanted});
        if (loot == PqActions.LootBeat.NONE) return false;
        transitionTo(OPQBotState.STAGE_2_LOOT, "today's record is available");
        requestTickWithin(loot == PqActions.LootBeat.PICKED_UP ? 0 : GrindTickRegistry.TICK_MS);
        return true;
    }

    /** Read the run's own music-box requirement, fixed when this instance opened. */
    static int wantedRecord(MapleMap room) {
        Reactor music = room.getReactorByName("music");
        if (music == null || !music.isAlive() || music.getReactorType() != 100) return -1;
        var requirement = music.getReactItem(music.getEventState());
        return requirement == null ? -1 : requirement.getLeft();
    }

    private boolean musicPlaying() {
        return PqActions.readEimInt(getChr(), "statusStg3", -1) >= 0;
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
        if (!PqActions.walkTo(getChr(), OPQConstants.STAGE_2_DROP_POS)) return;
        transitionTo(OPQBotState.STAGE_2_DROP_ITEMS,
                "arrived at music box drop zone");
        requestTickWithin(0);
    }

    private void handleStage2DropItems() {
        if (lootedRecordItemId > 0) {
            int recordId = lootedRecordItemId;
            Character bot = getChr();
            MapleMap musicRoom = bot.getMap();
            BotTiming.after(400, () -> {
                if (bot.getMap() == musicRoom && wantedRecord(musicRoom) == recordId
                        && !musicPlaying()) {
                    SocialCommands.BotSpeak(bot, BotMessages.get("opq.dropping_record"));
                    DropCommands.botThrowItem(bot, recordId, OPQConstants.STAGE_2_DROP_POS);
                }
            });
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
        int leaderMap = partyLeaderMapId();
        if (leaderMap == OPQConstants.OPQ_EXIT_LOBBY) {
            blockingSleep(1000); // deliberate: blocking follow-warp below
            orchestrator.followLeaderWarp(getChr(), new Point(-161, 323));
            transitionTo(OPQBotState.EXIT_LOBBY, "followed leader to exit lobby");
            return;
        }
        if (leaderMap == OPQConstants.OPQ_LOBBY) {
            returnToLobby();
            return;
        }

        if (collectMissingRecord(wantedRecord(getChr().getMap()))) return;
        if (sharedContext.isStage2Complete()) {
            transitionTo(OPQBotState.EXIT_DETECT,
                    "stage2Complete flag flipped by orchestrator");
            return;
        }
        if (orchestrator.hasUnclaimedLiveBoxReactor(getChr().getMap(), getChr().getId())) {
            transitionTo(OPQBotState.STAGE_2_NAVIGATE, "a record box is available again");
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

    private void workSettlement(MapleMap here) {
        Character bot = getChr();
        var instance = here.getEventInstance();
        MapleMap target = PqActions.findHumanPartyRoom(bot,
                room -> room.getEventInstance() == instance && OrbisPqData.isOrbisRoom(room.getId()));
        if (target == null) {
            returnToLobby();
            return;
        }
        int mapId = here.getId();
        int to = target.getId();
        if (mapId != OrbisPqData.TREASURE_MAP
                && !(here == target && OrbisPqData.isJailRoom(mapId) && mapId % 10 == 2)) {
            PqActions.armReactorBeat(bot, -1);
        }
        if (mapId == OrbisPqData.REWARD_MAP) {
            PqActions.waitNearStageNpc(bot, OrbisPqData.MINERVA);
        } else if (to != mapId && (to == OrbisPqData.REWARD_MAP || to == OrbisPqData.TREASURE_MAP)) {
            // Minerva can be entered from either branch; Eak's hidden-room dialogue is
            // leader-only. Follow only into a settlement room already opened for the party.
            GCMovement.changeMap(bot, target, target.getPortal(0));
        } else if (mapId == OrbisPqData.TREASURE_MAP) {
            if (!PqActions.workActiveReactors(bot, OrbisPqData.TREASURE_BOX)) {
                PqActions.waitNearStageNpc(bot, OrbisPqData.MINERVA);
            }
        } else if (mapId == OrbisPqData.STAGE_PRIZE && to == mapId) {
            if (here.countMonsters() == 0) {
                PqActions.stopHunting(bot);
                PqActions.waitNearStageNpc(bot, OrbisPqData.EAK);
            }
        } else {
            followSettlementDoor(here, to);
        }
    }

    private void followSettlementDoor(MapleMap here, int to) {
        int mapId = here.getId();
        var door = switch (mapId) {
            case OrbisPqData.TOWER_MAP -> OrbisPqData.isJailRoom(to) ? here.getPortal("hiden00")
                    : to == OrbisPqData.STAGE_PRIZE ? here.getPortal("in06") : null;
            case OrbisPqData.STAGE_JAIL -> OrbisPqData.isJailRoom(to) && to != mapId
                    ? here.getPortal("in0" + (to - mapId) / 10) : null;
            default -> null;
        };
        if (door != null) {
            PqActions.walkThroughPortal(getChr(), door);
        } else if (mapId == OrbisPqData.TOWER_MAP) {
            PqActions.waitNearStageNpc(getChr(), OrbisPqData.MINERVA);
        } else if (mapId == OrbisPqData.STAGE_JAIL && to == OrbisPqData.TOWER_MAP) {
            PqActions.walkThroughPortal(getChr(), here.getPortal("out00"));
        } else if (OrbisPqData.isJailRoom(mapId) && mapId != OrbisPqData.STAGE_JAIL) {
            if (mapId % 10 == 2) {
                if (to == mapId) {
                    PqActions.workActiveReactors(getChr(), 2002017, 2002018);
                } else {
                    PqActions.walkThroughPortal(getChr(), here.getPortal("out00"));
                }
                return;
            }
            if (to == mapId || to == mapId + 2) {
                var lever = here.getAllReactors().stream()
                        .filter(reactor -> reactor.getId() == 2008007 && reactor.getState() == 0)
                        .findFirst().orElse(null);
                if (lever != null) PqActions.hitReactor(getChr(), lever.getObjectId());
            }
            var gate = here.getPortal("in00");
            if (to == mapId + 2 && gate != null) PqActions.walkThroughPortal(getChr(), gate);
            if (to == OrbisPqData.TOWER_MAP || to == OrbisPqData.STAGE_JAIL
                    || OrbisPqData.isJailRoom(to) && to / 10 != mapId / 10) {
                PqActions.walkThroughPortal(getChr(), here.getPortal("out00"));
            }
        } else if (mapId != to) {
            walkOutOfCurrentRoom();
        }
    }

    private void returnToLobby() {
        Character bot = getChr();
        PqActions.leaveEventInstance(bot);
        var lobby = mapForBot(bot, OPQConstants.OPQ_LOBBY);
        if (lobby != null) {
            GCMovement.changeMap(bot, lobby, lobby.getPortal(0));
            if (bot.getMap() == lobby) handleLoopCheck();
        }
    }

    private void handleExitDetect() {
        debugLogf("handleExitDetect: mapId=" + getChr().getMapId()
                + " leaderMap=" + partyLeaderMapId());
        sharedContext_trySetPhase(OPQPhase.EXIT);

        if (getChr().getMapId() == OPQConstants.OPQ_EXIT_LOBBY) {
            transitionTo(OPQBotState.EXIT_LOBBY, "arrived at exit lobby");
            return;
        }

        // Actively follow leader to exit lobby or recruitment lobby
        int leaderMap = partyLeaderMapId();
        if (leaderMap == OPQConstants.OPQ_EXIT_LOBBY) {
            blockingSleep(1000); // deliberate: blocking follow-warp below
            orchestrator.followLeaderWarp(getChr(), new Point(-161, 323));
            transitionTo(OPQBotState.EXIT_LOBBY, "followed leader to exit lobby");
        } else if (leaderMap == OPQConstants.OPQ_LOBBY) {
            returnToLobby();
        }
    }

    private void handleExitLobby() {
        debugLogf("handleExitLobby: mapId=" + getChr().getMapId()
                + " leaderMap=" + partyLeaderMapId());

        MapleMap here = getChr().getMap();
        if (PqActions.findHumanPartyRoom(getChr(), room -> room == here) != null) {
            PqActions.waitNearStageNpc(getChr(), OrbisPqData.EAK);
            return;
        }

        returnToLobby();
    }

    private void handleLoopCheck() {
        debugLogf("handleLoopCheck: inParty=" + isInParty()
                + " mapId=" + getChr().getMapId());
        // Clear ALL per-run scratch so next run starts completely clean.
        sharedContext.clearTaskComplete(getChr().getId());
        sharedContext.putCloudAssignment(getChr().getId(), null);
        sharedContext.putBoxAssignment(getChr().getId(), null);
        sharedContext.putPlatformAssignment(getChr().getId(), null);
        stageWaitStartTime = 0;
        lootedRecordItemId = -1;
        resetRoomProgress();

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

        returnToLobby();

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

    private int partyLeaderMapId() {
        Character leader = PqActions.partyLeader(getChr());
        return leader == null ? -1 : leader.getMapId();
    }

    private boolean leaderLeftStage2() {
        int leaderMap = partyLeaderMapId();
        return leaderMap == OPQConstants.OPQ_EXIT_LOBBY || leaderMap == OPQConstants.OPQ_LOBBY;
    }

    private void followLeaderOut() {
        int leaderMap = partyLeaderMapId();
        blockingSleep(1000); // deliberate: blocking follow-warps below
        if (leaderMap == OPQConstants.OPQ_EXIT_LOBBY) {
            orchestrator.followLeaderWarp(getChr(), new Point(-161, 323));
            transitionTo(OPQBotState.EXIT_LOBBY, "leader already left stage 2 — following to exit lobby");
        } else if (leaderMap == OPQConstants.OPQ_LOBBY) {
            returnToLobby();
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
            orchestrator.mirrorPhase(this, phase);
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
        GrindTickRegistry.getInstance().unregister(this);
        if (orchestrator != null) {
            OPQOrchestrator.unbind(this, orchestrator);
            orchestrator = null;
        }
        // OPQBot extends BotSM directly, so it does not inherit PartyQuestBot's GCMovement.disable.
        // Release the dynamic engine (and the shared movement lock it holds) on stop/convert.
        GCMovement.disable(getChr());
        PqActions.clearSeekState(getChr().getId());
        PqActions.clearReactorBeat(getChr().getId());
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
