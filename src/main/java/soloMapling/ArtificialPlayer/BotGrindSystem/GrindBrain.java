package soloMapling.ArtificialPlayer.BotGrindSystem;

import org.gms.client.Character;
import org.gms.server.life.Monster;
import org.gms.server.maps.MapObject;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackDriver;
import soloMapling.ArtificialPlayer.BotStatusSystem.BotDebuffState;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

import java.awt.Point;
import java.util.Random;
import java.util.function.Consumer;

// The per-bot grind engine: a thin facade + shared combat context over one active GrindStrategy
// (CAMP / ROAM today; PATROL / STACK planned). TrainingBot's macro brain talks only to this class —
// the public surface is identical to the pre-split GrindBrain, so the macro FSM and watchdog didn't
// change. The strategy owns WHERE the bot fights (spots, claims, rings, tethers); this class owns
// what every style shares: the sticky target, engage flags, blink/turn/kite cadences (EngageBeat),
// organic loot (GrindLoot), rope recovery (ClimbRecovery), approach-progress tracking, the watchdog
// heartbeat, and the claim lock. Ours (SoloMapling); reads terrain only through GCMovement.
public final class GrindBrain {

    // ── Shared approach/engage tunables (read by the strategies + beats) ──
    static final int APPROACH_X = 70;                // melee floor: within this dx -> stop and swing
    static final int APPROACH_Y = 90;                // melee floor: within this dy -> in range
    // How long after a kill the bot still tidies up that kill's drop before walking to the next mob.
    // Long enough to cover a drop's settle, short enough that an unreachable drop (one that fell to a
    // lower floor) cannot keep the bot looting instead of fighting.
    static final long COLLECT_AFTER_KILL_MS = 2_500;
    // A lull on a dry platform ends as soon as another spot/stack on the map feeds — but only after
    // this grace beat, so a kill inside our own spot's radius (a mob walking home to us, or a spawn
    // landing mid-check) isn't read as "empty here, mobs there" the instant the last one died.
    static final long NEARBY_MOB_GRACE_MS = 1_500;
    private static final double APPROACH_REACH_FRAC = 0.80;  // stop at this fraction of attack reach
    static final int ROAM_RETARGET_EPS = 16;         // skip re-issuing move for tiny shifts
    private static final long TARGET_RETARGET_TIMEOUT = 4_000; // give up walking to an unreachable target/spot after this
    private static final int APPROACH_PROGRESS_EPS = 20;     // bot must move at least this far to count as progress
    static final int APPROACH_Y_TOLERANCE = 120;     // within() vertical slack (covers a sloped/stacked anchor)
    static final int ACQUIRE_MARGIN_PX = 130;        // reach a bit past the leash to grab a near-miss same-ledge mob

    // Claim-hygiene lock: claim-mutating transitions (start / release / SELECT_SPOT / relocate) span
    // TWO thread families — the shared combat ticker runs the states, the macro tick (virtual thread)
    // runs start/release — and an unsynchronized release() racing an in-flight select could land its
    // release BEFORE the select's claim, orphaning that claim forever (BotSpotClaims has no TTL; a
    // ghost holder then repels real bots from the spot for the rest of the uptime). All claim mutations
    // hold claimLock, and selects re-check claimActive under it so a released brain never claims.
    // Movement calls stay OUTSIDE the lock.
    final Object claimLock = new Object();
    boolean claimActive = false;     // guarded by claimLock: true between start() and release()

    private final Consumer<String> debug;  // debug narration sink (TrainingBot::debugChat)
    final Random rng = new Random();

