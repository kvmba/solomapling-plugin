package soloMapling.ArtificialPlayer.BotSummonSystem;

import org.gms.client.Character;
import org.gms.server.life.Monster;
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
import java.util.concurrent.TimeUnit;

/**
 * Drives every bot's summons. The v83 server never moves a summon itself - a real client animates a
 * summon's follow/orbit from the spawn packet's movementType, and the host's {@code MoveSummonHandler}
 * only echoes the client's own MOVE_SUMMON frames. A headless bot has no client, so this class does
 * the two things the CLIENT used to do that the server must not:
 *
 * <ul>
 *   <li><b>spawn / re-place the entity</b> - at the owner's feet on grant, and re-spawned (a fresh
 *       entity at a new object id) when the owner warps, so the new map's observers see it appear
 *       and the old map's see it leave. Stationary turrets are never re-placed while the owner stays,
 *       so they sit exactly where cast.</li>
 *   <li><b>attack</b> - an attacking summon picks the nearest mob within range and fires a
 *       SUMMON_ATTACK (rendered by the client) whose damage lands through the shared bot kill path.</li>
 * </ul>
 *
 * <p>It deliberately sends NO movement packets: a server-built {@code MOVE_SUMMON} frame would be
 * consumed as a client-origin movement stream, not authored, so sending one risks a client mis-parse
 * for no benefit (the client already animates the follow/orbit from the spawn movementType).</p>
 *
 * <p>All state lives here in the plugin. The bot learns the summon skill's level (the host
 * {@link Summon} constructor requires it) but <b>no host buff is ever registered</b>, so the host's
 * own SUMMON/PUPPET buff lifecycle is never involved; this class owns spawn and teardown outright.</p>
 *
 * <p><b>LOD.</b> Attacks are gated on {@link GCMovement#isMapObserved}: while no real player watches
 * the map nothing is broadcast. Map re-homing is cheap and runs unconditionally so a turret can never
 * ghost on a map the owner left.</p>
 */
public final class BotSummonFollower {

    private static final Logger log = LoggerFactory.getLogger(BotSummonFollower.class);

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
        long period = Math.max(100L, cfg.attackTickMs() / 2); // poll faster than the attack cadence
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
        TRACKED.computeIfAbsent(botId, k -> new ArrayList<>()).add(s);
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
        // The bot's own summon map is never populated (we do not call addSummon - the entity is
        // owned here), so the host's leave-map path already sees an empty collection and does
        // nothing for it. Removing the entities above is the whole teardown.
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
                // The host's own leave-map path sends NO remove packet for a summon, so the old
                // map's observers would keep a ghost. Broadcast the removal, then drop it.
                map.broadcastMessage(PacketCreator.removeSummon(summon, true), summon.getPosition());
                map.removeMapObject(summon);
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
            // Re-homing is pure cleanup + spawn and MUST run whether or not the map is observed: a
            // stationary summon is not removed by the host's own leave-map path, so skipping it on an
            // unobserved map would ghost the entity on the old map until a player walked in.
            if (s.map != botMap) {
                handleMapChange(bot, s);
                continue;
            }
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
            case CIRCLE -> org.gms.server.maps.SummonMovementType.CIRCLE_FOLLOW;
            case FOLLOW -> org.gms.server.maps.SummonMovementType.FOLLOW;
        };
        Point pos = spawnPosition(bot, map);
        Summon summon = new Summon(bot, skillId, pos, moveType);
        summon.setStance(0); // nMoveAction STAND; the client animates from there
        map.spawnSummon(summon);
        return summon;
    }

    private static Point spawnPosition(Character bot, MapleMap map) {
        Point owner = bot.getPosition();
        org.gms.server.maps.Foothold ground = GCMovement.footholdBelow(map, owner.x, owner.y);
        int y = ground != null ? ground.calculateFooting(owner.x) : owner.y;
        return new Point(owner.x, y);
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
}
