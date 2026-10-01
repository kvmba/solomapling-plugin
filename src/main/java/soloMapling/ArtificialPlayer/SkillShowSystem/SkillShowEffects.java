package soloMapling.ArtificialPlayer.SkillShowSystem;

import org.gms.client.Character;
import org.gms.client.Skill;
import org.gms.client.SkillFactory;
import org.gms.constants.skills.FPMage;
import org.gms.constants.skills.Hermit;
import org.gms.server.StatEffect;
import org.gms.server.life.Monster;
import org.gms.server.maps.Mist;
import org.gms.util.PacketCreator;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackData;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAuraState;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotDamageModel;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotEnergyCharge;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.ArtificialPlayer.BotMountSystem.BotMount;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 木桩条件技的表演函数：复刻各 beat 系统在 AUTO 回合的真实出招路径
 * （同款发包、同款 Mist/反击帧），但跳过实战里才成立的条件门（Own
 * Pickpocket 钱袋存量、能量条已充满、目标须被移动扫描触到），让 GM 在
 * 站桩场景看到一次完整演出。木桩不在场时全部返回 false，步骤零耗时跳过。
 */
public final class SkillShowEffects {

    private SkillShowEffects() {
    }

    /** 与 beat 系统同源的 SP 预算等级：3 转技 70 级起学。表演 bot 固定 180 级 → 技能满级。 */
    private static final int THIRD_JOB_LEARN_LEVEL = 70;
    /** 同上：180 - 70 = 110 级余量 ×3 SP/级，必封顶 maxLevel；仍按式计算保持同源。 */
    private static final int SHOW_BOT_LEVEL = 180;

    /** 木桩 shorthand：不存在或已被杀返回 null。 */
    private static Monster target(Character bot) {
        Monster mob = SkillShowTarget.find(bot.getMapId());
        return mob != null && mob.isAlive() ? mob : null;
    }

    /** 表演公共前置：下坐骑、破隐身、markAlerted。 */
    private static void preCast(Character bot) {
        BotMount.cancelForAction(bot);
        BotAuraState.cancelHidesForAction(bot);
        GCMovement.markAlerted(bot);
    }

    /** 技能级（SP 预算推导，封顶 maxLevel）：表演 bot 固定 180 级 → 全部满级。 */
    private static int levelOf(int skillId) {
        Skill skill = SkillFactory.getSkill(skillId);
        if (skill == null) {
            return -1;
        }
        return Math.max(1, Math.min(skill.getMaxLevel(),
                Math.max(1, (SHOW_BOT_LEVEL - THIRD_JOB_LEARN_LEVEL) * 3)));
    }

    /** 技能效果；WZ 缺行返回 null（调用方跳过）。 */
    private static StatEffect resolveEffect(int skillId) {
        Skill skill = SkillFactory.getSkill(skillId);
        if (skill == null) {
            return null;
        }
        return skill.getEffect(levelOf(skillId));
    }

    /** 单怪单行伤害包（木桩表演的统一伤害面）。 */
    private static Map<Integer, List<Integer>> singleLine(Character bot, Monster mob) {
        Map<Integer, List<Integer>> dmg = new LinkedHashMap<>();
        dmg.put(mob.getObjectId(), List.of(
                BotDamageModel.rollLine(bot.getJob().getJobTier(), bot.getLevel(), 1)));
        return dmg;
    }

    private static boolean facingLeft(Character bot) {
        return soloMapling.ArtificialPlayer.BotMovementSystem.MovementCommands.facingLeft(bot);
    }

    private static int facingMask(boolean left) {
        return left ? BotAttackData.FACING_LEFT_MASK : BotAttackData.FACING_RIGHT_MASK;
    }