    // ── Components (shared beats) + strategies (one instance each; active one switches per episode) ──
    final EngageBeat engage = new EngageBeat(this);
    final GrindLoot loot = new GrindLoot(this);
    private final ClimbRecovery climb = new ClimbRecovery(this);
    private final CampStrategy camp = new CampStrategy(this);
    private final RoamStrategy roam = new RoamStrategy(this);
    private final StackStrategy stack = new StackStrategy(this);
    private final PatrolStrategy patrol = new PatrolStrategy(this);
    private volatile GrindStrategy strategy;      // active strategy (null until start())
    private volatile int episodeMapId = -1;       // map the episode started on (mid-episode warp detect)
    private volatile GrindStyle forcedStyle;      // GM override (!bot grindstyle); null = auto
    private volatile boolean styleSwapRequested = false; // consumed at the next combat tick (live swap)

    // ── Shared combat state (package-visible: strategies + beats read/write directly) ──
    int targetOid = -1;
    long retargetDeadline = 0L;
    private int noProgressAnchorX = Integer.MIN_VALUE;
    private int noProgressAnchorY = 0;
    boolean engaged = false;
    int lastMoveTargetX = Integer.MIN_VALUE;
    private int approachX = -1;              // lazy attack-reach cache
    private int approachY = -1;
    MovementStyle style = MovementStyle.PLANTED;
    float fjCap = 1f;                        // level-tier flash-jump dash cap (FlashJumpTiers)
    long attackWalkLockUntil = 0L;           // hold walking until this time after a swing (bypassed by blink/dash)
    long lastKillMs = 0L;

    // ── Travel-clear beat (途经清怪) ──
    // One stop-and-swing per map per episode: the first beat a live hostile sits inside the attack
    // reach box while the bot is moving between spots, it plants, swings once, and resumes. The
    // map id gate is what keeps a bot walking THROUGH a dense map from clearing every pack it
    // brushes (a per-swing-cooldown gate alone would turn TRAVEL into a fighting crawl) while the
    // single en-route hit kills the "walks through mobs without reacting" bot-tell.
    private int lastSwingMapId = -1;

    // ── Retaliate-when-hit beat (被打反击) ──
    // The mob oid that last actually hurt this bot (fed by BotContactDamage.applyMobHit) once it is
    // promoted to a vendetta target. -1 = none. While set and on the map it was registered on, the
    // retaliate beat owns the tick: walk the attacker down / swing it, then hand the tick back to
    // the ordinary strategy flow (whatever state the bot was in — TRAVEL resumes, FIGHT re-acquires).
    private int retaliatingOid = -1;
    private int retaliatingMapId = -1;

    // ── Combat heartbeat (read by TrainingBot's macro watchdog -> volatile) ──
    private volatile long lastCombatProgressMs = 0L;
    private boolean wasObserved = false;

    // ── Debug narration (edge-triggered: speak only when the message changes) ──
    static final boolean NARRATE = false; // master toggle: false silences all grind debug chatter
    private static final long GIVE_UP_NARRATE_GAP_MS = 8_000;
    private String lastNarrate = "";
    private long lastGiveUpNarrateMs = 0L;

    public GrindBrain(Consumer<String> debug) {
        this.debug = debug != null ? debug : msg -> { };
    }

    // ── Public surface (unchanged from the pre-split GrindBrain) ──

    // Grind-episode entry: fresh heartbeat, resolve the map's strategy, and let it pre-position so the
    // bot disperses into the field even before a player arrives.
    public void start(Character chr) {
        synchronized (claimLock) {
            claimActive = true;
            lastCombatProgressMs = now();
            wasObserved = false;
            style = MovementStylePolicy.forBot(chr);
            fjCap = FlashJumpTiers.capFor(chr);
            engage.reset();
            loot.reset();
            climb.reset();
            engaged = false;
            targetOid = -1;
            retaliatingOid = -1; // a fresh episode starts with a clean vendetta slate
            retaliatingMapId = -1;
            lastMoveTargetX = Integer.MIN_VALUE;
            lastKillMs = now();
            lastSwingMapId = -1; // fresh episode re-arms the one-stop travel-clear
            lastNarrate = "";
            lastGiveUpNarrateMs = 0L;
            camp.resetEpisodeUnderLock();
            roam.resetEpisodeUnderLock();
            stack.resetEpisodeUnderLock();
            patrol.resetEpisodeUnderLock();
        }
        // Strategy resolution + pre-positioning stay outside the lock: profile() may bake the nav
        // graph on first touch, and movement takes its own locks.
        styleSwapRequested = false; // a fresh episode resolves the (possibly forced) style anyway
        episodeMapId = (chr != null) ? chr.getMapId() : -1;
        strategy = resolveStrategy(chr);
        GrindStrategy s = strategy;
        if (s != null) {
            s.start(chr);
        }
    }

