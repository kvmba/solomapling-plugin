package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.client.Character;
import org.gms.constants.game.CharacterStance;
import org.gms.server.maps.Foothold;
import org.gms.server.maps.MapleMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotDashBurst;
import soloMapling.ArtificialPlayer.BotHealthSystem.BotDeath;
import soloMapling.ArtificialPlayer.BotMovementSystem.MovementCommands;
import soloMapling.ArtificialPlayer.BotStatusSystem.BotDebuffState;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

// Adapted from GreenCatMS - GCMoveSystem tick driver, now also ticks contact damage. Credit: NutNNut.
/*
 * The 50 ms (20 Hz) tick driver for GCMoveSystem dynamic movement. Replaces GreenCat's
 * per-bot TimerManager task + BotManager.stepMovementCore funnel with a
 * self-contained scheduled driver.
 *
 * Threading: a shared scheduled pool; each bot has one self-rescheduling deadline chain.
 * Its state monitor serializes ticks across stop/start races, preserving the per-bot
 * single-thread assumption made by GreenCat's non-volatile physics fields.
 */
final class GCMovementDriver {
    private GCMovementDriver() {
    }

    private static final Logger log = LoggerFactory.getLogger(GCMovementDriver.class);

    private static final int AI_TICK_MS = 100;            // heavy decisions every other tick
    // LOD scheduling cadence for an unobserved bot (no real player on or adjacent to its map): the
    // driver self-reschedules its tick at this interval instead of TICK_MS, so it stops consuming
    // 20 Hz wakeups. The analytic CoarseExecutor is pure wall-clock, so the slower cadence yields
    // identical positions.
    private static final int UNOBSERVED_TICK_MS = 1000;
    // Ours (Fable Phase 3): an unobserved bot with no movement job at all doesn't need
    // the UNOBSERVED_TICK_MS coarse cadence either. A job started while idling begins up to one
    // idle period late (~4s), which is invisible on a map nobody can see; on promotion the next
    // tick returns to TICK_MS. At ~1200 mostly-idle background bots this cuts the constant
    // movement wakeups roughly 16x.
    private static final int UNOBSERVED_IDLE_TICK_MS = 4000;
    // Coarse "arrived" box: within this of the goal, hold (and fire arrival) instead of replanning.
    private static final int COARSE_ARRIVE_PX = 12;
    private static final boolean ENABLE_UNSTUCK = true;
    private static final int AIR_STUCK_RECOVER_TICKS = 30;
    // Ours: live fall-off-map catch. The airborne integrator has no VR-bottom clamp, so a bot that slips
    // through a foothold gap (or off the side) free-falls forever — the frozen-air watchdog only fires once
    // the position STOPS changing, which a live plummet never does. Trigger a snap-back once the bot is this
    // far outside the map's VR bounds (footholds live inside the VR, so a legit deep drop never reaches here).
    private static final int FALL_RECOVER_SLACK_PX = 400;
    // Organic map-entry: appear standing at the spawn portal, wait a "client load" beat, then drop.
    // Mirrors WarpCommands.botEnterPortalDropDown (the recorded engine's ~1.5s lag before the drop).
    private static final long PORTAL_DROP_DELAY_MS = 1500;
    private static final int PORTAL_DROP_DELAY_JITTER_MS = 600; // + 0..600ms so arrivals aren't uniform
    // Extra beat past the drop release during which the idle-expression layer stays silent, so the
    // natural fall to the floor completes before the bot thinks about emoting. A 60px drop at gravity
    // 2000px/s^2 lands in ~0.25s; this leaves generous headroom. See BotMovementState.portalArrivalGuardUntilMs.
    private static final int PORTAL_FALL_GUARD_MS = 1_500;
    // Abandon a move target the bot can't get closer to for this long (unreachable / blocked / bug),
    // so a bot never tries to reach a point forever. The clock resets on any real progress.
    private static final long MOVE_NO_PROGRESS_MS = 8_000;
    private static final int MOVE_PROGRESS_EPS_PX = 16;
    // Recompute a bot's movement profile on this cadence so runtime changes (chiefly party Haste —
    // joining/leaving a party with a high-level thief) take effect without needing a map change.
    // refreshMovementProfile no-ops when the bucket is unchanged, so this is ~free for non-party bots.
    private static final long PROFILE_REFRESH_INTERVAL_MS = 20_000;

    private static final AtomicInteger THREAD_SEQ = new AtomicInteger();

    // Movement-tick pool size: one driver thread per core, but never fewer than 1 (the JVM can
    // report 0 processors). The old max(2, cores / 2) forced >= 2 threads even on a single-core
    // box and gave away half the machine on a big one.
    private static final int TICK_POOL_SIZE =
            Math.max(1, Runtime.getRuntime().availableProcessors());

    private static final ScheduledExecutorService POOL = Executors.newScheduledThreadPool(
            TICK_POOL_SIZE,
            r -> {
                Thread t = new Thread(r, "gcmove-tick-" + THREAD_SEQ.getAndIncrement());
                t.setDaemon(true);
                return t;
            });

    static void start(BotMovementState entry) {
        synchronized (entry) {
            cancelScheduledTick(entry);
            entry.tickStopped = false;
            long generation = ++entry.tickGeneration;
            scheduleNext(entry, generation, System.nanoTime());
        }
    }

    static void stop(BotMovementState entry) {
        synchronized (entry) {
            entry.tickStopped = true;
            entry.tickGeneration++;
            cancelScheduledTick(entry);
        }
    }

    private static void cancelScheduledTick(BotMovementState entry) {
        if (entry.task != null) {
            entry.task.cancel(false);
            entry.task = null;
        }
    }

    /*
     * Self-rescheduling tick: instead of a fixed 20 Hz task per bot, each tick schedules the next at a
     * cadence that matches the bot's tier — TICK_MS (50 ms) when its map is observed,
     * UNOBSERVED_TICK_MS (1000 ms) when not. So an unobserved bot stops consuming 20 Hz wakeups
     * (the analytic CoarseExecutor is pure wall-clock, so a slower cadence gives identical positions).
     * Each next deadline is based on the preceding deadline, not on tick completion, so tick work does
     * not get added to the visible 50 ms cadence. If work overruns one or more periods, missed deadlines
     * are skipped rather than executed as an unbounded catch-up burst. Re-reading the tier after every
     * tick preserves dynamic promotion/demotion between 50/250/1000 ms.
     */
    private static void scheduleNext(BotMovementState entry, long generation, long deadlineNanos) {
        long delayNanos = Math.max(0L, deadlineNanos - System.nanoTime());
        entry.task = POOL.schedule(
                () -> runScheduledTick(entry, generation, deadlineNanos),
                delayNanos,
                TimeUnit.NANOSECONDS);
    }

