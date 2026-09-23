package soloMapling.ArtificialPlayer.BotTypes;

import org.gms.client.Character;
import org.gms.constants.game.ExpTable;
import org.gms.server.maps.Portal;
import soloMapling.ArtificialPlayer.BotGrindSystem.GrindBrain;
import soloMapling.ArtificialPlayer.BotGrindSystem.GrindTickRegistry;
import soloMapling.ArtificialPlayer.BotGrindSystem.MapMobIndex;
import soloMapling.ArtificialPlayer.BotGrindSystem.TrainingMap;
import soloMapling.ArtificialPlayer.BotGrindSystem.TrainingMapChooser;
import soloMapling.ArtificialPlayer.BotGrindSystem.TrainingRegions;
import soloMapling.ArtificialPlayer.BotHealthSystem.BotPotionSim;
import soloMapling.ArtificialPlayer.BotWanderSystem.BotWanderSystem;
import soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.ArtificialPlayer.BotSM;

import java.awt.Point;
import java.util.Random;
import java.util.Set;

import static soloMapling.ArtificialPlayer.BotMovementSystem.MovementCommands.botCancelChair;
import static soloMapling.BotLogger.log;

// A high-level wanderer that hunts LOW-level monsters on purpose and drifts the world freely.
//
// Where a TrainingBot climbs a level-scaled ladder of hunting grounds and only ever moves UP a continent
// (and, below the free-move level, only when it has genuinely outgrown the one it stands on), a RoamerBot
// does the opposite: it fixes its sight on the easy maps (see TrainingMapChooser's ROAMER profile — a flat
// [1, 60] band, the whole landmass in discovery reach, a bias toward the LOWEST maps) and crosses to any
// continent it qualifies for at its own whim. The intended read is a veteran player slumming it in the
// starter fields, not a grinder working a route.
//
// It reuses the whole grind stack — GrindBrain (spot selection, combat, loot), the shared 250ms
// GrindTickRegistry sweep, TrainingMapFinder/MapMobIndex for WZ-driven discovery, GCMovement for the
// walk/jump/climb/travel, BotWanderSystem for the town stroll — and adds only the FSM that
// choreographs them. Ours (SoloMapling).
//
// Occupancy is a SEPARATE table (TrainingMapChooser.Scope.ROAMER), so a roamer never consumes a slot a
// TrainingBot's low cohorts need. Physical stacking is still prevented by SpotFinder/BotSpotClaims, so two
// registries that both read a map as "roomy" cannot actually park two bots on the same ledge — one side
// simply reads the map saturated and moves on.
public class RoamerBot extends BotSM implements GrindTickRegistry.Participant {

    // ── Tunables (decoration, not balance — rough is fine) ───────────────────
    // Shorter sessions than a TrainingBot's 10-20 min: a wanderer works a field for a few minutes and moves
    // on, so the world reads as restless rather than as camps of grinders.
    private static final double KILLS_PER_MIN = 22.0;         // abstract grind speed (unobserved)
    private static final long GRIND_MIN_MS = 120_000;         // 2-6 min per field
    private static final long GRIND_MAX_MS = 360_000;
    private static final long TOWN_DWELL_MIN_MS = 15_000;     // linger and mill about town 15-40 s
    private static final long TOWN_DWELL_MAX_MS = 40_000;
    private static final long TRAVEL_TIMEOUT_MS = 120_000;    // abandon a trip only after this long with no progress
    private static final int TRAVEL_PROGRESS_EPS_PX = 24;
    private static final int LEVEL_CAP = 195;                 // guard, same as TrainingBot

    // Free migration: unlike a TrainingBot, a roamer is never forced off a continent and never has to be
    // outgrown — it goes where it likes. But it should not live aboard, so the move is a dice roll and is
    // cooldown-gated, shorter than a TrainingBot's 3-8 h (a wanderer crosses more readily).
    private static final double MOVE_CHANCE = 0.6;
    private static final long MOVE_COOLDOWN_MIN_MS = 15 * 60 * 1000L;  // 15 min
    private static final long MOVE_COOLDOWN_MAX_MS = 45 * 60 * 1000L;  // 45 min

    private enum Phase { INIT, IN_TOWN, DECIDE, GO_TRAIN, GRIND, GO_TOWN, MIGRATE }

    private volatile Phase phase = Phase.INIT;
    private boolean phaseEntered = false;
    private long phaseDeadlineMs = 0;

