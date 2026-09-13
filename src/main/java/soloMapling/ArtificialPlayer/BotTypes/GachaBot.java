package soloMapling.ArtificialPlayer.BotTypes;

import org.gms.client.Character;
import org.gms.client.inventory.Item;
import org.gms.server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotMessagingSystem.ChatMessage;
import soloMapling.ArtificialPlayer.BotMessagingSystem.MessageQueue;
import soloMapling.ArtificialPlayer.BotMovementSystem.MovementStructures.MovementRecording;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.itemPool.GachaPrizePool;
import soloMapling.server.EventMessageSystem.EventBus;
import soloMapling.server.EventMessageSystem.EventType;
import soloMapling.server.EventMessageSystem.GameEvent;
import soloMapling.Environment.BotMessages;

import java.awt.Point;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static soloMapling.ArtificialPlayer.BotCommandsPack.DropCommands.botLootOwnerItems;
import static soloMapling.ArtificialPlayer.BotMovementSystem.InPacketReader.getMovementRecording;
import static soloMapling.ArtificialPlayer.BotMovementSystem.MovementCommands.BotMoveStreamOffset;
import static soloMapling.DebugUtilities.debugprint;
import static soloMapling.MapVFX.CustomReactor.createReactorDropList;
import static soloMapling.MapVFX.CustomReactor.gachaPop;
import static soloMapling.BotLogger.log;
import static soloMapling.itemPool.GachaFillerSystem.createGachaListWithPrize;

public class GachaBot extends BotSM {
    private GachaBotState gachaBotState = GachaBotState.RESET;
    private List<String> hint = Collections.singletonList(getChr().getName());

    public GachaBot(Character character) {
        super(character);
        dialoguePath = "GachaBotDialogue.yaml";
        botType = "GachaBot";
    }

    private void setGachaBotState(GachaBotState state) {
        this.gachaBotState = state;
    }

    protected enum GachaBotState {
        RESET,
        SET_POSITION,
        RUN_ROULETTE,
        STAND_BY_1,
        STAND_BY_2,
        PICKUP_ITEM,
        STAND_BY_3,
        STAND_BY_4,
        PROCESS_REWARD
    }

    private void resetGachaBotState() {
        setGachaBotState(GachaBotState.RESET);

        EventBus.getInstance().subscribe(EventType.LEVEL_UP, this);
        EventBus.getInstance().subscribe(EventType.SCROLLING, this);
    }

    // Chance a spray comes with a taunt aimed at whoever is watching. Occasional, not every round -
    // baiting on every single drop would read as a scripted loop instead of someone working the crowd.
    private static final double TAUNT_CHANCE = 0.25;

    private void setPosition() {
        SocialCommands.BotChatbubble(getChr(), BotMessages.get("gacha.position_set"));
    }

    private void runRoulette() {
        // Check if there are main characters on the map
        if (!checkMainPlayersOnMap()) {
            return;
        }

        // Run the roulette drop animation
        int prizeId = rollPrizeId();
        List<Integer> filler = createGachaListWithPrize(prizeId);
        // The pool holds equips and junk side by side; buildPrize guts the
        // former and passes the latter through untouched.
        Item prize = prizeId > 0 ? GachaPrizePool.buildPrize(prizeId) : null;
        gachaPop(getChr(), createReactorDropList(filler), prize);
        maybeTaunt();
    }

    // The bot's whole act is bait: it sprays a tempting pile of drops (occasionally a gutted
    // "jackpot") and then picks them back up, daring whoever is watching to grab something first.
    // A taunt lands on a fraction of sprays so it reads as someone working the crowd, not a script.
    private void maybeTaunt() {
        if (ThreadLocalRandom.current().nextDouble() >= TAUNT_CHANCE) {
            return;
        }
        getDialogueHandler().executeBotFlavorDialogue("Taunt", this);
    }

    /**
     * The prize pool is the joke: big-name equips whose stats have been cut to
     * nothing. Most rounds carry none at all - a prize has to stay an event, not
     * a given. Returns 0 (a plain filler spray) when the round misses or the
     * yaml is missing, so a broken file costs the gag rather than the bot.
     */
    private int rollPrizeId() {
        GachaPrizePool pool = GachaPrizePool.load();
        if (pool.isEmpty()) {
            return 0;
        }
        GachaPrizePool.Entry entry = pool.rollForRound();
        return entry == null ? 0 : entry.itemId;
    }

