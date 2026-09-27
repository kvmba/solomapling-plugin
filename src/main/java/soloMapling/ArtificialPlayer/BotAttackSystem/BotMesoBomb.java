package soloMapling.ArtificialPlayer.BotAttackSystem;

import org.gms.client.Character;
import org.gms.client.Job;
import org.gms.client.Skill;
import org.gms.client.SkillFactory;
import org.gms.constants.skills.Bandit;
import org.gms.constants.skills.ChiefBandit;
import org.gms.constants.skills.Rogue;
import org.gms.constants.skills.Shadower;
import org.gms.server.StatEffect;
import org.gms.server.life.Monster;
import org.gms.server.maps.MapItem;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.MapObject;
import org.gms.server.maps.MapObjectType;
import org.gms.util.PacketCreator;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/*
 * 独行客的攒袋-引爆循环：敛财术掉袋，金钱炸弹吃袋。
 *
 * The vanilla pair works through the host's damage pipeline: applyAttack drops a meso bag per
 * damage line while the PICKPOCKET buff is up (AbstractDealDamageHandler), and a MESO_EXPLOSION
 * packet consumes exactly those floor bags for damage. Bot attacks never enter that pipeline
 * (BotAttackEffects applies damage directly), so this class mirrors both halves at the points the
 * plugin owns:
 *
 *   - onAttackLanded (wired into BotAttackEffects.broadcastAndApply, next to the combo orbs):
 *     every landed damage line scatters exactly one bag - no chance roll - with the amount
 *     following the WZ curve (damage / 20000 * x, clamped to [1, x]), dropped with the bot as
 *     owner so it reads as the bot's own money on the floor.
 *   - tryDetonate (called from BotAttackDriver's AUTO plan): when enough own bags sit inside the
 *     skill's WZ box, broadcast a real MESO_EXPLOSION close-range packet (the host's own
 *     PacketCreator writes the per-entry bag-count byte for this skill, so observers parse the
 *     exact frame a real player's explosion produces), apply the summed damage through the normal
 *     bot kill/EXP/loot path, and make the consumed bags disappear from the map.
 *
 * The bot never picks its own bags back up: DropCommands.botCanLoot consults isDetonationBag, so
 * the pile survives to be blown up instead of being scooped by the grind loot sweep. A bag that
 * was never detonated simply expires on the floor like any meso drop - no leak either way.
 */
public final class BotMesoBomb {

    /** Bags on the floor before the bot prefers the bomb over its regular swing. */
    private static final int MIN_BAGS_TO_DETONATE = 3;
    /** A bomb needs the throw to read as an action of its own, not every other swing. */
    private static final long DETONATE_COOLDOWN_MS = 1_500L;
    /** Let a fresh bag land (its drop arc plays out) before it can be a fuse. */
    private static final long BAG_MIN_AGE_MS = 1_000L;
    /** Search radius (px) around the bot when collecting candidate bags / mobs for the WZ box. */
    private static final int SCAN_RADIUS_PX = 500;
    /**
     * Per-bot bag register cap. Meso drops expire within minutes, so a register bounded at 64
     * (a few swings' worth) can never grow on a bot that never detonates; eldest oids evict first.
     */
    private static final int BAG_REGISTER_CAP = 64;

    /** Attacks that scatter bags, exactly the host's Pickpocket trigger list (plus the no-skill swing). */
    private static final Set<Integer> TRIGGER_SKILLS = Set.of(0, Rogue.DOUBLE_STAB, Bandit.SAVAGE_BLOW,
            ChiefBandit.ASSAULTER, ChiefBandit.BAND_OF_THIEVES, Shadower.ASSASSINATE, Shadower.TAUNT,
            Shadower.BOOMERANG_STEP);