    private volatile boolean moveDone = false;
    private volatile boolean moveOk = false;
    private int travelLastMapId = -1;
    private Point travelLastPos = null;

    private int homeMapId = -1;           // the town this bot is currently based in
    private int migrateTargetMapId = -1;  // the continent town a crossing is heading for (MIGRATE phase)
    private long movedUntilMs = 0;        // migration cooldown

    private int currentTrainMapId = -1;
    private int currentMobLevel = 0;
    private long grindUntilMs = 0;
    private long lastExpAccrualMs = 0;

    private final GrindBrain grind = new GrindBrain(this::debugChat);
    private final BotPotionSim potionSim = new BotPotionSim();
    private final Random rng = new Random();

    // ── Map crowding balance (port of TrainingBot's crowd-bail) ──────────────
    // The roamer's occupancy table is SEPARATE from the training one, so a low map can read "roomy" to a
    // roamer while its slot claims are already taken by a low training cohort. SpotFinder/BotSpotClaims
    // still refuse the physical claim, so this bot reads the map saturated and leaves for another — which
    // is what keeps the independent-registry design from stacking bots despite the double-count.
    private static final long MAP_SATURATED_DWELL_MS = 8_000;
    private static final long MAP_EXCLUDE_MS = 45_000;
    private static final int MAX_MAP_HOPS_PER_EPISODE = 2;
    private final java.util.Map<Integer, Long> mapCrowdCooldown = new java.util.HashMap<>();
    private int crowdHopsThisEpisode = 0;
    private long mapSaturatedSinceMs = 0L;

    public RoamerBot(Character character) {
        super(character);
        botType = "RoamerBot";
        dialoguePath = "RoamerBotDialogue.yaml";
    }

    @Override
    public boolean allowsMount() {
        return true; // 漫游打怪
    }

    // Answer social chat like any ambient role-player: BotSM.respondSocial falls back to the shared
    // SocialBot pool for any intent this pack does not carry, so no extra nodes are required here.
    @Override
    public boolean respondsToSocialChat() {
        return true;
    }

    // Deep-background while grinding, exactly like a TrainingBot: abstract EXP accrues by elapsed time and
    // the grind watchdog skips unobserved bots, so the 4-8 min cadence loses nothing.
    @Override
    protected long lowPriorityDelayMs() {
        if (phase == Phase.GRIND) {
            return 240_000 + rng.nextInt(240_000);
        }
        return super.lowPriorityDelayMs();
    }

    // The shared combat sweep entry. Only OBSERVED grinders do real work: grind.tick early-returns on an
    // unobserved map, so an unobserved roamer pays a cheap early-return and levels by elapsed time instead.
    @Override
    public void grindTick() {
        Character chr = getChr();
        if (chr == null || !getRunning() || phase != Phase.GRIND) {
            return;
        }
        // A grinder drained to zero (a map's decHP field, a mob hit) must hand the grind back before the
        // sweep keeps swinging from the floor — same rule and same recovery as TrainingBot.
        if (death().adoptIfZeroHp() || death().isDead()) {
            if (phase == Phase.GRIND) {
                leaveGrind();
                enterPhase(Phase.DECIDE);
            }
            return;
        }
        grind.tick(chr);
    }

    @Override
    public void updateState() {
        super.updateState();
        if (checkIfNotRunningOrPaused()) {
            return;
        }
        Character chr = getChr();
        if (chr == null || chr.getMap() == null) {
            return;
        }
        getDebugger().debugLoggingFull(
                String.format("%s RoamerBot phase: %s", chr.getName(), phase), String.format("%s", phase));

        potionSim.tick(chr);

        switch (phase) {
            case INIT -> doInit();
            case IN_TOWN -> doInTown();
            case DECIDE -> doDecide();
            case GO_TRAIN -> doTravel(currentTrainMapId, Phase.GRIND, Phase.DECIDE);
            case GRIND -> doGrind();
            case GO_TOWN -> doTravel(homeMapId >= 0 ? homeMapId : chr.getMapId(), Phase.IN_TOWN, Phase.IN_TOWN);
            case MIGRATE -> doTravel(migrateTargetMapId, Phase.IN_TOWN, Phase.DECIDE);
        }
    }

    // ── Phases ───────────────────────────────────────────────────────────────

