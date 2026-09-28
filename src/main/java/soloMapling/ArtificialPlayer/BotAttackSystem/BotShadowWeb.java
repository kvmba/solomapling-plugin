package soloMapling.ArtificialPlayer.BotAttackSystem;

import org.gms.client.Character;
import org.gms.client.Job;
import org.gms.client.Skill;
import org.gms.client.SkillFactory;
import org.gms.client.status.MonsterStatus;
import org.gms.client.status.MonsterStatusEffect;
import org.gms.constants.skills.Hermit;
import org.gms.server.StatEffect;
import org.gms.server.life.Monster;
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
import java.util.concurrent.ConcurrentHashMap;

/*
 * The Hermit's Shadow Web (影网术, 4111003): a melee burst that webs the pack in place,
 * mirrored on the host's own attack pipeline. The vanilla cast rides CLOSE_RANGE_ATTACK
 * (Skill.wz action "swingO1"), and the damage handler's generic branch
 * (AbstractDealDamageHandler: roll attackEffect.makeChanceResult() per mob and
 * monster.applyStatus(attackEffect.getMonsterStati(), ...)) applies the SHADOW_WEB monster
 * status on each hit mob. Everything the status does after that - its packets, its expiry and
 * whatever upkeep the host wires for SHADOW_WEB - is Monster.applyStatus's code; the bot only
 * applies the status and adds nothing there. Bosses are refused by applyStatus's own gate.
 *
 * The cast gate is the bot's OWN skill level, for the same reason BotGroundMists gates on a
 * learned mist: a bot that never spent SP on the web does not cast one, and the web's WZ row
 * (its 5-8s hold time) is read at the level actually held.
 */
public final class BotShadowWeb {

    /** Reach cap when collecting candidates - same scan radius BotMesoBomb uses. */
    private static final int SCAN_RADIUS_PX = 500;
    private static final Map<Integer, Long> NEXT_WEB_BY_BOT = new ConcurrentHashMap<>();

    private BotShadowWeb() {}

    /* Release a despawned bot's recast timer (mirrors the other per-bot clearBot hooks). */
    public static void clearBot(int botId) {
        NEXT_WEB_BY_BOT.remove(botId);
    }

    /*
     * Web beat: off cooldown, learned, with mobs inside the skill's WZ box turns the Hermit's
     * next AUTO swing into the burst. Returns null when nothing was cast (the caller swings
     * normally) - the same contract as BotMesoBomb.tryDetonate.
     */
    public static BotMesoBomb.Blast tryCast(Character bot, boolean facingLeft) {
        if (bot == null || bot.getMap() == null || bot.getJob() == null
                || !bot.getJob().isA(Job.HERMIT)) {
            return null;
        }
        Skill skill = SkillFactory.getSkill(Hermit.SHADOW_WEB);
        if (skill == null) {
            return null;
        }
        // Level derives from the bot's SP budget (BotShadowMeso's rule): learnable the day 3rd job
        // opens (70), then 3 SP per level. Ambient bots never register skills server-side, so
        // reading getSkillLevel here (the companion's own proof-of-purchase) would lock every
        // ambient Hermit out of its signature web - the derivation lets the whole job show it.
        int level = Math.max(1, Math.min(skill.getMaxLevel(), Math.max(1, (bot.getLevel() - 70) * 3)));
        long now = System.currentTimeMillis();
        Long next = NEXT_WEB_BY_BOT.get(bot.getId());
        if (next != null && now < next) {
            return null;
        }
        StatEffect effect = skill.getEffect(level);
        if (effect == null || effect.getMobCount() <= 0) {
            return null;
        }

        // The skill's own WZ box (mirrored by facing; lt -200..rb 0 reads left-handed, which
        // the host's facing-mirrored box already normalises).
        Point pos = bot.getPosition();
        if (pos == null) {
            return null;
        }
        Rectangle box = effect.hasBoundingBox()
                ? effect.calculateBoundingBox(pos, facingLeft)
                : new Rectangle(pos.x - 200, pos.y - 120, 200, 120);
        MapleMap map = bot.getMap();
        List<Monster> mobs = new ArrayList<>();
        for (MapObject mo : map.getMapObjectsInRange(pos, (double) SCAN_RADIUS_PX * SCAN_RADIUS_PX,
                List.of(MapObjectType.MONSTER))) {
            Monster m = (Monster) mo;
            if (m.isAlive() && box.contains(m.getPosition())) {
                mobs.add(m);
            }
        }
        if (mobs.isEmpty()) {
            return null; // nothing to web - let the regular swing handle targeting
        }
        mobs.sort((a, b) -> Double.compare(pos.distanceSq(a.getPosition()), pos.distanceSq(b.getPosition())));
        int mobCount = effect.getMobCount();
        List<Monster> targets = mobs.size() <= mobCount ? mobs : mobs.subList(0, mobCount);

        // An attack is never made from the saddle, and the hide auras break the moment the
        // attack key is pressed - the driver's own rule, once the cast is committed.
        soloMapling.ArtificialPlayer.BotMountSystem.BotMount.cancelForAction(bot);
        BotAuraState.cancelHidesForAction(bot);

        // The burst's cosmetic damage - one line per mob, bot damage like every other swing.
        Map<Integer, List<Integer>> mobDamage = new LinkedHashMap<>();
        int total = 0;
        for (Monster mob : targets) {
            int dmg = BotDamageModel.rollLine(bot.getJob().getJobTier(), bot.getLevel(), 1);
            mobDamage.put(mob.getObjectId(), List.of(dmg));
            total += dmg;
        }

        int facingMask = facingLeft ? BotAttackData.FACING_LEFT_MASK : BotAttackData.FACING_RIGHT_MASK;
        // The host's own melee writer with the web's swingO1 pose.
        map.broadcastMessage(bot, PacketCreator.closeRangeAttack(bot,
                Hermit.SHADOW_WEB, level, facingMask, (mobDamage.size() << 4) | 1,
                mobDamage, BotAttackData.DEFAULT_ATTACK_SPEED,
                BotAttackData.actionFor(Hermit.SHADOW_WEB, null), 0), false);
        GCMovement.markAlerted(bot);

        // Per-mob status roll, the host's generic attack branch: chance first (the WZ prop,
        // 42-46% by level), then applyStatus with the effect's own monster statuses. The web
        // lands as a plain timed status - this host's SHADOW_WEB drain branch is commented out
        // (Monster.applyStatus), so a player's web shows and expires exactly the same way.
        // Bosses are refused by applyStatus's own gate.
        boolean killed = false;
        for (Monster mob : targets) {
            if (BotAttackEffects.applyExternalHit(bot, mob, mobDamage.get(mob.getObjectId()).get(0))) {
                killed = true;
            }
            if (!mob.isAlive()) {
                continue; // a dead mob takes no web
            }
            if (effect.makeChanceResult()) {
                mob.applyStatus(bot, new MonsterStatusEffect(
                        Collections.singletonMap(MonsterStatus.SHADOW_WEB, 1), skill, null, false),
                        false, effect.getDuration());
            }
        }

        // Recast waits out the web's hold plus a breath.
        NEXT_WEB_BY_BOT.put(bot.getId(), now + effect.getDuration() + 1_000L);
        return new BotMesoBomb.Blast(total, killed);
    }
}