    // botId -> floor bags this bot owns and may detonate. Written by the bag-drop hook and the
    // detonator (both the bot's single grind tick); the outer map is concurrent because clearBot
    // runs on the bot's lifecycle thread. Bag oids are per-map, so a stale oid after a map change
    // is harmless: eligibility also re-checks the bag's owner and meso-ness below.
    private static final Map<Integer, Set<Integer>> BAGS_BY_BOT = new ConcurrentHashMap<>();
    private static final Map<Integer, Long> NEXT_DETONATE_BY_BOT = new ConcurrentHashMap<>();

    private BotMesoBomb() {}

    /** Result of one detonation, for the driver's attack report. */
    record Blast(int totalDamage, boolean killed) {}

    /*
     * The pickpocket half: called after every landed bot swing with the skill that rendered.
     * Per the rule this loop plays by: EVERY landed damage line scatters exactly one bag - no
     * chance roll (the host's per-line prop roll is deliberately not mirrored; a six-line swing
     * must read as six bags, not a maybe). Amount follows the WZ curve instead: per line,
     * damage / 20000 * x clamped to [1, x], so bag size breathes with the hit while staying
     * pocket change - the payoff was always the explosion, not the crumbs.
     * Runs on the bot's grind tick (single writer for the register).
     */
    public static void onAttackLanded(Character bot, int skillId, Map<Monster, List<Integer>> hits) {
        if (bot == null || bot.getMap() == null || hits == null || hits.isEmpty()) {
            return;
        }
        if (!TRIGGER_SKILLS.contains(skillId) || !bot.getJob().isA(Job.CHIEFBANDIT)) {
            return;
        }
        Skill pickpocket = SkillFactory.getSkill(ChiefBandit.PICKPOCKET);
        if (pickpocket == null) {
            return;
        }
        // Level from the bot's own progression: masterable at 120 (level/6 caps at 20), the same
        // curve the summon system uses to derive a bot's strike level without touching its SP.
        int level = Math.max(1, Math.min(pickpocket.getMaxLevel(), bot.getLevel() / 6));
        StatEffect effect = pickpocket.getEffect(level);
        if (effect == null) {
            return;
        }
        int cap = effect.getX(); // max bag value: 105 at lv1 .. 200 at lv20
        Set<Integer> register = BAGS_BY_BOT.computeIfAbsent(bot.getId(), k -> newBagRegister());
        MapleMap map = bot.getMap();
        int scattered = 0;
        for (Map.Entry<Monster, List<Integer>> hit : hits.entrySet()) {
            Point mobPos = hit.getKey().getPosition();
            if (mobPos == null) {
                continue;
            }
            for (Integer line : hit.getValue()) {
                int dmg = BotAttackData.decodeDamageLine(line);
                if (dmg <= 0) {
                    continue; // MISS lines are not hits - they scatter nothing
                }
                int amount = (int) Math.min(Math.max(dmg / 20000.0 * cap, 1), cap);
                // playerDrop=true keeps the owner lock window (a real bot drop, not a monster
                // drop) and lets the register demand playerDrop, so regular monster meso from a
                // bot kill is never mistaken for a bomb bag and stays lootable.
                map.spawnMesoDrop(amount, mobPos, bot, bot, true, (byte) 0);
                scattered++;
            }
        }
        if (scattered > 0) {
            // spawnMesoDrop assigns oids server-side and returns void, so reconcile by an
            // ownership scan around the bot - the map's object list is the authoritative source.
            registerFloorBags(bot, register);
        }
    }

    /*
     * Ownership-scan the map around the bot and register the bot's own un-picked meso drops.
     * Doing the reconciliation here (instead of tracking each spawnMesoDrop return) keeps the
     * register correct across the map's own spawn path, which returns void.
     */
    private static void registerFloorBags(Character bot, Set<Integer> register) {
        MapleMap map = bot.getMap();
        Point pos = bot.getPosition();
        if (map == null || pos == null) {
            return;
        }
        double radiusSq = (double) SCAN_RADIUS_PX * SCAN_RADIUS_PX;
        for (MapObject mo : map.getMapObjectsInRange(pos, radiusSq, List.of(MapObjectType.ITEM))) {
            MapItem mi = (MapItem) mo;
            if (mi.getMeso() > 0 && mi.isPlayerDrop() && !mi.isPickedUp()
                    && mi.getOwnerId() == bot.getId()) {
                register.add(mi.getObjectId());
            }
        }
    }