    // One combat tick (already gated by the caller on running && phase == GRIND). Observed maps run the
    // active strategy; unobserved maps no-op here (the macro tick accrues abstract EXP instead).
    public void tick(Character chr) {
        if (chr == null) {
            return;
        }
        // Mid-episode map change (GM warp etc.) or a GM style override: swap strategies safely. The
        // release path uses the RECORDED claim map, never the new one, so no ghost claim survives;
        // then re-anchor here under the (possibly forced) style.
        boolean mapChanged = episodeMapId >= 0 && chr.getMapId() != episodeMapId;
        if (mapChanged || styleSwapRequested) {
            styleSwapRequested = false;
            synchronized (claimLock) {
                if (!claimActive) {
                    return; // bot is being released — don't restart an episode under it
                }
                GrindStrategy old = strategy;
                if (old != null) {
                    old.releaseUnderLock(chr);
                }
            }
            episodeMapId = chr.getMapId();
            strategy = resolveStrategy(chr);
            GrindStrategy fresh = strategy;
            if (fresh != null) {
                fresh.start(chr);
            }
            return;
        }
        if (!GCMovement.isMapObserved(chr.getMapId())) {
            wasObserved = false; // map went quiet; the next observed tick starts a fresh stuck window
            return;
        }
        if (!wasObserved) {
            // A player just arrived: the bot was abstract-leveling, not swinging, so reset the heartbeat so
            // the watchdog gives a grace window instead of instantly judging it stuck.
            wasObserved = true;
            lastCombatProgressMs = now();
        }
        // STUN / SEDUCE pins the bot: the grind brain must not steer it. This ticker (GrindTickRegistry,
        // 250ms) is separate from the movement driver, and the strategies move the body directly -
        // blink/teleport/dash/hop - so without this gate a stunned mage would keep blinking at mobs
        // (the driver's freeze hold only covers driver-issued movement). SEAL is NOT gated here: a
        // sealed bot may still walk, and BotAttackDriver already blocks its swings.
        if (isFrozen(chr)) {
            return;
        }
        if (GCMovement.isClimbing(chr)) {
            climb.handleClimb(chr); // RECOVER inline
            return;
        }
        climb.onGrounded();
        // 被打过且没打到过它 -> 立刻转火这个仇家（被打反击）：抢在途经清怪的单次机会之前，
        // 否则那次顺手一挥（记 lastSwingMapId）会把本次报消灭耗掉，bot 又变回贴脸挨打。
        // 复仇结束（击杀/消失/换图）或没打过 -> 走原有逻辑。
        if (tryRetaliate(chr)) {
            return;
        }
        // 好打的怪横在路上：途经者停下来顺手清掉，而不是贴脸走过。出手冷却未好时不看（散步经
        // 过不硬打，和玩家走路时手不在攻击键上一样）；FIGHT/WAIT 分支自己有 swing-first 门，
        // 这里的顺势一挥在驱动层共用同一冷却，不会造成双重出手。
        Monster interloper = bestInReachInterloper(chr);
        if (interloper != null) {
            engageAndSwingShared(chr);
        }
        GrindStrategy s = strategy;
        if (s != null) {
            s.tick(chr);
        }
    }