    private static void runScheduledTick(BotMovementState entry, long generation, long deadlineNanos) {
        synchronized (entry) {
            if (entry.tickStopped || entry.tickGeneration != generation) {
                return;
            }
            safeTick(entry);
            if (entry.tickStopped || entry.tickGeneration != generation) {
                return;
            }
            long completedAtNanos = System.nanoTime();
            long cadenceNanos = TimeUnit.MILLISECONDS.toNanos(nextCadenceMs(entry));
            long nextDeadlineNanos = nextDeadline(deadlineNanos, completedAtNanos, cadenceNanos);
            scheduleNext(entry, generation, nextDeadlineNanos);
        }
    }

    /*
     * Pure scheduling calculation, with arbitrary-but-consistent time units for focused tests.
     * A completion exactly on the next deadline may run immediately; a completion after it skips
     * enough whole slots to return to the first future deadline.
     */
    static long nextDeadline(long previousDeadline, long completedAt, long cadence) {
        if (cadence <= 0L) {
            throw new IllegalArgumentException("cadence must be positive");
        }
        long next = previousDeadline + cadence;
        if (next >= completedAt) {
            return next;
        }
        long missedSlots = (completedAt - next) / cadence + 1L;
        return next + missedSlots * cadence;
    }

    private static long nextCadenceMs(BotMovementState entry) {
        Character bot = entry.bot;
        boolean active = bot != null && bot.getMap() != null
                && ObserverTracker.isActiveMap(bot.getMapId());
        if (active) {
            return BotPhysicsEngine.cfg.TICK_MS;
        }
        // Airborne or on a rope: never drop to the slow cadence. The physics integrator is
        // step-based, so at 1s per tick a bot hangs in the air for ~20 seconds before it lands -
        // which is what "stuck in the jump pose" and "standing in mid-air" are. It also keeps
        // isMoving() true, so the bot never reaches the idle branch that would clear the stance.
        if (entry.inAir || entry.climbing) {
            return BotPhysicsEngine.cfg.TICK_MS;
        }
        if (bot != null
                && !GCMovement.isMoving(bot) && !GCMovement.isTraveling(bot) && !GCMovement.isFollowing(bot)) {
            return UNOBSERVED_IDLE_TICK_MS; // jobless + unseen: idle heartbeat only
        }
        return UNOBSERVED_TICK_MS;
    }

    // Throttled profile recompute. Self-thief speed/jump is static (job/level), but party Haste is dynamic
    // (roster changes at runtime), and the profile is otherwise only built once at enable(). Every
    // PROFILE_REFRESH_INTERVAL_MS we recompute; refreshMovementProfile returns fast and re-warms only when
    // the speed/jump bucket actually changed, so a non-party bot pays just one fromCharacter() per interval.
    private static void maybeRefreshProfile(BotMovementState entry) {
        long now = System.currentTimeMillis();
        if (now - entry.lastProfileRefreshMs < PROFILE_REFRESH_INTERVAL_MS) {
            return;
        }
        entry.lastProfileRefreshMs = now;
        BotMovementManager.refreshMovementProfile(entry);
    }

    private static void safeTick(BotMovementState entry) {
        try {
            soloMapling.server.BotPerfStats.MOVEMENT_TICKS.increment();
            tick(entry);
            // Govern the state-bound auras (疾驰 / 伪装) from the stance this tick just settled. Inside
            // the try so a failure here can never break the self-reschedule chain.
            soloMapling.ArtificialPlayer.BotAttackSystem.BotAuraState.tickMovement(entry.bot);
        } catch (Throwable t) {
            // A thrown exception must not break the self-reschedule chain - swallow so the bot keeps
            // ticking. But do NOT swallow it silently: a tick that throws leaves the bot frozen in
            // whatever pose the aborted tick had already set (a half-applied airborne/rope transition is
            // the "bot hangs mid-air in the jump pose" report), and with no log there is nothing to
            // trace. One line per bot per 10s keeps a chronically failing bot cheap; the stack names the
            // subsystem the first time.
            int botId = entry.bot != null ? entry.bot.getId() : -1;
            long now = System.currentTimeMillis();
            Long last = TICK_FAILURE_LOG_AT.get(botId);
            if (last == null || now - last >= TICK_FAILURE_LOG_INTERVAL_MS) {
                // Keep the map bounded: entries outside the interval prune themselves on the next
                // failure, so a bot that stops failing drops out without needing a teardown hook (the
                // lazy cleanup BotPlayerReaction uses for its per-player cooldowns).
                TICK_FAILURE_LOG_AT.entrySet().removeIf(e -> now - e.getValue() >= TICK_FAILURE_LOG_INTERVAL_MS);
                TICK_FAILURE_LOG_AT.put(botId, now);
                log.warn("GCMove tick failed for bot {} (map {}); the bot stays in its current movement state",
                        botId, entry.bot != null ? entry.bot.getMapId() : -1, t);
            }
        }
    }

    // Per-bot throttle for the tick-failure log above (never a global gate: one broken bot must not hide
    // another's failure).
    private static final long TICK_FAILURE_LOG_INTERVAL_MS = 10_000L;
    private static final Map<Integer, Long> TICK_FAILURE_LOG_AT = new ConcurrentHashMap<>();