    private void doInit() {
        homeMapId = getChr().getMapId();
        crowdHopsThisEpisode = 0; // fresh outing
        enterPhase(MapMobIndex.level(homeMapId) < 0 ? Phase.IN_TOWN : Phase.DECIDE);
    }

    // In town: stroll the whole map for a beat, then either migrate or go hunt. Uses BotWanderSystem
    // alone (the wanderer primitive, as TownWandererBot does) — NOT TownLoiter, whose settle() also starts
    // its own fidget/drift loop; running both would put two movement owners on one bot.
    private void doInTown() {
        if (!phaseEntered) {
            phaseEntered = true;
            crowdHopsThisEpisode = 0; // back home -> fresh outing, reset the crowd-migration budget
            BotWanderSystem.start(getChr());  // stroll the town while it lingers (also clears the doorway)
            phaseDeadlineMs = now() + dwellMs();
            if (rng.nextInt(3) == 0) {
                SocialCommands.BotEmote(getChr(), 1 + rng.nextInt(7));
            }
            return;
        }
        if (now() < phaseDeadlineMs) {
            return;
        }
        if (startMigration()) {
            return; // heading for a continent of its choosing
        }
        enterPhase(Phase.DECIDE);
    }

    // Free crossing: any continent the roamer qualifies for, at its own whim. Deliberately does NOT use
    // TrainingMapChooser.forcedCrossing — that is the TrainingBot/companion's "outgrown or island exit"
    // rule, a different question. A roamer has no ladder to climb and no continent to outgrow; it moves
    // because it feels like it. Returns true if a crossing was started.
    private boolean startMigration() {
        Character chr = getChr();
        if (homeMapId < 0) {
            return false;
        }
        if (now() <= movedUntilMs) {
            return false; // just crossed — settle a while first
        }
        if (rng.nextDouble() >= MOVE_CHANCE) {
            return false; // not this time
        }
        int dest = TrainingRegions.returnTarget(homeMapId, chr.getLevel());
        if (dest <= 0 || dest == homeMapId) {
            return false;
        }
        movedUntilMs = now() + MOVE_COOLDOWN_MIN_MS
                + (long) (rng.nextDouble() * (MOVE_COOLDOWN_MAX_MS - MOVE_COOLDOWN_MIN_MS));
        // The new continent is home from the moment it sets out, so a turned-back crossing still leaves it
        // deciding from where it stands (same reasoning as TrainingBot.startMigration).
        homeMapId = dest;
        clearTrainTarget();
        migrateTargetMapId = dest;
        // Enter the travel phase CLEANLY (not doTravel directly): enterPhase resets phaseEntered, which is
        // still true from the town beat. doTravel issues the trip only on its first tick (gated by
        // phaseEntered), so calling it while phaseEntered is true would skip the issue and stall forever
        // re-checking a trip nobody started. The MIGRATE switch arms it on the next tick.
        enterPhase(Phase.MIGRATE); // enterPhase's IN_TOWN branch ends the loiter/wander as it leaves
        return true;
    }

    // Pick a LOW-level map (the roamer selection) and reserve its slot in the ROAMER occupancy table.
    private void doDecide() {
        Character chr = getChr();
        clearTrainTarget(); // release any stale reservation before choosing
        TrainingMap pick = TrainingMapChooser.choose(chr, homeMapId, crowdExcludedMaps(), this::debugChat, true);
        if (pick == null) {
            // Nothing low-level reachable from here (e.g. a continent whose fields are all above the
            // roamer ceiling). Idle in town, but clear the crossing cooldown so the town beat can migrate
            // straight back out — otherwise a roamer that wandered into, say, Leafre would sit there for the
            // full 15-45 min before it was allowed to leave a continent with nothing for it.
            movedUntilMs = 0;
            debugChat("DECIDE: no low-level map reachable (lv " + chr.getLevel() + ") -> idle in town");
            enterPhase(Phase.IN_TOWN);
            return;
        }
        currentTrainMapId = pick.mapId();
        currentMobLevel = Math.max(1, pick.mobLevel());
        debugChat("DECIDE: chose map " + pick.mapId() + " (mob lv " + pick.mobLevel() + ")");
        if (chr.getMapId() == pick.mapId()) {
            enterPhase(Phase.GRIND);
            return;
        }
        enterPhase(Phase.GO_TRAIN);
    }

