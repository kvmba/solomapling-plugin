package soloMapling.ArtificialPlayer.BotAttackSystem;

import org.gms.client.Character;
import org.gms.client.Job;
import org.gms.client.Skill;
import org.gms.client.SkillFactory;
import org.gms.client.inventory.WeaponType;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/*
 * The Hermit's Shadow Meso (金钱攻击, 4111004): a server-side mirror of the host's ranged
 * pipeline. The vanilla skill rides RANGED_ATTACK: the host forces bulletCount = 0 (no star
 * spent or rendered - the flying sprites are the WZ "ball" coin canvases the client draws
 * itself), charges the skill's moneyCon meso with a small random top-up (RangedAttackHandler:
 * money += nextInt(money / 2)), clamps the charge at the wallet and throws even when broke,
 * and the damage handler prices the single line at moneyCon * 10 * 1.5
 * (AbstractDealDamageHandler's SHADOW_MESO branch). The WZ row carries no mobCount, so the
 * host's StatEffect parse defaults it to 1 - one line on the nearest mob. It also tags the hit
 * mob with debuffMob, skipped here: it feeds no visual a bot needs. A bot has no client to
 * produce that packet, so this class authors the same frame server-side: projectile 0, the
 * same charge/clamp/price rules, the hit applied through the ordinary external-hit path
 * (kill / EXP / loot). The skill has no Skill.wz action node, so the pose resolves through the
 * weapon default (BotAttackData.actionFor) - exactly what a real client plays for a claw
 * throw with no override.
 */
public final class BotShadowMeso {

    /** Recast cadence - reads as a planned throw, not every other swing. */
    private static final long SHADOW_MESO_COOLDOWN_MS = 1_500L;
    /** Reach cap when collecting candidates - same scan radius BotMesoBomb uses. */
    private static final int SCAN_RADIUS_PX = 500;

    private static final Map<Integer, Long> NEXT_THROW_BY_BOT = new ConcurrentHashMap<>();

    private BotShadowMeso() {}

    /* Release a despawned bot's throw cooldown (mirrors the other per-bot clearBot hooks). */
    public static void clearBot(int botId) {
        NEXT_THROW_BY_BOT.remove(botId);
    }

    /*
     * Shadow Meso beat: off cooldown with a mob inside the skill's WZ box turns the Hermit's
     * next AUTO swing into a coin throw. Returns null when nothing was thrown (the caller
     * swings normally) - the same contract as BotMesoBomb.tryDetonate.
     */
    public static BotMesoBomb.Blast tryThrow(Character bot, WeaponType weapon, boolean facingLeft) {
        if (bot == null || bot.getMap() == null || bot.getJob() == null
                || !bot.getJob().isA(Job.HERMIT)) {
            return null;
        }
        long now = System.currentTimeMillis();
        Long next = NEXT_THROW_BY_BOT.get(bot.getId());
        if (next != null && now < next) {
            return null;
        }
        Skill skill = SkillFactory.getSkill(Hermit.SHADOW_MESO);
        if (skill == null) {
            return null;
        }
        // Skill level paces with the bot's SP budget the same way BotMesoBomb levels the
        // Chief Bandit's bomb: learnable the day 3rd job opens (70), then 3 SP per level.
        int level = Math.max(1, Math.min(skill.getMaxLevel(), Math.max(1, (bot.getLevel() - 70) * 3)));
        StatEffect effect = skill.getEffect(level);
        if (effect == null) {
            return null;
        }
        int moneyCon = effect.getMoneyCon();
        if (moneyCon <= 0) {
            return null;
        }

        // The skill's own WZ box (mirrored by facing) - what the host's ranged hit test uses.
        // Falls back to a conservative short box when the WZ row has none.
        Point pos = bot.getPosition();
        if (pos == null) {
            return null;
        }
        Rectangle box = effect.hasBoundingBox()
                ? effect.calculateBoundingBox(pos, facingLeft)
                : new Rectangle(pos.x - 200, pos.y - 120, 200, 170);
        MapleMap map = bot.getMap();
        List<Monster> mobs = new ArrayList<>();
        for (MapObject mo : map.getMapObjectsInRange(pos, (double) SCAN_RADIUS_PX * SCAN_RADIUS_PX,
                List.of(MapObjectType.MONSTER))) {
            Monster m = (Monster) mo;
            if (m.isAlive() && box.contains(m.getPosition())) {
                mobs.add(m);
            }
        }
        mobs.sort((a, b) -> Double.compare(pos.distanceSq(a.getPosition()), pos.distanceSq(b.getPosition())));
        // The host's own StatEffect parse: no mobCount node in the WZ row -> default 1. One
        // mob, one line - a real throw prices a single hit on the nearest target.
        int mobCount = effect.getMobCount();
        List<Monster> targets = mobs.size() <= mobCount ? mobs : mobs.subList(0, mobCount);
        if (targets.isEmpty()) {
            return null; // nothing in reach - let the regular swing handle targeting
        }
        // An attack is never made from the saddle, and the hide auras (Dark Sight / Oak
        // Barrel) break the moment the attack key is pressed - the driver's own rule before
        // every swing. Cancelling here, after the reach check, mirrors it: the early-return
        // beats above spent nothing, so a null return never tears a disguise off.
        soloMapling.ArtificialPlayer.BotMountSystem.BotMount.cancelForAction(bot);
        BotAuraState.cancelHidesForAction(bot);

        // The host's charge rules: moneyCon with a small random top-up, clamped at the
        // wallet, and the throw happens even when broke (the charge simply floors at 0).
        // Mirror all three - a broke bot still throws, the wallet just stops draining.
        int money = moneyCon + ThreadLocalRandom.current().nextInt(Math.max(1, moneyCon / 2));
        int charge = Math.min(money, bot.getMeso());
        if (charge > 0) {
            bot.gainMeso(-charge, false);
        }
        // The host's SHADOW_MESO line price, keyed on the level-table moneyCon.
        int perLine = (int) Math.floor(moneyCon * 10 * 1.5);

        Map<Integer, List<Integer>> mobDamage = new LinkedHashMap<>();
        for (Monster mob : targets) {
            mobDamage.put(mob.getObjectId(), List.of(perLine));
        }

        int facingMask = facingLeft ? BotAttackData.FACING_LEFT_MASK : BotAttackData.FACING_RIGHT_MASK;
        // The host's own ranged writer with projectile 0 - the same frame a real Hermit's
        // throw produces (the client draws the coin sprites; no star is consumed or rendered).
        bot.getMap().broadcastMessage(bot, PacketCreator.rangedAttack(bot,
                Hermit.SHADOW_MESO, level, facingMask, (mobDamage.size() << 4) | 1,
                0, mobDamage, BotAttackData.DEFAULT_ATTACK_SPEED,
                BotAttackData.actionFor(Hermit.SHADOW_MESO, weapon), 0), false);
        GCMovement.markAlerted(bot);

        boolean killed = false;
        for (Monster mob : targets) {
            if (BotAttackEffects.applyExternalHit(bot, mob, perLine)) {
                killed = true;
            }
        }
        NEXT_THROW_BY_BOT.put(bot.getId(), now + SHADOW_MESO_COOLDOWN_MS);
        return new BotMesoBomb.Blast(perLine * targets.size(), killed);
    }
}