    // A live hostile sitting inside this bot's attack-reach box on its own ledge. Null = nothing
    // worth a travel-clear this tick. Read-only scan (no facing change, no claim mutation), so it
    // is safe to run ahead of every strategy tick while the strategies' FIGHT branch stays the
    // swing authority.
    private Monster bestInReachInterloper(Character chr) {
        if (!claimActive) {
            return null; // believability: only a bot holding a spot/ledge/far-target clears en route
        }
        if (BotAttackDriver.nextAttackEpochMs(chr.getId()) > now()) {
            return null; // swing still cooling — walk on (the driver re-gates at swing time anyway)
        }
        int rid = chr.getMapId();
        if (rid == lastSwingMapId) {
            return null; // already stopped for a travel-clear on this map this episode — keep walking
        }
        int reachX = BotAttackDriver.attackReachX(chr);
        int reachY = BotAttackDriver.attackReachY(chr);
        if (reachX <= 0) {
            return null; // no configured attack (or the aura-gated slot is down right now)
        }
        Point pos = chr.getPosition();
        if (pos == null || chr.getMap() == null) {
            return null;
        }
        Monster best = null;
        int bestAbsDx = Integer.MAX_VALUE;
        for (Monster m : chr.getMap().getAllMonsters()) {
            if (!SpotFinder.isHostile(m)) {
                continue;
            }
            Point mp = m.getPosition();
            if (mp == null || Math.abs(mp.x - pos.x) > reachX || Math.abs(mp.y - pos.y) > reachY) {
                continue;
            }
            if (GCMovement.onDifferentLedge(chr.getMap(), pos.x, pos.y, mp.x, mp.y)) {
                continue; // one platform over is someone else's fight, not a roadblock
            }
            int absDx = Math.abs(mp.x - pos.x);
            if (absDx < bestAbsDx) {
                bestAbsDx = absDx;
                best = m;
            }
        }
        return best;
    }

    // Shared settle-and-swing tail for the travel-clear beat: plant, fire the AUTO swing, and
    // bookkeep the beat (attack walk lock + progress heartbeats).
    private void engageAndSwingShared(Character chr) {
        GCMovement.stop(chr);
        engaged = true;
        lastMoveTargetX = Integer.MIN_VALUE;
        lastSwingMapId = chr.getMapId();
        BotAttackDriver.botAttack(chr); // driver's own cooldown/debuff gates still apply
        attackWalkLockUntil = now() + EngageBeat.ATTACK_WALK_LOCK_MS;
        markProgress(); // the en-route clear is productive, not a wedge
    }

    // ── Retaliate-when-hit beat (被打反击) ──

    /**
     * The mob that last actually hurt this bot becomes the fight target wherever the bot happens to
     * be in its grind (TRAVEL_TO_SPOT, a camp band walk, whatever) — a real player turns and kills
     * the thing that just hit them instead of strolling on through its hits. The bot walks the
     * attacker down, swings it in range (kite/blink cadences included), and on the kill (or if the
     * mob despawns or the bot changes maps) hands the tick straight back to the ordinary strategy
     * flow, so the original plan resumes untouched. The vendetta is STICKY once promoted: the
     * attacker register's 15s memory expiry only gates re-promotion, it does not end an ongoing
     * pursuit — a live, hostile attacker is finished even if its register entry has gone stale.
     *
     * <p>Deliberately NOT the one-stop travel-clear's {@code lastSwingMapId} gate: being hit is the
     * mob engaging the bot, so the bot finishing the fight reads as believable, not as a fighting
     * crawl. The swing itself still goes through the driver's cooldown/debuff gates via the shared
     * {@link EngageBeat#engageAndSwing}, so a swing that cannot fire is a no-op beat here.</p>
     *
     * @return true when this tick was spent retaliating (the caller skips the strategy tick).
     */
    private boolean tryRetaliate(Character chr) {
        int oid = GCMovement.lastAttackerOid(chr);
        if (oid >= 0 && retaliatingOid != oid) {
            // Fresh attacker (or a second mob jumped in): promote it. Same oid already tracked
            // (a 2.5s i-frame gate keeps re-registration sparse) -> keep the current vendetta.
            retaliatingOid = oid;
            retaliatingMapId = chr.getMapId();
            lastMoveTargetX = Integer.MIN_VALUE;
        }
        if (retaliatingOid < 0) {
            return false;
        }
        if (retaliatingMapId != chr.getMapId()) {
            dropRetaliation(chr); // warped off the vendetta map (transit / macro brain): resume the plan
            return false;
        }
        MapObject mo = chr.getMap().getMapObject(retaliatingOid);
        if (!(mo instanceof Monster m) || !m.isAlive() || !SpotFinder.isHostile(m)) {
            dropRetaliation(chr); // killed, despawned, or pacified -> back to the ordinary flow
            return false;
        }
        Point mp = m.getPosition();
        Point pos = chr.getPosition();
        if (mp == null || pos == null) {
            return false;
        }
        if (inAttackRange(chr, m)) {
            retaliateSwing(chr, m);
        } else {
            if (engage.skillMoveToward(chr, mp.x, mp.y)) {
                return true; // mage blinked / hermit dashed at the attacker this beat
            }
            if (now() < attackWalkLockUntil) {
                return true; // mid-swing beat: hold the plant, keep the vendetta
            }
            if (Math.abs(mp.x - lastMoveTargetX) >= ROAM_RETARGET_EPS) {
                GCMovement.move(chr, mp.x, mp.y);
                lastMoveTargetX = mp.x;
            }
        }
        return true;
    }