    private static void tick(BotMovementState entry) {
        Character bot = entry.bot;
        if (bot == null || bot.getMap() == null) {
            stop(entry);
            return;
        }
        // Pick up runtime profile changes (party Haste) before the map-change branch, so onMapChange warms
        // the new map's graph with the up-to-date profile. Throttled + no-op when unchanged (see helper).
        maybeRefreshProfile(entry);
        if (entry.lastMapId != bot.getMapId()) {
            onMapChange(entry, bot);
            return;
        }

        // Mob debuffs run on the movement tick: advance the clock (expiry + the poison tick) and
        // publish the SLOW scale the ground physics reads this tick. Separate from the freeze hold
        // below so the clock keeps running while frozen — otherwise a stun would never expire.
        BotDebuffState status = BotDebuffState.of(bot);
        if (status != null) {
            status.tick();
            entry.debuffMoveScale = status.moveFactor();
        } else {
            entry.debuffMoveScale = 1.0;
        }

        // The pirate 疾驰 burst rolls on a long enough walk, rides through jumps and ropes, and
        // is RELEASED the moment the bot stops moving on the ground (grounded, no hspeed, no
        // intent — the same tick, so the aura and the speed bonus end together). Published next
        // to the SLOW scale (the same step-profile channel, in the opposite direction) so the
        // ground step sees both in one tick; the roll fires exactly once per qualifying walk.
        // The stop edge is read from the PREVIOUS tick's settled physics — the same settled state
        // the aura tick reads at the end of this tick.
        boolean dashStopped = !(entry.inAir || entry.climbing)
                && entry.hspeed == 0.0 && entry.moveDir == 0 && entry.groundBrakeDir == 0;
        BotDashBurst.tickMovement(bot, bot.getPosition().x, System.currentTimeMillis(), dashStopped);
        entry.dashSpeedBonus = BotDashBurst.isActive(bot) ? BotDashBurst.speedBonus(bot) : 0;

        // Pending organic portal/teleport drop: hold standing at the spawn portal (the bot appears
        // up at the portal, above the floor), then release the natural fall once the load beat
        // passes. Deliberately FIRST, before the dead / chair / rope-rest / player-reaction holds:
        // those all return early, so a branch placed after them could have its release skipped for
        // the whole hold (or forever if one keeps re-triggering), leaving the bot hanging at the
        // float point in the jump pose. The entry beat owns the bot for its ~1.5-2.1s; nothing
        // else about an entering bot is meaningful until it has actually dropped.
        if (entry.portalDropAtMs > 0L) {
            if (System.currentTimeMillis() < entry.portalDropAtMs) {
                BotPhysicsEngine.idleOnGround(entry, bot); // float at the spawn point
                entry.inAir = true; // show the JUMP stance while floating (the release fall already does)
                broadcastIfObserved(entry);
                return;
            }
            entry.portalDropAtMs = 0L;
            BotPhysicsEngine.beginPortalDrop(entry, bot, bot.getPosition()); // release the fall
            broadcastIfObserved(entry);
            return;
        }

        // A disable() that arrived mid-air: do NOT cut the session mid-fall. Keep the driver alive
        // and let the physics land the bot, then hand it over (GCMovement.finishDeferredDisable).
        //
        // Placed here, ahead of the dead / chair / rope-rest / player-reaction holds on purpose.
        // Those all return early; below them a death or a chair taken mid-fall would starve this
        // hand-over forever, leaving the state and the shared movement lock behind.
        //
        // Critical: while the bot is still airborne this must FALL THROUGH, never return. It sits
        // above stepMovementCore, so returning here would skip the very physics (tickAirborne) that
        // lands the bot - and the stuck-fall watchdog lives down there too, so it would never run
        // either. The bot would hang in the jump pose forever, which is exactly the freeze this
        // hand-over exists to prevent.
        //
        // Only the FALL is waited out: a bot that ends up on a rope (inAir is cleared when it
        // grabs) is handed over at once, with the rope pose left alone - finishDeferredDisable
        // skips the settle for a climber. Waiting on climbing too would hold the shared movement
        // lock for as long as it hangs, starving the old engine. The flag is cleared inside the
        // hand-over itself, so a racing disable() cannot lose it between check and clear.
        if (entry.disableAfterLanding && !entry.inAir) {
            GCMovement.finishDeferredDisable(entry.bot);
            return;
        }

        // Dead: hold the corpse still. Everything below — steering, navigation, the contact-
        // damage tick, the player-reaction glance — would drag the body around or fire a hurt
        // packet at a bot that is past hurting. The DEAD wire stance needs no work here:
        // BotPhysicsEngine.resolveStance picks it from hp <= 0 on its own.
        //
        // Placed above the chair/resting holds: those keep a bot deliberately still, and a bot
        // killed mid-rest must stop resting. This branch does their job better — it is the
        // stricter one — so let it win.
        if (bot.getHp() <= 0) {
            // The host can zero a bot outside the damage layer: a map's WZ decHP field (Aqua
            // Road's underwater breathing damage, El Nath's cold) calls Character.addHP
            // directly. Adopt it here — the fastest path that runs for every moving bot — so
            // it gets an episode and is carried home at full HP instead of standing at zero.
            BotDeath death = BotDeath.of(bot);
            if (death != null) {
                death.adoptIfZeroHp();
            }
            // Settle it once, not every tick: re-settling re-broadcasts the same frame (cheap
            // and deduped, but pointless) and would pin a bot that is still legitimately
            // falling to its death spot mid-air.
            if (entry.inAir || entry.climbing || entry.moveDir != 0 || entry.resting) {
                BotPhysicsEngine.idleOnGround(entry, bot);
            }
            broadcastIfObserved(entry);
            return;
        }

        // Sitting in a chair (e.g. a grinding TrainingBot on a rest break): hold the sit and skip the
        // tick. botSitChair set the SIT stance and broadcast showChair; if the driver kept idling it
        // would reset the stance to standing (idleOnGround -> syncCharacterState) and broadcast that
        // every tick, snapping the bot upright the instant it sits. A sitting bot isn't moving, so
        // nothing is lost by holding; botCancelChair clears the chair and the driver resumes normally.
        // Mirrors the old engine's getChair() > 0 guards in MovementCommands.
        if (bot.getChair() > 0) {
            // Only hold once the bot is actually on the ground. A seat pinned mid-air/mid-swim (a sit
            // that raced the integrator, or one taken while a map change's portal float had the bot
            // airborne) would otherwise freeze here in the SIT pose at whatever water/air column it sat
            // in - the "bot sitting in the air" report - because this early return skips the very physics
            // that grounds it. Clear the chair and fall through instead so the bot lands, then the chair
            // hold (or nothing) re-engages on solid ground.
            if (GCMovement.isGrounded(entry)) {
                return;
            }
            MovementCommands.botCancelChair(bot);
        }

        // Rope rest hold (grind break): the bot is deliberately hanging idle on a rope. Freeze the hang and
        // broadcast — never run nav / steering / player-reaction / contact-knockback below, any of which
        // could dislodge it. Set on arrival by GrindBreakRoutine, cleared on break end (and leaveGrind).
        if (entry.resting) {
            if (entry.climbing) {
                BotPhysicsEngine.holdClimb(entry, bot);
            } else {
                BotPhysicsEngine.idleOnGround(entry, bot); // shouldn't happen (rest is a rope hang), but hold anyway
            }
            broadcastIfObserved(entry);
            return;
        }

        // A mob debuff (STUN/SEDUCE) pins the bot where it stands - it must not walk, attack or be
        // steered. This is the movement-layer half of the debuff rule; the attack layer (BotAttackDriver)
        // gates swings and the grind brain (GrindBrain) gates its direct moves separately.
        if (status != null && status.isFrozen()) {
            // Still let a mob touch a frozen bot (it is standing right there): run the contact-damage
            // tick, then hold the pose. A climber is held ON its rope (idleOnGround would clear the
            // climb and drop it); anything else settles to a grounded idle - mirrors the resting hold.
            BotContactDamage.tickMobDamage(entry, bot);
            if (entry.climbing) {
                BotPhysicsEngine.holdClimb(entry, bot);
            } else {
                BotPhysicsEngine.idleOnGround(entry, bot);
            }
            broadcastIfObserved(entry);
            return;
        }

        // Contact/fall damage: render the bot taking hits from mobs. Runs before the movement branches
        // so even an idle/standing bot recoils when a mob touches it. Self-gates on LOD (does ~nothing
        // when no real player shares the bot's map) and on the i-frame window.
        BotContactDamage.tickMobDamage(entry, bot);

        boolean active = ObserverTracker.isActiveMap(bot.getMapId());

        // LOD promotion (M2): the bot was moving analytically (coarse) and a player just arrived —
        // rebuild the physics shadow at its current interpolated position so physics resumes cleanly
        // instead of snapping from stale shadow coords. Minimal reconstruction; full hysteresis is M3.
        if (active && entry.coarseActive) {
            reconstructPhysicsFromCoarse(entry, bot);
        }

        boolean runAiTick = consumeAiTick(entry);

        // Player reaction (ported pathAware): on an observed map, occasionally emote/chat at or stop-and-
        // turn toward a nearby real player while roaming. Grounded bots only; no-op when unobserved.
        if (active && runAiTick && !entry.inAir && !entry.climbing) {
            BotPlayerReaction.maybeReact(entry, bot);
        }
        // While a stop-reaction is in progress, hold position (don't walk off mid-greeting) and keep the
        // stall timer fresh so the pause isn't mistaken for being stuck. Resumes automatically after.
        if (entry.reactingUntilMs > System.currentTimeMillis()) {
            entry.moveProgressAtMs = System.currentTimeMillis();
            BotPhysicsEngine.idleOnGround(entry, bot);
            broadcastIfObserved(entry);
            return;
        }

        // Abandon a move target the bot can't make progress toward, so it never tries forever.
        if (giveUpStalledMove(entry)) {
            BotPhysicsEngine.idleOnGround(entry, bot);
            broadcastIfObserved(entry);
            return;
        }

        Point target = resolveTarget(entry, bot);
        boolean hasGoal = target != null || entry.inAir || entry.climbing || entry.navEdge != null;
        if (!hasGoal) {
            if (entry.duckUntilMs > System.currentTimeMillis()) {
                BotPhysicsEngine.proneOnGround(entry, bot); // idle fidget: hold a crouch/duck pose
            } else {
                BotPhysicsEngine.idleOnGround(entry, bot);
            }
            broadcastIfObserved(entry); // dedup suppresses idle spam; skipped entirely when unobserved
            return;
        }

        // LOD coarse (M2): an unobserved bot with a plannable in-map point goal moves by analytic ETA
        // over the baked edge times — no physics, no broadcast — so it costs ~nothing. Falls through to
        // throttled physics when mid-air/climbing or the map has no baked graph (never bake just to move
        // an unwatched bot). `active` is false here only when nobody can see the bot.
        if (!active && tryCoarseAdvance(entry, bot, target)) {
            return;
        }
        stepMovementCore(entry, target != null ? target : bot.getPosition(), runAiTick);
    }