    /**
     * 致命毒雾（2111003）：魔攻包 + 宿主 spawnMist 毒云管线，与
     * {@code BotGroundMists.tryCast} 同款帧。毒调度器的每 2.5s 掷 prop、
     * 每秒扣血、到期消失全部是宿主代码，表演里不重写。
     */
    public static boolean castMist(Character bot) {
        Monster mob = target(bot);
        if (mob == null) {
            return false;
        }
        StatEffect effect = resolveEffect(FPMage.POISON_MIST);
        if (effect == null) {
            return false;
        }
        preCast(bot);
        Point pos = bot.getPosition();
        boolean left = facingLeft(bot);
        Rectangle box = effect.hasBoundingBox()
                ? effect.calculateBoundingBox(pos, left)
                : new Rectangle(pos.x - 110, pos.y - 82, 220, 165);
        Map<Integer, List<Integer>> dmg = singleLine(bot, mob);
        bot.getMap().broadcastMessage(bot, PacketCreator.magicAttack(bot,
                FPMage.POISON_MIST, levelOf(FPMage.POISON_MIST), facingMask(left),
                (dmg.size() << 4) | 1, dmg, BotAttackData.magicChargeFor(FPMage.POISON_MIST),
                BotAttackData.DEFAULT_ATTACK_SPEED,
                BotAttackData.actionFor(FPMage.POISON_MIST, null), 0), false);
        bot.getMap().spawnMist(new Mist(box, bot, effect, levelOf(FPMage.POISON_MIST)),
                effect.getDuration(), true, false, false);
        return true;
    }

    /**
     * 影网术（4111003）：近战包（web 的 swingO1 动作），与
     * {@code BotShadowWeb.tryCast} 同款帧；网住怪物的状态由宿主管。
     */
    public static boolean castWeb(Character bot) {
        Monster mob = target(bot);
        if (mob == null) {
            return false;
        }
        if (resolveEffect(Hermit.SHADOW_WEB) == null) {
            return false;
        }
        preCast(bot);
        boolean left = facingLeft(bot);
        Map<Integer, List<Integer>> dmg = singleLine(bot, mob);
        bot.getMap().broadcastMessage(bot, PacketCreator.closeRangeAttack(bot,
                Hermit.SHADOW_WEB, levelOf(Hermit.SHADOW_WEB), facingMask(left),
                (dmg.size() << 4) | 1, dmg, BotAttackData.DEFAULT_ATTACK_SPEED,
                BotAttackData.actionFor(Hermit.SHADOW_WEB, null), 0), false);
        return true;
    }

    /**
     * 影子貔貅（4111004）：宿主 SHADOW_MESO 分支的远程帧（projectile 0），
     * 与 {@code BotShadowMeso.tryThrow} 同款；实战的钱包价格在表演里免了。
     */
    public static boolean throwShadowMeso(Character bot) {
        Monster mob = target(bot);
        if (mob == null) {
            return false;
        }
        if (resolveEffect(Hermit.SHADOW_MESO) == null) {
            return false;
        }
        preCast(bot);
        boolean left = facingLeft(bot);
        Map<Integer, List<Integer>> dmg = singleLine(bot, mob);
        bot.getMap().broadcastMessage(bot, PacketCreator.rangedAttack(bot,
                Hermit.SHADOW_MESO, levelOf(Hermit.SHADOW_MESO), facingMask(left),
                (dmg.size() << 4) | 1, /* projectile */ 0, dmg,
                BotAttackData.DEFAULT_ATTACK_SPEED,
                BotAttackData.actionFor(Hermit.SHADOW_MESO, null), 0), false);
        return true;
    }

    /**
     * 能量获得·反击：GM 测试口 {@code fillForTest} 瞬间充满能量条（满条
     * 光环走宿主 ENERGY_CHARGE 帧），再 {@code strike} 对木桩 bodyStrike ——
     * 与 BotContactDamage 的反击路径同一份代码。
     */
    public static boolean energyRetaliate(Character bot) {
        Monster mob = target(bot);
        if (mob == null) {
            return false;
        }
        BotEnergyCharge.fillForTest(bot);
        preCast(bot);
        return BotEnergyCharge.strike(bot, mob);
    }
}