    // The vendetta's in-range tail, shared by every style through the ordinary engage beat: the
    // flourish (jump-attack / kite hop / turn beat) is exactly what an engaged player looks like.
    // Return-to-plan mirrors the strategies' own post-kill handling: roam-style re-plant (so the
    // walk to wherever the bot was headed re-issues fresh) and the next mob re-acquired by the
    // strategy's own acquire path on the next tick.
    private void retaliateSwing(Character chr, Monster m) {
        var r = engage.engageAndSwing(chr, m);
        if (r != null && r.killed()) {
            dropRetaliation(chr);
            engaged = false;
            lastMoveTargetX = Integer.MIN_VALUE;
        }
    }

    // End the vendetta and drop the contact register along with it: a killed / despawned / left-behind
    // attacker must not re-enter through lastAttackerOid on the next tick and restart the pursuit on a
    // corpse oid (that would idle-flip lastMoveTargetX every beat until the memory window expires).
    private void dropRetaliation(Character chr) {
        retaliatingOid = -1;
        if (chr != null) {
            GCMovement.forgetLastAttacker(chr);
        }
    }

    // Drop the spot claim + reset combat state when the bot leaves the map / stops.
    public void release(Character chr) {
        synchronized (claimLock) {
            claimActive = false; // an in-flight select on the ticker aborts instead of claiming into a leak
            GrindStrategy s = strategy;
            if (s != null) {
                s.releaseUnderLock(chr);
            }
            targetOid = -1;
            engaged = false;
            retaliatingOid = -1; // off the map / out of the grind: no vendetta survives
            lastMoveTargetX = Integer.MIN_VALUE;
            lastSwingMapId = -1;
        }
    }

    // Watchdog recovery: drop the stale target and let the strategy re-anchor/reselect from the
    // character's current position. Deliberately does NOT reset the heartbeat, so escalation can
    // continue if ordinary repathing does not help.
    public void resetAfterStall(Character chr) {
        targetOid = -1;
        resetApproachProgress(chr);
        GrindStrategy s = strategy;
        if (s != null) {
            s.resetAfterStall(chr);
        }
    }

    public long msSinceProgress() {
        return now() - lastCombatProgressMs;
    }

    // True when this map is full for the active style (camp: every reachable spot claimed; roam: seek
    // range persistently dry). TrainingBot's macro crowd-bail reads this to leave for a deeper map.
    public boolean mapSaturated() {
        GrindStrategy s = strategy;
        return s != null && s.saturated();
    }

