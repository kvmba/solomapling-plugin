package soloMapling.companion.execution;

import org.gms.client.Character;
import org.gms.constants.game.ExpTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.ArtificialPlayer.BotGrindSystem.GrindBrain;
import soloMapling.ArtificialPlayer.BotGrindSystem.TrainingMap;
import soloMapling.ArtificialPlayer.BotGrindSystem.BotPlaceNames;
import soloMapling.ArtificialPlayer.BotGrindSystem.TrainingMapChooser;
import soloMapling.ArtificialPlayer.BotGrindSystem.TrainingRegions;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.companion.routine.CompanionNoviceLevel;

import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Solo grinding: what a companion does with its day when no player asked it to
 * train with them.
 *
 * <p>Without this a companion only fights as somebody's tag-along, and its
 * {@code TRAIN} blocks — the bulk of its generated routine — are spent standing
 * in town. This gives the block something to actually be: pick a hunting ground,
 * walk to it, and work it.</p>
 *
 * <p>Two ways it advances, and which one runs depends on whether anybody is
 * watching:</p>
 * <ul>
 *   <li><b>A player is on the map</b> — real combat. {@link GrindBrain} drives
 *   movement, target selection and attacks against live monsters, and the
 *   experience is whatever the host's combat awards.</li>
 *   <li><b>Nobody is on the map</b> — simulated combat. Real swings at nothing
 *   would be the cost of full combat physics for an audience of nobody, so the
 *   bot holds its position and accrues experience arithmetically at the rate a
 *   player would earn on that map. Same curve, no per-frame work.</li>
 * </ul>
 *
 * <p>The simulated rate is deliberately modest: a companion is not meant to
 * out-level the players it exists to accompany, and its offline settlement is
 * capped at a fraction of a level for the same reason.</p>
 */
public final class SoloGrindController {

    private static final Logger log = LoggerFactory.getLogger(SoloGrindController.class);

    /**
     * Kills per minute a solo companion is credited with while unobserved.
     *
     * <p>Modelled on the rate the ambient training bots use, since the situation
     * is the same: a bot left alone on a hunting ground. Halved from theirs —
     * an ambient bot exists to fill a map and is never seen closely, while a
     * companion has to stay plausible to the players who meet it.</p>
     */
    private static final double KILLS_PER_MIN = 15.0;

    /** How long to keep working one map before considering a move. */
    private static final long SESSION_MIN_MS = 4 * 60_000L;
    private static final long SESSION_MAX_MS = 11 * 60_000L;

    /** How long to wait after a session before starting the next. */
    private static final long REST_MIN_MS = 60_000L;
    private static final long REST_MAX_MS = 4 * 60_000L;

    /** How long to wait before retrying when no hunting ground can be found. */
    private static final long RETRY_MS = 90_000L;

    private enum Phase { IDLE, TRAVELLING, GRINDING, RESTING }

    private final GrindBrain grind;

    /*
     * Every field below is read and written from two different pools: the bot's
     * own state machine (seconds apart) and the shared 250ms combat sweep that
     * asks whether this companion is fighting. They are volatile for the same
     * reason TrainingBot's phase is — without it the sweep can keep acting on a
     * session the state machine has already ended.
     *
     * Volatile is enough here and a lock would be wrong: the sweep only reads,
     * and the single writer is the state machine tick. There is no pair of
     * fields that must move together.
     */
    private volatile Phase phase = Phase.IDLE;
    private volatile int targetMapId = -1;
    private volatile int targetMobLevel = 0;
    private volatile long phaseUntilMs = 0L;
    private volatile boolean grindRegistered = false;
    private volatile long lastAccrualMs = 0L;
    /**
     * True while TRAVELLING means "changing continent" rather than "walking to a hunting ground".
     *
     * <p>The pick this trip is heading for is a continent's town, not a grind map, so arriving
     * must not start a session there. Written only by the state machine tick, read by the 250ms
     * combat sweep through {@link #isGrindingOn}, hence volatile like the fields above.</p>
     */
    private volatile boolean relocating = false;
    /**
     * Until when a companion must stay where it landed: the crossing cooldown.
     *
     * <p>It covers every crossing, climbs included. Without it a companion that can go anywhere
     * is offered a new continent on its very next decision, so it spends its life aboard instead
     * of ever reaching a hunting ground — and one that keeps leaving is never findable, which
     * matters because a companion's job is to be met.</p>
     */
    private volatile long relocatedUntilMs = 0L;