    /*
     * Move an unobserved bot toward target analytically. Builds a
     * MovementPlan from the cached graph once, then advances it by wall-clock time and writes
     * the interpolated position. Returns false (caller falls back to throttled physics) when
     * the bot is airborne/climbing or the map has no cached graph.
     */
    private static boolean tryCoarseAdvance(BotMovementState entry, Character bot, Point target) {
        if (target == null || entry.inAir || entry.climbing) {
            return false;
        }
        // Already at the goal: arrive (fires the callback, holds an anchor) without replanning each tick.
        if (entry.coarsePlan == null
                && Math.abs(bot.getPosition().x - target.x) <= COARSE_ARRIVE_PX
                && Math.abs(bot.getPosition().y - target.y) <= COARSE_ARRIVE_PX) {
            arriveCoarse(entry, bot, target);
            return true;
        }
        BotNavigationGraph graph = BotNavigationGraphProvider.peekBestGraph(bot.getMap(), entry.movementProfile);
        if (graph == null) {
            return false; // no cached graph -> let the M1 throttle cover it (don't trigger a bake)
        }
        long now = System.currentTimeMillis();
        boolean needPlan = entry.coarsePlan == null
                || entry.coarsePlanMapId != bot.getMapId()
                || !target.equals(entry.coarsePlanTarget);
        if (needPlan) {
            MovementPlan plan = MovementPlan.inMap(graph, bot.getMap(), bot.getPosition(), target);
            if (plan == null) {
                arriveCoarse(entry, bot, target); // already in the target region / unplannable
                return true;
            }
            entry.coarsePlan = plan;
            entry.coarsePlanStartMs = now;
            entry.coarsePlanTarget = new Point(target);
            entry.coarsePlanMapId = bot.getMapId();
        }
        entry.coarseActive = true;
        CoarseExecutor.Step step = CoarseExecutor.advance(entry.coarsePlan, entry.coarsePlanStartMs, now);
        if (step.position() != null) {
            bot.setPosition(step.position());
        }
        if (step.complete()) {
            arriveCoarse(entry, bot, entry.coarsePlanTarget);
        }
        return true;
    }

    /* Finish a coarse trip: snap to the goal, clear the plan + move target, fire the arrival callback. */
    private static void arriveCoarse(BotMovementState entry, Character bot, Point target) {
        if (target != null) {
            bot.setPosition(new Point(target));
        }
        entry.coarsePlan = null;
        entry.coarsePlanTarget = null;
        entry.moveTarget = null;
        entry.moveTargetPrecise = false;
        entry.moveBestDist = Integer.MAX_VALUE;
        BotMovementManager.clearNavigationState(entry);
        GCMovement.fireArrival(entry);
    }

