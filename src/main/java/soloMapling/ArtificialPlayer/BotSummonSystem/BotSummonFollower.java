package soloMapling.ArtificialPlayer.BotSummonSystem;

import org.gms.client.Character;
import org.gms.constants.game.CharacterStance;
import org.gms.net.opcodes.SendOpcode;
import org.gms.net.packet.OutPacket;
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
 * Drives every bot's summons: one shared tick that repositions FOLLOW / CIRCLE summons near their
 * owner and lets attacking summons strike nearby mobs. STATIONARY summons (the pirate turrets and
 * the archer puppet) are <b>never moved</b> once spawned - the whole point of a turret is that it
 * sits where it was placed.
 *
 * <p>All state lives here in the plugin; the host's {@link Summon} entity is only the serialization
 * surface. The bot learns the summon skill's level (the host's Summon constructor requires it) but
 * <b>no buff is ever registered</b>, so the host's own SUMMON/PUPPET buff lifecycle never runs and
 * the summon lifecycle (spawn / map-change re-spawn / teardown) is owned entirely by this class - it
 * can never leave a ghost entity behind.</p>
 *
 * <p><b>LOD.</b> Movement, attacks and packets are gated on {@link GCMovement#isMapObserved}: while
 * no real player watches the map the tick does nothing but keep the tracked set warm. Spawning is
 * always cheap because {@code MapleMap.spawnSummon} only sends to real observers, and an arriving
 * player is shown the existing entity by the host's own map-placement path.</p>
 */
public final class BotSummonFollower {

    private static final Logger log = LoggerFactory.getLogger(BotSummonFollower.class);

    // Spawn-packet moveAction bytes (CSummoned action table): 0 STAND, 1 MOVE, 2 FLY.
    private static final int ACTION_STAND = 0;
    private static final int ACTION_MOVE = 1;
    private static final int ACTION_FLY = 2;

    // v83 MovePath fragment command 0 = normal/absolute movement (matches AbsoluteLifeMovement).
    private static final int MOVE_CMD_NORMAL = 0;

    // botId -> that bot's live summons.
    private static final Map<Integer, List<BotSummon>> TRACKED = new ConcurrentHashMap<>();

    private static volatile BotSummonConfig config = BotSummonConfig.defaults();
    private static ScheduledFuture<?> task;

    private BotSummonFollower() {}

    public static synchronized void start(BotSummonConfig cfg) {
        config = cfg;
        if (task != null) {
            return;
        }
        long period = Math.max(100L, cfg.followTickMs());
        task = soloMapling.server.ExecutorServiceManager.getScheduledExecutorService()
                .scheduleAtFixedRate(BotSummonFollower::tick, period, period, TimeUnit.MILLISECONDS);
        System.out.println("[BotSummonFollower] started, period=" + period + "ms");
    }

    public static synchronized void stop() {
        if (task != null) {
            task.cancel(false);
            task = null;
        }
        // Best-effort teardown of anything still tracked, so a reload never leaves ghosts.
        for (Integer botId : new ArrayList<>(TRACKED.keySet())) {
            Character bot = BotHelpers.getCharFromChannelStorage(botId);
            List<BotSummon> summons = TRACKED.get(botId);
            if (bot != null && summons != null) {
                for (BotSummon s : summons) {
                    removeEntity(bot, s);
                }
            }
        }
        TRACKED.clear();
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
        if (spec.move() == BotSummonTable.Move.CIRCLE) {
            s.angleDeg = ThreadLocalRandom.current().nextDouble() * 360.0;
        }
        TRACKED.computeIfAbsent(botId, k -> new ArrayList<>()).add(s);
    }

    /** Forget a bot's tracking without touching the host (used when the bot is already gone). */
    public static void forget(int botId) {
        TRACKED.remove(botId);
    }

    /** Tear every tracked summon down and stop tracking the bot. Safe to call repeatedly. */
    public static void despawnAll(Character bot) {
        if (bot == null) {
            return;
        }
        List<BotSummon> summons = TRACKED.remove(bot.getId());
        if (summons == null) {
            return;
        }
        for (BotSummon s : summons) {
            removeEntity(bot, s);
        }
        // Drop the entries from the owner's summon map too, so the host's own leave-map path sees an
        // empty collection (no else-branch removeMapObject on an already-removed object).
        bot.clearSummons();
    }

    /** Remove a single summon's host entity + packets (never throws). */
    private static void removeEntity(Character bot, BotSummon s) {
        Summon summon = s.summon;
        if (summon == null) {
            return;
        }
        try {
            MapleMap map = s.map;
            if (map != null) {
                map.broadcastMessage(PacketCreator.removeSummon(summon, true), summon.getPosition());
                map.removeMapObject(summon);
                if (summon.isPuppet()) {
                    map.removePlayerPuppet(bot); // undo the aggro the puppet attracted
                }
            }
            bot.removeVisibleMapObject(summon);
        } catch (Throwable t) {
            log.warn("summon teardown failed cid={} skill={}: {}", bot.getId(), s.skillId, t.toString());
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
            // The bot is gone; the host removed its map objects with it. Drop our state.
            TRACKED.remove(botId);
            return;
        }

        boolean observed = GCMovement.isMapObserved(bot.getMapId());
        MapleMap botMap = bot.getMap();
        // Snapshot: a spawn on the bot's lifecycle thread may append to this list concurrently.
        for (BotSummon s : new ArrayList<>(summons)) {
            if (s.summon == null) {
                continue;
            }
            // Map-change re-homing is pure cleanup + spawn and MUST run whether or not the map is
            // observed: a stationary summon is not removed by the host's own leave-map path, so if
            // we skipped this on an unobserved map the entity would ghost on the old map until a
            // player walked in. Spawning is cheap (it only sends to real observers).
            if (s.map != botMap) {
                handleMapChange(bot, s);
                continue; // a movement packet would race the fresh spawn
            }
            // Movement, attacks and their packets only run on an observed map (the LOD gate).
            if (!observed) {
                continue;
            }
            // A stationary summon (turret / puppet) is never repositioned; only a follow/circle one
            // glides toward its owner. shouldTickMovement is the single expression of that rule.
            if (shouldTickMovement(s.isStationary(), observed)) {
                moveFollow(bot, s, cfg);
            }
            if (s.attacks()) {
                tryAttack(bot, s, cfg);
            }
        }
    }

    /**
     * The owner warped to another map. Re-home the summon there: drop it off the old map (a
     * stationary summon is NOT removed by the host's own leave-map path, so this must happen here)
     * and spawn it fresh at the owner's feet on the new map.
     */
    private static void handleMapChange(Character bot, BotSummon s) {
        Summon summon = s.summon;
        MapleMap newMap = bot.getMap();
        boolean puppet = summon.isPuppet();
        try {
            if (s.map != null) {
                // The host's own leave-map path sends NO remove packet for a summon, so the old
                // map's observers would keep a ghost. Broadcast the removal here, then drop it.
                s.map.broadcastMessage(PacketCreator.removeSummon(summon, true), summon.getPosition());
                s.map.removeMapObject(summon);
                if (puppet) {
                    s.map.removePlayerPuppet(bot); // drop the old map's puppet aggro
                }
            }
            summon.setPosition(new Point(bot.getPosition()));
            newMap.spawnSummon(summon);
            if (puppet) {
                newMap.addPlayerPuppet(bot); // re-attract aggro on the new map
            }
            s.map = newMap;
        } catch (Throwable t) {
            log.warn("summon re-home failed cid={} skill={}: {}", bot.getId(), s.skillId, t.toString());
            s.map = newMap;
        }
    }

    /**
     * Reposition a FOLLOW / CIRCLE summon at a point derived from the owner. The summon is set to the
     * target directly (clients interpolate between the positions we send) and the move is broadcast so
     * observers see it glide; a summon left implausibly far behind (owner just teleported) is snapped
     * straight to the target instead of creeping across the map.
     */
    private static void moveFollow(Character bot, BotSummon s, BotSummonConfig cfg) {
        Point cur = s.summon.getPosition();
        Point target = targetFor(bot, s, cfg);

        boolean warped = cur.distance(target) > cfg.warpDistance();
        if (!warped && cur.distanceSq(target) < 1.0) {
            return; // already there; nothing worth a packet
        }

        int dx = target.x - cur.x;
        int dy = target.y - cur.y;
        long tick = Math.max(1L, cfg.followTickMs());
        int vx = clampVelocity(dx * 1000L / tick);
        int vy = clampVelocity(dy * 1000L / tick);

        int fh = 0;
        int action = ACTION_MOVE;
        if (!s.spec.airborne()) {
            Foothold ground = GCMovement.footholdBelow(bot.getMap(), target.x, target.y);
            if (ground != null) {
                target = new Point(target.x, ground.calculateFooting(target.x));
                fh = ground.getId();
            }
        } else {
            action = ACTION_FLY;
        }
        if (Math.abs(dx) <= 2 && Math.abs(dy) <= 2) {
            action = s.spec.airborne() ? ACTION_FLY : ACTION_STAND;
        }

        Point from = cur;
        s.summon.setPosition(new Point(target));
        broadcastMove(bot, s.summon, from, target, vx, vy, fh, action, (int) tick);
    }

    private static int clampVelocity(long v) {
        return (int) Math.max(-2000, Math.min(2000, v));
    }

    private static Point targetFor(Character bot, BotSummon s, BotSummonConfig cfg) {
        Point owner = bot.getPosition();
        if (s.spec.move() == BotSummonTable.Move.CIRCLE) {
            s.angleDeg += cfg.circleStepDeg();
            double rad = Math.toRadians(s.angleDeg);
            return new Point(
                    owner.x + (int) Math.round(Math.cos(rad) * cfg.circleRadius()),
                    owner.y + (int) Math.round(Math.sin(rad) * cfg.circleRadius()));
        }
        boolean left = CharacterStance.isFacingLeft(bot.getStance());
        int offX = cfg.followOffsetX();
        return new Point(owner.x + (left ? -offX : offX), owner.y + cfg.followOffsetY());
    }

    /** Emit one MOVE_SUMMON carrying a single absolute-move fragment. */
    private static void broadcastMove(Character bot, Summon summon, Point start, Point dest,
                                      int vx, int vy, int fh, int moveAction, int durationMs) {
        OutPacket p = OutPacket.create(SendOpcode.MOVE_SUMMON);
        p.writeInt(bot.getId());
        p.writeInt(summon.getObjectId());
        p.writePos(start);
        p.writeByte(1);                    // one movement fragment
        p.writeByte(MOVE_CMD_NORMAL);      // absolute move
        p.writePos(dest);
        p.writePos(new Point(vx, vy));     // pixels-per-second wobble
        p.writeShort(fh);
        p.writeByte(moveAction);
        p.writeShort(durationMs);
        bot.getMap().broadcastMessage(bot, p, summon.getPosition());
    }

    private static void tryAttack(Character bot, BotSummon s, BotSummonConfig cfg) {
        long now = System.currentTimeMillis();
        if (now < s.nextAttackAtMs) {
            return;
        }
        Point from = s.summon.getPosition();
        Monster target = nearestMob(bot.getMap(), from, cfg.attackRange());
        if (target == null) {
            return;
        }
        s.nextAttackAtMs = now + cfg.attackTickMs();

        int tier = bot.getJob() != null ? bot.getJob().getJobTier() : 0;
        int damage = BotDamageModel.rollLine(tier, bot.getLevel(), Math.max(1, s.spec.attackLines()));
        byte direction = (byte) (target.getPosition().x < from.x ? 1 : 0);

        try {
            BotSummonBroadcast.summonAttack(bot, s.summon, direction, target.getObjectId(), damage);
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

    /* Package-visible seam for unit tests. */

    /** A stationary summon is never repositioned; a moving one only moves when the map is observed. */
    static boolean shouldTickMovement(boolean stationary, boolean observed) {
        return !stationary && observed;
    }
}