    /** How long a crossing keeps a companion on the continent it moved to. */
    private static final long STAY_MIN_MS = 180 * 60_000L;  // 3 h
    private static final long STAY_MAX_MS = 480 * 60_000L;  // 8 h

    public SoloGrindController(GrindBrain grind) {
        this.grind = java.util.Objects.requireNonNull(grind, "grind");
    }

    /**
     * True when the companion is working this particular map.
     *
     * <p>Only the GRINDING phase counts. TRAVELLING is deliberately excluded:
     * the companion is on its way, standing in whatever town or field the route
     * crosses, and reporting that as active would have the combat sweep start
     * fights there on the way to the ground that was actually chosen.</p>
     *
     * <p>The map is compared too, because a companion can be warped off its
     * ground while its phase still says it is grinding.</p>
     */
    public boolean isGrindingOn(int mapId) {
        return phase == Phase.GRINDING && targetMapId == mapId;
    }

    /**
     * True while a solo session is running, including travel to and rest between
     * grounds.
     *
     * <p>RESTING counts as engaged: it is this controller's own pause, and the
     * caller must not start something else on top of it. IDLE is the only phase
     * that is genuinely free — in every other phase the controller has a session
     * in progress or one it is about to resume.</p>
     *
     * <p>{@link #stop(Character)} therefore returns to IDLE, not RESTING: a stop
     * is someone else taking over, and leaving the controller in a phase that
     * reads as engaged would have the caller register the companion for combat
     * sweep at the very moment it stopped grinding.</p>
     */
    public boolean engaged() {
        return phase != Phase.IDLE;
    }

    /**
     * Runs one decision tick. Callers gate this on survival/gear being idle and
     * on no player having asked the companion to train with them.
     */
    public void tick(Character companion) {
        if (companion == null || companion.getMap() == null) {
            return;
        }
        long now = System.currentTimeMillis();
        switch (phase) {
            case IDLE -> {
                if (now < phaseUntilMs) {
                    return;
                }
                startSession(companion, now);
            }
            case RESTING -> {
                if (now >= phaseUntilMs) {
                    phase = Phase.IDLE;
                }
            }
            case TRAVELLING -> {
                if (companion.getMapId() != targetMapId) {
                    if (GCMovement.isWaitingForTransit(companion)) {
                        // Waiting for a boat to board or a crossing to dock is stillness by design,
                        // and a cycle runs minutes. Counting it as no progress would cancel the
                        // crossing and re-plan forever — which is why a companion never used to
                        // reach another continent at all. GCTravel's transit ceiling bounds it.
                        phaseUntilMs = now + TRAVEL_TIMEOUT_MS;
                    } else if (now > phaseUntilMs) {
                        // Never arrived. Cancel the crossing as well as dropping the
                        // session: the trip is still in flight, and if it is left
                        // alone it will deliver the companion to a hunting ground
                        // this controller has already given up on — arriving on a
                        // map nothing is grinding, and staying there.
                        log.info("Companion solo grind travel timed out cid={} target={} at={}",
                                companion.getId(), targetMapId, companion.getMapId());
                        GCMovement.cancelTravel(companion);
                        endSession(companion, now);
                    }
                } else if (relocating) {
                    // Landed on another continent's town. It is a place to be, not a ground to
                    // work, so drop the trip and go straight back to idle — the next tick picks a
                    // hunting ground here, which is the whole point of having crossed.
                    log.info("Companion relocated cid={} from={} to={} level={}",
                            companion.getId(), targetMapId, companion.getMapId(),
                            companion.getLevel());
                    relocating = false;
                    // No reservation was ever taken out on a continent's town: it is not a
                    // hunting ground, and releasing one here would decrement a counter this
                    // companion never incremented — skewing that map's capacity for everyone.
                    targetMapId = -1;
                    phase = Phase.IDLE;
                    phaseUntilMs = 0L;
                } else {
                    beginGrinding(companion, now);
                }
            }
            case GRINDING -> {
                if (companion.getMapId() != targetMapId) {
                    endSession(companion, now); // warped away mid-session
                    return;
                }
                if (GCMovement.isMapObserved(companion.getMapId())) {
                    // A player can see it: let the real combat tick own the
                    // fighting, and don't double-pay with simulated kills.
                    lastAccrualMs = now;
                    if (now > phaseUntilMs) {
                        endSession(companion, now);
                    }
                    return;
                }
                accrueSimulatedExperience(companion, now);
                if (now > phaseUntilMs) {
                    endSession(companion, now);
                }
            }
            default -> phase = Phase.IDLE;
        }
    }