    // One-shot travel to destMapId, advancing on the async result. Non-blocking: re-polled each macro tick.
    // This is the same progress-aware watchdog TrainingBot uses — a big map or a long route is never cut
    // off for merely taking a while; only a trip that makes no progress at all trips it, and onFail re-plans.
    private void doTravel(int destMapId, Phase onArrive, Phase onFail) {
        Character chr = getChr();
        if (destMapId < 0) {
            enterPhase(onFail);
            return;
        }
        if (chr.getMapId() == destMapId) {
            enterPhase(onArrive);
            return;
        }
        if (!phaseEntered) {
            phaseEntered = true;
            phaseDeadlineMs = now() + TRAVEL_TIMEOUT_MS;
            travelLastMapId = chr.getMapId();
            travelLastPos = chr.getPosition();
            debugChat("travel -> map " + destMapId);
            GCMovement.travel(chr, destMapId, ok -> {
                moveOk = (ok != null && ok);
                moveDone = true;
            });
            return;
        }
        if (moveDone) {
            enterPhase(moveOk ? onArrive : onFail);
            return;
        }
        if (madeTravelProgress(chr) || GCMovement.isWaitingForTransit(chr)) {
            phaseDeadlineMs = now() + TRAVEL_TIMEOUT_MS; // still advancing (or a boat waiting by design)
        } else if (now() > phaseDeadlineMs) {
            debugChat("travel STUCK -> " + onFail);
            GCMovement.cancelTravel(chr);
            enterPhase(onFail);
        }
    }

    private boolean madeTravelProgress(Character chr) {
        int curMap = chr.getMapId();
        if (curMap != travelLastMapId) {
            travelLastMapId = curMap;
            travelLastPos = chr.getPosition();
            return true;
        }
        Point pos = chr.getPosition();
        if (pos != null && (travelLastPos == null
                || Math.abs(pos.x - travelLastPos.x) + Math.abs(pos.y - travelLastPos.y) > TRAVEL_PROGRESS_EPS_PX)) {
            travelLastPos = pos;
            return true;
        }
        return false;
    }

    // At a hunting map: engage the grind, accrue abstract EXP while unobserved, and leave after the session.
    private void doGrind() {
        Character chr = getChr();
        if (!phaseEntered) {
            phaseEntered = true;
            GCMovement.setGrinding(chr, true);
            grind.start(chr);
            grindUntilMs = now() + grindSessionMs();
            lastExpAccrualMs = now();
            GrindTickRegistry.getInstance().register(this);
            sayContext("GrindStart", chr); // a little life when a watching player sees it engage
            return;
        }

        long nowMs = now();
        if (currentMobLevel > 0 && !GCMovement.isMapObserved(chr.getMapId())) {
            double sec = (nowMs - lastExpAccrualMs) / 1000.0;
            if (sec > 0) {
                accrueAbstractExp(currentMobLevel, sec);
            }
        }
        lastExpAccrualMs = nowMs;

        maybeGrindChatter(chr);
        maybeSelfBuff(chr);

        // Bail if the field dried up (a wedged position, or a map whose mobs are unreachable).
        if (grindWatchdog(chr)) {
            return;
        }
        if (grindCrowdBail(chr)) {
            return; // map too crowded — left for another (re-DECIDE)
        }
        if (nowMs >= grindUntilMs) {
            sayContext("MapTransition", chr); // "moving on" — said while still on the map
            leaveGrind();
            enterPhase(Phase.GO_TOWN);
        }
    }

    // ── Ambient dialogue (observed-only flavor, throttled) ───────────────────
    private static final long AMBIENT_MIN_MS = 120_000;
    private static final long AMBIENT_MAX_MS = 240_000;
    private static final long BUFF_MIN_MS = 90_000;
    private static final long BUFF_MAX_MS = 120_000;
    private long nextChatterMs = 0;
    private long nextBuffMs = 0;
    private boolean silentNextBuff = false;

    private void maybeGrindChatter(Character chr) {
        long t = now();
        if (t < nextChatterMs) {
            return;
        }
        nextChatterMs = t + AMBIENT_MIN_MS + (long) (rng.nextDouble() * (AMBIENT_MAX_MS - AMBIENT_MIN_MS));
        if (!GCMovement.isMapObserved(chr.getMapId())) {
            return; // gate advanced; nothing worth saying unobserved
        }
        sayContext("GrindAmbient", chr);
    }

