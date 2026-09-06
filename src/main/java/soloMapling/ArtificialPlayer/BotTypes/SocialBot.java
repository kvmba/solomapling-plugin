package soloMapling.ArtificialPlayer.BotTypes;

import org.gms.client.Character;
import soloMapling.ArtificialPlayer.BotChatterSystem.BotChatter;
import soloMapling.ArtificialPlayer.ConversationManager;
import soloMapling.ArtificialPlayer.BotDialogueHandler;
import soloMapling.ArtificialPlayer.BotFlavorSystem.BotFlavor;
import soloMapling.ArtificialPlayer.BotFlavorSystem.LevelUpCongrats;
import soloMapling.ArtificialPlayer.BotGrindSystem.MapMobIndex;
import soloMapling.ArtificialPlayer.LlmSystem.SocialChatSessionStore;
import soloMapling.ArtificialPlayer.LlmSystem.SocialLlmConfig;
import soloMapling.ArtificialPlayer.LlmSystem.SocialLlmService;
import soloMapling.ArtificialPlayer.BotMessagingSystem.ChatMessage;
import soloMapling.ArtificialPlayer.BotMessagingSystem.MessageQueue;
import soloMapling.ArtificialPlayer.BotPartySystem.BotPartyQueue;
import soloMapling.ArtificialPlayer.BotPartySystem.BotRecruitManager;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotTownSystem.TownStation;
import soloMapling.ArtificialPlayer.BotTypeManager;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.server.BotTiming;
import soloMapling.server.EventMessageSystem.EventBus;
import soloMapling.server.EventMessageSystem.EventType;
import soloMapling.server.EventMessageSystem.GameEvent;
import soloMapling.server.MethodScheduler;
import org.gms.util.PacketCreator;
import soloMapling.Environment.BotMessages;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands.*;
import static soloMapling.ArtificialPlayer.BotHelpers.isBot;
import static soloMapling.ArtificialPlayer.BotCustomization.getRandomChairId;
import static soloMapling.ArtificialPlayer.BotMovementSystem.MovementCommands.*;
import static soloMapling.BotLogger.log;
import static soloMapling.server.SoloMaplingUtilities.random;

public class SocialBot extends BotSM {

    public enum SocialBotVariant { SINGLE_RESPONSE, INTERACTIVE }

    private enum InteractionLevel { NORMAL, REDUCED, NONVERBAL, IGNORE }

    private enum SocialBotState { IDLE_AMBIENT, GREETING, AWAITING_CHOICE, RESPONDING }

    // Written from Dispatcher/chain virtual threads, read on the tick — keep volatile.
    private volatile SocialBotState socialState = SocialBotState.IDLE_AMBIENT;
    private final SocialBotVariant variant;
    private final Map<Integer, InteractionTracker> interactionTrackers = new ConcurrentHashMap<>();

    private static final long CONVERSATION_TIMEOUT_MS = 35_000;
    private volatile long lastRespondantMessageTime = 0;

    private static final long TRACKER_CLEANUP_INTERVAL_MS = 120_000;
    private long lastCleanupTime = System.currentTimeMillis();

    private static final double RARE_LINE_CHANCE = 0.01;
    private static final double GOODBYE_SIT_CHANCE = 0.40;

    // Smack talk ("你 120 级还穿这破烂"): the server-rat flavour, but gated hard. It only fires
    // when the OUTMATCHED check passes (a bully picks on someone weaker, not on a 200-geared
    // player), only sometimes, and only once per player per cooldown - a player who keeps talking
    // to a bot should not get roasted every single time.
    private static final double SMACK_TALK_CHANCE = 0.25;
    private static final int SMACK_TALK_LEVEL_GAP = 15;   // bot must lead by this much
    private static final long SMACK_TALK_COOLDOWN_MS = 300_000; // 5 min per player
    private final Map<Integer, Long> smackTalkCooldowns = new ConcurrentHashMap<>();
    private volatile boolean wasSittingBeforeInteraction = false;
    private volatile int originalChairId = 0;

    // Stationed loiter (Feature B): claim a ledge on arrival so this cohort coordinates with returning
    // training bots' TownLoiter through the shared BotSpotClaims registry (Gap #3), and drift occasionally.
    private volatile boolean townClaimed = false;
    private volatile Point townAnchor = null; // spawn portal - the reference point relocation samples around
    private volatile boolean relocating = false; // owns movement during a drift walk; blocks ambient actions
    private volatile long nextChairActionMs = 0;
    private volatile long nextRelocateAtMs = 0;
    private volatile long nextStrollAtMs = 0;

    // Occasional walk to a neighbouring town map (Henesys Market/Park and the like) and back. In-map
    // relocation can never leave the current map - the nav graph drops cross-map portals - so without
    // this a bot stationed on one street of a town never sees the rest of it.
    private static final long STROLL_MIN_MS = 240_000;         // 4 min
    private static final long STROLL_MAX_MS = 660_000;         // 11 min
    private static final long STROLL_DWELL_MIN_MS = 30_000;    // browse the neighbouring map 30-90 s
    private static final long STROLL_DWELL_MAX_MS = 90_000;
    private volatile boolean strolling = false; // owns movement for the whole outing; blocks ambient actions
    private volatile int strollHomeMapId = -1;  // where to come back to (and where to bail to if engaged)
    // Guards the return trip: strollHome can be reached twice (a player engaging mid-stroll, and the
    // dwell timer firing), and GCTravel.travel() cancels the trip in flight, so a second call would
    // restart the walk from wherever the first one had got to.
    private volatile boolean strollReturning = false;