    private void pickupItem() {
        // Check if there are main characters on the map
        if (!checkMainPlayersOnMap()) {
            return;
        }
//        SocialCommands.BotChatbubble(getChr(), "Running pickup!");
        SocialCommands.BotEmote(getChr(), 3);
        botLootOwnerItems(getChr(), getChr().getPosition(), 12000);
        maybeNudge();
    }

    // Chance the bot shifts position after a pickup. It never wanders off the ledge it works from
    // (the spray lands on that ledge's floor band, so leaving it would strand the loot), but a
    // perch it returns to every ~60s reads as a machine - a small shuffle keeps it looking alive.
    private static final double NUDGE_CHANCE = 0.35;
    // Shift distance is rolled per nudge (and the roll is symmetric), so the drift never repeats the
    // same offset twice.
    private static final int NUDGE_MIN_PX = 30;
    private static final int NUDGE_MAX_PX = 140;
    // A candidate a little below the current spot is still the same ledge band; this tolerates the
    // small slope of a hill without letting the bot hop down to the floor beneath a platform.
    private static final int NUDGE_MAX_DROP_PX = 30;

    // True while a nudge walk is in flight, so the tick can reclaim the bot if the walk ends without
    // firing the arrival callback (the driver drops it when it gives up on an unreachable target).
    // Written by the driver's callback thread, read by the macro tick, hence volatile.
    private volatile boolean nudgePending = false;

    private void maybeNudge() {
        if (ThreadLocalRandom.current().nextDouble() >= NUDGE_CHANCE) {
            return;
        }
        Character chr = getChr();
        Point pos = chr.getPosition();
        MapleMap map = chr.getMap();
        // Roll the distance per nudge (symmetric), so the shuffle never repeats the same offset.
        int range = ThreadLocalRandom.current().nextInt(NUDGE_MIN_PX, NUDGE_MAX_PX + 1);
        int dx = ThreadLocalRandom.current().nextInt(-range, range + 1);
        if (dx == 0) {
            return;
        }
        // groundPointBelow is pure physics (no nav-graph build, no tick stall) and returns null on a
        // gap or drop-off, so a candidate that isn't on solid ground at the same height is discarded.
        Point dest = GCMovement.groundPointBelow(map, pos.x + dx, pos.y);
        if (dest == null || Math.abs(dest.y - pos.y) > NUDGE_MAX_DROP_PX) {
            return;
        }
        // Same-ledge guard on a baked map: reject a target that sits on another walkable region, so
        // "shift a spot" can never mean hopping to an adjacent platform. Peek-only - no graph build.
        if (GCMovement.onDifferentLedge(map, pos.x, pos.y, dest.x, dest.y)) {
            return;
        }
        // move() enables a dynamic session that HOLDS the shared movement lock until it is released,
        // so hand the bot back the moment it arrives (the SocialBot relocation pattern) - otherwise
        // it would sit under GC control forever and block its own recorded-movement routines.
        nudgePending = true;
        GCMovement.move(chr, dest.x, dest.y, () -> {
            nudgePending = false;
            GCMovement.disable(chr);
        });
    }

    private void processReward() {
        return;
    }

    // Release any GC session a nudge still holds when this bot is torn down (stop / type conversion),
    // so the shared movement lock is handed back before the next owner takes over. disable() is
    // idempotent, so this is a no-op when no nudge is in flight.
    @Override
    public synchronized void stopScheduledTask() {
        nudgePending = false;
        GCMovement.disable(getChr());
        super.stopScheduledTask();
    }


