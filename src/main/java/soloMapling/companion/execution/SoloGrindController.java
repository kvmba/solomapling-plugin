package soloMapling.companion.execution;

import org.gms.client.Character;
import org.gms.constants.game.ExpTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.ArtificialPlayer.BotGrindSystem.GrindBrain;
import soloMapling.ArtificialPlayer.BotGrindSystem.TrainingMap;
import soloMapling.ArtificialPlayer.BotGrindSystem.TrainingMapChooser;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.companion.lifecycle.HostCompanionRuntimeAdapter;

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

    /**
     * Below this level a companion does not solo at all. It is the same bar that
     * gates the offline settlement: on the beginner island a companion should be
     * doing the island's own content, not being handed levels in its sleep.
     */
    private static final int MIN_SOLO_LEVEL = HostCompanionRuntimeAdapter.NOVICE_SETTLEMENT_LEVEL;

    /** How long to keep working one map before considering a move. */
    private static final long SESSION_MIN_MS = 4 * 60_000L;
    private static final long SESSION_MAX_MS = 11 * 60_000L;

    /** How long to wait after a session before starting the next. */
    private static final long REST_MIN_MS = 60_000L;
    private static final long REST_MAX_MS = 4 * 60_000L;

    /** How long to wait before retrying when no hunting ground can be found. */
    private static final long RETRY_MS = 90_000L;

    /** Cap on remembered unproductive maps, so the set cannot grow forever. */
    private static final int EXCLUDED_LIMIT = 8;

    private enum Phase { IDLE, TRAVELLING, GRINDING, RESTING }

    private final GrindBrain grind;
    /** Working set of maps this session found unproductive. Bounded: a long-lived
     * companion would otherwise accumulate a set that excludes everything. */
    private final Set<Integer> excluded = java.util.Collections.newSetFromMap(
            new java.util.LinkedHashMap<Integer, Boolean>() {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<Integer, Boolean> eldest) {
                    return size() > EXCLUDED_LIMIT;
                }
            });

    private Phase phase = Phase.IDLE;
    private int targetMapId = -1;
    private int targetMobLevel = 0;
    private long phaseUntilMs = 0L;
    private boolean grindRegistered = false;
    private long lastAccrualMs = 0L;

    public SoloGrindController(GrindBrain grind) {
        this.grind = java.util.Objects.requireNonNull(grind, "grind");
    }

    /** True while this controller owns the companion's combat. */
    public boolean active() {
        return phase == Phase.TRAVELLING || phase == Phase.GRINDING;
    }

    /**
     * True while this controller is mid-session: unlike {@link #active()} this
     * stays true through the rest between sessions, so the caller knows not to
     * let anything else take the companion's attention and start a competing
     * session while it waits.
     */
    public boolean engaged() {
        return phase != Phase.IDLE;
    }

    /**
     * Runs one decision tick. Callers gate this on survival/gear being idle and
     * on no player having asked the companion to train with them.
     *
     * @return true when the companion's attention is spoken for this tick
     */
    public boolean tick(Character companion) {
        if (companion == null || companion.getMap() == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        switch (phase) {
            case IDLE -> {
                if (now < phaseUntilMs) {
                    return false;
                }
                return startSession(companion, now);
            }
            case RESTING -> {
                if (now < phaseUntilMs) {
                    return true;
                }
                phase = Phase.IDLE;
                return false;
            }
            case TRAVELLING -> {
                if (companion.getMapId() == targetMapId) {
                    beginGrinding(companion, now);
                } else if (now > phaseUntilMs) {
                    // Never arrived. Drop it and rest, rather than leaving the
                    // companion registered for combat on a map it is not on.
                    log.info("Companion solo grind travel timed out cid={} target={} at={}",
                            companion.getId(), targetMapId, companion.getMapId());
                    endSession(companion, now);
                }
                return true;
            }
            case GRINDING -> {
                if (companion.getMapId() != targetMapId) {
                    endSession(companion, now); // warped away mid-session
                    return true;
                }
                if (GCMovement.isMapObserved(companion.getMapId())) {
                    // A player can see it: let the real combat tick own the
                    // fighting, and don't double-pay with simulated kills.
                    lastAccrualMs = now;
                    if (now > phaseUntilMs) {
                        endSession(companion, now);
                    }
                    return true;
                }
                accrueSimulatedExperience(companion, now);
                if (now > phaseUntilMs) {
                    endSession(companion, now);
                }
                return true;
            }
            default -> {
                phase = Phase.IDLE;
                return false;
            }
        }
    }

    /** Releases any claim this controller holds. Safe to call at any time. */
    public void stop(Character companion) {
        if (grindRegistered) {
            grind.release(companion);
            GCMovement.setGrinding(companion, false);
            grindRegistered = false;
        }
        releaseReservation();
        excluded.clear();
        targetMapId = -1;
        targetMobLevel = 0;
        phase = Phase.RESTING;
        phaseUntilMs = System.currentTimeMillis() + REST_MIN_MS;
    }

    private boolean startSession(Character companion, long now) {
        if (companion.getLevel() < MIN_SOLO_LEVEL) {
            // Too new to solo. Check again later, not every tick.
            phase = Phase.RESTING;
            phaseUntilMs = now + RETRY_MS;
            return false;
        }
        TrainingMap pick = TrainingMapChooser.choose(
                companion, companion.getMapId(), excluded, message -> { });
        if (pick == null) {
            phase = Phase.RESTING;
            phaseUntilMs = now + RETRY_MS;
            log.debug("Companion solo grind found no hunting ground cid={} level={} at={}",
                    companion.getId(), companion.getLevel(), companion.getMapId());
            return false;
        }
        targetMapId = pick.mapId();
        targetMobLevel = pick.mobLevel();
        phase = Phase.TRAVELLING;
        phaseUntilMs = now + TRAVEL_TIMEOUT_MS;
        if (companion.getMapId() == targetMapId) {
            beginGrinding(companion, now);
            return true;
        }
        GCMovement.travel(companion, targetMapId, null);
        log.info("Companion solo grind travelling cid={} from={} to={} mobLevel={}",
                companion.getId(), companion.getMapId(), targetMapId, targetMobLevel);
        return true;
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
        if (grindRegistered) {
            grind.release(companion);
            GCMovement.setGrinding(companion, false);
            grindRegistered = false;
        }
        // A map that produced nothing is not worth another try this session.
        if (targetMapId >= 0 && now - lastAccrualMs > SESSION_MIN_MS) {
            excluded.add(targetMapId);
        }
        releaseReservation();
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
        if (targetMapId >= 0) {
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
