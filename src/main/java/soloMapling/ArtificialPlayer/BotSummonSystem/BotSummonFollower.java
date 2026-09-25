package soloMapling.ArtificialPlayer.BotSummonSystem;

import org.gms.client.Character;
import org.gms.constants.game.CharacterStance;
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
 * buff lifecycle is never involved; this class owns spawn, movement and teardown outright.</p>
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
    static final int FOLLOW_MIN_PX = 16;
    /** Follow distance cap (px) - a tight flight ring, held stable per summon. */
    static final int FOLLOW_MAX_PX = 36;
    /** Idle jitter dead zone (px): slot moves inside this are ignored, exactly like the pet's. */
    static final int FOLLOW_DEAD_ZONE_PX = 15;

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

    private static volatile BotSummonConfig config = BotSummonConfig.defaults();
    private static ScheduledFuture<?> task;

    private BotSummonFollower() {}

    public static synchronized void start(BotSummonConfig cfg) {
        config = cfg;
        if (task != null) {
            return;
        }
        long period = Math.max(100L, cfg.moveTickMs());
        task = soloMapling.server.ExecutorServiceManager.getScheduledExecutorService()
                .scheduleAtFixedRate(BotSummonFollower::tick, period, period, TimeUnit.MILLISECONDS);
        System.out.println("[BotSummonFollower] started, period=" + period + "ms");
    }

    public static synchronized void stop() {
        if (task != null) {
            task.cancel(false);
            task = null;
        }
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
        synchronized (LIFECYCLE_LOCK) {
            TRACKED.computeIfAbsent(botId, k -> new ArrayList<>()).add(s);
        }
    }

    /** Tear every tracked summon down and stop tracking the bot. Safe to call repeatedly. */
    public static void despawnAll(Character bot) {
        if (bot == null) {
            return;
        }
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
        for (Integer botId : new ArrayList<>(TRACKED.keySet())) {
            try {
                tickBot(botId, cfg);
            } catch (Throwable t) {
                // Per-bot isolation, mirroring GrindTickRegistry: one bad summon never stops the sweep.
                log.warn("summon tick failed cid={}: {}", botId, t.toString());
            }
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
            if (s.attacks() && observed) {
                tryAttack(bot, s, cfg);
            }
        }
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
        Point pos = spawnPosition(bot, spec, map);
        Summon summon = new Summon(bot, skillId, pos, moveType);
        // nMoveAction with the facing bit: the client mirrors the sprite from bit 0, so a bird
        // spawned beside a left-facing owner must spawn facing left too (it was stamped 0 - right -
        // regardless of the owner, which is what the "always faces right" report showed).
        summon.setStance(spec.isStationary() ? standAction(left) : flyAction(left));
        map.spawnSummon(summon);
        return summon;
    }

    /** Where the entity appears: trailing the owner for a flyer, on the ground for a turret. */
    private static Point spawnPosition(Character bot, BotSummonTable.Spec spec, MapleMap map) {
        Point owner = bot.getPosition();
        if (!spec.isStationary()) {
            // A flyer materialises BEHIND the owner at its follow ring (the same slot the
            // follower's tick will hold it at), high above as the official bird rides.
            boolean facingLeft = CharacterStance.isFacingLeft(bot.getStance());
            int dist = freshFollowDistancePx();
            int x = facingLeft ? owner.x + dist : owner.x - dist;
            return new Point(x, owner.y + config.hoverOffsetY());
        }
        Foothold ground = GCMovement.footholdBelow(map, owner.x, owner.y);
        int y = ground != null ? ground.calculateFooting(owner.x) : owner.y;
        return new Point(owner.x, y);
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
        Point from = summon.getPosition();
        Monster target = nearestMob(bot.getMap(), from, cfg.attackRange());
        if (target == null) {
            return;
        }
        s.nextAttackAtMs = now + cfg.attackTickMs();

        int tier = bot.getJob() != null ? bot.getJob().getJobTier() : 0;
        int damage = BotDamageModel.rollLine(tier, bot.getLevel(), Math.max(1, s.spec.attackLines()));
        byte direction = (byte) (target.getPosition().x < from.x ? 1 : 0);

        try {
            BotSummonBroadcast.summonAttack(bot, summon, direction, target.getObjectId(), damage);
        } catch (Throwable t) {
            log.warn("summon attack broadcast failed cid={}: {}", bot.getId(), t.toString());
        }
        // Apply the damage through the shared bot kill/EXP/loot path (its own loot, no vanilla drops).
        BotAttackEffects.applyExternalHit(bot, target, damage);
    }

    /** Closest live mob within {@code range} px of the summon, or null. */
    private static Monster nearestMob(MapleMap map, Point from, int range) {
        if (map == null || from == null) {
            return null;
        }
        double rangeSq = (double) range * range;
        Monster nearest = null;
        double bestSq = Double.MAX_VALUE;
        for (MapObject mo : map.getMapObjectsInRange(from, rangeSq, List.of(MapObjectType.MONSTER))) {
            Monster m = (Monster) mo;
            if (!m.isAlive() || m.getPosition() == null) {
                continue;
            }
            double dsq = from.distanceSq(m.getPosition());
            if (dsq < bestSq) {
                bestSq = dsq;
                nearest = m;
            }
        }
        return nearest;
    }
}