    /** Releases any claim this controller holds. Safe to call at any time. */
    public void stop(Character companion) {
        // A trip in flight outlives the session that asked for it, and would
        // walk the companion to a hunting ground nothing is grinding any more.
        if (phase == Phase.TRAVELLING && companion != null) {
            GCMovement.cancelTravel(companion);
        }
        // Only release through the character when there is one: a companion that
        // was never given a body cannot have a live grind claim, and handing
        // null to the grind brain would only push the problem deeper.
        if (grindRegistered && companion != null) {
            grind.release(companion);
            GCMovement.setGrinding(companion, false);
        }
        grindRegistered = false;
        // Release BEFORE clearing the flag: releaseReservation() reads `relocating` to tell a
        // hunting-ground slot (which was reserved) from a continent town (which never was), so
        // the flag has to still describe the trip being dropped.
        releaseReservation();
        relocating = false;
        targetMapId = -1;
        targetMobLevel = 0;
        // IDLE, not RESTING: a stop means someone else is taking over, and a
        // RESTING controller is one that still claims the companion.
        phase = Phase.IDLE;
        phaseUntilMs = 0L;
    }

    private void startSession(Character companion, long now) {
        if (CompanionNoviceLevel.isNovice(companion.getLevel())) {
            // Too new to solo. Check again later, not every tick.
            phase = Phase.RESTING;
            phaseUntilMs = now + RETRY_MS;
            return;
        }
        // Outgrown this continent? The chooser only ever looks a few hops around where the
        // companion stands, so it can never find a hunting ground across the water — a companion
        // that stayed where it spawned would grind trivial mobs forever. Take the crossing first;
        // the next tick picks a ground on the far side.
        if (now >= relocatedUntilMs) {
            int continent = TrainingRegions.migrationTarget(
                    companion.getMapId(), companion.getLevel());
            if (continent > 0 && continent != companion.getMapId()) {
                // The dice apply to a free move only — a companion that can go anywhere should
                // not leave every single time it could. The cooldown below covers both kinds:
                // a crossing is a trip like any other, and a companion that keeps leaving
                // never settles anywhere long enough to be found.
                boolean freeMove = companion.getLevel() >= TrainingRegions.FREE_MOVE_LEVEL;
                if (freeMove
                        && ThreadLocalRandom.current().nextDouble()
                                >= TrainingRegions.OPTIONAL_MOVE_CHANCE) {
                    relocatedUntilMs = now + RETRY_MS; // not this time — ask again later
                } else {
                    relocatedUntilMs = now + STAY_MIN_MS
                            + (long) (ThreadLocalRandom.current().nextDouble()
                                    * (STAY_MAX_MS - STAY_MIN_MS));
                    relocating = true;
                    targetMapId = continent;
                    targetMobLevel = 0;
                    phase = Phase.TRAVELLING;
                    phaseUntilMs = now + TRAVEL_TIMEOUT_MS;
                    GCMovement.travel(companion, continent, null);
                    log.info("Companion relocating cid={} from={} to={} level={}",
                            companion.getId(), companion.getMapId(), continent,
                            companion.getLevel());
                    // stdout so it sits next to the training bots' [MIGRATE] lines — a
                    // companion's moves are the ones hardest to attribute, since it has no
                    // home town to compare against.
                    System.out.println("[MIGRATE] bot=" + companion.getName()
                            + " lv=" + companion.getLevel()
                            + " from=" + companion.getMapId()
                            + "(" + BotPlaceNames.name(companion.getMapId()) + ")"
                            + " dest=" + continent
                            + "(" + BotPlaceNames.name(continent) + ")"
                            + " (companion)");
                    return;
                }
            }
        }
        // No excluded set: the chooser already watches how many bots target each
        // map and picks another when one is full, so a per-bot memory of
        // "unproductive" maps would only duplicate that and shrink the field.
        TrainingMap pick = TrainingMapChooser.choose(
                companion, companion.getMapId(), Set.of(), message -> { });
        if (pick == null) {
            phase = Phase.RESTING;
            phaseUntilMs = now + RETRY_MS;
            log.debug("Companion solo grind found no hunting ground cid={} level={} at={}",
                    companion.getId(), companion.getLevel(), companion.getMapId());
            return;
        }
        targetMapId = pick.mapId();
        targetMobLevel = pick.mobLevel();
        phase = Phase.TRAVELLING;
        phaseUntilMs = now + TRAVEL_TIMEOUT_MS;
        if (companion.getMapId() == targetMapId) {
            beginGrinding(companion, now);
            return;
        }
        GCMovement.travel(companion, targetMapId, null);
        log.info("Companion solo grind travelling cid={} from={} to={} mobLevel={}",
                companion.getId(), companion.getMapId(), targetMapId, targetMobLevel);
    }