    /*
     * Promotion (COARSE -> FULL/HALO): resume physics from the bot's current analytic position.
     *
     * The room's new player was just spawned at this exact point (MapleMap broadcasts the bot at its
     * live position), so the physics shadow must continue from there — snapping to a re-probed ground
     * instead would move the bot a second time in the player's first frames, reading as a teleport.
     * MovementPlan already keeps the coarse position on real standing/rope points (only WALK edges are
     * interpolated), so re-seating the shadow at it is both correct and continuous. Minimal — full
     * hysteresis/safe-node reconstruction is M3.
     */
    private static void reconstructPhysicsFromCoarse(BotMovementState entry, Character bot) {
        entry.coarseActive = false;
        entry.coarsePlan = null;
        entry.coarsePlanTarget = null;
        BotPhysicsEngine.teleportTo(entry, bot, bot.getPosition());
        BotMovementManager.resetEntryStateAfterTeleport(entry);
    }

    /*
     * No-progress give-up: if the bot can't get meaningfully closer to its moveTarget for
     * MOVE_NO_PROGRESS_MS, abandon the move (clear target + nav, drop the callback) so an
     * unreachable / blocked / buggy goal can't loop forever. Any real progress resets the clock, so
     * long legitimate walks are never cut off. Follow/farm without a moveTarget are unaffected.
     */
    private static boolean giveUpStalledMove(BotMovementState entry) {
        if (entry.moveTarget == null || entry.inAir || entry.climbing) {
            return false;
        }
        Point bp = entry.bot.getPosition();
        int dist = Math.abs(bp.x - entry.moveTarget.x) + Math.abs(bp.y - entry.moveTarget.y);
        long now = System.currentTimeMillis();
        if (entry.moveProgressAtMs == 0L) {
            entry.moveProgressAtMs = now;
        }
        if (dist < entry.moveBestDist - MOVE_PROGRESS_EPS_PX) {
            entry.moveBestDist = dist;
            entry.moveProgressAtMs = now;
            return false;
        }
        if (now - entry.moveProgressAtMs <= MOVE_NO_PROGRESS_MS) {
            return false;
        }
        entry.moveTarget = null;
        entry.moveTargetPrecise = false;
        entry.moveBestDist = Integer.MAX_VALUE;
        BotMovementManager.clearNavigationState(entry);
        GCMovement.abandonMove(entry);
        return true;
    }

    private static Point resolveTarget(BotMovementState entry, Character bot) {
        if (entry.moveTarget != null) {
            return entry.moveTarget;
        }
        if (entry.following && entry.owner != null && entry.owner.getMap() == bot.getMap()) {
            return followStandoffTarget(entry, bot);
        }
        if (entry.farmAnchor != null) {
            return entry.farmAnchor;
        }
        return null;
    }

    // Lateral stand-off band for a following bot, in px either side of the anchor.
    //
    // LOWER bound must EXCEED cfg.FOLLOW_DIST (80), not merely cfg.STOP_DIST. calcStepX suppresses
    // the first step while the bot is standing still and the target is within followDist:
    //     if (absDx <= stopDist) return 0;
    //     if (!wasMovingX && absDx <= followDist) return 0;
    // A follower with no committed nav edge runs at stopDist=STOP_DIST(30) / followDist=FOLLOW_DIST(80),
    // so a stand-off of 40-70 px leaves a bot already parked on the leader permanently inside that
    // dead-band: it never takes the first step and stays stacked on the leader — the exact bug. Once
    // moving, wasMovingX latches true and the bot closes to STOP_DIST of its own stand-off point,
    // which is why the lower bound only has to clear the dead-band, not the stop tolerance.
    private static final int FOLLOW_OFFSET_MIN_PX = 90;
    private static final int FOLLOW_OFFSET_MAX_PX = 140;
    // Max Y gap between the anchor and the stand-off point: more than this and the clamped X sits over
    // a hole or on a lower level, so the bot must not be sent there.
    private static final int FOLLOW_STANDOFF_MAX_DROP_PX = 40;

