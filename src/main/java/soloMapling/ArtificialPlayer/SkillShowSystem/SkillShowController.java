package soloMapling.ArtificialPlayer.SkillShowSystem;

import org.gms.client.Character;
import org.gms.client.Job;
import org.gms.client.inventory.WeaponType;
import org.gms.constants.skills.Crusader;
import org.gms.constants.skills.FPMage;
import org.gms.constants.skills.Hermit;
import org.gms.server.life.Monster;
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
import soloMapling.server.BotChannelRouter;
import soloMapling.server.MethodScheduler;

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

    /**
     * 木桩条件技（毒雾/影网/影子貔貅/能量反击）：木桩在场且存活时执行
     * {@code action}（返回是否真的出招），否则零耗时跳过 —— 不阻塞整场表演。
     */
    private record TargetedStep(int skillId, String label,
                                java.util.function.Predicate<Character> action) implements Step {
        @Override
        public long perform(Character bot) {
            Monster staged = SkillShowTarget.find(bot.getMapId());
            if (staged == null || !staged.isAlive()) {
                return 0;
            }
            return action.test(bot) ? PERFORM_MS : 0;
        }
    }

    private enum Phase { IDLE, SPAWN, ARRIVE, STEP, DESPAWN }

    private static Phase phase = Phase.IDLE;
    private static int jobIndex = -1;
    private static Character bot;
    private static int botId = -1;
    private static Point spawnPos;
    private static int mapId = -1;
    /** GM 发起表演时所在的频道：bot 与木桩全程跟随这个频道的地图实例。 */
    private static int channelId = BotChannelRouter.DEFAULT_CHANNEL;
    private static final Deque<Step> steps = new java.util.ArrayDeque<>();
    private static Step pending;           // 已预告、等 3 秒后表演
    private static int stepTotal;
    private static int stepDone;
    private static int jobTotal = SHOWCASE_JOBS.length;
    /** 连续生成失败计数：到顶自动收场，防止 12 个职业逐个静默失败拖 12 轮。 */
    private static int consecutiveSpawnFailures;

    // ---- 命令入口（!bot skillshow） ----

    /** 开启。返回给 GM 的错误提示，null = 已开启。表演全程在 GM 所在频道进行。 */
    public static synchronized String start(Point pos, int gmMapId, int gmChannel) {
        if (phase != Phase.IDLE) {
            return "表演已在进行中（第 " + (jobIndex + 1) + "/" + jobTotal
                    + " 个职业），!bot skillshow stop 可停止。";
        }
        if (pos == null) {
            return "无法取得 GM 的位置。";
        }
        spawnPos = new Point(pos);
        mapId = gmMapId;
        channelId = gmChannel > 0 ? gmChannel : BotChannelRouter.DEFAULT_CHANNEL;
        jobIndex = -1;
        steps.clear();
        pending = null;
        stepTotal = 0;
        stepDone = 0;
        jobTotal = SHOWCASE_JOBS.length;
        consecutiveSpawnFailures = 0;
        phase = Phase.SPAWN;
        say("[skillshow] 开始登场：job 队列 " + jobTotal + " 个职业");
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

    /**
     * 回喊 GM（dropMessage，不依赖 bot 存活）。控制台场景（GM 已下线）回喊
     * 静默降级为日志 —— 表演照常进行，诊断信息不丢。
     */
    private static void say(String message) {
        BotLogger.log(message);
        Character gm = soloMapling.command.ArtificialPlayerCommand.currentPlayer();
        if (gm != null && gm.getMap() != null) {
            gm.dropMessage(message);
        }
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
                    say("[skillshow] job " + (jobIndex >= 0 ? SHOWCASE_JOBS[jobIndex] : 0)
                            + " 表演完毕退场");
                    phase = Phase.SPAWN;
                    MethodScheduler.runAfterDelay(SkillShowController::beat, INTER_JOB_GAP_MS);
                }
                default -> { }
            }
        } catch (Throwable t) {
            say("[skillshow] 拍子异常，中断收场: " + t);
            stop();
        }
    }

    /** 频道解析不到时回退频道 1（EnvironmentManager.mapOnChannel 同款兜底）。 */
    private static MapleMap mapOnGmChannel(int mapId) {
        try {
            org.gms.net.server.Server server = org.gms.net.server.Server.getInstance();
            org.gms.net.server.world.World world = server.getWorld(
                    soloMapling.server.SoloMaplingConstants.GameConstants.WORLD_SCANIA);
            if (world != null) {
                org.gms.net.server.channel.Channel ch = world.getChannel(channelId);
                if (ch != null) {
                    MapleMap resolved = ch.getMapFactory().getMap(mapId);
                    if (resolved != null) {
                        return resolved;
                    }
                }
            }
        } catch (RuntimeException e) {
            BotLogger.log("[skillshow] could not resolve map " + mapId + " on ch" + channelId
                    + " (" + e + ")");
        }
        return soloMapling.server.SoloMaplingConstants.mainChannel().getMapFactory().getMap(mapId);
    }

    /** 登场：生成下一个职业的 bot；全部演完则收场。 */
    private static void beatSpawn() {
        if (jobIndex + 1 >= SHOWCASE_JOBS.length) {
            say("[skillshow] 全部职业表演完毕，收场");
            stop();
            return;
        }
        if (consecutiveSpawnFailures >= 3) {
            say("[skillshow] 连续 " + consecutiveSpawnFailures
                    + " 个职业生成失败，停止表演。常见原因：channel_capacity 已满 / fmbot 模板缺失。");
            stop();
            return;
        }
        jobIndex++;
        int jobId = SHOWCASE_JOBS[jobIndex];
        // GM 所在频道的地图实例：地图是按频道隔离的对象，bot 与木桩必须落在
        // GM 能看见的那份实例上（createBot 内部还会把地图重解析到 bot 自己的
        // 频道，这里给对频道，两边就是同一份）。
        MapleMap map = mapOnGmChannel(mapId);
        if (map == null) {
            say("[skillshow] 频道 " + channelId + " 的地图 " + mapId + " 已不可用，收场");
            stop();
            return;
        }
        // 生成是全异步管线（模板加载 + 节流排队 + 落地编排），createBot 返回后
        // bot 并不立刻进 channelStorage —— 既有 createBotPollReadiness 就是为此
        // 而设的轮询。这里等待期间先把进度喊给 GM，别让他对着黑屏猜。
        say("[skillshow] (" + (jobIndex + 1) + "/" + jobTotal + ") 生成 job " + jobId + " …");
        int created;
        try {
            // 频道必须与上面解析的地图实例一致：pin 在 GM 的频道，频道满员时
            // 宁可跳过该职业，也不让 bot 落到别的频道让 GM 看不见。
            created = BotGeneration.createBotOnChannel(spawnPos, map, jobId / 100, 180, 180,
                    jobId, channelId);
        } catch (Throwable t) {
            created = -1;
            say("[skillshow] job " + jobId + " 生成抛异常: " + t);
        }
        if (created <= 0) {
            // -1 = GM 频道满员或不存在（BotChannelRouter.NONE）；节流排队不会走这条。
            consecutiveSpawnFailures++;
            say("[skillshow] job " + jobId + " 在频道 " + channelId + " 生成失败（频道满员？），跳过。失败 "
                    + consecutiveSpawnFailures + "/3");
            MethodScheduler.runAfterDelay(SkillShowController::beat, INTER_JOB_GAP_MS);
            return;
        }
        botId = created;
        phase = Phase.ARRIVE;
        waitBotReady(created, 30, 0);
    }

    /**
     * 轮询等 bot 进入 channelStorage（createBotPollReadiness 的非阻塞版）：
     * 就绪 → 落地等待后开演；超时 → 记一次失败并跳过该职业。
     */
    private static void waitBotReady(int cid, int attemptsLeft, int waitedMs) {
        Character ready = attemptsLeft > 0 ? BotHelpers.getCharFromChannelStorage(cid) : null;
        if (ready == null) {
            if (attemptsLeft <= 0) {
                consecutiveSpawnFailures++;
                say("[skillshow] job " + cidToJob(cid) + " 的 bot " + cid
                        + " 生成后 3s 未就绪，跳过。失败 " + consecutiveSpawnFailures + "/3");
                removeShowBot();
                phase = Phase.SPAWN;
                MethodScheduler.runAfterDelay(SkillShowController::beat, INTER_JOB_GAP_MS);
                return;
            }
            MethodScheduler.runAfterDelay(() -> waitBotReady(cid, attemptsLeft - 1, waitedMs + 100), 100);
            return;
        }
        bot = ready;
        steps.clear();
        try {
            steps.addAll(buildSteps(bot));
        } catch (Throwable t) {
            consecutiveSpawnFailures++;
            say("[skillshow] job " + cidToJob(cid) + " 表演序列构建失败: " + t);
            removeShowBot();
            phase = Phase.SPAWN;
            MethodScheduler.runAfterDelay(SkillShowController::beat, INTER_JOB_GAP_MS);
            return;
        }
        stepTotal = steps.size();
        stepDone = 0;
        pending = null;
        consecutiveSpawnFailures = 0;
        // 木桩（毒雾/影网/影子貔貅/能量反击的可作用目标）与 bot 同登同退。
        boolean staged = SkillShowTarget.spawn(spawnPos, bot.getMap(), bot.getId());
        say("[skillshow] job " + cidToJob(cid) + " 登场（cid " + cid + "），"
                + stepTotal + " 个技能" + (staged ? "，木桩就位" : "") + "，"
                + (stepTotal * 4500 / 1000) + " 秒演完");
        MethodScheduler.runAfterDelay(SkillShowController::beat, ARRIVE_MS);
    }

    /** cid 反查本次表演的 job id（仅用于日志文案）。 */
    private static int cidToJob(int cid) {
        return jobIndex >= 0 && jobIndex < SHOWCASE_JOBS.length ? SHOWCASE_JOBS[jobIndex] : 0;
    }

    /** 表演节拍：有待演的先演，否则预告下一个。 */
    private static void beatStep() {
        if (!alive()) {
            say("[skillshow] 表演 bot 或地图丢失，中断收场");
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
        // 预告气泡：bot 在自检后、广播前的一瞬间被移除时 getMap() 会变 null，
        // 广播前显式守住（alive() 已过，这里是竞态窗口的最后一道闸）。
        if (bot.getMap() == null) {
            say("[skillshow] 表演 bot 地图丢失，中断收场");
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
                SkillShowTarget.remove(bot.getMapId()); // 木桩与 bot 同退
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
        out.addAll(targetedSteps(job));
        return out;
    }

    /**
     * 木桩条件技：三张注册表之外、实战 bot 在 AUTO 回合会打出来的技能
     * （毒雾/影网/影子貔貅的 beat、能量获得触身反击）。依赖木桩在场；
     * 木桩不在时这些步骤零耗时跳过。
     */
    private static List<Step> targetedSteps(Job job) {
        List<Step> out = new ArrayList<>();
        if (job.isA(Job.FP_MAGE)) { // F/P 3/4 转: 致命毒雾（魔攻包 + 宿主 Mist 毒云管线）
            out.add(new TargetedStep(FPMage.POISON_MIST, "致命毒雾",
                    SkillShowEffects::castMist));
        }
        if (job.isA(Job.HERMIT)) { // 隐士 3 转: 影网术（近战包 + 网怪）
            out.add(new TargetedStep(Hermit.SHADOW_WEB, "影网术",
                    SkillShowEffects::castWeb));
        }
        if (job.isA(Job.HERMIT)) { // 隐士 3 转: 影子貔貅（远程包 + 落钱）
            out.add(new TargetedStep(Hermit.SHADOW_MESO, "影子貔貅",
                    SkillShowEffects::throwShadowMeso));
        }
        if (job.isA(Job.BUCCANEER)) { // 冲锋队长 3 转: 能量获得充满 → 触身反击
            out.add(new TargetedStep(0, "能量获得·反击",
                    SkillShowEffects::energyRetaliate));
        }
        return out;
    }
}
