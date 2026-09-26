package soloMapling.ArtificialPlayer.BotSummonSystem;

import org.gms.client.Character;
import org.gms.client.Skill;
import org.gms.client.SkillFactory;
import org.gms.client.status.MonsterStatus;
import org.gms.client.status.MonsterStatusEffect;
import org.gms.constants.game.CharacterStance;
import org.gms.server.StatEffect;
import org.gms.server.life.Monster;
import org.gms.server.maps.Foothold;
import org.gms.server.maps.MapObject;
import org.gms.server.maps.MapObjectType;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Summon;
import org.gms.util.PacketCreator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackEffects;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotDamageModel;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Drives every bot's summons: one shared tick that moves hovering summons the way the owning
 * client would, and lets attacking summons strike nearby mobs.
 *
 * <p><b>Why the server authors the movement.</b> A client only animates the summon it owns - the
 * local player's own. A summon belonging to anyone else, a bot's included, is rendered by observers
 * purely from the {@code MOVE_SUMMON} frames the server relays: for a real player the owner's own
 * client produces those frames and the host merely echoes them ({@code MoveSummonHandler}), but a
 * headless bot has no client to produce them, so without this class its summon would stand frozen
 * at its spawn point while the bot fought across the map. The plugin therefore plays the client's
 * part: every tick it computes the follow slot and broadcasts a {@code MOVE_SUMMON}
 * frame carrying the client's own movement-fragment layout - the exact frame the host relays for a
 * real player, and the same technique the pet system already uses in-game. STATIONARY summons (the
 * pirate turrets) are never moved and get no movement frames at all: a placed cannon sits where it
 * was placed.</p>
 *
 * <p><b>No host buff.</b> The bot learns the summon skill's level (the host {@link Summon}
 * constructor requires it) but no host buff is ever registered, so the host's own SUMMON/PUPPET
 * buff lifecycle is never involved; this class owns spawn, movement, LIFETIME and teardown
 * outright - including the player-parity expiry (the skill's own WZ buff time) and the recast
 * that follows it, which the regrant beat performs the way a player pressing the skill again
 * would.</p>
 *
 * <p><b>LOD.</b> Movement frames and attacks are gated on {@link GCMovement#isMapObserved}: while
 * no real player watches the map nothing is sent, and the tick only keeps each summon's position in
 * memory (cheap arithmetic) so a player arriving sees it where it should be rather than at a stale
 * spawn point. Map re-homing is cleanup + spawn and runs unconditionally, so a turret can never
 * ghost on a map the owner left.</p>
 */
public final class BotSummonFollower {

    private static final Logger log = LoggerFactory.getLogger(BotSummonFollower.class);

    // CSummoned action bytes carried by the spawn packet's nMoveAction and by every move fragment.
    // The action table itself is the kinoko SummonedActionType (STAND 0, MOVE 1, FLY 2, ...), but
    // the BYTE is packed like every other Life's moveAction (kinoko Life.isLeft, the pet frames'
    // even/odd table): bit 0 = the FACING (0 right, 1 left), the action sits in the high bits. The
    // v83 client reads this bit to mirror the sprite, so the follower packs
    // flyAction(facingLeft) = ACTION_FLY_BASE | facing for every summon it moves or spawns; without
    // it the client renders a plain 2 and the bird faces right forever, whatever the owner does.
    private static final int ACTION_FLY_BASE = 2;
    private static final int ACTION_STAND_BASE = 0;

    /** The spawn/move nMoveAction byte for a flyer facing {@code left}: FLY with the facing bit. */
    static int flyAction(boolean left) {
        return ACTION_FLY_BASE | (left ? 1 : 0);
    }

    /**
     * The spawn nMoveAction byte for a grounded summon facing {@code left}: STAND with the facing
     * bit (a turret is placed, and a placed cannon should at least look at its owner).
     */
    static int standAction(boolean left) {
        return ACTION_STAND_BASE | (left ? 1 : 0);
    }

    // ── The follow (a pet-style leash, lifted into the air) ───────────────────
    // Like BotPetFollower, the summon holds a STABLE follow distance rather than tracking the
    // owner's facing: it rides BEHIND the owner on a comfort ring (FOLLOW_MIN_PX..FOLLOW_MAX_PX),
    // "behind" meaning its own side of the owner, so the owner walking past its column is what
    // swaps the sides - a turn in place never moves the summon at all (that was the reported
    // 转身拉扯). The ring is TIGHTER than a pet's ground distance: the bird already rides
    // offset_y px up, so the pet's 23..60px ring read as floating too far away - the flight ring
    // keeps it close to the owner's silhouette. Same small sine bob; the two systems stay
    // deliberately decoupled.

    /** Follow distance floor (px) - a tight flight ring, held stable per summon. */
    static final int FOLLOW_MIN_PX = 10;
    /** Follow distance cap (px) - a tight flight ring, held stable per summon. */
    static final int FOLLOW_MAX_PX = 22;
    /** Idle jitter dead zone (px): slot moves inside this are ignored, exactly like the pet's. */
    static final int FOLLOW_DEAD_ZONE_PX = 8;

    /**
     * How long a summon's monster status lasts (ms) - the flat value the host's
     * {@code SummonDamageHandler} passes to {@code Monster.applyStatus}, which ignores the skill's
     * own WZ {@code time} entirely (a 30th-level Silver Hawk's 180s is never read). Mirrored here so
     * a bot's stun/freeze expires on a real player's clock rather than the summon's lifetime.
     */
    static final long SUMMON_DEBUFF_MS = 4000L;

    /** A fresh follow distance (px) in [{@code FOLLOW_MIN_PX}, {@code FOLLOW_MAX_PX}]. */
    static int freshFollowDistancePx() {
        return FOLLOW_MIN_PX + ThreadLocalRandom.current().nextInt(FOLLOW_MAX_PX - FOLLOW_MIN_PX + 1);
    }

    // botId -> that bot's live summons.
    private static final Map<Integer, List<BotSummon>> TRACKED = new ConcurrentHashMap<>();

    // One lock serialises the lifecycle of every bot's summon list: register / despawnAll / stop /
    // the tick's snapshot and re-home. A summon torn down by the bot's own thread and a summon
    // re-homed by the tick can therefore never interleave - under the lock, exactly one side wins,
    // so a re-home can never resurrect an entity the teardown just retired (a ghost). The lock may
    // span the remove + spawn broadcasts of a single re-home; both are rare lifecycle events and
    // the sends just queue to sockets, so the hold is brief and bounded.
    private static final Object LIFECYCLE_LOCK = new Object();

    // skillId -> the skill's WZ buff time in ms (0 = none). Memoized: the same Skill.wz row the
    // strike cadence and the host's own buff timer read, and like them read once per skill.
    private static final Map<Integer, Long> BUFF_TIME_BY_SKILL = new ConcurrentHashMap<>();

    // botIds whose summon expired and whose recast is due within the config's refresh window.
    // A scheduling beat, not a registry: the entry is consumed by the regrant sweep that armed
    // it, and a bot torn down (despawnAll) is removed so an expired grant can never resurrect
    // a bot that became an FM keeper mid-window.
    private static final Map<Integer, Long> REGRANT_DUE_AT = new ConcurrentHashMap<>();

    private static volatile BotSummonConfig config = BotSummonConfig.defaults();
    private static ScheduledFuture<?> task;
    private static ScheduledFuture<?> regrantTask;

    /**
     * Period (ms) of the dedicated regrant sweep. Faster than the jitter window itself (20s), so
     * a recast never waits noticeably longer than the window the config promised.
     */
    private static final long REGRANT_SWEEP_MS = 2_000L;

    private BotSummonFollower() {}

    public static synchronized void start(BotSummonConfig cfg) {
        config = cfg;
        if (task != null) {
            return;
        }
        long period = Math.max(100L, cfg.moveTickMs());
        task = soloMapling.server.ExecutorServiceManager.getScheduledExecutorService()
                .scheduleAtFixedRate(BotSummonFollower::tick, period, period, TimeUnit.MILLISECONDS);
        regrantTask = soloMapling.server.ExecutorServiceManager.getScheduledExecutorService()
                .scheduleAtFixedRate(() -> regrantIfDue(config),
                        REGRANT_SWEEP_MS, REGRANT_SWEEP_MS, TimeUnit.MILLISECONDS);
        System.out.println("[BotSummonFollower] started, period=" + period + "ms");
    }

    public static synchronized void stop() {
        if (task != null) {
            task.cancel(false);
            task = null;
        }
        if (regrantTask != null) {
            regrantTask.cancel(false);
            regrantTask = null;
        }
        REGRANT_DUE_AT.clear();
        // Best-effort teardown of everything still tracked, so a reload never leaves ghosts. Each
        // bot's list is retired under the lifecycle lock before its entities are removed: an
        // in-flight tick that later sees such a summon finds the list gone (the identity check in
        // tickBot) and does not re-spawn it. A bot that cannot be looked up still gets its entity
        // removed (removeEntity tolerates a null bot): the entity is plugin-owned and the host
        // would never have cleaned it up. The drain repeats until the registry is empty, so a
        // grant racing this teardown is still drained rather than dropped untracked.
        while (true) {
            Integer botId = null;
            List<BotSummon> summons = null;
            synchronized (LIFECYCLE_LOCK) {
                for (Integer id : TRACKED.keySet()) {
                    botId = id;
                    summons = TRACKED.remove(id);
                    break;
                }
            }
            if (botId == null) {
                return; // nothing left tracked
            }
            Character bot = BotHelpers.getCharFromChannelStorage(botId);
            if (summons != null) {
                for (BotSummon s : summons) {
                    removeEntity(bot, s);
                }
            }
        }
    }

    /** True when this bot currently has summons tracked. */
    static boolean isTracked(int botId) {
        List<BotSummon> list = TRACKED.get(botId);
        return list != null && !list.isEmpty();
    }

    /** Add one freshly spawned summon to a bot's tracked list. */
    static void register(int botId, BotSummonTable.Spec spec, Summon summon, MapleMap map) {
        BotSummon s = new BotSummon(botId, spec.skillId(), spec);
        s.summon = summon;
        s.map = map;
        // Random phase so a bot's summons bob out of lockstep with each other, and a random
        // comfort distance so each holds its own spot on the pet's follow ring.
        s.phaseRad = ThreadLocalRandom.current().nextDouble() * Math.PI * 2.0;
        s.followDistancePx = freshFollowDistancePx();
        // The player-parity lifetime: a player's summon is its buff, and the buff expires on the
        // skill's own WZ buff time. The bot's recast after an expiry is spread over a jitter
        // window (a player mashes the skill again within seconds, not on a stopwatch), so every
        // bot's clock never reads clockwork. When the WZ row has no readable time the summon
        // keeps the old rule: no lifetime at all.
        long buffMs = summonBuffTimeMs(spec.skillId());
        if (config.lifetimeWz() && buffMs > 0) {
            long jitter = (long) (ThreadLocalRandom.current().nextDouble()
                    * Math.max(0L, config.lifetimeRefreshWindowMs()));
            s.expireAtMs = System.currentTimeMillis() + buffMs + jitter;
        }
        synchronized (LIFECYCLE_LOCK) {
            TRACKED.computeIfAbsent(botId, k -> new ArrayList<>()).add(s);
        }
    }

    /**
     * The summon skill's WZ buff time in ms ({@code StatEffect.getDuration()}), the clock a
     * real player's summon expires on; 0 when unreadable. Memoized per skill.
     */
    private static long summonBuffTimeMs(int skillId) {
        return BUFF_TIME_BY_SKILL.computeIfAbsent(skillId, id -> {
            try {
                org.gms.client.Skill skill = org.gms.client.SkillFactory.getSkill(id);
                if (skill == null) {
                    return 0L;
                }
                long ms = skill.getEffect(skill.getMaxLevel()).getDuration();
                return ms > 0 ? ms : 0L;
            } catch (Throwable t) {
                return 0L;
            }
        });
    }

    /** Tear every tracked summon down and stop tracking the bot. Safe to call repeatedly. */
    public static void despawnAll(Character bot) {
        if (bot == null) {
            return;
        }
        REGRANT_DUE_AT.remove(bot.getId()); // an armed recast must never resurrect a torn-down bot
        List<BotSummon> summons;
        synchronized (LIFECYCLE_LOCK) {
            summons = TRACKED.remove(bot.getId());
        }
        if (summons == null) {
            return;
        }
        for (BotSummon s : summons) {
            removeEntity(bot, s);
        }
        // The bot's own summon map is never populated (we do not call addSummon - the entity is
        // owned here), so the host's leave-map path already sees an empty collection and does
        // nothing for it. Removing the entities above is the whole teardown. A tick that had
        // already snapshotted this list finds TRACKED.get(botId) != summons under the lock and
        // drops the summon instead of re-homing it, so the two sides cannot both act on it.
    }

    /** Remove a single summon's host entity + packets (never throws; tolerates a gone bot). */
    private static void removeEntity(Character bot, BotSummon s) {
        Summon summon = s.summon;
        if (summon == null) {
            return;
        }
        try {
            MapleMap map = s.map;
            if (map != null) {
                // The host's own leave-map path sends NO remove packet for a summon, so the old
                // map's observers would keep a ghost. Broadcast the removal, then drop it.
                map.broadcastMessage(PacketCreator.removeSummon(summon, true), summon.getPosition());
                map.removeMapObject(summon);
            }
            if (bot != null) {
                bot.removeVisibleMapObject(summon);
            }
        } catch (Throwable t) {
            log.warn("summon teardown failed cid={} skill={}: {}", bot == null ? -1 : bot.getId(), s.skillId, t.toString());
        } finally {
            s.summon = null;
        }
    }

    private static void tick() {
        BotSummonConfig cfg = config;
        regrantIfDue(cfg);
        for (Integer botId : new ArrayList<>(TRACKED.keySet())) {
            try {
                tickBot(botId, cfg);
            } catch (Throwable t) {
                // Per-bot isolation, mirroring GrindTickRegistry: one bad summon never stops the sweep.
                log.warn("summon tick failed cid={}: {}", botId, t.toString());
            }
        }
    }

    /**
     * The recast beat: grant a fresh summon to every bot whose previous one expired. Runs at the
     * head of the tick, before the per-bot sweep. A bot is re-armed only when the grant actually
     * took - a bot whose roll lost, or whose map went dark, retries on the NEXT sweep instead
     * (a short, invisible stretch past expiry that reads exactly as a player pausing before
     * recasting), never an entry that can survive a grant to re-arm forever.
     */
    private static void regrantIfDue(BotSummonConfig cfg) {
        if (REGRANT_DUE_AT.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Map.Entry<Integer, Long> e : REGRANT_DUE_AT.entrySet()) {
            if (e.getValue() > now) {
                continue; // jitter window not elapsed yet
            }
            Character bot = BotHelpers.getCharFromChannelStorage(e.getKey());
            REGRANT_DUE_AT.remove(e.getKey());
            if (bot == null || bot.getMap() == null) {
                continue; // the bot is gone; nothing left to recast for
            }
            if (!GCMovement.isMapObserved(bot.getMapId())) {
                continue; // dark map: nobody can watch the recast, so skip it (grant stays off)
            }
            // Deliberately NOT the config-gated BotSummonSystem.grant: the lifetime feature is
            // what armed this beat, so the owner's toggle decides only NEW bots, and a bot the
            // spawn roll once denied stays denied.
            BotSummonController.grantForBot(bot, cfg);
        }
    }

    private static void tickBot(int botId, BotSummonConfig cfg) {
        Character bot = BotHelpers.getCharFromChannelStorage(botId);
        List<BotSummon> summons = TRACKED.get(botId);
        if (summons == null) {
            return;
        }
        if (bot == null || bot.getMap() == null) {
            // The bot is gone from the world. Our entities are NOT in chr.summons (the plugin owns
            // them directly), so the host's own leave-map path never removed them - tear them down
            // here rather than leave ghosts on the bot's last map, then drop our state.
            List<BotSummon> gone;
            synchronized (LIFECYCLE_LOCK) {
                gone = TRACKED.remove(botId);
            }
            if (gone != null) {
                for (BotSummon s : gone) {
                    removeEntity(bot, s); // bot may be null here - removeEntity tolerates it
                }
            }
            return;
        }

        boolean observed = GCMovement.isMapObserved(bot.getMapId());
        MapleMap botMap = bot.getMap();
        // Snapshot: register/despawnAll may touch the list on the bot's own lifecycle thread.
        List<BotSummon> snapshot;
        synchronized (LIFECYCLE_LOCK) {
            snapshot = new ArrayList<>(summons);
        }
        // Player-parity lifetime (an expiry armed by register, not the host buff pipeline this
        // system deliberately bypasses): when the clock runs out the summon ends the way a
        // player's does - removal broadcast, no dramatics - and the bot recasts after the
        // jitter window, which is exactly what a player pressing the skill again looks like.
        // Dark maps skip the expiry (the removal nobody can see would still ghost observers on
        // arrival) and torn-down bots never arm (a keeper keeps its summon; despawnAll also
        // clears any armed entry).
        if (observed) {
            for (BotSummon s : snapshot) {
                if (s.expireAtMs > 0 && now() >= s.expireAtMs) {
                    synchronized (LIFECYCLE_LOCK) {
                        if (TRACKED.get(botId) == summons) {
                            despawnSummons(bot, botId, snapshot);
                        }
                    }
                    REGRANT_DUE_AT.put(botId,
                            now() + ThreadLocalRandom.current().nextLong(
                                    Math.max(1L, cfg.lifetimeRefreshWindowMs())));
                    break; // one beat per bot per tick
                }
            }
        }
        for (BotSummon s : snapshot) {
            // Re-homing is pure cleanup + spawn and MUST run whether or not the map is observed: a
            // stationary summon is not removed by the host's own leave-map path, so skipping it on an
            // unobserved map would ghost the entity on the old map until a player walked in. A null
            // entity (a spawn that failed, e.g. a transient construction error) is retried the same
            // way instead of being skipped forever.
            //
            // The re-home runs under the lifecycle lock: a despawnAll/stop that ran meanwhile has
            // retired this list (the same lock), and re-homing after that would resurrect an entity
            // nobody tracks (a ghost). Holding the lock across the check makes exactly one side win -
            // either the tick re-homes a still-tracked summon, or the teardown tears it down.
            if (s.summon == null || s.map != botMap) {
                synchronized (LIFECYCLE_LOCK) {
                    if (TRACKED.get(botId) == summons) {
                        handleMapChange(bot, s);
                    }
                }
                continue;
            }
            // Move BEFORE attacking so a strike uses the frame's fresh position. The turret rule
            // (a placed cannon is never repositioned, and gets no frame) lives inside moveSummon.
            moveSummon(bot, s, cfg, observed);
            if (s.spec.isStationary() && s.attacks() && observed) {
                // A turret's placement, unlike a flyer's follow, is the whole game: a turret
                // standing where the pack has wandered away from fires nothing, so a placed one
                // is re-seated at a fresh mob near the owner. Unobserved maps skip it - the whole
                // combat view is frozen there anyway.
                maybeRelocateTurret(bot, s, cfg, botMap, observed);
            }
            if (s.attacks() && observed) {
                tryAttack(bot, s, cfg);
            }
        }
    }

    /**
     * Tear down every summon in {@code summons} and stop tracking the bot - the mechanical half
     * of {@link #despawnAll}, shared with the tick's expiry path (which must NOT clear a regrant
     * it is about to arm itself).
     */
    private static void despawnSummons(Character bot, int botId, List<BotSummon> summons) {
        synchronized (LIFECYCLE_LOCK) {
            if (TRACKED.remove(botId, summons)) {
                for (BotSummon s : summons) {
                    removeEntity(bot, s);
                }
            }
        }
    }

    /** Millis since the epoch - the clock the expiry fields are written and judged on. */
    private static long now() {
        return System.currentTimeMillis();
    }

    /**
     * The owner warped to another map. Drop the old entity off the old map and spawn a FRESH one at
     * the owner's feet on the new map. A new entity is used (new object id) rather than re-placing the
     * old: the old object id may still be tracked by clients that saw the removal, so reusing it is
     * the kind of aliasing that can make a stale entity appear in two places.
     */
    private static void handleMapChange(Character bot, BotSummon s) {
        MapleMap newMap = bot.getMap();
        try {
            removeEntity(bot, s);
            Summon fresh = spawnEntity(bot, s.skillId, s.spec, newMap);
            if (fresh != null) {
                s.summon = fresh;
                s.map = newMap;
            }
        } catch (Throwable t) {
            log.warn("summon re-home failed cid={} skill={}: {}", bot.getId(), s.skillId, t.toString());
            s.summon = null;
            s.map = newMap;
        }
    }

    /** Construct + broadcast a summon entity at the bot's feet. Returns null if it cannot be made. */
    static Summon spawnEntity(Character bot, int skillId, BotSummonTable.Spec spec, MapleMap map) {
        org.gms.client.Skill skill = org.gms.client.SkillFactory.getSkill(skillId);
        if (skill == null || bot.getSkillLevel(skill) < 1) {
            return null; // not learnable / not learned - constructing would throw or crash observers
        }
        org.gms.server.maps.SummonMovementType moveType = switch (spec.move()) {
            case STATIONARY -> org.gms.server.maps.SummonMovementType.STATIONARY;
            case FOLLOW -> org.gms.server.maps.SummonMovementType.FOLLOW;
            case CIRCLE_FOLLOW -> org.gms.server.maps.SummonMovementType.CIRCLE_FOLLOW;
        };
        boolean left = CharacterStance.isFacingLeft(bot.getStance());
        Point pos = spawnPosition(bot, spec, map, config);
        Summon summon = new Summon(bot, skillId, pos, moveType);
        // nMoveAction with the facing bit: the client mirrors the sprite from bit 0, so a bird
        // spawned beside a left-facing owner must spawn facing left too (it was stamped 0 - right -
        // regardless of the owner, which is what the "always faces right" report showed).
        summon.setStance(spec.isStationary() ? standAction(left) : flyAction(left));
        map.spawnSummon(summon);
        return summon;
    }

    /**
     * Where the entity appears: trailing the owner for a flyer, on the ground for a turret. A
     * turret is planted AT A MOB when one is near its owner ({@code place.at_mobs}): a turret
     * never moves, so planting one on an empty stretch of a hunting field means it never fires.
     * The mob's own ground is the spot (the turret is a ground piece); with no mob in radius - a
     * town, or a bot between packs - the turret falls back to the owner's feet, the pure
     * set-dressing spawn the towns know.
     */
    private static Point spawnPosition(Character bot, BotSummonTable.Spec spec, MapleMap map,
                                       BotSummonConfig cfg) {
        Point owner = bot.getPosition();
        if (!spec.isStationary()) {
            // A flyer materialises BEHIND the owner at its follow ring (the same slot the
            // follower's tick will hold it at), high above as the official bird rides.
            boolean facingLeft = CharacterStance.isFacingLeft(bot.getStance());
            int dist = freshFollowDistancePx();
            int x = facingLeft ? owner.x + dist : owner.x - dist;
            return new Point(x, owner.y + cfg.hoverOffsetY());
        }
        Point at = turretAnchor(bot, owner, map, cfg);
        Foothold ground = GCMovement.footholdBelow(map, at.x, at.y);
        int y = ground != null ? ground.calculateFooting(at.x) : at.y;
        return new Point(at.x, y);
    }

    /**
     * The x/y a turret is planted at: the nearest live mob within {@code place.search_radius} of
     * the owner (its own position, not smoothed - a mob stands on real ground already), or the
     * owner's position when the radius is dry. Only live mobs count - a dead mob's corpse spot is
     * exactly the empty stretch this rule exists to avoid.
     */
    static Point turretAnchor(Character bot, Point owner, MapleMap map, BotSummonConfig cfg) {
        if (cfg.placeAtMobs()) {
            List<Monster> near = nearestMobs(map, owner, cfg.placeSearchRadius(), 1);
            if (!near.isEmpty()) {
                return near.get(0).getPosition();
            }
        }
        return owner;
    }

    /**
     * A placed turret's relocate beat, run per tick BEFORE the attack. A turret never moves and
     * never receives a movement frame - re-seating is a placement, not a move: drop the entity
     * (with its removal broadcast) and spawn a fresh one at a mob near the owner, the exact
     * discipline a map change already uses (new object id; clients that saw the removal never see
     * the new id alias the old). When the map holds no mobs within the search radius, or the map
     * went unobserved, nothing happens - a dry probe costs one range query per throttle window.
     */
    private static void maybeRelocateTurret(Character bot, BotSummon s, BotSummonConfig cfg,
                                            MapleMap botMap, boolean observed) {
        long now = System.currentTimeMillis();
        if (now < s.nextRelocateProbeAtMs) {
            return; // throttle: a dry map must not pay a range query every tick
        }
        s.nextRelocateProbeAtMs = now + cfg.placeRelocateAfterMs();
        Point from = s.summon.getPosition();
        int mobsInRange = nearestMobs(botMap, from, cfg.attackRange(), Integer.MAX_VALUE).size();
        if (mobsInRange >= cfg.placeRelocateMinMobs()) {
            return; // the turret is still earning its keep where it stands
        }
        Point anchor = turretAnchor(bot, bot.getPosition(), botMap, cfg);
        if (anchor == bot.getPosition() && mobsInRange == 0) {
            return; // no mobs near the owner either (a town, or between packs): stand fast
        }
        try {
            synchronized (LIFECYCLE_LOCK) {
                // The teardown check from the tick's own re-home: a despawnAll that ran meanwhile
                // retired this summon - resurrecting it would spawn a ghost nobody tracks.
                if (TRACKED.get(s.botId) == null || !TRACKED.get(s.botId).contains(s)) {
                    return;
                }
                removeEntity(bot, s);
                Summon fresh = spawnEntity(bot, s.skillId, s.spec, botMap);
                if (fresh != null) {
                    s.summon = fresh;
                    s.map = botMap;
                } else {
                    s.summon = null; // retried by the tick's own re-home path, like a failed spawn
                }
            }
        } catch (Throwable t) {
            log.warn("turret re-seat failed cid={} skill={}: {}", bot.getId(), s.skillId, t.toString());
        }
    }

    /*
     * One movement step for a hovering summon. The entity glides toward its follow slot - the pet's
     * own leash lifted into the air - at a bounded speed, never warping, and - only when the map is
     * observed - a MOVE_SUMMON frame carries the step to the clients. Unobserved, the same
     * arithmetic still runs (no probe, no packet): the entity's position stays current so a player
     * entering the map sees it at its proper slot instead of a stale spawn point.
     *
     * <p>The follow is the PET's model ({@code BotPetFollower.followTargetX}), not a facing mirror:
     * the slot sits BEHIND the owner at a stable per-summon distance, and "behind" is defined by the
     * summon's own side of the owner (the sign of summon.x - owner.x). The owner crossing the
     * summon's column is what swaps the sides - so a TURN IN PLACE never moves the summon at all
     * (the swapped-side slot lands inside the pet's own dead zone even when it did), and the summon
     * only drifts around behind once the owner actually walks past it, gliding at its bounded speed
     * so the swap reads as the bird wheeling around, never a yank. The facing still matters: the
     * summon flies the way its OWNER faces (the wire action bit), so it turns when the bot turns
     * without moving for it.</p>
     */
    private static void moveSummon(Character bot, BotSummon s, BotSummonConfig cfg, boolean observed) {
        if (!shouldReposition(s.spec.isStationary())) {
            return; // a placed turret is never repositioned and receives no movement frame
        }
        Summon summon = s.summon;
        if (summon == null) {
            return; // concurrently torn down by the bot's own lifecycle thread
        }
        Point cur = summon.getPosition();
        Point owner = bot.getPosition();
        boolean facingLeft = CharacterStance.isFacingLeft(bot.getStance());
        long tickMs = Math.max(100L, cfg.moveTickMs());
        double elapsedSec = System.currentTimeMillis() / 1000.0;
        // The bob-free slot is what the dead zone and the re-seat threshold judge (judging the
        // bobbed point would ping-pong the summon between the bob's extremes); the bob rides the
        // glide target, so it shows while the summon is actually moving.
        Point base = followTarget(owner.x, owner.y, cur.x, s.followDistancePx, cfg.hoverOffsetY(),
                s.phaseRad, elapsedSec, 0.0, 0.0);
        if (cur.distance(base) > cfg.snapDistance()) {
            // The owner just teleported (or the summon fell implausibly far behind): re-seat the
            // entity at its slot. One same-point frame carries it straight there instead of
            // streaking it across the map.
            summon.setPosition(base);
            summon.setStance(flyAction(facingLeft));
            if (observed) {
                BotSummonBroadcast.summonMove(bot, summon, base, base, 0, 0, 0,
                        flyAction(facingLeft), (int) tickMs);
            }
            return;
        }
        if (!CharacterStance.isWalking(bot.getStance())
                && Math.abs(base.x - cur.x) < FOLLOW_DEAD_ZONE_PX
                && Math.abs(base.y - cur.y) < FOLLOW_DEAD_ZONE_PX) {
            // Inside the pet's dead zone with the owner not walking: hold position, but keep the
            // facing fresh - the owner may have turned in place, and the summon must turn with
            // them WITHOUT moving (that was the reported "转身拉扯").
            if (CharacterStance.isFacingLeft(summon.getStance()) != facingLeft) {
                summon.setStance(flyAction(facingLeft));
                if (observed) {
                    BotSummonBroadcast.summonMove(bot, summon, cur, cur, 0, 0, 0,
                            flyAction(facingLeft), (int) tickMs);
                }
            }
            return;
        }
        Point target = followTarget(owner.x, owner.y, cur.x, s.followDistancePx, cfg.hoverOffsetY(),
                s.phaseRad, elapsedSec, cfg.bobXAmplitude(), cfg.bobYAmplitude());
        // Bounded glide: cap the per-tick travel so the summon eases into its slot instead of
        // snapping (the bob is only a few px, so most ticks take the full step).
        int maxDx = (int) Math.max(1, cfg.followSpeedX() * tickMs / 1000L);
        int maxDy = (int) Math.max(1, cfg.followSpeedY() * tickMs / 1000L);
        int stepX = clampDelta(target.x - cur.x, maxDx);
        int stepY = clampDelta(target.y - cur.y, maxDy);
        if (stepX == 0 && stepY == 0) {
            return; // too far out to be inside the dead zone but capped to no motion this tick
        }
        Point next = new Point(cur.x + stepX, cur.y + stepY);
        int vx = (int) (stepX * 1000L / tickMs);
        int vy = (int) (stepY * 1000L / tickMs);
        summon.setPosition(next);
        summon.setStance(flyAction(facingLeft));
        if (observed) {
            BotSummonBroadcast.summonMove(bot, summon, cur, next, vx, vy, 0, flyAction(facingLeft), (int) tickMs);
        }
    }

    /**
     * Whether the follower may reposition this summon: a STATIONARY turret (the pirate octopus) is
     * placed where it spawned and never moved, so it must never receive a movement frame either.
     * Pure, so the rule is pinned by a unit test rather than re-derived at the call site.
     */
    static boolean shouldReposition(boolean stationary) {
        return !stationary;
    }

    /** Clamp a signed delta to +/-{@code max} (the per-tick travel cap). */
    private static int clampDelta(int delta, int max) {
        return Math.max(-max, Math.min(max, delta));
    }

    /**
     * Pure seam for tests: the pet's own follow ring, in the air. {@code summonX} is only read for
     * its SIGN against {@code ownerX} - the summon's own side of the owner, exactly
     * {@code BotPetFollower.followTargetX}: inside the leash the summon holds where it is (a turn
     * in place can never move it), outside it is pulled to its own-side ring at the stable
     * distance, so an owner walking past is what swaps the sides. {@code offsetY} is the height
     * (NEGATIVE = above, the same convention the config always used); the bob perturbs both axes.
     */
    static Point followTarget(int ownerX, int ownerY, int summonX, int followDistancePx, int offsetY,
                              double phaseRad, double elapsedSec, double bobXAmplitude, double bobYAmplitude) {
        double bobX = Math.sin(elapsedSec * 2.1 + phaseRad) * bobXAmplitude;
        double bobY = Math.cos(elapsedSec * 3.3 + phaseRad * 0.5) * bobYAmplitude;
        return new Point(
                followTargetX(ownerX, summonX, followDistancePx) + (int) Math.round(bobX),
                ownerY + offsetY + (int) Math.round(bobY));
    }

    /** The pet's own leash rule ({@code BotPetFollower.followTargetX}), verbatim. */
    static int followTargetX(int ownerX, int summonX, int comfort) {
        int delta = summonX - ownerX;
        if (Math.abs(delta) <= comfort) {
            return summonX; // inside the leash: nothing pulls the summon - it holds where it is
        }
        int side = delta > 0 ? 1 : -1; // the summon's own side, so it is never sent past the owner
        return ownerX + side * comfort;
    }

    private static void tryAttack(Character bot, BotSummon s, BotSummonConfig cfg) {
        long now = System.currentTimeMillis();
        if (now < s.nextAttackAtMs) {
            return;
        }
        Summon summon = s.summon;
        if (summon == null) {
            return; // concurrently torn down by the bot's own lifecycle thread
        }
        Skill skill = SkillFactory.getSkill(s.skillId);
        if (skill == null) {
            return; // gone from Skill.wz since the grant (a reload swapped the data)
        }
        // The strike's WZ row is read at the level the bot's CURRENT character level earns
        // (BotSummonTable.skillLevelForBot), NOT the stored grant: deriving it here is what makes a
        // summon grow with its owner - the analogue of a player recasting after a level-up - without
        // this tick ever writing to the Character's skill map (changeSkillLevel is a LinkedHashMap
        // write that would race the grind tick's own grant on the same bot). What that level yields
        // is everything a real player's summon uses: how many mobs one strike reaches (mobCount,
        // e.g. Bahamut's 3..6) and which monster status the strike carries (the hawks' STUN,
        // Elquines' / Frost Prey's FREEZE). The stored grant is a spawn-time snapshot the Summon
        // entity and its packet byte read; only the strike-level maths needs to track the owner.
        StatEffect effect = skill.getEffect(
                Math.max(1, BotSummonTable.skillLevelForBot(bot.getLevel(), s.skillId, skill.getMaxLevel())));
        Point from = summon.getPosition();
        List<Monster> targets = nearestMobs(bot.getMap(), from, cfg.attackRange(),
                Math.max(1, effect.getMobCount()));
        if (targets.isEmpty()) {
            return;
        }
        // A real client fires the next attack only once the current swing has played out AND the
        // recovery pause has passed, so the cadence differs per summon (a 600ms dragon vs a 2280ms
        // Ifrit). The animation half is cached per skill.
        s.nextAttackAtMs = now + BotSummonStrikeAnimation.animationMs(s.skillId) + cfg.attackRecoveryMs();

        int tier = bot.getJob() != null ? bot.getJob().getJobTier() : 0;
        // One direction byte covers the whole frame, so it follows the summon's primary (nearest) target.
        byte direction = (byte) (targets.get(0).getPosition().x < from.x ? 1 : 0);
        List<BotSummonBroadcast.Strike> hits = new ArrayList<>(targets.size());
        for (Monster target : targets) {
            hits.add(new BotSummonBroadcast.Strike(target.getObjectId(),
                    BotDamageModel.rollLine(tier, bot.getLevel(), Math.max(1, s.spec.attackLines()))));
        }

        try {
            BotSummonBroadcast.summonAttack(bot, summon, direction, hits);
        } catch (Throwable t) {
            log.warn("summon attack broadcast failed cid={}: {}", bot.getId(), t.toString());
        }

        Map<MonsterStatus, Integer> stati = effect.getMonsterStati();
        for (int i = 0; i < targets.size(); i++) {
            Monster target = targets.get(i);
            // Host parity (SummonDamageHandler): the summon's WZ status rides the strike, rolled on
            // the skill's own `prop` (the hawks' 50%..99%, or a flat 100% for Elquines / Frost Prey),
            // and lands BEFORE the damage - same order as the host's handler. Monster.applyStatus
            // refuses an immune/strong/neutral mob (read off its own WZ elemAttr) and refuses every
            // boss, so no resistance test of our own belongs here.
            if (!stati.isEmpty() && effect.makeChanceResult()) {
                target.applyStatus(bot, new MonsterStatusEffect(stati, skill, null, false),
                        effect.isPoison(), SUMMON_DEBUFF_MS);
            }
            // Apply the damage through the shared bot kill/EXP/loot path (its own loot, no vanilla drops).
            BotAttackEffects.applyExternalHit(bot, target, hits.get(i).damage());
        }
    }

    /**
     * Up to {@code limit} live mobs within {@code range} px of the summon, nearest first.
     * {@code limit} may be {@code Integer.MAX_VALUE} to mean "all of them" (the relocate probe's
     * count).
     */
    private static List<Monster> nearestMobs(MapleMap map, Point from, int range, int limit) {
        if (map == null || from == null) {
            return List.of();
        }
        double rangeSq = (double) range * range;
        List<Monster> inRange = new ArrayList<>();
        for (MapObject mo : map.getMapObjectsInRange(from, rangeSq, List.of(MapObjectType.MONSTER))) {
            Monster m = (Monster) mo;
            if (m.isAlive() && m.getPosition() != null) {
                inRange.add(m);
            }
        }
        inRange.sort(Comparator.comparingDouble(m -> from.distanceSq(m.getPosition())));
        if (limit == Integer.MAX_VALUE) {
            return inRange;
        }
        return inRange.size() <= limit ? inRange : inRange.subList(0, limit);
    }
}