    /*
     * Where a following bot actually walks to: the anchor's spot pushed sideways by THIS bot's own
     * stand-off, so several followers of one leader spread along the platform instead of fighting
     * over the leader's exact pixel (which reads as bots overlapping / humping the player).
     *
     * Rolled once per bot and then kept — re-rolling each tick would jitter the target and make the
     * bot vibrate in place. On ground maps, only honoured where the shifted point is real ground, so
     * a bot never walks off a ledge or parks in mid-air to chase an offset it can't stand on. In swim
     * maps the offset is kept at the anchor's depth instead: there are no ledges to fall off and the
     * ground clamp would otherwise collapse it back onto the leader (see the swim branch below).
     */
    private static Point followStandoffTarget(BotMovementState entry, Character bot) {
        Point anchor = entry.owner.getPosition();
        if (anchor == null) {
            return null;
        }
        // No stand-off while either end is on a rope: the anchor's X IS the rope's X, so the bot has to
        // match it exactly to climb along. The bot's own climb matters just as much — on a rope its X is
        // pinned to the rope, and tickClimbing judges the target's X against the rope's X (dxOwner) to
        // pick both the climb-idle hold and, past FOLLOW_DIST, whether to jump off the rope. A band that
        // deliberately exceeds FOLLOW_DIST therefore reads as "the leader is far away" and would make a
        // bot bail off every rope as soon as its leader stood on the platform below, despite the leader
        // being right there. On a rope the bot can only move vertically, so a lateral offset is moot.
        if (entry.climbing || CharacterStance.isClimbing(entry.owner.getStance())) {
            return anchor;
        }
        if (entry.followOffsetPx == 0) {
            int magnitude = FOLLOW_OFFSET_MIN_PX
                    + ThreadLocalRandom.current().nextInt(FOLLOW_OFFSET_MAX_PX - FOLLOW_OFFSET_MIN_PX + 1);
            entry.followOffsetPx = ThreadLocalRandom.current().nextBoolean() ? magnitude : -magnitude;
        }
        // Swim maps (Aquarium etc.): the anchor is usually treading open water, not standing on a
        // foothold, so the ground-clamp below resolves the shifted X onto the seafloor far beneath and
        // gives up (`? anchor`) — collapsing the target back onto the leader's exact pixel, the very
        // overlap this method exists to prevent. Underwater there are no ledges to walk off and every
        // depth is swimmable, so keep the lateral stand-off at the anchor's own depth and skip the
        // clamp. A leader still standing on a platform keeps nearby ground and takes the clamp as before.
        if (bot.getMap().isSwim()) {
            Point anchorGround = BotPhysicsEngine.findGroundPoint(bot.getMap(), anchor);
            if (anchorGround == null
                    || Math.abs(anchorGround.y - anchor.y) > FOLLOW_STANDOFF_MAX_DROP_PX) {
                return new Point(anchor.x + entry.followOffsetPx, anchor.y);
            }
        }
        // Clamp into the foothold the anchor stands on: findGroundPoint searches straight down, so an
        // offset X past the ledge edge would otherwise resolve onto whatever platform is below and the
        // bot would walk off (or jump down) to reach it. Short footholds just get a smaller stand-off.
        Foothold anchorFh = BotPhysicsEngine.findGroundFoothold(bot.getMap(), anchor);
        if (anchorFh == null) {
            return anchor;
        }
        int loX = Math.min(anchorFh.getX1(), anchorFh.getX2());
        int hiX = Math.max(anchorFh.getX1(), anchorFh.getX2());
        int offset = anchor.x + entry.followOffsetPx < loX || anchor.x + entry.followOffsetPx > hiX
                ? -entry.followOffsetPx  // no room on the rolled side — try the other side instead
                : entry.followOffsetPx;
        int targetX = Math.max(loX, Math.min(hiX, anchor.x + offset));
        // A foothold too narrow to hold the rolled stand-off still gives the best separation it can
        // (clamped to the ledge edge). Below the band minimum that separation is too small to matter —
        // the bot would sit back inside the start-up dead-band / on the anchor — so just tail the anchor.
        if (Math.abs(targetX - anchor.x) < FOLLOW_OFFSET_MIN_PX) {
            return anchor;
        }
        Point ground = BotPhysicsEngine.findGroundPoint(bot.getMap(), new Point(targetX, anchor.y - 1));
        // A big Y drop means the clamped X still landed over a hole or a lower level — don't send the
        // bot there.
        return ground == null || Math.abs(ground.y - anchor.y) > FOLLOW_STANDOFF_MAX_DROP_PX
                ? anchor
                : new Point(targetX, ground.y);
    }

    /* Faithful port of BotManager.stepMovementCore (minus fidget). */
    private static void stepMovementCore(BotMovementState entry, Point target, boolean runAiTick) {
        BotNavigationManager.NavigationDirective nav =
                BotNavigationManager.resolveTarget(entry, target, runAiTick);
        if (nav.consumedTick) {
            return;
        }
        Point steering = nav.targetPos;
        if (entry.moveTargetPrecise && entry.navEdge == null) {
            entry.navPreciseTarget = true;
        }
        tickMovementPhase(entry, steering, runAiTick);
        if (runAiTick && !entry.inAir && !entry.climbing) {
            BotNavigationManager.tryExecuteCommittedEdgeAfterGroundMovement(entry, target);
        }
        tickStuckDetection(entry);
        clearReachedMoveTarget(entry);
    }

    private static void tickMovementPhase(BotMovementState entry, Point target, boolean runAiTick) {
        if (entry.climbing) {
            BotMovementManager.tickClimbing(entry, target, runAiTick);
        } else if (isSwimMap(entry) && entry.inAir) {
            BotMovementManager.tickSwimming(entry, target);
        } else if (entry.inAir) {
            BotMovementManager.tickAirborne(entry, target);
        } else {
            BotMovementManager.tickGrounded(entry, target);
        }
    }

    private static boolean isSwimMap(BotMovementState entry) {
        return entry.bot != null && entry.bot.getMap() != null && entry.bot.getMap().isSwim();
    }

    private static void clearReachedMoveTarget(BotMovementState entry) {
        if (entry.moveTarget == null) {
            return;
        }
        Point botPos = entry.bot.getPosition();
        int arrivalDist = entry.moveTargetPrecise ? 8 : BotMovementManager.cfg.STOP_DIST;
        if (!reachedMoveTarget(entry.climbing, entry.inAir, entry.swimming, botPos, entry.moveTarget, arrivalDist)) {
            return;
        }
        entry.moveTarget = null;
        entry.moveTargetPrecise = false;
        // Drop any committed nav edge too. Arriving is the end of the trip, and a leftover
        // edge keeps hasGoal true on the next tick, so the bot never takes the idle branch -
        // and that branch is what clears the walk stance. Without this a bot that stops can
        // stand still forever still rendering the walk animation.
        BotMovementManager.clearNavigationState(entry);
        GCMovement.fireArrival(entry);
    }

    /*
     * Whether a bot may be considered to have reached the move target. Pure, so the rule is pinned by a
     * unit test rather than re-derived at the call site.
     *
     * A bot that is still CLIMBING or mid-AIR has not "arrived" even if it is standing on the target's
     * pixels. Both phases pin or sweep the bot across the target early:
     *
     *  - Climbing pins X to the rope's column while Y is still making its way up, so the box test below
     *    reads a mid-climb position as arrival the moment the bot comes within STOP_DIST (30px) of a
     *    target near the rope head.
     *  - A jump sweeps the bot through the whole vertical band of a same-column target and can be within
     *    STOP_DIST on both axes mid-arc without ever landing on it.
     *
     * On map 221000200 (地球防御本部/机库), a tall shaft that stacks many short ladders, this fired
     * constantly: of 250 random routes, 18 ended with the bot frozen 400-2900 ticks, 107 of the clears
     * were mid-jump and 18 mid-climb (an instrumented run). Clearing the target there left a frozen bot
     * ~27px below the ledge - the "bot sticks at the top of the ladder, flickering climb/stand" report.
     * It is not stuck; it believed it had already arrived.
     *
     * Swimming is deliberately EXEMPT: a swim-mode bot runs with {@code inAir} set for the whole session
     * (applySwimMotion sets swimming+inAir together) and its position is the real integrated position, so
     * gating on airborne would strand every swim bot. It arrives on a normal grounded tick once it makes
     * landfall (swimming cleared). The gate is entirely transient: physics clears climbing/inAir within a
     * few ticks, and the next eligible tick resolves the arrival.
     */
    static boolean reachedMoveTarget(boolean climbing, boolean inAir, boolean swimming, Point botPos, Point target, int arrivalDist) {
        if (climbing || (inAir && !swimming)) {
            return false;
        }
        return Math.abs(botPos.x - target.x) <= arrivalDist
                && Math.abs(botPos.y - target.y) <= arrivalDist;
    }