    private void maybeSelfBuff(Character chr) {
        if (now() < nextBuffMs || !GCMovement.isMapObserved(chr.getMapId()) || !GCMovement.isGrounded(chr)) {
            return;
        }
        soloMapling.ArtificialPlayer.BotAttackSystem.BotBuffDriver.forceBuff(chr, silentNextBuff);
        silentNextBuff = false;
        nextBuffMs = now() + BUFF_MIN_MS + (long) (rng.nextDouble() * (BUFF_MAX_MS - BUFF_MIN_MS));
    }

    // Fires a context-token dialogue node, but only when a real player can see it (observed map), and off
    // the macro thread so the line's hold never blocks the tick. A missing node is not an error.
    private void sayContext(String node, Character chr) {
        if (chr == null || !GCMovement.isMapObserved(chr.getMapId())) {
            return;
        }
        soloMapling.server.ExecutorServiceManager.runAsync(() ->
                getDialogueHandler().executeBotContextDialogue(
                        node, this, null, soloMapling.ArtificialPlayer.BotDialogueHandler.CONTEXT_LINE_CHANCE));
    }

    // Maps recently left because they were saturated, with expired entries pruned. Fed to the chooser so a
    // just-left packed map is not immediately re-picked.
    private Set<Integer> crowdExcludedMaps() {
        long t = now();
        mapCrowdCooldown.values().removeIf(until -> until <= t);
        return new java.util.HashSet<>(mapCrowdCooldown.keySet());
    }

    // Crowd-balance escalation (macro tick, runs unobserved too). The grind strategy reports the map
    // saturated when every reachable spot is claimed; if that persists past the dwell, leave for another map
    // (capacity-aware DECIDE spreads it out). Three guards stop thrash: a persist timer, a per-map cooldown,
    // and a per-outing hop cap. Returns true if it left the map.
    private boolean grindCrowdBail(Character chr) {
        if (chr == null) {
            return false;
        }
        if (!grind.mapSaturated() || crowdHopsThisEpisode >= MAX_MAP_HOPS_PER_EPISODE) {
            mapSaturatedSinceMs = 0L;
            return false;
        }
        if (mapSaturatedSinceMs == 0L) {
            mapSaturatedSinceMs = now();
            return false;
        }
        if (now() - mapSaturatedSinceMs < MAP_SATURATED_DWELL_MS) {
            return false;
        }
        int leaving = chr.getMapId();
        debugChat("crowd: map " + leaving + " saturated -> bail to another map");
        mapCrowdCooldown.put(leaving, now() + MAP_EXCLUDE_MS);
        crowdHopsThisEpisode++;
        mapSaturatedSinceMs = 0L;
        leaveGrind();
        enterPhase(Phase.DECIDE);
        return true;
    }

    // Observed-only self-repair: no landed hit for STUCK_TELEPORT_MS with a mob in reach -> teleport to the
    // nearest portal and re-grind; still nothing after STUCK_BAIL_MS -> bail the map and re-decide. The grind
    // heartbeat (a landed hit) resets it. Returns true only when it changed phase.
    private static final long STUCK_TELEPORT_MS = 30_000;
    private static final long STUCK_BAIL_MS = 60_000;
    private static final long REPAIR_COOLDOWN_MS = 12_000;
    private boolean teleportedThisEpisode = false;
    private long lastRepairMs = 0L;

    private boolean grindWatchdog(Character chr) {
        if (chr == null || !GCMovement.isMapObserved(chr.getMapId())) {
            return false; // unobserved bots don't fight (abstract EXP) — they can't be physically stuck
        }
        if (status().isFrozen()) {
            return false; // a frozen bot lands no hits; that stall is the debuff, not a wedge
        }
        long stuck = grind.msSinceProgress();
        if (stuck < STUCK_TELEPORT_MS) {
            teleportedThisEpisode = false;
            return false;
        }
        if (now() - lastRepairMs < REPAIR_COOLDOWN_MS) {
            return false;
        }
        if (!teleportedThisEpisode && grind.isRecovering(chr)) {
            return false; // the strategy is already recovering this position
        }
        if (!teleportedThisEpisode) {
            teleportToNearestPortal(chr);
            grind.resetAfterStall(chr);
            teleportedThisEpisode = true;
            lastRepairMs = now();
            return false;
        }
        if (stuck >= STUCK_BAIL_MS) {
            leaveGrind();
            enterPhase(Phase.DECIDE);
            return true;
        }
        return false;
    }