    // Loiter tuning (candidates for a live !env chatter/loiter readout). Chair sit/stand is rare so it reads
    // as a real person resting, not a metronome; relocation is a slow per-bot drift, biased to happen while
    // unobserved so players just find bots in fresh spots.
    private static final double IDLE_CHAIR_CHANCE = 0.04;      // per eligible observed tick
    private static final long IDLE_CHAIR_COOLDOWN_MIN_MS = 60_000;
    private static final long IDLE_CHAIR_COOLDOWN_MAX_MS = 180_000;
    private static final long RELOCATE_MIN_MS = 180_000;       // 3 min
    private static final long RELOCATE_MAX_MS = 480_000;       // 8 min
    private static final double OBSERVED_RELOCATE_CHANCE = 0.15; // usually defer a drift while watched
    private static final long OBSERVED_DEFER_MS = 30_000;      // retry window when we defer an observed drift

    // Interactive menu shown once a player engages. Labels are localized; the keyword sets below
    // are the English ones and stay authoritative (they are matched as substrings, and a couple
    // deliberately narrow entries - see resolveMenuCategory). BotMessages.keywords() appends the
    // localized label text and any per-language YAML aliases, so the menu answers in both
    // languages no matter which one is displayed.
    private static final String[] INTERACTIVE_SUFFIXES = {
            "whatsup", "interesting", "rumors", "teamup", "goodbye"
    };
    private static final String[][] INTERACTIVE_KEYWORDS = {
            {"what's up", "whats up"},
            {"interesting"},
            {"rumor"},
            {"team", "party"},
            {"bye", "goodbye", "cya", "later"}
    };

    private static final String DIALOGUE_PATH = "SocialBotDialogue.yaml";
    private static final String BOT_TYPE_KEY = "SocialBot";

    public SocialBot(Character character) {
        super(character);
        dialoguePath = DIALOGUE_PATH;
        botType = "SocialBot";
        this.variant = random.nextDouble() < 0.30 ? SocialBotVariant.INTERACTIVE : SocialBotVariant.SINGLE_RESPONSE;
        EventBus.getInstance().subscribe(EventType.LEVEL_UP, this); // congratulate nearby levelers
    }

    public SocialBotVariant getVariant() {
        return variant;
    }

    @Override
    public boolean isAvailableForAmbientActions() {
        // Single source of truth for every ambient system. isInConversation covers the scripted
        // multi-bot cluster convos (ConversationManager); folding it in here stops BotChatter (which
        // gates only on availability) from grabbing a bot mid cluster-conversation -> double bubbles.
        return !hasActiveRespondant() && !relocating && !strolling && !BotChatter.isEngaged(getChr())
                && !ConversationManager.getInstance().isInConversation(getChr().getId());
    }

    public boolean hasActiveRespondant() {
        return getInteractors().getRespondant() != null;
    }

    @Override
    public void checkPrioritySpeed() {
        // Snappy while a player is mid-conversation, and also while the bot is armed after saying yes -
        // so pollRecruitInvite drains the incoming invite promptly instead of dropping to slow cadence
        // once resetConversation ends the exchange.
        if (hasActiveRespondant() || BotRecruitManager.isArmed(getChr().getId())) {
            setPriorityHigh();
            return;
        }
        if (checkMainPlayersOnMap()) {
            setPriorityNormal();
            return;
        }
        updateScheduleDelay(30000);
    }

    @Override
    public void updateState() {
        super.updateState();
        if (checkIfNotRunningOrPaused()) return;
        getDebugger().debugLoggingFull(String.format("%s SocialBotState: %s", getChr().getName(), socialState), String.format("%s", socialState));

        checkPrioritySpeed();

        if (hasActiveRespondant()) {
            // Conversations run outside the ambient gate, so a stroll would keep walking under the
            // player's conversation (and never come back). Cut it short and head home - the bot should
            // be standing still when it talks to someone.
            if (strolling && strollHomeMapId > 0) {
                strollHome(getChr(), strollHomeMapId);
            }
            checkConversationTimeout();
            processMessages();
        }

        cleanupExpiredTrackers();
        processQueuedEvents(); // drain any LEVEL_UP congrats events buffered since the last tick
        if (isAvailableForAmbientActions()) {
            ensureTownClaim(); // claim this bot's ledge once (coordinates with returning training-bot loiter)
            BotFlavor.maybeExpress(this); // occasional idle emote / buff-flex / skill-swing (self-gated)
            BotChatter.maybeStartChatter(this); // occasional short back-and-forth with a nearby town bot
            maybeIdleChair(); // occasional sit/stand while idle - a resting townsperson
            maybeRelocate(); // last: rare drift to a fresh anchor-weighted spot (may block the tick's walk)
            maybeStroll(); // walk to a neighbouring town map and back (cross-map; relocation can't)
        }
        pollRecruitInvite(); // last: a JOINED poll converts this bot to a FollowerBot
    }

