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
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackAction;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackData;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackEffects;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackProfile;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAuraState;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotBuffConfig;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotBuffDriver;
import soloMapling.ArtificialPlayer.BotMountSystem.BotMount;
import soloMapling.ArtificialPlayer.BotCommandsPack.BotAttack;
import soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands;
import soloMapling.ArtificialPlayer.BotGeneration;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotSummonSystem.BotSummonSystem;
import soloMapling.ArtificialPlayer.BotSummonSystem.BotSummonTable;
import soloMapling.ArtificialPlayer.BotMovementSystem.MovementCommands;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.BotLogger;
import soloMapling.server.BotChannelRouter;
import soloMapling.server.MethodScheduler;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * GM 技能观测表演：4 转 bot 逐个登场（job id 升序），把自己的职业体系技能
 * 逐个「同图气泡预告中文名 → 3 秒 → 表演 1 次」，全部演完退场，下一个职业接力。
 *
 * <p>表演 bot 是站桩实体：{@code createBot} 之后不挂任何 FSM（不手动 start），
 * 由本控制器外部驱动。技能来源是插件既有注册表 —— {@link BotBuffConfig}（增益）、
 * {@link BotAttackConfig#resolve}（攻击三槽）、{@link BotSummonTable}（召唤兽）——
 * 即 bot 实战会用的全部技能。表演动作走与实战相同的广播函数
 * （{@link BotBuffDriver#castSkill} / {@link BotAttackEffects} 的真打击路径 /
 * {@link BotSummonSystem#showSummon}），观众看到的与实战一致。</p>
 *
 * <p>攻击技在木桩在场时走 {@link BotAttackEffects} 的三条打击路径（发包带伤害行 +
 * {@code applyExternalHit} 真实扣血），木桩缺席时才退回 {@link BotAttack} 的空挥族。</p>
 *
 * <p>拍子链（MethodScheduler）的每一拍（含就绪轮询）都是 synchronized 且携带代际号
 * {@link #generation}：stop()/重新 start 使旧 token 立即作废，过期续拍与过期轮询既不会
 * 驱动新一场表演，也不会把上一场已生成、尚未登记的 bot 遗留在图里。</p>
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
    /** bot 与木桩的最小间隔：太近时近战挥击/远程弹道在视觉上从木桩身体里穿出。 */
    private static final int TARGET_GAP_PX = 45;

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
            // 木桩在场：走实战同款的真打击路径（BotAttackEffects.*Strike）—— 发包带伤害行、
            // 木桩真实扣血（10 亿血桩，整场打不死），观众看到的是与实战一致的命中特效。
            // 木桩缺席（WZ 缺怪 / 已退场）：退回原来的空挥，表演不中断。
            Monster staged = SkillShowTarget.find(bot.getMapId());
            if (staged != null && staged.isAlive()) {
                strikeStagedTarget(bot, staged, profile);
            } else {
                switch (profile.route) {
                    case CLOSE -> BotAttack.skillSwing(bot, skillId);
                    case RANGED -> BotAttack.rangedSwing(bot, skillId);
                    case MAGIC -> BotAttack.magicSwing(bot, skillId);
                }
            }
            return PERFORM_MS;
        }
    }

    /**
     * 对木桩打出一次「有伤害」的表演攻击：滚出该 profile 的伤害行，走
     * {@link BotAttackEffects} 的近/远/法三条发包路径 —— 与
     * {@code BotAttackDriver.attack} 同一份代码（含 crit 负值编码与真实扣血）。
     * 打包失败（bot/木桩状态异常）时静默：表演是视觉，不值得为一次发包中断整场。
     */
    private static void strikeStagedTarget(Character bot, Monster staged, BotAttackProfile profile) {
        BotAttackAction.tryRun(bot, false, false, () -> {
            strikeStagedTargetNow(bot, staged, profile);
            return true;
        });
    }

    private static void strikeStagedTargetNow(Character bot, Monster staged, BotAttackProfile profile) {
        // 与 BotAttackDriver.attack 发包前完全一致的两个动作：一个攻击动作绝不在坐骑上
        // 打出，且按下攻击键的瞬间就破掉橡木伪装/隐身术。Brawler/Marauder/Buccaneer 的
        // 增益段含橡木伪装，少了这两步，攻击会在伪装状态下打出。
        BotMount.cancelForAction(bot);
        BotAuraState.cancelHidesForAction(bot);

        WeaponType weapon = BotAttack.resolveEquippedWeaponType(bot);
        int skill = profile.skillFor(weapon);
        int lines = profile.numDamage;
        int facingMask = MovementCommands.facingLeft(bot) ? BotAttackData.FACING_LEFT_MASK : BotAttackData.FACING_RIGHT_MASK;
        int bodyActionId = BotAttackData.actionFor(skill, weapon);
        List<Integer> damage = new ArrayList<>(lines);
        for (int i = 0; i < lines; i++) {
            int dmg = profile.rollDamage(bot.getLevel(), bot.getJob());
            damage.add(ThreadLocalRandom.current().nextDouble() < BotAttackConfig.critChanceFor(bot.getJob())
                    ? BotAttackData.encodeCritLine(dmg) : dmg);
        }
        Map<Monster, List<Integer>> hits = new LinkedHashMap<>();
        hits.put(staged, damage);
        switch (profile.route) {
            case CLOSE -> BotAttackEffects.meleeStrike(bot, hits, skill, profile.skillLevel,
                    bodyActionId, facingMask, profile.speed, profile.hitDelayMs);
            case RANGED -> BotAttackEffects.rangedStrike(bot, hits, skill, profile.skillLevel,
                    BotAttackData.projectileFor(weapon, bot), bodyActionId, facingMask,
                    BotAttackData.speedFor(weapon, profile.speed), profile.hitDelayMs);
            case MAGIC -> BotAttackEffects.magicStrike(bot, hits, skill, profile.skillLevel,
                    bodyActionId, facingMask, profile.speed, profile.hitDelayMs);
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
    /**
     * 本场表演的代际号：start()/stop() 自增。每一拍与每一条就绪轮询都携带发起时的 token，
     * 到点先比对；不相等说明这场已被 stop/新一场取代，续拍直接退出——旧续拍绝不会驱动
     * 新一场的状态，也绝不会把上一场已创建但未登记的 bot 交出去。
     */
    private static int generation;

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
        mapId = gmMapId;
        channelId = gmChannel > 0 ? gmChannel : BotChannelRouter.DEFAULT_CHANNEL;
        jobIndex = -1;
        steps.clear();
        pending = null;
        stepTotal = 0;
        stepDone = 0;
        jobTotal = SHOWCASE_JOBS.length;
        consecutiveSpawnFailures = 0;
        // 起点吸附：GM 在楼梯/斜坡上启动时 pos 是坡面坐标（可能不在任何 foothold
        // 的支撑正上方），bot 带着它出生、落地编排播完仍悬空表演。先吸附到该 x
        // 的真实地面，bot 与木桩的出生点才是「高于地面的可站立点」。
        spawnPos = groundSnap(mapId, pos);
        phase = Phase.SPAWN;
        generation++; // 本场的代际号：先前残留的拍子/轮询从此作废
        say("[skillshow] 开始登场：job 队列 " + jobTotal + " 个职业");
        scheduleBeat(0, generation);
        return null;
    }

    /** 停止并清理。 */
    public static synchronized void stop() {
        if (phase == Phase.IDLE) {
            return;
        }
        generation++; // 在途的拍子与就绪轮询全部作废（迟到的续拍会自行清掉它等的那只 bot）
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
    private static synchronized void beat(int token) {
        if (token != generation) {
            return; // 过期续拍：stop()/重新 start 之后，这一拍不属于任何在演的场次
        }
        try {
            switch (phase) {
                case SPAWN -> beatSpawn(token);
                case ARRIVE -> {
                    phase = Phase.STEP;
                    scheduleBeat(0, token);
                }
                case STEP -> beatStep(token);
                case DESPAWN -> {
                    removeShowBot();
                    say("[skillshow] job " + (jobIndex >= 0 ? SHOWCASE_JOBS[jobIndex] : 0)
                            + " 表演完毕退场");
                    phase = Phase.SPAWN;
                    scheduleBeat(INTER_JOB_GAP_MS, token);
                }
                default -> { }
            }
        } catch (Throwable t) {
            say("[skillshow] 拍子异常，中断收场: " + t);
            stop();
        }
    }

    /** 排下一拍：token 随拍子传递，过期拍子在 {@link #beat} 入口即被丢弃。 */
    private static void scheduleBeat(long delayMs, int token) {
        MethodScheduler.runAfterDelay(() -> beat(token), delayMs);
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

    /**
     * 出生点吸附：把 GM 给的点垂直落到该 x 的真实地面上。
     *
     * <p>地图对象按频道隔离，而 start 时频道里可能还没有人 —— 直接拿 GM 的
     * mapId 任意一份实例做 foothold 查询即可：foothold 地形是同图各频道共享的
     * WZ 数据，落在哪份实例上结果一致。查不到地面（黑屏图/坐标离谱）时原样
     * 返回，后续流程照旧。</p>
     */
    private static Point groundSnap(int mapId, Point pos) {
        MapleMap map = mapOnGmChannel(mapId);
        if (map == null) {
            return new Point(pos);
        }
        Point ground = GCMovement.groundPointBelow(map, pos.x, pos.y);
        return ground != null ? ground : new Point(pos);
    }

    /**
     * 木桩出生点：bot 落点朝演出方向（朝 GM/观众一侧）让出 {@value #TARGET_GAP_PX}px
     * 的同 foothold 地面点。间隔太近时近战挥击/远程弹道在视觉上从木桩身体里穿出。
     *
     * <p>演出方向取 bot 落点相对 GM 起点的那一侧 —— GM 通常把机位架在起点看表演，
     * 木桩立在 bot 与 GM 之间，观众看到的是正脸出招。地面吸附失败时退回 bot 落点
     * （紧贴着演，好过把木桩丢进空中）。</p>
     */
    private static Point stageTargetPos(Character bot) {
        Point at = bot.getPosition();
        boolean left = spawnPos == null || bot.getPosition().x <= spawnPos.x;
        int tx = left ? at.x - TARGET_GAP_PX : at.x + TARGET_GAP_PX;
        Point ground = GCMovement.groundPointBelow(bot.getMap(), tx, at.y);
        return ground != null ? ground : new Point(at);
    }

    /** 让 bot 面向木桩：面向不对时补一次原地转身（录像回放，无 X 漂移）。 */
    private static void faceTarget(Character bot) {
        Monster mob = SkillShowTarget.find(bot.getMapId());
        if (mob == null) {
            return;
        }
        MovementCommands.botFaceTowardsPoint(bot, mob.getPosition());
    }

    /** 登场：生成下一个职业的 bot；全部演完则收场。 */
    private static void beatSpawn(int token) {
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
        // 生成走同步登记（addBotToServer 在 createBot 返回前完成），但世界 storage 的
        // 写入方在宿主侧并非只有本调用；createBotPollReadiness 的轮询形态保留为登记延迟的
        // 防御。等待期间先把进度喊给 GM，别让他对着黑屏猜。
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
            scheduleBeat(INTER_JOB_GAP_MS, token);
            return;
        }
        botId = created;
        phase = Phase.ARRIVE;
        waitBotReady(token, created, 30);
    }

    /**
     * 轮询等 bot 进入 channelStorage（createBotPollReadiness 的非阻塞版）：
     * 就绪 → 落地等待后开演；超时 → 记一次失败并跳过该职业。
     *
     * <p>整条轮询链与 {@link #beat} 同一把锁、同一个代际号：stop()/重新 start 之后
     * 到点的续询只做一件事——在剩余预算内按 cid 把它创建的那只 bot 退掉，然后退出。
     * 它绝不触碰木桩：本链从未执行到生成木桩的就绪分支（就绪即转入拍子链），而同图
     * 此刻可能已站着新一场表演的木桩。旧续询既不能把上一场的 bot 交给新一场，也不会
     * 把没有归属的 bot 留在图里。</p>
     */
    private static synchronized void waitBotReady(int token, int cid, int attemptsLeft) {
        if (token != generation) {
            // 生成管线的登记可能晚于本拍（这正是轮询存在的理由）：预算内继续找，
            // 找到即退；预算耗尽仍未出现就放手（终端超时路径同样处理）。
            if (!removeCreatedBot(cid) && attemptsLeft > 0) {
                MethodScheduler.runAfterDelay(() -> waitBotReady(token, cid, attemptsLeft - 1), 100);
            }
            return;
        }
        Character ready = attemptsLeft > 0 ? BotHelpers.getCharFromChannelStorage(cid) : null;
        if (ready == null) {
            if (attemptsLeft <= 0) {
                consecutiveSpawnFailures++;
                say("[skillshow] job " + cidToJob(cid) + " 的 bot " + cid
                        + " 生成后 3s 未就绪，跳过。失败 " + consecutiveSpawnFailures + "/3");
                removeShowBot();
                phase = Phase.SPAWN;
                scheduleBeat(INTER_JOB_GAP_MS, token);
                return;
            }
            MethodScheduler.runAfterDelay(() -> waitBotReady(token, cid, attemptsLeft - 1), 100);
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
            scheduleBeat(INTER_JOB_GAP_MS, token);
            return;
        }
        stepTotal = steps.size();
        stepDone = 0;
        pending = null;
        consecutiveSpawnFailures = 0;
        // 落地锚定：bot 的出生编排是录像回放（带 offset 平移），GM 在楼梯/斜坡上
        // 启动时回放终点不在 foothold 上 —— 无客户端重力替它结算，最后一帧悬空
        // 就是整场表演的姿态。开演前把位置吸附到脚下地面并广播 idle 站立帧
        // （settleRecordedReplay 同款收尾），确保站得实、不掉屏。
        MovementCommands.settleOnGround(bot);
        // 木桩（毒雾/影网/影子貔貅/能量反击的可作用目标）与 bot 同登同退，
        // 放在 bot 摆位之后：木桩出生点由 bot 落点 + TARGET_GAP_PX 推出，
        // 保证「面向木桩 + 一小段间隔」，近战/远程的攻击帧才落在木桩身上。
        boolean staged = SkillShowTarget.spawn(stageTargetPos(bot), bot.getMap(), bot.getId());
        faceTarget(bot);
        say("[skillshow] job " + cidToJob(cid) + " 登场（cid " + cid + "），"
                + stepTotal + " 个技能" + (staged ? "，木桩就位" : "") + "，"
                + (stepTotal * 4500 / 1000) + " 秒演完");
        scheduleBeat(ARRIVE_MS, token);
    }

    /** cid 反查本次表演的 job id（仅用于日志文案）。 */
    private static int cidToJob(int cid) {
        return jobIndex >= 0 && jobIndex < SHOWCASE_JOBS.length ? SHOWCASE_JOBS[jobIndex] : 0;
    }

    /** 表演节拍：有待演的先演，否则预告下一个。 */
    private static void beatStep(int token) {
        if (!alive()) {
            say("[skillshow] 表演 bot 或地图丢失，中断收场");
            stop();
            return;
        }
        if (pending != null) {
            Step step = pending;
            pending = null;
            stepDone++;
            faceTarget(bot); // 出招前再校一次面向：预告气泡的转身/杂项可能翻回原向
            long hold = step.perform(bot);
            scheduleBeat(Math.max(0, hold), token);
            return;
        }
        Step next = steps.poll();
        if (next == null) {
            phase = Phase.DESPAWN; // 本职业演完 → 退场 → 下一个登场
            scheduleBeat(0, token);
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
        scheduleBeat(ANNOUNCE_LEAD_MS, token);
    }

    /** 每拍自检：bot 仍在线且仍在原地图。 */
    private static boolean alive() {
        return bot != null && botId > 0
                && bot.getMap() != null && bot.getMapId() == mapId
                && BotHelpers.getCharFromChannelStorage(botId) == bot;
    }

    private static void removeShowBot() {
        // 就绪轮询窗口内 stop()/超时收场时 bot 字段还没赋值：按 botId 把已生成的实体找回再退，
        // 否则它就成了图里没人驱动的站桩（还可能带着 grant 的宠物/召唤兽）。
        Character target = bot != null ? bot
                : (botId > 0 ? BotHelpers.getCharFromChannelStorage(botId) : null);
        removeShowCharacter(target);
        bot = null;
        botId = -1;
        pending = null;
    }

    /**
     * 作废轮询的退场：按它自己携带的 cid 找回那只 bot，而不是看当前场次的 {@link #botId}。
     *
     * <p>只退 bot，绝不按地图动木桩：作废轮询在其就绪分支（唯一生成木桩的地方）之前就
     * 被取代，本链从未给这只 bot 生成过木桩；而它等待的这张图上此刻可能已站着新一场
     * 表演的木桩，按 mapId 放木桩会误删新一场的道具。</p>
     *
     * @return true 表示找到了这只 bot 并已退场
     */
    private static boolean removeCreatedBot(int cid) {
        Character stale = cid > 0 ? BotHelpers.getCharFromChannelStorage(cid) : null;
        if (stale == null) {
            return false;
        }
        try {
            BotGeneration.removeBotFromServer(stale);
        } catch (Throwable t) {
            BotLogger.log("[skillshow] 作废轮询退场失败: " + t);
        }
        return true;
    }

    private static void removeShowCharacter(Character target) {
        if (target == null) {
            return;
        }
        try {
            SkillShowTarget.remove(target.getMapId()); // 木桩与 bot 同退
            BotGeneration.removeBotFromServer(target);
        } catch (Throwable t) {
            BotLogger.log("[skillshow] 退场失败: " + t);
        }
    }

    // ---- 表演序列构建 ----

    /**
     * 表演序列构建：增益 → 攻击 → 召唤兽 → 木桩条件技。攻击步在木桩在场时
     * 打出真实伤害（{@link #strikeStagedTarget}），木桩缺席时为空挥。
     *
     * <p>对刚登场的表演 bot 展开序列：增益（{@link BotBuffConfig#buffsForJob}
     * 谱系并集，低转在前）→ 攻击（{@link BotAttackConfig#resolve} 的
     * single/aoe/ultimate 三槽，即 bot 实战真正会出的招；按 skillId 去重）→
     * 召唤兽（谱系并集）。enabler 门控技（金手指/潜龙出渊/战舰炮击…）的
     * enabler（超级变身/变身/海盗船）都在增益段先演，天然满足攻击门控。
     * 斗气集中（COMBO）不进序列：球环是 {@code BotComboOrb} 专属线格式，
     * 通用光环帧会打碎观察者的球环（与 BotBuffDriver 周期扫描同一豁免）。</p>
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
