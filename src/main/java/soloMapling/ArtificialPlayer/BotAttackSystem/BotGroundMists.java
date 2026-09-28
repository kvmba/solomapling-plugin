package soloMapling.ArtificialPlayer.BotAttackSystem;

import org.gms.client.Character;
import org.gms.client.Job;
import org.gms.client.Skill;
import org.gms.client.SkillFactory;
import org.gms.constants.skills.FPMage;
import org.gms.server.StatEffect;
import org.gms.server.life.Monster;
import org.gms.server.maps.Mist;
import org.gms.server.maps.MapObject;
import org.gms.server.maps.MapObjectType;
import org.gms.util.PacketCreator;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/*
 * The F/P mage's Poison Mist (致命毒雾, 2111003): the green cloud, mirrored on the host's own
 * mist pipeline. The vanilla cast rides MAGIC_ATTACK; because the skill is a "mist"
 * (StatEffect.isMist), applyTo also spawns a real Mist map object around the caster, and
 * MapleMap.spawnMist hands it to the host's own poison scheduler - a 2.5s tick that rolls the
 * WZ prop and applies the POISON monster status, whose DamageTask drains
 * maxHp / (70 - poisonLevel) per second (Monster.applyStatus's poison branch; bosses are
 * refused there). None of that is re-implemented here: spawning the Mist through the host's
 * spawnMist runs the exact code a player's cloud runs on.
 *
 * The cast gate is the bot's OWN skill level: a bot that has not learned 2111003 never casts.
 * Persistent companions learn it in their v83 SP build; ambient bots spend no SP and stay
 * silent rather than being handed a fake level into a persisted skill map. Mist.makeSpawnData
 * reads the owner's real skill level for the spawn packet, so the cloud renders at the level
 * its caster actually holds - which is also why the gate exists.
 *
 * Recast waits out the cloud's own lifetime (the WZ time of the level held): a mage does not
 * stack two mists on one spot.
 */
public final class BotGroundMists {

    /** Reach cap when collecting candidates - same scan radius BotMesoBomb uses. */
    private static final int SCAN_RADIUS_PX = 500;

    private static final Map<Integer, Long> NEXT_MIST_BY_BOT = new ConcurrentHashMap<>();

    private BotGroundMists() {}

    /* Release a despawned bot's recast timer (mirrors the other per-bot clearBot hooks). */
    public static void clearBot(int botId) {
        NEXT_MIST_BY_BOT.remove(botId);
    }