    // True while the bot is mid-rope or just dismounted — the watchdog defers escalation during recovery.
    public boolean isRecovering(Character chr) {
        return climb.isRecovering(chr);
    }

    public String spotLabel() {
        GrindStrategy s = strategy;
        return s != null ? s.label() : "[no-spot]";
    }

    // ── Strategy resolution + GM override ──

    // GM tooling (!bot grindstyle): force this bot's archetype (null = back to auto). A live grind
    // episode swaps its strategy on the next combat tick (claims released against their recorded
    // map); an idle bot just resolves the forced style when it next starts grinding.
    public void forceStyle(GrindStyle wanted) {
        forcedStyle = wanted;
        styleSwapRequested = true;
    }

    public GrindStyle forcedStyle() {
        return forcedStyle;
    }

    // The style the active strategy is running (null = no episode live).
    public GrindStyle activeStyle() {
        GrindStrategy s = strategy;
        return (s != null) ? s.style() : null;
    }

    private GrindStrategy resolveStrategy(Character chr) {
        MapGrindProfile p = (chr != null && chr.getMap() != null) ? SpotFinder.profile(chr.getMap()) : null;
        GrindStyle forced = forcedStyle;
        return strategyFor(forced != null ? forced : GrindStylePolicy.natural(p, style));
    }

    private GrindStrategy strategyFor(GrindStyle wanted) {
        return switch (wanted) {
            case CAMP -> camp;
            case PATROL -> patrol;
            case ROAM -> roam;
            case STACK -> stack;
        };
    }

    // ── Shared services (package-visible for the strategies + beats) ──

    // Densest-pack targeting radius for the travel-approach scan (same knob as camp/stack targeting).
    static final int TRAVEL_CLUSTER_RADIUS_PX = 160;

    /*
     * The approach destination for a travel beat toward `s`: the spot ledge's live-mob foothold
     * when the swing is ready and the ledge feeds (fight on arrival — a player walks INTO the pack
     * they pass, not to an empty anchor pixel), else the anchor's own ground. Pairs with the
     * travel-clear beat above: that stops for a mob already in reach, this shapes the last stretch
     * of the walk so arrival converts straight into FIGHT swings. Read-only.
     */
    Point travelApproachPoint(Character chr, Spot s) {
        Point anchor = s.anchor();
        if (BotAttackDriver.nextAttackEpochMs(chr.getId()) <= now()) {
            Monster m = SpotFinder.bestClusterHostileInBand(chr.getMap(), anchor, s.radius(),
                    anchor.x - s.radius(), anchor.x + s.radius(), chr.getPosition(), TRAVEL_CLUSTER_RADIUS_PX);
            if (m != null) {
                Point mp = m.getPosition();
                Point mgp = GCMovement.groundPointBelow(chr.getMap(), mp.x, mp.y);
                return new Point(mp.x, (mgp != null) ? mgp.y : mp.y);
            }
        }
        Point gp = GCMovement.groundPointBelow(chr.getMap(), anchor.x, anchor.y);
        return new Point(anchor.x, (gp != null) ? gp.y : anchor.y);
    }

    // True while a just-fired swing (the shared travel-clear beat, or the walk-out swing FIGHT
    // leaves behind) holds the bot planted: an attack animation cannot walk mid-swing. The beat is
    // spent standing; the walk resumes next tick and the claim keeps its regular re-acquire flow.
    boolean midSwingPlant() {
        return now() < attackWalkLockUntil;
    }

    void markProgress() {
        lastCombatProgressMs = now();
    }

    /**
     * Collect the drop the last kill left, for the strategies to call just before they commit to
     * walking at a mob that is out of attack range. Shared so every grind style loots the same way,
     * and so the settle window is bounded in one place.
     *
     * <p>Returns true while there is loot to deal with, so the caller skips the approach this tick.
     */
    boolean collectAfterKill(Character chr, int x0, int x1, int searchRangePx) {
        if (now() - lastKillMs > COLLECT_AFTER_KILL_MS) {
            return false;
        }
        return loot.collectAfterKill(chr, x0, x1, searchRangePx);
    }