    // Hard-teleport to the nearest portal — a guaranteed-valid foothold — to break a geometry wedge. Same
    // repair the TrainingBot uses (the portal, not the current spot, is what breaks a wedge).
    private void teleportToNearestPortal(Character chr) {
        java.util.Collection<Portal> portals = chr.getMap().getPortals();
        Point pos = chr.getPosition();
        if (pos == null) {
            return;
        }
        Point best = null;
        double bestSq = Double.MAX_VALUE;
        for (Portal p : portals) {
            Point pp = p.getPosition();
            if (pp == null) {
                continue;
            }
            double dsq = pos.distanceSq(pp);
            if (dsq < bestSq) {
                bestSq = dsq;
                best = pp;
            }
        }
        if (best != null) {
            GCMovement.stop(chr); // drop the in-flight move so the driver re-acquires from the new spot
            GCMovement.teleportTo(chr, best.x, best.y);
        }
    }

    private void leaveGrind() {
        GrindTickRegistry.getInstance().unregister(this);
        grind.release(getChr()); // drop the spot claim + reset combat state
        clearTrainTarget();      // release this map's ROAMER occupancy slot
        GCMovement.setGrinding(getChr(), false);
        GCMovement.stop(getChr());
        teleportedThisEpisode = false;
    }

    private void enterPhase(Phase next) {
        if (phase == Phase.IN_TOWN) {
            BotWanderSystem.stop(getChr()); // leaving town: end the stroll that owned movement there
        }
        phase = next;
        phaseEntered = false;
        moveDone = false;
        moveOk = false;
        debugChat("phase -> " + next);
    }

    // ── Selection plumbing (reserve/release against the ROAMER table) ──────────

    private void clearTrainTarget() {
        if (currentTrainMapId >= 0) {
            TrainingMapChooser.release(TrainingMapChooser.Scope.ROAMER, currentTrainMapId);
        }
        currentTrainMapId = -1;
        currentMobLevel = 0;
    }

    private long dwellMs() {
        return TOWN_DWELL_MIN_MS + (long) (rng.nextDouble() * (TOWN_DWELL_MAX_MS - TOWN_DWELL_MIN_MS));
    }

    private long grindSessionMs() {
        return GRIND_MIN_MS + (long) (rng.nextDouble() * (GRIND_MAX_MS - GRIND_MIN_MS));
    }

    // Silent, level-scaled accrual: kills/min x per-kill-exp, where per-kill-exp ~ the map's mob level.
    // A high-level roamer on a low map earns almost nothing per kill, which is exactly why it stays low —
    // it is decoration with a plausible level, not a bot that should out-level its own hunting grounds.
    private void accrueAbstractExp(int mobLevel, double elapsedSec) {
        int perKillExp = Math.max(1, mobLevel);
        int gain = (int) Math.round((KILLS_PER_MIN / 60.0) * perKillExp * elapsedSec);
        if (gain <= 0) {
            return;
        }
        Character chr = getChr();
        long exp = (long) chr.getExp() + gain;
        int level = chr.getLevel();
        while (level < LEVEL_CAP) {
            int need = ExpTable.getExpNeededForLevel(level);
            if (need <= 0 || exp < need) {
                break;
            }
            exp -= need;
            level++;
        }
        chr.setLevel(level);
        chr.setExp((int) Math.min(exp, Integer.MAX_VALUE));
    }

    // Debug narration (OFF by default), same hot-swap-friendly shape as TrainingBot's.
    void debugChat(String message) {
        boolean debugChatEnabled = false; // <-- flip to true + HotSwap to turn narration on
        if (!debugChatEnabled) {
            return;
        }
        Character chr = getChr();
        if (chr == null || chr.getMap() == null) {
            return;
        }
        SocialCommands.BotFullChat(chr, message);
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    @Override
    public synchronized void stopScheduledTask() {
        Character chr = getChr();
        if (chr != null) {
            if (chr.getChair() > 0) {
                botCancelChair(chr);
            }
            GrindTickRegistry.getInstance().unregister(this);
            grind.release(chr);
            clearTrainTarget();
            BotWanderSystem.stop(chr);
            soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackDriver.clearBot(chr.getId());
            soloMapling.ArtificialPlayer.BotAttackSystem.BotBuffDriver.clearBot(chr.getId());
            GCMovement.disable(chr);
        }
        super.stopScheduledTask();
        log("[RoamerBot] stopped: " + (chr != null ? chr.getName() : "?"));
    }
}