    /*
     * Mist beat: off cooldown, learned, with a mob inside the skill's WZ box turns the F/P
     * mage's next AUTO swing into the green cloud. Returns null when nothing was cast (the
     * caller swings normally) - the same contract as BotMesoBomb.tryDetonate.
     */
    public static BotMesoBomb.Blast tryCast(Character bot, boolean facingLeft) {
        if (bot == null || bot.getMap() == null || bot.getJob() == null
                || !bot.getJob().isA(Job.FP_MAGE)) {
            return null;
        }
        Skill skill = SkillFactory.getSkill(FPMage.POISON_MIST);
        if (skill == null) {
            return null;
        }
        // Level derives from the bot's SP budget (BotShadowMeso's rule): learnable the day 3rd job
        // opens (70), then 3 SP per level. Ambient bots never register skills server-side, so
        // reading getSkillLevel here (the companion's own proof-of-purchase) would lock every
        // ambient F/P mage out of its signature cloud - the derivation lets the whole job show it.
        int level = Math.max(1, Math.min(skill.getMaxLevel(), Math.max(1, (bot.getLevel() - 70) * 3)));
        long now = System.currentTimeMillis();
        Long next = NEXT_MIST_BY_BOT.get(bot.getId());
        if (next != null && now < next) {
            return null;
        }
        StatEffect effect = skill.getEffect(level);
        if (effect == null) {
            return null;
        }

        // The skill's own WZ box (mirrored by facing) - the same box the host's applyTo hands
        // the Mist. Fallback covers a WZ row without one.
        Point pos = bot.getPosition();
        if (pos == null) {
            return null;
        }
        Rectangle box = effect.hasBoundingBox()
                ? effect.calculateBoundingBox(pos, facingLeft)
                : new Rectangle(pos.x - 110, pos.y - 82, 220, 165);
        List<Monster> targets = mobsInBox(bot, box);
        if (targets.isEmpty()) {
            return null; // nothing to poison - let the regular swing handle targeting
        }

        // An attack is never made from the saddle, and the hide auras break the moment the
        // cast key is pressed - the driver's own rule, applied once the cast is committed (the
        // early-return beats above spent nothing, so a null return never tears a disguise off).
        soloMapling.ArtificialPlayer.BotMountSystem.BotMount.cancelForAction(bot);
        BotAuraState.cancelHidesForAction(bot);

        // The cast's cosmetic damage, one line per mob - the host's mist cast is a magic
        // attack first (the cloud is the aftermath); bot damage reads like every other swing.
        Map<Integer, List<Integer>> mobDamage = new LinkedHashMap<>();
        int total = 0;
        for (Monster mob : targets) {
            int dmg = BotDamageModel.rollLine(bot.getJob().getJobTier(), bot.getLevel(), 1);
            mobDamage.put(mob.getObjectId(), List.of(dmg));
            total += dmg;
        }

        int facingMask = facingLeft ? BotAttackData.FACING_LEFT_MASK : BotAttackData.FACING_RIGHT_MASK;
        // The host's own magic writer - the same frame a real F/P mage's mist cast produces.
        bot.getMap().broadcastMessage(bot, PacketCreator.magicAttack(bot,
                FPMage.POISON_MIST, level, facingMask, (mobDamage.size() << 4) | 1,
                mobDamage, BotAttackData.magicChargeFor(FPMage.POISON_MIST),
                BotAttackData.DEFAULT_ATTACK_SPEED,
                BotAttackData.actionFor(FPMage.POISON_MIST, null), 0), false);
        GCMovement.markAlerted(bot);

        // The cloud: the host's own Mist object through the host's own spawnMist - the poison
        // scheduler (2.5s prop rolls, the per-second drain, the expiry broadcast) is the host's
        // from here. Duration is the WZ time of the level held, already in ms (skill times are
        // stored in seconds and x1000 by loadFromData).
        bot.getMap().spawnMist(new Mist(box, bot, effect), effect.getDuration(),
                true, false, false);

        // Land the cast's damage through the ordinary external-hit path (kill / EXP / loot).
        boolean killed = false;
        for (Monster mob : targets) {
            if (BotAttackEffects.applyExternalHit(bot, mob, mobDamage.get(mob.getObjectId()).get(0))) {
                killed = true;
            }
        }

        // Recast waits out the cloud's lifetime plus a breath - no double-misting one spot.
        NEXT_MIST_BY_BOT.put(bot.getId(), now + effect.getDuration() + 1_000L);
        return new BotMesoBomb.Blast(total, killed);
    }

    /* Live mobs inside the box, nearest first - the BotMesoBomb collection idiom. */
    private static List<Monster> mobsInBox(Character bot, Rectangle box) {
        Point pos = bot.getPosition();
        List<Monster> mobs = new ArrayList<>();
        for (MapObject mo : bot.getMap().getMapObjectsInRange(pos,
                (double) SCAN_RADIUS_PX * SCAN_RADIUS_PX, List.of(MapObjectType.MONSTER))) {
            Monster m = (Monster) mo;
            if (m.isAlive() && box.contains(m.getPosition())) {
                mobs.add(m);
            }
        }
        mobs.sort((a, b) -> Double.compare(pos.distanceSq(a.getPosition()), pos.distanceSq(b.getPosition())));
        return mobs;
    }
}