    @Override
    public void updateState() {
        super.updateState();
        if (checkIfNotRunningOrPaused()) {
            return;
        }
        // If a nudge walk ended without firing its arrival callback (the driver drops the callback
        // when it gives up on an unreachable target), reclaim the bot here so the GC session and the
        // shared movement lock it holds are never stranded. disable() is idempotent.
        if (nudgePending && !GCMovement.isMoving(getChr())) {
            nudgePending = false;
            GCMovement.disable(getChr());
        }
        getDebugger().debugLoggingFull(String.format("%s GachaBotState: %s", this.getChr().getName(), gachaBotState), String.format("%s", gachaBotState));

        switch (gachaBotState) {
            case RESET:
                resetGachaBotState();
                setGachaBotState(GachaBotState.SET_POSITION);
                break;
            case SET_POSITION:
                setPosition();
                setGachaBotState(GachaBotState.RUN_ROULETTE);
                break;
            case RUN_ROULETTE:
                runRoulette();
                setGachaBotState(GachaBotState.STAND_BY_1);
                break;
            case STAND_BY_1:
//                SocialCommands.BotChatbubble(getChr(), "Stand by 1!");
                setGachaBotState(GachaBotState.STAND_BY_2);
                break;
            case STAND_BY_2:
//                SocialCommands.BotChatbubble(getChr(), "Stand by 2!");
                setGachaBotState(GachaBotState.PICKUP_ITEM);
                break;
            case PICKUP_ITEM:
                pickupItem();
                setGachaBotState(GachaBotState.STAND_BY_3);
                break;
            case STAND_BY_3:
//                SocialCommands.BotChatbubble(getChr(), "Stand by 3!");
                executeEventQueueGacha();
//                setGachaBotState(GachaBotState.STAND_BY_4);
                break;
            case STAND_BY_4:
//                SocialCommands.BotChatbubble(getChr(), "Stand by 4!");
                setGachaBotState(GachaBotState.PROCESS_REWARD);
                break;
            case PROCESS_REWARD:
                processReward();
                // Linger between rounds. The FSM advances one state per macro tick (2-6s), so a round
                // is ~7 ticks and the spray alone repeats every ~30s - a pile appearing on that beat
                // reads as a machine, not as someone sorting their bag. One extra beat here doubles
                // the cycle to ~60s.
                waitForRandom(24000, 36000);
                setGachaBotState(GachaBotState.RUN_ROULETTE); // Loop back to state 1
                break;
            default:
                log("Unexpected state: " + gachaBotState);
                state = BotState.FINISHED;
                resetGachaBotState();
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
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void executeEventQueueGacha() {
        // Process all queued events
        super.processQueuedEvents();

        // Stay in STAND_BY_3 while events remain queued; otherwise advance.
        if (!super.hasQueuedEvents()) {
            setGachaBotState(GachaBotState.STAND_BY_4);
        }
    }

    @Override
    public void handleEvent(GameEvent event) {
        debugprint("handleEvent GachaBot: ");
        event.printDescription();
        switch (event.getType()) {
            case GACHAPON_REWARD:
                handleGachaponEvent(event);
                break;
            case SCROLLING:
                handleScrollingEvent(event);
                break;
            case LEVEL_UP:
                handleLevelUpEvent(event);
                break;
            default:
                throw new IllegalStateException("Unknown Event Type: " + event.getType());
                // Add other cases as needed
        }
    }

    private void handleGachaponEvent(GameEvent event) {
        SocialCommands.BotEmote(getChr(), 2);
        SocialCommands.BotChatbubble(getChr(), BotMessages.get("gacha.lucky", event.getPlayerName()));
    }

    private void handleScrollingEvent(GameEvent event) {
        if (event.getPass()) {
            SocialCommands.BotEmote(getChr(), 3);
            SocialCommands.BotChatbubble(getChr(), BotMessages.get("gacha.nice_scroll", event.getPlayerName()));
        } else {
            SocialCommands.BotEmote(getChr(), 4);
            SocialCommands.BotChatbubble(getChr(), BotMessages.get("gacha.unlucky"));
        }
    }

    // Deliberate synchronous choreography — the recording playbacks block for
    // their own (data-driven) duration, so the celebration runs on its tick.
    private void handleLevelUpEvent(GameEvent event) {
        MovementRecording mvr = getMovementRecording(0, "rightleft45");
        BotMoveStreamOffset(mvr, getChr());
        BotHelpers.blockingSleep(1500);
        SocialCommands.BotEmote(getChr(), 2);
        SocialCommands.BotChatbubble(getChr(), BotMessages.get("gacha.congrats", event.getPlayerName()));
        BotHelpers.blockingSleep(1500);
        MovementRecording mvr2 = getMovementRecording(0, "leftright70");
        BotMoveStreamOffset(mvr2, getChr());
    }

}