    /** True when this floor drop is one of the bot's unexploded bags (blocks its own loot sweep). */
    public static boolean isDetonationBag(int botId, MapItem mi) {
        if (mi == null || mi.getMeso() <= 0) {
            return false;
        }
        Set<Integer> register = BAGS_BY_BOT.get(botId);
        return register != null && register.contains(mi.getObjectId());
    }

    /** True when a landed swing of this skill can scatter Pickpocket bags (the host's trigger set). */
    static boolean isTriggerSkill(int skillId) {
        return TRIGGER_SKILLS.contains(skillId);
    }

    /*
     * The detonation half: called on the driver's AUTO plan before a regular swing. When the bot's
     * own settled bags inside the skill's WZ box reach the threshold, fire the bomb. Returns null
     * when nothing is worth blowing up (the caller swings normally).
     */
    public static Blast tryDetonate(Character bot, boolean facingLeft) {
        if (bot == null || bot.getMap() == null || !bot.getJob().isA(Job.CHIEFBANDIT)) {
            return null;
        }
        Set<Integer> register = BAGS_BY_BOT.get(bot.getId());
        if (register == null || register.isEmpty()) {
            return null;
        }
        long now = System.currentTimeMillis();
        Long next = NEXT_DETONATE_BY_BOT.get(bot.getId());
        if (next != null && now < next) {
            return null;
        }
        Skill skill = SkillFactory.getSkill(ChiefBandit.MESO_EXPLOSION);
        if (skill == null) {
            return null;
        }
        int level = Math.max(1, Math.min(skill.getMaxLevel(), Math.max(1, (bot.getLevel() - 60) / 2)));
        StatEffect effect = skill.getEffect(level);
        if (effect == null) {
            return null;
        }
        Point pos = bot.getPosition();
        if (pos == null) {
            return null;
        }
        // The skill's own WZ box (mirrored by facing), exactly what the host's hit test uses.
        Rectangle box = effect.hasBoundingBox()
                ? effect.calculateBoundingBox(pos, facingLeft)
                : new Rectangle(pos.x - 300, pos.y - 120, 300, 170);

        List<MapItem> bags = ownBagsInBox(bot, register, box, now);
        if (bags.size() < MIN_BAGS_TO_DETONATE) {
            return null;
        }
        List<Monster> mobs = mobsInBox(bot, box, effect.getMobCount());
        if (mobs.isEmpty()) {
            return null; // nothing in the blast - let the regular swing handle targeting
        }

        // Deal every bag to a mob, nearest-mob round-robin; the packet renders one damage line
        // per bag on its mob (the WZ attackCount caps the fuses, mobCount the targets).
        int bagCap = Math.max(1, effect.getAttackCount());
        if (bags.size() > bagCap) {
            bags = bags.subList(0, bagCap);
        }
        int perBag = BotDamageModel.rollLine(bot.getJob().getJobTier(), bot.getLevel(), 6)
                * effect.getX() / 100; // x = % per bag: 500 at lv1 .. 1000 at lv30

        Map<Integer, List<Integer>> mobDamage = new LinkedHashMap<>();
        int idx = 0;
        for (MapItem bag : bags) {
            Monster mob = mobs.get(idx % mobs.size());
            idx++;
            mobDamage.computeIfAbsent(mob.getObjectId(), k -> new ArrayList<>()).add(perBag);
        }

        int maxLines = 0;
        for (List<Integer> lines : mobDamage.values()) {
            maxLines = Math.max(maxLines, lines.size());
        }
        int facingMask = facingLeft ? BotAttackData.FACING_LEFT_MASK : BotAttackData.FACING_RIGHT_MASK;
        // The host's own close-range writer: addAttackBody special-cases 4211006 with the
        // per-entry bag-count byte, so this frame is byte-identical to a real player's explosion,
        // prone2 pose included (BotAttackData.actionFor resolves it).
        bot.getMap().broadcastMessage(bot, PacketCreator.closeRangeAttack(bot,
                ChiefBandit.MESO_EXPLOSION, level, facingMask, (mobDamage.size() << 4) | maxLines,
                mobDamage, BotAttackData.DEFAULT_ATTACK_SPEED,
                BotAttackData.actionFor(ChiefBandit.MESO_EXPLOSION, null), 0), false);
        GCMovement.markAlerted(bot);

        int total = 0;
        boolean killed = false;
        MapleMap map = bot.getMap();
        for (Map.Entry<Integer, List<Integer>> entry : mobDamage.entrySet()) {
            int sum = 0;
            for (Integer dmg : entry.getValue()) {
                sum += dmg;
            }
            total += sum;
            Monster mob = map.getMonsterByOid(entry.getKey());
            if (mob != null && BotAttackEffects.applyExternalHit(bot, mob, sum)) {
                killed = true;
            }
        }
        for (MapItem bag : bags) { // the fuses are gone from the floor
            map.makeDisappearItemFromMap(bag);
            register.remove(bag.getObjectId());
        }
        NEXT_DETONATE_BY_BOT.put(bot.getId(), now + DETONATE_COOLDOWN_MS);
        return new Blast(total, killed);
    }