    private void beginGrinding(Character companion, long now) {
        phase = Phase.GRINDING;
        grind.start(companion);
        GCMovement.setGrinding(companion, true);
        grindRegistered = true;
        lastAccrualMs = now;
        phaseUntilMs = now + SESSION_MIN_MS
                + (long) (ThreadLocalRandom.current().nextDouble()
                        * (SESSION_MAX_MS - SESSION_MIN_MS));
        log.info("Companion solo grind started cid={} map={} mobLevel={} observed={}",
                companion.getId(), companion.getMapId(), targetMobLevel,
                GCMovement.isMapObserved(companion.getMapId()));
    }

    private void endSession(Character companion, long now) {
        if (grindRegistered && companion != null) {
            grind.release(companion);
            GCMovement.setGrinding(companion, false);
        }
        grindRegistered = false;
        // Release BEFORE clearing the flag: releaseReservation() reads `relocating` to tell a
        // hunting-ground slot (which was reserved) from a continent town (which never was), so
        // the flag has to still describe the trip being dropped.
        releaseReservation();
        relocating = false;
        targetMapId = -1;
        targetMobLevel = 0;
        phase = Phase.RESTING;
        phaseUntilMs = now + REST_MIN_MS
                + (long) (ThreadLocalRandom.current().nextDouble()
                        * (REST_MAX_MS - REST_MIN_MS));
    }

    private void releaseReservation() {
        // -1 would decrement the "no map" bucket and permanently skew every
        // other bot's capacity maths for it.
        // A relocation holds no reservation at all: its target is a continent's town, not a
        // hunting ground, so nothing was ever incremented for it. Releasing anyway would
        // decrement a counter this companion never touched and leave that map's capacity
        // permanently wrong for every other bot that wants to grind there.
        if (targetMapId >= 0 && !relocating) {
            TrainingMapChooser.release(targetMapId);
        }
    }

    /**
     * Credits experience for time spent alone on a hunting ground.
     *
     * <p>The rate is the map's own: a companion on a map of level-30 monsters
     * earns what those monsters are worth, so picking a harder ground is worth
     * more — the same trade a player makes. Levels are granted through
     * {@link ExpTable}, the host's own curve, so a companion crosses each
     * threshold exactly where a player would.</p>
     */
    private void accrueSimulatedExperience(Character companion, long now) {
        double elapsedSec = (now - lastAccrualMs) / 1000.0;
        lastAccrualMs = now;
        if (elapsedSec <= 0 || targetMobLevel <= 0) {
            return;
        }
        int gain = (int) Math.round((KILLS_PER_MIN / 60.0) * targetMobLevel * elapsedSec);
        if (gain <= 0) {
            return;
        }
        int startLevel = companion.getLevel();
        long exp = (long) companion.getExp() + gain;
        int level = startLevel;
        while (level < companion.getMaxLevel()) {
            int needed = ExpTable.getExpNeededForLevel(level);
            if (needed <= 0 || exp < needed) {
                break;
            }
            exp -= needed;
            level++;
        }
        companion.setLevel(level);
        companion.setExp((int) Math.min(exp, Integer.MAX_VALUE));
        if (level > startLevel) {
            log.info("Companion solo grind level up cid={} from={} to={} map={}",
                    companion.getId(), startLevel, level, companion.getMapId());
        }
    }

    private static final long TRAVEL_TIMEOUT_MS = 120_000L;
}