    private static boolean consumeAiTick(BotMovementState entry) {
        entry.aiTickAccumulatorMs += BotPhysicsEngine.cfg.TICK_MS;
        if (entry.aiTickAccumulatorMs < AI_TICK_MS) {
            return false;
        }
        entry.aiTickAccumulatorMs -= AI_TICK_MS;
        return true;
    }

    // Organic portal entry: the bot appears at the portal lifted at least this far above the floor
    // (a natural portal sits higher → keep its height), floats the "client load" beat, then drops.
    // Guarantees a visible spawn→float→drop even when the arrival portal sits on the ground. Mirrors
    // the recorded engine's portalenterdrop feel; tune to taste.
    private static final int PORTAL_FLOAT_HEIGHT_PX = 60;

    static void onMapChange(BotMovementState entry, Character bot) {
        // Read the target map id ONCE here and publish it to entry.lastMapId only at the very end
        // (below). Keeping lastMapId trailing the live map for the whole handler lets
        // GCMovement.disable() - which may run concurrently off the travel/FSM threads - tell that a
        // map change is still being processed and defer its hand-over instead of stopping the driver
        // mid-arrival. Reading once up front (rather than at the end) preserves the old entry-time
        // semantics: should the bot warp AGAIN mid-handler, the end assignment cannot bless the new
        // map as already-processed.
        final int mapId = bot.getMapId();
        // New map: drop any in-progress reaction pause. The per-player react cooldown is intentionally
        // NOT reset here - it's tied to the player so map-hopping can't re-trigger greetings at them.
        entry.reactingUntilMs = 0L;
        // Mob debuffs do not survive a map change (the engine does not carry diseases across maps
        // either): clear them so a bot warped away mid-stun arrives clean.
        BotDebuffState status = BotDebuffState.of(bot);
        if (status != null) {
            status.clearAll();
        }
        entry.debuffMoveScale = 1.0;
        // Drop any coarse plan from the previous map; onMapChange re-seeds the physics shadow below.
        entry.coarsePlan = null;
        entry.coarsePlanTarget = null;
        entry.coarseActive = false;
        MapleMap map = bot.getMap();
        // If a real player is already on this map, mark it observed NOW so the visible spawn (float →
        // drop, jump stance) and FULL combat start this tick instead of waiting up to one observer poll.
        if (ObserverTracker.hasRealPlayerNow(map)) {
            ObserverTracker.markObservedNow(bot.getMapId());
            // Ours: the movement above is already instant - also wake the arriving bot's macro brain so
            // it acts promptly instead of on its slow 2-6s/10s wheel. See BotMapEntryResponder (B).
            soloMapling.ArtificialPlayer.BotMapEntryResponder.onBotArrivedObserved(bot);
        }
        entry.fhIndex = BotMovementManager.buildFhIndex(map);
        Point spawn = bot.getPosition();
        Point ground = BotPhysicsEngine.findGroundPoint(map, new Point(spawn.x, spawn.y - 1));
        // Clear stale nav from the old map first (does not touch the physics state set below).
        BotMovementManager.resetEntryStateAfterTeleport(entry);
        // Only play the visible spawn→float→drop where a player can actually see it; on unobserved
        // maps just land on the floor (nobody's watching, and it keeps coarse travel from stalling
        // ~1.5s per hop). The hold + release runs in tick(); beginPortalDrop hands off to the
        // airborne integrator.
        if (ground != null && ObserverTracker.isActiveMap(bot.getMapId())) {
            // Lift to at least PORTAL_FLOAT_HEIGHT_PX above the floor (keep a higher natural portal),
            // so the bot floats at/above the portal a beat, then drops — every time, not just for
            // portals that happen to sit high.
            // Arm the pending drop BEFORE teleportTo: teleportTo -> clearMovementState leaves inAir
            // FALSE, so between the two writes there is otherwise a window with neither flag set.
            // GCMovement.disable() landing in that window used to settle+stop the driver with the
            // drop never armed - the jump-pose freeze. Arming first makes the window always visible
            // as "pending drop" instead.
            entry.portalDropAtMs = System.currentTimeMillis() + PORTAL_DROP_DELAY_MS
                    + ThreadLocalRandom.current().nextInt(PORTAL_DROP_DELAY_JITTER_MS + 1);
            // Also silence flavor for the float + the fall that follows the drop release, so an arriving
            // bot doesn't swing a skill in mid-air in front of the player who is watching the entry.
            entry.portalArrivalGuardUntilMs = entry.portalDropAtMs + PORTAL_FALL_GUARD_MS;
            int floatY = Math.min(spawn.y, ground.y - PORTAL_FLOAT_HEIGHT_PX);
            BotPhysicsEngine.teleportTo(entry, bot, new Point(spawn.x, floatY));
        } else {
            // Unobserved (or no floor below): just stand where we landed — no float.
            BotPhysicsEngine.teleportTo(entry, bot, ground != null ? ground : spawn);
            entry.portalDropAtMs = 0L;
        }
        BotNavigationGraphProvider.warmGraphAsync(map, entry.movementProfile);
        broadcastIfObserved(entry);
        // Now the arrival is fully armed: publish the captured map id. Any disable() blocked on the
        // trailing lastMapId has already deferred (or saw a live drop/airborne flag), so this cannot
        // re-open the window. Uses the id read on entry, not a fresh read, to match the old behaviour.
        entry.lastMapId = mapId;
    }

    /*
     * Emit a movement packet only when the bot's map is observed (a real player is on it or an
     * adjacent map). For an unobserved bot nobody receives the packet, so this skips the snapshot +
     * map-iterate entirely. Gates only the driver-owned broadcasts (idle / drop / map-change /
     * recovery); the moving-bot broadcasts live inside the untouchable core and are reduced instead by
     * the unobserved tick throttle.
     */
    private static void broadcastIfObserved(BotMovementState entry) {
        if (entry.bot != null && ObserverTracker.isActiveMap(entry.bot.getMapId())) {
            BotMovementManager.broadcastMovement(entry);
        }
    }