    @Override
    public void handleEvent(GameEvent event) {
        // A nearby character (player or bot) levelled up - maybe congratulate them.
        LevelUpCongrats.react(this, event);
    }

    // Best-effort ambient cleanup on teardown / convert. The chatter engaged-registry self-expires, so
    // forget() is belt-and-suspenders; releasing the ledge claim frees a slot for other town bots.
    @Override
    public synchronized void stopScheduledTask() {
        Character chr = getChr();
        BotChatter.forget(chr);
        TownStation.releaseSpot(chr);
        // A stroll in flight is driven by GCTravel's own pool, not by this tick, so it would survive
        // teardown and dump the bot into a strange map after it was stopped or converted.
        if (strolling) {
            strolling = false;
            strollReturning = false;
            GCMovement.cancelTravel(chr);
            // Also drop the GC session enable() took for the trip: town bots are old-engine walkers,
            // and a lingering GC session would hold the shared movement lock the old engine needs.
            GCMovement.disable(chr);
        }
        super.stopScheduledTask();
    }

    // Claim this bot's ledge once, on its first available tick. The sampler already placed it well, so this
    // is claim-only (no move); the shared BotSpotClaims cap then keeps a returning training-bot crowd from
    // stacking on a ledge a stationed bot already holds. Stores the town anchor (spawn portal) for drift.
    private void ensureTownClaim() {
        if (townClaimed) {
            return;
        }
        Character chr = getChr();
        if (chr == null || chr.getMap() == null) {
            return;
        }
        townAnchor = resolveTownAnchor(chr);
        TownStation.claimSpot(chr);
        // Desync: seed the first chair + relocation timers with a random offset so a freshly-spawned cohort
        // (or a crowd reacting to a player's arrival) doesn't sit / drift in lockstep.
        long now = System.currentTimeMillis();
        nextChairActionMs = now + (long) (random.nextDouble() * IDLE_CHAIR_COOLDOWN_MAX_MS);
        nextRelocateAtMs = now + RELOCATE_MIN_MS + (long) (random.nextDouble() * (RELOCATE_MAX_MS - RELOCATE_MIN_MS));
        // Same desync for the first stroll, so a freshly-spawned cohort doesn't all walk out at once.
        nextStrollAtMs = now + STROLL_MIN_MS + (long) (random.nextDouble() * (STROLL_MAX_MS - STROLL_MIN_MS));
        townClaimed = true;
    }

