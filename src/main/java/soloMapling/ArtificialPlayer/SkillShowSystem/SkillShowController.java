package soloMapling.ArtificialPlayer.SkillShowSystem;

import org.gms.client.Character;
import org.gms.client.Job;
import org.gms.client.inventory.WeaponType;
import org.gms.constants.skills.Crusader;
import org.gms.server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackConfig;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackProfile;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotBuffConfig;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotBuffDriver;
import soloMapling.ArtificialPlayer.BotCommandsPack.BotAttack;
import soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands;
import soloMapling.ArtificialPlayer.BotGeneration;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotSummonSystem.BotSummonSystem;
import soloMapling.ArtificialPlayer.BotSummonSystem.BotSummonTable;
import soloMapling.BotLogger;
import soloMapling.server.MethodScheduler;
import soloMapling.server.SoloMaplingUtilities;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * GM 技能观测表演：4 转 bot 逐个登场（job id 升序），把自己的职业体系技能
 * 逐个「同图气泡预告中文名 → 3 秒 → 表演 1 次」，全部演完退场，下一个职业接力。
 *
 * <p>表演 bot 是站桩实体：{@code createBot} 之后不挂任何 FSM（不手动 start），
 * 由本控制器外部驱动。技能来源是插件既有注册表 —— {@link BotBuffConfig}（增益）、
 * {@link BotAttackConfig#resolve}（攻击三槽）、{@link BotSummonTable}（召唤兽）——
 * 即 bot 实战会用的全部技能。表演动作走与实战相同的广播函数
 * （{@link BotBuffDriver#castSkill} / {@link BotAttack} 的空挥族 /
 * {@link BotSummonSystem#showSummon}），观众看到的与实战一致。</p>
 *
 * <p>单线程拍子链（MethodScheduler），无共享可变状态跨线程；每个入口与每个
 * 拍子都是 synchronized 的，stop 与拍子互斥。</p>
 */
public final class SkillShowController {

    private SkillShowController() {
    }

    // ---- 节奏 ----
    /** 气泡预告到出招的间隔。 */
    private static final long ANNOUNCE_LEAD_MS = 3000;
    /** 一次表演动作的播放时长，之后进下一个技能。 */
    private static final long PERFORM_MS = 1500;
    /** 召唤兽的展示时长（跟随/环绕/攻击由 follower 驱动，多留几秒看动作）。 */
    private static final long SUMMON_SHOW_MS = 5000;
    /** 登场后等待落地的时长（SPAWN_CHOREOGRAPHY_MAX_MS 语义）。 */
    private static final long ARRIVE_MS = BotGeneration.SPAWN_CHOREOGRAPHY_MAX_MS;
    /** 换职业之间的空场。 */
    private static final long INTER_JOB_GAP_MS = 2000;

    /** 12 个冒险家 4 转终职，job id 升序（战→法→弓→贼→海盗）。 */
    private static final int[] SHOWCASE_JOBS = {
            112, 122, 132,          // 英雄 / 圣骑士 / 黑骑士
            212, 222, 232,          // 火毒 / 冰雷 / 主教
            312, 322,               // 神射手 / 箭神
            412, 422,               // 隐士 / 侠盗
            512, 522                // 冲锋队长 / 船长
    };

    /** 一场表演的一个动作：预告什么、怎么演。 */
    private sealed interface Step {
        /** 气泡预告的技能 id。 */
        int skillId();

        /** 表演并返回占用时长（ms）。 */
        long perform(Character bot);
    }

    private record BuffStep(int skillId) implements Step {
        @Override
        public long perform(Character bot) {
            BotBuffDriver.castSkill(bot, skillId);
            return PERFORM_MS;
        }
    }

    private record AttackStep(int skillId, BotAttackProfile profile) implements Step {
        @Override
        public long perform(Character bot) {
            switch (profile.route) {
                case CLOSE -> BotAttack.skillSwing(bot, skillId);
                case RANGED -> BotAttack.rangedSwing(bot, skillId);
                case MAGIC -> BotAttack.magicSwing(bot, skillId);
            }
            return PERFORM_MS;
        }
    }

    private record SummonStep(int skillId) implements Step {
        @Override
        public long perform(Character bot) {
            // 先收掉上一只，再召唤这一只：同一 bot 的召唤兽逐只亮相，不叠影。
            BotSummonSystem.remove(bot);
            return BotSummonSystem.showSummon(bot, skillId) ? SUMMON_SHOW_MS : 0;
        }
    }

    private enum Phase { IDLE, SPAWN, ARRIVE, STEP, DESPAWN }

    private static Phase phase = Phase.IDLE;
    private static int jobIndex = -1;
    private static Character bot;
    private static int botId = -1;
    private static Point spawnPos;
    private static int mapId = -1;
    private static final Deque<Step> steps = new java.util.ArrayDeque<>();
    private static Step pending;           // 已预告、等 3 秒后表演
    private static int stepTotal;
    private static int stepDone;
    private static int jobTotal = SHOWCASE_JOBS.length;

    // ---- 命令入口（!bot skillshow） ----

    /** 开启。返回给 GM 的错误提示，null = 已开启。 */
    public static synchronized String start(Point pos, int gmMapId) {
        if (phase != Phase.IDLE) {
            return "表演已在进行中（第 " + (jobIndex + 1) + "/" + jobTotal
                    + " 个职业），!bot skillshow stop 可停止。";
        }
        if (pos == null) {
            return "无法取得 GM 的位置。";
        }
        spawnPos = new Point(pos);
        mapId = gmMapId;
        jobIndex = -1;
        steps.clear();
        pending = null;
        stepTotal = 0;
        stepDone = 0;
        jobTotal = SHOWCASE_JOBS.length;
        phase = Phase.SPAWN;
        MethodScheduler.runAfterDelay(SkillShowController::beat, 0);
        return null;
    }

    /** 停止并清理。 */
    public static synchronized void stop() {
        if (phase == Phase.IDLE) {
            return;
        }
        removeShowBot();
        phase = Phase.IDLE;
        jobIndex = -1;
        steps.clear();
        pending = null;
    }

    /** 当前进度，null = 未在进行。 */
    public static synchronized String status() {
        if (phase == Phase.IDLE) {
            return null;
        }
        int job = jobIndex >= 0 && jobIndex < SHOWCASE_JOBS.length ? SHOWCASE_JOBS[jobIndex] : 0;
        return "skillshow: 第 " + (jobIndex + 1) + "/" + jobTotal + " 个职业 job=" + job
                + "，技能 " + stepDone + "/" + stepTotal;
    }

    static boolean running() {
        return phase != Phase.IDLE;
    }

    // ---- 拍子链 ----

    /*
     * 拍子链是整场表演的唯一生命线：任何一拍抛出异常，MethodScheduler 的兜底只
     * 打日志不重排下一拍，链条就永久断死 —— 表演 bot（挂着 morph 光环与召唤兽）
     * 会滞留地图。所以整拍包在这里兜：出错则干净收场（退场 + 状态归位），而不是
     * 留下一具再也不会动的站桩尸体。
     */
    private static synchronized void beat() {
        try {
            switch (phase) {
                case SPAWN -> beatSpawn();
                case ARRIVE -> {
                    phase = Phase.STEP;
                    MethodScheduler.runAfterDelay(SkillShowController::beat, 0);
                }
                case STEP -> beatStep();
                case DESPAWN -> {
                    removeShowBot();
                    phase = Phase.SPAWN;
                    MethodScheduler.runAfterDelay(SkillShowController::beat, INTER_JOB_GAP_MS);
                }
                default -> { }
            }
        } catch (Throwable t) {
            BotLogger.log("[skillshow] 拍子异常，中断收场: " + t);
            stop();
        }
    }

    /** 登场：生成下一个职业的 bot；全部演完则收场。 */
    private static void beatSpawn() {
        if (jobIndex + 1 >= SHOWCASE_JOBS.length) {
            BotLogger.log("[skillshow] 全部职业表演完毕，收场");
            stop();
            return;
        }
        jobIndex++;
        int jobId = SHOWCASE_JOBS[jobIndex];
        MapleMap map = SoloMaplingUtilities.getMapleMapById(mapId);
        if (map == null) {
            BotLogger.log("[skillshow] map " + mapId + " gone, 收场");
            stop();
            return;
        }
        int created = BotGeneration.createBot(spawnPos, map, jobId / 100, 180, 180, jobId);
        Character createdBot = created > 0 ? BotHelpers.getCharFromChannelStorage(created) : null;
        if (createdBot == null) {
            BotLogger.log("[skillshow] createBot failed for job " + jobId + ", 收场");
            stop();
            return;
        }
        bot = createdBot;
        botId = created;
        steps.clear();
        steps.addAll(buildSteps(createdBot));
        stepTotal = steps.size();
        stepDone = 0;
        pending = null;
        phase = Phase.ARRIVE;
        BotLogger.log("[skillshow] job " + jobId + " 登场，" + stepTotal + " 个技能");
        MethodScheduler.runAfterDelay(SkillShowController::beat, ARRIVE_MS);
    }

    /** 表演节拍：有待演的先演，否则预告下一个。 */
    private static void beatStep() {
        if (!alive()) {
            BotLogger.log("[skillshow] bot/map 丢失，中断");
            stop();
            return;
        }
        if (pending != null) {
            Step step = pending;
            pending = null;
            stepDone++;
            long hold = step.perform(bot);
            MethodScheduler.runAfterDelay(SkillShowController::beat, Math.max(0, hold));
            return;
        }
        Step next = steps.poll();
        if (next == null) {
            phase = Phase.DESPAWN; // 本职业演完 → 退场 → 下一个登场
            MethodScheduler.runAfterDelay(SkillShowController::beat, 0);
            return;
        }
        // 预告气泡：bot 在自检后、广播前的一瞬间被移除时 getMap() 会变 null ——
        // BotChatbubble 对此不设防，走 BotSpeak 的判空路径（打字式/整聊式都带
        // null map 守卫的入口在 BotFullChat 之下，仍需 alive() 前置；这里是
        // alive() 已过的窗口，选唯一带判空的广播口）。
        if (bot.getMap() == null) {
            stop();
            return;
        }
        SocialCommands.BotSpeakPlain(bot, "接下来表演：" + BotSkillNames.name(next.skillId()));
        pending = next;
        MethodScheduler.runAfterDelay(SkillShowController::beat, ANNOUNCE_LEAD_MS);
    }

    /** 每拍自检：bot 仍在线且仍在原地图。 */
    private static boolean alive() {
        return bot != null && botId > 0
                && bot.getMap() != null && bot.getMapId() == mapId
                && BotHelpers.getCharFromChannelStorage(botId) == bot;
    }

    private static void removeShowBot() {
        if (bot != null) {
            try {
                BotGeneration.removeBotFromServer(bot);
            } catch (Throwable t) {
                BotLogger.log("[skillshow] 退场失败: " + t);
            }
        }
        bot = null;
        botId = -1;
        pending = null;
    }

    // ---- 表演序列构建 ----

    /**
     * 对刚登场的表演 bot 展开序列：增益（{@link BotBuffConfig#buffsForJob}
     * 谱系并集，低转在前）→ 攻击（{@link BotAttackConfig#resolve} 的
     * single/aoe/ultimate 三槽，即 bot 实战真正会出的招；按 skillId 去重）→
     * 召唤兽（谱系并集）。enabler 门控技（金手指/潜龙出渊/战舰炮击…）的
     * enabler（超级变身/变身/海盗船）都在增益段先演，天然满足攻击门控。
     * 斗气集中（COMBO）不进序列：球环是 {@code BotComboOrb} 专属线格式，
     * 通用光环帧会打碎观察者的球环（与 BotBuffDriver 周期扫描同一豁免）。
     */
    static List<Step> buildSteps(Character showBot) {
        Job job = showBot.getJob();
        if (job == null) {
            return List.of();
        }
        List<Step> out = new ArrayList<>();

        for (int skillId : BotBuffConfig.buffsForJob(job)) {
            if (skillId == Crusader.COMBO) {
                continue;
            }
            out.add(new BuffStep(skillId));
        }

        WeaponType weapon = BotAttack.resolveEquippedWeaponType(showBot);
        BotAttackConfig.JobAttacks atks = BotAttackConfig.resolve(job, weapon);
        Set<Integer> seen = new LinkedHashSet<>();
        for (BotAttackProfile p : new BotAttackProfile[]{atks.single(), atks.aoe(), atks.ultimate()}) {
            if (p == null) {
                continue;
            }
            int id = p.skillFor(weapon);
            if (id > 0 && seen.add(id)) {
                out.add(new AttackStep(id, p));
            }
        }

        for (int skillId : BotSummonTable.summonsForJobId(job.getId())) {
            out.add(new SummonStep(skillId));
        }
        return out;
    }
}