    // ── Stuck detection (faithful port; recovery teleport inlined) ──
    private static void tickStuckDetection(BotMovementState entry) {
        entry.unstuckCooldownMs = BotMovementManager.tickDown(entry.unstuckCooldownMs);
        tickFrozenAirborneWatchdog(entry);
        tickFallOffMapRecovery(entry); // ours: catch a live plummet the frozen-air watchdog above misses
        if (BotMovementManager.isStuckCheckExempt(entry)) {
            entry.stuckMs = 0;
            entry.stuckCheckX = Integer.MIN_VALUE;
            return;
        }
        Point botPos = entry.bot.getPosition();
        if (entry.stuckCheckX == Integer.MIN_VALUE) {
            entry.stuckCheckX = botPos.x;
            entry.stuckCheckY = botPos.y;
            return;
        }
        boolean moved = Math.abs(botPos.x - entry.stuckCheckX) > 8
                || Math.abs(botPos.y - entry.stuckCheckY) > 8;
        if (moved) {
            entry.stuckMs = 0;
            entry.stuckCheckX = botPos.x;
            entry.stuckCheckY = botPos.y;
        } else {
            entry.stuckMs += BotPhysicsEngine.cfg.TICK_MS;
        }
        if (ENABLE_UNSTUCK && entry.stuckMs >= 500 && entry.unstuckCooldownMs == 0) {
            entry.stuckMs = 0;
            entry.stuckCheckX = Integer.MIN_VALUE;
            BotMovementManager.tickUnstuck(entry);
        }
    }

    private static void tickFrozenAirborneWatchdog(BotMovementState entry) {
        if (!entry.inAir || entry.climbing) {
            entry.airStuckTicks = 0;
            entry.airStuckX = Integer.MIN_VALUE;
            return;
        }
        Point pos = entry.bot.getPosition();
        if (pos.x != entry.airStuckX || pos.y != entry.airStuckY) {
            entry.airStuckTicks = 0;
            entry.airStuckX = pos.x;
            entry.airStuckY = pos.y;
            return;
        }
        if (++entry.airStuckTicks < AIR_STUCK_RECOVER_TICKS) {
            return;
        }
        entry.airStuckTicks = 0;
        entry.airStuckX = Integer.MIN_VALUE;
        // Recovery: snap to ground directly UNDER THE BOT, never under the goal. A goal-anchored
        // snap on a vertically-stacked map (Time Lane <1>) re-appeared the bot on the GOAL MOB'S
        // floor one or more platforms up - the observed "bot on a lower floor suddenly fights from
        // the upper floor" cross-floor teleport. Self-anchored keeps the rescue in the bot's own
        // column; the brain re-targets on its next tick.
        MapleMap map = entry.bot.getMap();
        Point ground = BotPhysicsEngine.findGroundPoint(map, new Point(pos.x, pos.y - 1));
        Point dest = (ground != null) ? ground : pos;
        BotPhysicsEngine.teleportTo(entry, entry.bot, dest);
        BotMovementManager.resetEntryStateAfterTeleport(entry);
        renderRecoveryCut(entry, entry.bot, pos, dest);
    }

    /*
     * Broadcast a driver-owned recovery snap. Same reasoning as GCMovement.teleportTo: on an observed
     * map a bare absolute fragment is a one-frame sprite jump, so cut like a blink instead.
     */
    private static void renderRecoveryCut(BotMovementState entry, Character bot, Point origin, Point dest) {
        switch (TeleportCutPolicy.choose(ObserverTracker.isActiveMap(bot.getMapId()), origin, dest)) {
            case ANIMATED -> GCMovementSkills.teleportCut(entry, bot, origin, dest);
            case NONE -> BotMovementManager.invalidateBroadcastSnapshot(entry);
            default -> broadcastIfObserved(entry);
        }
    }

    // Live fall-off-map recovery: if the bot has left the map's VR bounds by more than a slack margin (fell
    // below the floor or off the side), snap it back to solid ground under its own position. Mirrors the
    // frozen-air watchdog's recovery, but keys off "outside the map" instead of "frozen in the air", so it
    // catches an active free-fall. Ours (Fable fluid-combat pass).
    private static void tickFallOffMapRecovery(BotMovementState entry) {
        Character bot = entry.bot;
        MapleMap map = (bot != null) ? bot.getMap() : null;
        if (map == null) {
            return;
        }
        Rectangle vr = map.getMapArea();
        if (vr == null || vr.width <= 0 || vr.height <= 0) {
            return; // no usable VR bounds — can't tell inside from outside
        }
        Point pos = bot.getPosition();
        if (pos == null) {
            return;
        }
        boolean belowFloor = pos.y > vr.y + vr.height + FALL_RECOVER_SLACK_PX;
        boolean offSides = pos.x < vr.x - FALL_RECOVER_SLACK_PX
                || pos.x > vr.x + vr.width + FALL_RECOVER_SLACK_PX;
        if (!belowFloor && !offSides) {
            return;
        }
        // Snap to ground directly UNDER THE BOT, never under the goal: on a vertically-stacked map
        // (Time Lane <1>) the goal is routinely a mob on a floor 100-900px up, so a goal-anchored
        // recovery re-appeared the bot on the MOB'S floor - the observed "bot on a lower platform
        // suddenly fights from the upper floor". x is clamped into the map (an off-side fall has no
        // foothold at its raw x); if the column has no floor at all, fall back to the VR-top row.
        Point clamped = new Point(Math.max(vr.x, Math.min(vr.x + vr.width, pos.x)), pos.y);
        Point ground = BotPhysicsEngine.findGroundPoint(map, new Point(clamped.x, clamped.y - 1));
        if (ground == null) {
            ground = BotPhysicsEngine.findGroundPoint(map, new Point(clamped.x, vr.y - 1));
        }
        Point dest = (ground != null) ? ground : new Point(clamped.x, vr.y);
        BotPhysicsEngine.teleportTo(entry, bot, dest);
        BotMovementManager.resetEntryStateAfterTeleport(entry);
        // De-thrash: resetEntryStateAfterTeleport only clears NAV state, leaving moveTarget — so the bot
        // would re-aim at the same too-far-below goal and re-fire the same doomed descent (teleport loop =
        // the "glitchy" fall on tall maps). Drop the goal too so the brain re-decides a safe target next tick.
        entry.moveTarget = null;
        renderRecoveryCut(entry, bot, pos, dest);
    }
}