    // Occasionally sit (then later stand) while idle - cheap, already-proven packets, no engine conflict.
    // Gated on a real observer (it's packets) and paced by a per-bot cooldown so it isn't a metronome.
    private void maybeIdleChair() {
        if (!isAvailableForAmbientActions()) {
            return; // an earlier tick step (e.g. chatter) may have engaged this bot
        }
        Character chr = getChr();
        if (chr == null || chr.getMap() == null || !GCMovement.isMapObserved(chr.getMapId())) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now < nextChairActionMs || random.nextDouble() >= IDLE_CHAIR_CHANCE) {
            return; // not yet, or a quiet tick (timer only advances when an action actually fires)
        }
        if (chr.getChair() > 0) {
            botCancelChair(chr);
        } else {
            botSitChair(chr, getRandomChairId());
        }
        nextChairActionMs = now + IDLE_CHAIR_COOLDOWN_MIN_MS
                + (long) (random.nextDouble() * (IDLE_CHAIR_COOLDOWN_MAX_MS - IDLE_CHAIR_COOLDOWN_MIN_MS));
    }

    // Rare drift to a fresh anchor-weighted spot ("stand near the potion shop a while, then wander to the
    // smithy"). Preferably fires while unobserved (bots just appear in new spots); a watched stroll is
    // allowed but rare. The walk is a BLOCKING old-engine pathfind, run synchronously on the tick as
    // deliberate choreography (the bot is intentionally inert while it strolls; relocating gates it out of
    // other ambient actions and partner selection).
    private void maybeRelocate() {
        if (!isAvailableForAmbientActions()) {
            return; // don't walk a bot that a chatter chain just engaged this tick
        }
        Character chr = getChr();
        if (chr == null || chr.getMap() == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now < nextRelocateAtMs) {
            return;
        }
        if (GCMovement.isMapObserved(chr.getMapId()) && random.nextDouble() >= OBSERVED_RELOCATE_CHANCE) {
            nextRelocateAtMs = now + OBSERVED_DEFER_MS; // defer: prefer to drift while nobody's watching
            return;
        }
        relocating = true;
        try {
            if (chr.getChair() > 0) {
                botCancelChair(chr); // can't stroll from a chair
            }
            TownStation.relocate(chr, townAnchor);
        } finally {
            relocating = false;
            nextRelocateAtMs = System.currentTimeMillis() + RELOCATE_MIN_MS
                    + (long) (random.nextDouble() * (RELOCATE_MAX_MS - RELOCATE_MIN_MS));
        }
    }

    // Occasional walk to a town map next door (Henesys main street -> Market -> Park -> home). In-map
    // relocation can never leave the current map: the nav graph drops cross-map portals, so a bot
    // stationed on one street would otherwise never see the rest of the town.
    //
    // Unlike relocation this is NOT deferred while observed - walking into a portal and coming back is
    // ordinary town behaviour, and a bot that only ever moves when nobody is looking would look frozen to
    // the players who are actually there. The travel itself is driven by GCTravel's own pool, so the bot's
    // tick is never blocked by the walk.
    private void maybeStroll() {
        if (!isAvailableForAmbientActions()) {
            return;
        }
        Character chr = getChr();
        if (chr == null || chr.getMap() == null) {
            return;
        }
        if (BotRecruitManager.isArmed(chr.getId())) {
            return; // a player is mid-invite: never wander out from under them
        }
        long now = System.currentTimeMillis();
        if (now < nextStrollAtMs) {
            return;
        }
        List<Integer> options = new ArrayList<>();
        for (int neighbor : GCMovement.walkableNeighbors(chr.getMapId())) {
            // Town maps only: a neighbour carrying mobs is a hunting field, not somewhere a
            // townsbot goes for a walk. Empty until the world graph exists (never forces its build).
            if (MapMobIndex.level(neighbor) < 0) {
                options.add(neighbor);
            }
        }
        if (options.isEmpty()) {
            nextStrollAtMs = now + STROLL_MIN_MS; // nowhere to go (or graph not built yet) - try later
            return;
        }
        int dest = options.get(random.nextInt(options.size()));
        int home = chr.getMapId();
        strolling = true;
        strollHomeMapId = home;
        if (chr.getChair() > 0) {
            botCancelChair(chr); // can't walk out of a chair
        }
        TownStation.releaseSpot(chr); // free the ledge for the duration, don't hold it from another town
        GCMovement.travel(chr, dest, ok -> {
            if (!Boolean.TRUE.equals(ok)) {
                strollHome(chr, home);
                return;
            }
            MethodScheduler.runAfterDelay(() -> strollHome(chr, home), strollDwellMs());
        });
    }

    // Come back and re-settle. On failure the bot stays wherever it got to and re-claims a ledge there:
    // strolling must still end, or the bot would never drift or stroll again.
    private void strollHome(Character chr, int home) {
        if (!strolling || strollReturning) {
            return; // converted away / stopped while out, or a return is already under way
        }
        strollReturning = true;
        GCMovement.travel(chr, home, ok -> {
            strolling = false;
            strollReturning = false;
            townClaimed = false; // re-claim a ledge where we ended up (townAnchor points at the old map)
            // Hand the bot back to the old movement engine. GCMovement.travel() enable()d it for the
            // trip, and enable() HOLDS the shared movement lock for the whole GC session - which the
            // in-map drift (TownStation.relocate -> pathFinderAware) needs. Without this the next
            // relocate silently no-ops on tryAcquireMovementLock, and its finally block then steals
            // the lock back out from under the GC state. Town bots walk with the old engine; unlike
            // TrainingBot they do not stay under GC control between trips.
            GCMovement.disable(chr);
            nextStrollAtMs = System.currentTimeMillis() + STROLL_MIN_MS
                    + (long) (random.nextDouble() * (STROLL_MAX_MS - STROLL_MIN_MS));
        });
    }

    private long strollDwellMs() {
        return STROLL_DWELL_MIN_MS + (long) (random.nextDouble() * (STROLL_DWELL_MAX_MS - STROLL_DWELL_MIN_MS));
    }

    private Point resolveTownAnchor(Character chr) {
        try {
            if (chr.getMap() != null && chr.getMap().getPortal(0) != null) {
                return chr.getMap().getPortal(0).getPosition();
            }
        } catch (Exception ignored) {
            // fall back to current position
        }
        return chr.getPosition();
    }

    @Override
    protected void processMessages() {
        try {
            ChatMessage message = MessageQueue.getInstance()
                    .getMessageWithTimeout("secondary", 1, TimeUnit.SECONDS);
            if (message == null) return;

            if (isBot(message.getSender())) return;

            Character respondant = getInteractors().getRespondant();
            if (respondant == null || message.getSender().getId() != respondant.getId()) return;

            lastRespondantMessageTime = System.currentTimeMillis();
            handlePlayerMessage(message);
        } catch (Exception e) {
            log("[SocialBot] processMessages error: " + e.getMessage());
        }
    }

    public void onFirstInteraction(Character player) {
        lastRespondantMessageTime = System.currentTimeMillis();

        InteractionTracker tracker = getOrCreateTracker(player.getId());
        InteractionLevel level = tracker.getLevel();

        if (level == InteractionLevel.IGNORE) {
            showBusyHint(player);
            resetConversation();
            return;
        }

        tracker.increment();
        wasSittingBeforeInteraction = getChr().getChair() > 0;
        originalChairId = wasSittingBeforeInteraction ? getChr().getChair() : 0;

        // Scripted beats ride a BotTiming chain; the gate silently drops the
        // rest of the script if the conversation resets or changes hands mid-way.
        BotTiming.Chain chain = BotTiming.chain()
                .stopUnless(() -> isConversationWith(player));
        if (wasSittingBeforeInteraction) {
            chain.run(() -> botCancelChair(getChr())).pause(600);
        }
        chain.run(() -> botFaceTowardsPoint(getChr(), player.getPosition()))
                .pauseRandom(2000, 4000);

        switch (level) {
            case NORMAL:
                if (variant == SocialBotVariant.SINGLE_RESPONSE) {
                    appendSingleResponse(chain, player);
                } else {
                    appendGreeting(chain, player);
                }
                break;
            case REDUCED:
                chain.run(() -> doReducedResponse(player))
                        .run(this::resetConversation);
                break;
            case NONVERBAL:
                chain.run(() -> doNonverbalResponse(player))
                        .run(this::resetConversation);
                break;
        }
        chain.start();
    }

    private void handlePlayerMessage(ChatMessage message) {
        Character player = message.getSender();
        InteractionTracker tracker = getOrCreateTracker(player.getId());
        InteractionLevel level = tracker.getLevel();

        if (level == InteractionLevel.IGNORE) {
            showBusyHint(player);
            resetConversation();
            return;
        }
        if (level == InteractionLevel.NONVERBAL) {
            doNonverbalResponse(player);
            tracker.increment();
            resetConversation();
            return;
        }
        if (level == InteractionLevel.REDUCED) {
            doReducedResponse(player);
            tracker.increment();
            resetConversation();
            return;
        }

        if (socialState == SocialBotState.AWAITING_CHOICE) {
            handleDialogueChoice(message.getContent(), player);
            tracker.increment();
        } else if (socialState == SocialBotState.RESPONDING) {
            // LLM reply in flight — ignore extra lines until the chain finishes.
        }
    }

    // Guards the menu-reply window. A reply is now paced by a random typing beat, so a player who
    // types again while it is pending would start a SECOND chain with its own random delay - two
    // replies racing, often out of order. RESPONDING (already the meaning "a reply is in flight")
    // makes extra lines wait; endReply restores AWAITING_CHOICE when the reply has played.
    //
    // If the chain is dropped by its gate (conversation reset, bot stopped), endReply never runs
    // and the state stays RESPONDING - but the conversation is over in that case too, and
    // checkConversationTimeout's resetConversation() is the backstop that clears it. Same window
    // the pre-existing LLM reply path already had.
    private void beginReply(Character player) {
        socialState = SocialBotState.RESPONDING;
    }

    private void endReply(Character player) {
        if (isConversationWith(player)) {
            showInteractiveOptions(player);
        }
        socialState = SocialBotState.AWAITING_CHOICE;
    }

    private void handleDialogueChoice(String content, Character player) {
        String lower = content.trim().toLowerCase();

        if (isGoodbyeIntent(lower)) {
            doGoodbye(player);
            resetConversation();
            return;
        }
        if (isPartyIntent(lower)) {
            handlePartyAsk(player);
            return;
        }

        String category = resolveMenuCategory(lower);
        if (category != null) {
            respondWithYamlCategory(category, player);
            return;
        }

        if (SocialLlmService.isEnabled()) {
            handleFreeformLlm(content, player);
            return;
        }

        respondWithYamlCategory("WhatsUp", player);
    }

    private static boolean isGoodbyeIntent(String lower) {
        return lower.equals("5")
                || lower.contains("goodbye")
                || lower.contains("bye")
                || lower.contains("cya");
    }

    // Menu keywords: English keywords plus the localized label text plus
    // any per-language YAML aliases (see BotMessages.keywords). Index order matches
    // INTERACTIVE_SUFFIXES. Resolved per instance rather than statically: a static field would
    // freeze at class-load time and would not follow the configured language.
    private final List<List<String>> interactiveKeywords =
            BotMessages.keywords("menu.social", INTERACTIVE_SUFFIXES, INTERACTIVE_KEYWORDS);

    private boolean isPartyIntent(String lower) {
        return lower.equals("4") || matchesOption(lower, 3);
    }

    /** True when the typed text matches option {@code index} of the interactive menu. */
    private boolean matchesOption(String lower, int index) {
        if (index >= interactiveKeywords.size()) {
            return false;
        }
        for (String kw : interactiveKeywords.get(index)) {
            if (lower.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    /** Returns a YAML dialogue category for structured menu picks, or null for free-form chat. */
    private String resolveMenuCategory(String lower) {
        if (lower.equals("1") || matchesOption(lower, 0)) {
            return "WhatsUp";
        }
        if (lower.equals("2") || matchesOption(lower, 1)) {
            return "Interesting";
        }
        if (lower.equals("3") || matchesOption(lower, 2)) {
            return "Rumors";
        }
        return null;
    }

    private void handleFreeformLlm(String content, Character player) {
        socialState = SocialBotState.RESPONDING;
        int botId = getChr().getId();
        int playerId = player.getId();

        SocialLlmService.completeAsync(getChr(), player, content,
                reply -> deliverLlmReply(player, botId, playerId, reply),
                () -> {
                    if (SocialLlmConfig.fallbackToYaml()) {
                        // already RESPONDING; the YAML reply's chain restores the menu
                        respondWithYamlCategory("WhatsUp", player);
                    } else {
                        BotTiming.chain()
                                .stopUnless(() -> isConversationWith(player))
                                .run(() -> endReply(player))
                                .start();
                    }
                });
    }

    private void deliverLlmReply(Character player, int botId, int playerId, String reply) {
        boolean speakable = reply != null && !reply.isBlank();
        BotTiming.Chain chain = BotTiming.chain()
                .stopUnless(() -> isConversationWith(player))
                // Nothing to say -> nothing to type. A beat here would just be the bot staring
                // at the player for seconds before repainting the menu.
                .pause(speakable ? BotTiming.typingPauseFor(reply) : 0L)
                .run(() -> botFaceTowardsPoint(getChr(), player.getPosition()));
        if (speakable) {
            chain.run(() -> {
                BotSpeak(getChr(), reply);
                SocialChatSessionStore.addAssistant(botId, playerId, reply);
            });
        }
        chain.run(() -> endReply(player));
        chain.start();
    }

    private void respondWithYamlCategory(String category, Character player) {
        if (random.nextDouble() < RARE_LINE_CHANCE) {
            category = "Rare";
        }

        String line = getRandomLine(category, player);
        int emote = getRandomEmote(category);
        beginReply(player);
        BotTiming.Chain chain = BotTiming.chain()
                .stopUnless(() -> isConversationWith(player));
        if (line != null) {
            // Beat before speaking, not before the menu repaint: with no line (missing dialogue
            // node) an unconditional pause is the bot silently staring for seconds.
            chain.pause(BotTiming.typingPauseFor(line))
                    .run(() -> botFaceTowardsPoint(getChr(), player.getPosition()))
                    .run(() -> BotSpeak(getChr(), line));
            if (emote > 0) {
                chain.pause(400).run(() -> BotEmote(getChr(), emote));
            }
        }
        chain.run(() -> endReply(player));
        chain.start();
    }

    // --- Party recruiting ---

    // Shouted offer from a stranger in range: the townsperson's half of the no-name broadcast.
    // Rolls through the same shared brain as the menu path but without opening a conversation -
    // the shout is the question, so there is no respondant, no hint balloon and no menu.
    //
    // Deliberately NOT handlePartyAsk(): that one drives the respondant/endReply state machine and
    // repaints the option menu, which is exactly what a shout must not do.
    @Override
    public boolean offerRecruit(Character player, String content) {
        Character chr = getChr();
        // Already partied, or already talking to somebody: a townsperson mid-conversation with
        // another player does not answer a shout from across the street.
        if (chr == null || chr.getParty() != null || hasActiveRespondant()) {
            return false;
        }
        BotRecruitManager.RecruitAnswer ans = BotRecruitManager.rollPartyAsk(
                chr, player, BotRecruitManager.SOCIAL_ACCEPT_CHANCE, true);
        if (ans == BotRecruitManager.RecruitAnswer.ON_COOLDOWN
                || ans == BotRecruitManager.RecruitAnswer.FOLLOWERS_FULL) {
            return false; // asked-and-refused recently, or the world is at its follower cap
        }
        String line = getRandomLine(
                ans == BotRecruitManager.RecruitAnswer.ACCEPTED ? "PartyAccept" : "PartyDecline", player);
        if (line == null) {
            return false; // no dialogue node -> nothing to say, leave the slot to another bot
        }
        speakAfterBeat(player, line); // read-and-type beat, so a crowd doesn't answer in lockstep
        return true;
    }

    // "Wanna team up?": roll accept/decline via the shared recruit brain. Accept arms a 60s window
    // for THIS player's party invite (pollRecruitInvite answers it) and ends the conversation;
    // decline speaks a flavored excuse and loops back to the options like any other category.
    private void handlePartyAsk(Character player) {
        BotRecruitManager.RecruitAnswer ans = getChr().getParty() != null
                ? BotRecruitManager.RecruitAnswer.DECLINED // already committed to a party
                : BotRecruitManager.rollPartyAsk(getChr(), player, BotRecruitManager.SOCIAL_ACCEPT_CHANCE, true);

        // The accept/decline roll is committed here, before the beat: the armed invite window must
        // start now, not when the line finally plays.
        boolean accepted = ans == BotRecruitManager.RecruitAnswer.ACCEPTED;
        String category = accepted ? "PartyAccept" : "PartyDecline";
        String line = getRandomLine(category, player);
        int emote = getRandomEmote(category);

        beginReply(player);
        BotTiming.Chain chain = BotTiming.chain()
                .stopUnless(() -> isConversationWith(player));
        if (line != null) {
            // Beat before speaking; with no line, skip straight to the outcome so a missing
            // dialogue node doesn't read as the bot thinking for seconds.
            chain.pause(BotTiming.typingPauseFor(line))
                    .run(() -> botFaceTowardsPoint(getChr(), player.getPosition()))
                    .run(() -> BotSpeak(getChr(), line));
            if (emote > 0) {
                chain.pause(400).run(() -> BotEmote(getChr(), emote));
            }
        }
        if (accepted) {
            chain.run(this::resetConversation); // hint clears; the tick poll now waits for the invite
        } else {
            chain.run(() -> endReply(player));
        }
        chain.start();
    }

    // Answers any pending party invite every tick: the armed recruiter's invite is accepted and the
    // bot converts itself into a FollowerBot (party membership rides the Character through the
    // conversion); anything unsolicited gets a polite decline so the first-wins queue never rots.
    private void pollRecruitInvite() {
        Character chr = getChr();
        if (!BotPartyQueue.getInstance().hasPendingInvite(chr)) {
            return;
        }
        int recruiterId = BotRecruitManager.armedInviterId(chr.getId()); // read BEFORE poll - JOINED clears it
        BotRecruitManager.InvitePoll res = BotRecruitManager.pollInvites(chr);
        if (res != BotRecruitManager.InvitePoll.JOINED) {
            return;
        }
        Character recruiter = chr.getClient().getChannelServer().getPlayerStorage().getCharacterById(recruiterId);
        String line = getRandomLine("PartyJoined", recruiter);
        if (line != null) {
            // Spoken on the spot, not after a typing beat: the bot converts to a FollowerBot on the
            // very next line, and a delayed bubble would land seconds later - after the type change
            // and possibly a walk-off - as a stray line answering nobody.
            BotSpeak(chr, line);
        }
        BotRecruitManager.setPendingLeader(chr.getId(), recruiterId);
        // Remember the town this bot belongs to (it has no travel of its own) so the ride can end
        // back here instead of stranding it wherever the leader stopped.
        BotRecruitManager.setReturnOrigin(chr.getId(), chr.getMapId(), false);
        BotTypeManager.convertBotType(chr, BotTypeManager.BotType.FOLLOWER_BOT);
    }

    // --- Response types ---
    // Lines/emotes are picked at chain-build time (a few seconds before they play);
    // no visible difference, and it keeps the chain steps to pure packet sends.

    private void appendSingleResponse(BotTiming.Chain chain, Character player) {
        String category = pickResponseCategory(player);
        String line = getRandomLine(category, player);
        int emote = getRandomEmote(category);
        if (line != null) {
            chain.run(() -> BotSpeak(getChr(), line))
                    .pauseRandom(500, 1000);
            if (emote > 0) {
                chain.run(() -> BotEmote(getChr(), emote));
            }
        }
        chain.run(() -> botFaceTowardsPoint(getChr(), player.getPosition()));
        appendResit(chain);
        chain.run(this::resetConversation);
    }

    /**
     * Picks which pool the bot answers from. Normally {@code SingleResponse} (with a rare
     * flavour line), but a bot that clearly out-levels the player may instead smack talk.
     *
     * <p>The level gate is the point: a bully picks on someone weaker. Without it, a 30-level bot
     * would talk down to a 200-level player, which reads as broken rather than as attitude.
     */
    private String pickResponseCategory(Character player) {
        if (random.nextDouble() < RARE_LINE_CHANCE) {
            return "Rare";
        }
        if (canSmackTalk(player)) {
            return "SmackTalk";
        }
        return "SingleResponse";
    }

    private boolean canSmackTalk(Character player) {
        Character chr = getChr();
        if (chr == null || player == null) {
            return false;
        }
        if (random.nextDouble() >= SMACK_TALK_CHANCE) {
            return false;
        }
        // Only when the bot clearly out-levels the player - see pickResponseCategory().
        if (chr.getLevel() - player.getLevel() < SMACK_TALK_LEVEL_GAP) {
            return false;
        }
        Long until = smackTalkCooldowns.get(player.getId());
        long now = System.currentTimeMillis();
        if (until != null && now < until) {
            return false;
        }
        smackTalkCooldowns.put(player.getId(), now + SMACK_TALK_COOLDOWN_MS);
        return true;
    }

    private void appendGreeting(BotTiming.Chain chain, Character player) {
        String line = getRandomLine("Greeting", player);
        int emote = getRandomEmote("Greeting");
        if (line != null) {
            chain.run(() -> BotSpeak(getChr(), line));
            if (emote > 0) {
                chain.pause(400).run(() -> BotEmote(getChr(), emote));
            }
        }
        chain.run(() -> {
            showInteractiveOptions(player);
            socialState = SocialBotState.AWAITING_CHOICE;
        });
    }

    // Farewell beats play on their own chain so the tick isn't blocked; the
    // conversation resets immediately after. The gate tolerates that reset
    // (respondant null) but drops the tail if a NEW conversation starts.
    private void doGoodbye(Character player) {
        String line = getRandomLine("Goodbye", player);
        int emote = getRandomEmote("Goodbye");
        BotTiming.Chain chain = BotTiming.chain().stopUnless(() -> {
            Character r = getInteractors().getRespondant();
            return r == null || r.getId() == player.getId();
        });
        if (line != null) {
            chain.pause(BotTiming.typingPauseFor(line))
                    .run(() -> BotSpeak(getChr(), line));
            if (emote > 0) {
                chain.pause(400).run(() -> BotEmote(getChr(), emote));
            }
        }
        appendResit(chain);
        chain.start();
    }

    private void doReducedResponse(Character player) {
        String line = getRandomLine("Reduced", player);
        if (line != null) {
            speakAfterBeat(player, line);
        }
    }

    private void doNonverbalResponse(Character player) {
        String line = getRandomLine("Nonverbal", player);
        if (line != null) {
            speakAfterBeat(player, line);
        }
    }

    // Same read-and-type beat the scripted chains use, for the response helpers that used to fire
    // in the tick the player's line arrived.
    //
    // BotTiming.after (one delayed side-effect) rather than a chain: callers here reset the
    // conversation immediately after speaking, so a chain gated on isConversationWith(...) would
    // find the respondant already cleared and silently drop the line. The bot is also being told
    // to stop engaging at this interaction level, so one beat later is still coherent.
    private void speakAfterBeat(Character player, String line) {
        Character chr = getChr();
        BotTiming.after(BotTiming.typingPauseFor(line), () -> {
            if (chr != null && chr.getMap() != null && getRunning()) {
                BotSpeak(chr, line);
            }
        });
    }

    // Decides the re-sit at build time; the sit itself lands as a later beat.
    private void appendResit(BotTiming.Chain chain) {
        boolean resit = wasSittingBeforeInteraction || random.nextDouble() < GOODBYE_SIT_CHANCE;
        int chairId = originalChairId > 0 ? originalChairId : getRandomChairId();
        wasSittingBeforeInteraction = false;
        originalChairId = 0;
        if (resit) {
            chain.pauseRandom(1000, 2500).run(() -> botSitChair(getChr(), chairId));
        }
    }

    private boolean isConversationWith(Character player) {
        Character r = getInteractors().getRespondant();
        return r != null && r.getId() == player.getId();
    }

    private void showBusyHint(Character player) {
        String busy = BotMessages.get("social.busy");
        player.yellowMessage(busy);
        // Only the immediate-response path needs enableActions(); the hint itself is harmless
        // on a client that is gone.
        player.getClient().sendPacket(PacketCreator.sendHint(busy, 150, 5));
        player.getClient().sendPacket(PacketCreator.enableActions());
        MethodScheduler.runAfterDelay(() -> expirePlayerChatCommands(player), 5000);
    }

    private void showInteractiveOptions(Character player) {
        displayPlayerChatCommands(player, BotMessages.labels("menu.social", INTERACTIVE_SUFFIXES));
    }

    // --- Timeout ---

    private void checkConversationTimeout() {
        if (!hasActiveRespondant()) return;
        if (lastRespondantMessageTime == 0) return;

        long elapsed = System.currentTimeMillis() - lastRespondantMessageTime;
        if (elapsed > CONVERSATION_TIMEOUT_MS) {
            String line = getRandomLine("Timeout", getInteractors().getRespondant());
            if (line != null) {
                BotSpeak(getChr(), line);
            }
            resetConversation();
        }
    }

    private void resetConversation() {
        Character respondant = getInteractors().getRespondant();
        if (respondant != null) {
            expirePlayerChatCommands(respondant);
            SocialChatSessionStore.clear(getChr().getId(), respondant.getId());
        }
        getInteractors().resetRespondant();
        socialState = SocialBotState.IDLE_AMBIENT;
        lastRespondantMessageTime = 0;
    }

    // --- Anti-spam tracker ---

    private InteractionTracker getOrCreateTracker(int playerId) {
        return interactionTrackers.computeIfAbsent(playerId, k -> new InteractionTracker());
    }

    private void cleanupExpiredTrackers() {
        long now = System.currentTimeMillis();
        if (now - lastCleanupTime < TRACKER_CLEANUP_INTERVAL_MS) return;
        lastCleanupTime = now;
        // Smack-talk cooldowns are per player id; without this the map grows for the bot's lifetime.
        smackTalkCooldowns.values().removeIf(until -> until <= now);
        interactionTrackers.entrySet().removeIf(e -> e.getValue().isExpired());
    }

    // --- Dialogue helpers ---

    // Resolves any {TOKEN}s (incl. {PLAYER_*}) against this bot and the player it's reacting to.
    // A line whose tokens can't resolve is dropped rather than spoken raw.
    private String getRandomLine(String category, Character player) {
        try {
            return BotDialogueHandler.getRandomResolvedLine(DIALOGUE_PATH, BOT_TYPE_KEY, category, getChr(), player);
        } catch (Exception e) {
            return null;
        }
    }

    private int getRandomEmote(String category) {
        try {
            BotDialogueHandler.DialogueConstructor dialog =
                    BotDialogueHandler.getDialogueCon(DIALOGUE_PATH, BOT_TYPE_KEY, category);
            if (dialog == null) return -1;
            return dialog.getEmote();
        } catch (Exception e) {
            return -1;
        }
    }

    // --- Anti-spam tracking ---

    private static class InteractionTracker {
        private int count = 0;
        private long lastInteractionTime;
        private static final long COOLDOWN_MS = 300_000;

        public InteractionTracker() {
            this.lastInteractionTime = System.currentTimeMillis();
        }

        public void increment() {
            count++;
            lastInteractionTime = System.currentTimeMillis();
        }

        public boolean isExpired() {
            return System.currentTimeMillis() - lastInteractionTime > COOLDOWN_MS;
        }

        public InteractionLevel getLevel() {
            if (isExpired()) {
                reset();
                return InteractionLevel.NORMAL;
            }
            if (count <= 3) return InteractionLevel.NORMAL;
            if (count <= 4) return InteractionLevel.REDUCED;
            if (count == 5) return InteractionLevel.NONVERBAL;
            return InteractionLevel.IGNORE;
        }

        public void reset() {
            count = 0;
            lastInteractionTime = System.currentTimeMillis();
        }
    }
}