    /** The bot's own, settled, unpicked meso drops inside the blast box, oldest first. */
    private static List<MapItem> ownBagsInBox(Character bot, Set<Integer> register,
                                              Rectangle box, long now) {
        MapleMap map = bot.getMap();
        Point pos = bot.getPosition();
        List<MapItem> bags = new ArrayList<>();
        double radiusSq = (double) SCAN_RADIUS_PX * SCAN_RADIUS_PX;
        for (MapObject mo : map.getMapObjectsInRange(pos, radiusSq, List.of(MapObjectType.ITEM))) {
            MapItem mi = (MapItem) mo;
            // The playerDrop demand is the second half of the bag test: a bot's own MONSTER meso
            // (regular loot from its kills) must stay lootable and never read as a fuse.
            if (mi.getMeso() <= 0 || !mi.isPlayerDrop() || mi.isPickedUp()
                    || !register.contains(mi.getObjectId())
                    || mi.getOwnerId() != bot.getId()
                    || now - mi.getDropTime() < BAG_MIN_AGE_MS) {
                continue;
            }
            if (box.contains(mi.getPosition())) {
                bags.add(mi);
            }
        }
        return bags;
    }

    /** Alive mobs whose centre sits in the blast box, nearest first, capped at the WZ mobCount. */
    private static List<Monster> mobsInBox(Character bot, Rectangle box, int mobCount) {
        MapleMap map = bot.getMap();
        Point pos = bot.getPosition();
        List<Monster> mobs = new ArrayList<>();
        double radiusSq = (double) SCAN_RADIUS_PX * SCAN_RADIUS_PX;
        for (MapObject mo : map.getMapObjectsInRange(pos, radiusSq, List.of(MapObjectType.MONSTER))) {
            Monster m = (Monster) mo;
            if (m.isAlive() && box.contains(m.getPosition())) {
                mobs.add(m);
            }
        }
        mobs.sort((a, b) -> Double.compare(pos.distanceSq(a.getPosition()), pos.distanceSq(b.getPosition())));
        return mobs.size() <= mobCount ? mobs : mobs.subList(0, mobCount);
    }

    /** Release a despawned bot's bag register and detonation cooldown (the per-bot clearBot hook). */
    public static void clearBot(int botId) {
        BAGS_BY_BOT.remove(botId);
        NEXT_DETONATE_BY_BOT.remove(botId);
    }

    /** Eldest-evicting register: bounded even for a bot that never blows anything up. */
    private static Set<Integer> newBagRegister() {
        return Collections.newSetFromMap(new LinkedHashMap<>(32, 0.75f) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Integer, Boolean> eldest) {
                return size() > BAG_REGISTER_CAP;
            }
        });
    }
}