    // The active strategy's movement clamp for the shared engage beats.
    int[] effectiveLeash(Character chr) {
        GrindStrategy s = strategy;
        if (s != null) {
            return s.leash(chr);
        }
        Point p = (chr != null) ? chr.getPosition() : null;
        int cx = (p != null) ? p.x : 0;
        return new int[]{cx - APPROACH_X, cx + APPROACH_X};
    }

    // Strategy-appropriate ground under x (camp: the spot's ledge; roam: whatever is below).
    Point strategyGroundAt(Character chr, int x) {
        GrindStrategy s = strategy;
        return (s != null) ? s.groundAt(chr, x) : null;
    }

    // The shared sticky target resolved to a live hostile Monster, with no leash validation — for
    // direction-only consumers (rope dismount). Strategies apply their own validity gates on top.
    Monster currentTargetMonster(Character chr) {
        if (targetOid < 0 || chr == null || chr.getMap() == null) {
            return null;
        }
        MapObject mo = chr.getMap().getMapObject(targetOid);
        return (mo instanceof Monster m && SpotFinder.isHostile(m)) ? m : null;
    }

    // Lazily derive (and cache) the stop-and-swing distance from this bot's attack reach so ranged/magic
    // bots fire from afar. Floored at the melee values for short attacks.
    boolean inAttackRange(Character chr, Monster mob) {
        if (approachX < 0) {
            approachX = Math.max(APPROACH_X, (int) (BotAttackDriver.attackReachX(chr) * APPROACH_REACH_FRAC));
            approachY = Math.max(APPROACH_Y, (int) (BotAttackDriver.attackReachY(chr) * APPROACH_REACH_FRAC));
        }
        Point pos = chr.getPosition();
        Point mp = mob.getPosition();
        return pos != null && mp != null
                && Math.abs(mp.x - pos.x) <= approachX && Math.abs(mp.y - pos.y) <= approachY;
    }

    // ── Approach-progress tracking (no-progress give-up on an unreachable target/spot) ──

    void resetApproachProgress(Character chr) {
        retargetDeadline = now() + TARGET_RETARGET_TIMEOUT;
        Point p = (chr != null) ? chr.getPosition() : null;
        noProgressAnchorX = (p != null) ? p.x : Integer.MIN_VALUE;
        noProgressAnchorY = (p != null) ? p.y : 0;
    }

    boolean madeApproachProgress(Character chr) {
        Point p = chr.getPosition();
        if (p == null) {
            return false;
        }
        if (noProgressAnchorX == Integer.MIN_VALUE
                || Math.abs(p.x - noProgressAnchorX) + Math.abs(p.y - noProgressAnchorY) > APPROACH_PROGRESS_EPS) {
            noProgressAnchorX = p.x;
            noProgressAnchorY = p.y;
            retargetDeadline = now() + TARGET_RETARGET_TIMEOUT; // moving -> push the give-up deadline out
            return true;
        }
        return false;
    }

    // ── Narration ──

    void narrate(String msg) {
        if (!NARRATE) {
            return;
        }
        if (!msg.equals(lastNarrate)) {
            lastNarrate = msg;
            debug.accept(msg);
        }
    }

    void narrateGiveUp() {
        if (!NARRATE) {
            return;
        }
        if (now() - lastGiveUpNarrateMs >= GIVE_UP_NARRATE_GAP_MS) {
            lastGiveUpNarrateMs = now();
            debug.accept("can't reach target mob -> retargeting (traversal?)");
        }
    }

    // Raw narration line (GrindLoot applies its own throttle before calling).
    void debugLine(String msg) {
        debug.accept(msg);
    }

    static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    /** STUN / SEDUCE: the bot is pinned, so the grind brain must not move or swing it this tick. */
    private static boolean isFrozen(Character chr) {
        BotDebuffState status = BotDebuffState.of(chr);
        return status != null && status.isFrozen();
    }
}
