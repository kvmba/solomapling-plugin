package soloMapling.ArtificialPlayer.SkillShowSystem;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 技能观测表演的序列不变量（source-level：{@code org.gms.client.Job} 的类初始化需要宿主
 * Spring 上下文，与 {@code BotBuffConfigSourceTest}/{@code BotSummonTableTest} 同款姿势）：
 *
 * <ol>
 *   <li>12 个展示终职在 SHOWCASE_JOBS 表里各出现一次、升序；</li>
 *   <li>表演 bot 的三张技能来源表覆盖每个终职（增益 / 攻击 / 召唤兽至少其一）；</li>
 *   <li>门控攻击技（金手指/潜龙出渊/战舰炮击/鱼雷）的 enabler（变身/超级变身/海盗船）
 *       都注册在该职的增益表 —— 增益段先演，天然满足攻击门控；</li>
 *   <li>斗气集中（COMBO）在控制器里被显式豁免（球环是 BotComboOrb 专属线格式）。</li>
 * </ol>
 */
class SkillShowSequenceTest {

    private static final Path CONTROLLER = Paths.get(
            "src/main/java/soloMapling/ArtificialPlayer/SkillShowSystem/SkillShowController.java");
    private static final Path BUFF_CONFIG = Paths.get(
            "src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotBuffConfig.java");
    private static final Path ATTACK_CONFIG = Paths.get(
            "src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotAttackConfig.java");

    private static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + p, e);
        }
    }

    @Test
    void showcaseJobListIsAscendingAndComplete() {
        String src = read(CONTROLLER);
        Matcher m = Pattern.compile("\\b(\\d{3})\\b").matcher(
                src.substring(src.indexOf("SHOWCASE_JOBS"), src.indexOf("private sealed")));
        List<Integer> ids = new ArrayList<>();
        while (m.find()) {
            int id = Integer.parseInt(m.group(1));
            if (id >= 100 && id <= 600) {
                ids.add(id);
            }
        }
        assertEquals(12, ids.size(), "12 个 4 转终职: " + ids);
        for (int i = 1; i < ids.size(); i++) {
            assertTrue(ids.get(i) > ids.get(i - 1), "按 job id 升序登场");
        }
        Set<Integer> distinct = new HashSet<>(ids);
        assertEquals(12, distinct.size());
    }

    @Test
    void controllerExemptsComboAndUsesTheLiveRegistries() {
        String src = read(CONTROLLER);
        assertTrue(src.contains("Crusader.COMBO"), "COMBO 必须显式豁免");
        assertTrue(src.contains("BotBuffConfig.buffsForJob"), "增益来自注册表");
        assertTrue(src.contains("BotAttackConfig.resolve"), "攻击来自注册表");
        assertTrue(src.contains("BotSummonTable.summonsForJobId"), "召唤兽来自注册表");
        assertTrue(src.contains("BotChatbubble"), "同图气泡预告");
    }

    @Test
    void everyShowcaseJobIsRegisteredInBothRegistries() {
        String buffs = read(BUFF_CONFIG);
        String attacks = read(ATTACK_CONFIG);
        // 每个终职在两表里必须有自己的一行（或其谱系祖先行覆盖其攻击 —— 黑骑士
        // 的攻击继承 DRAGONKNIGHT 的 CRUSHER，攻击表无 DARKKNIGHT 行，因此
        // 攻击侧允许「自己或谱系祖先」命中；增益侧仍要求自己的一行）。
        int[][] jobs = {
                {112, 112}, {122, 122}, {132, 131},           // (buffJob, attackJob-or-ancestor)
                {212, 212}, {222, 222}, {232, 232},
                {312, 312}, {322, 322}, {412, 412}, {422, 422},
                {512, 512}, {522, 522}
        };
        for (int[] pair : jobs) {
            String buffName = jobEnumName(pair[0]);
            String atkName = jobEnumName(pair[1]);
            assertTrue(buffs.contains("put(Job." + buffName + ","), buffName + " 增益已注册");
            assertTrue(attacks.contains("put(Job." + atkName + ","),
                    atkName + "（或其攻击来源）已注册");
        }
    }

    private static String jobEnumName(int job) {
        return switch (job) {
            case 112 -> "HERO";
            case 122 -> "PALADIN";
            case 132 -> "DARKKNIGHT";
            case 131 -> "DRAGONKNIGHT";
            case 212 -> "FP_ARCHMAGE";
            case 222 -> "IL_ARCHMAGE";
            case 232 -> "BISHOP";
            case 312 -> "BOWMASTER";
            case 322 -> "MARKSMAN";
            case 412 -> "NIGHTLORD";
            case 422 -> "SHADOWER";
            case 512 -> "BUCCANEER";
            case 522 -> "CORSAIR";
            default -> throw new IllegalStateException();
        };
    }

    @Test
    void pirateGatedAttacksHaveTheirEnablerBuff() {
        String buffs = read(BUFF_CONFIG);
        // 512 冲锋队长：DEMOLITION/DRAGON_STRIKE 需要 SUPER_TRANSFORMATION(5121003)；
        // 522 船长：BATTLESHIP_CANNON/TORPEDO 需要 BATTLE_SHIP(5221006)。
        assertTrue(buffs.contains("Buccaneer.SUPER_TRANSFORMATION"), "超级变身 5121003");
        assertTrue(buffs.contains("Corsair.BATTLE_SHIP"), "海盗船 5221006");
        // 超级变身与海盗船必须在各自的 4 转增益行里（先演增益 → 门控已开）。
        for (String line : buffs.split("\n")) {
            if (line.contains("put(Job.BUCCANEER")) {
                assertTrue(line.contains("SUPER_TRANSFORMATION"), "BUCCANEER 增益行含超级变身");
            }
            if (line.contains("put(Job.CORSAIR")) {
                assertTrue(line.contains("BATTLE_SHIP"), "CORSAIR 增益行含海盗船");
            }
        }
    }

    @Test
    void controllerSequenceFollowsBuffAttackSummonOrder() {
        String src = read(CONTROLLER);
        int buffAt = src.indexOf("buffsForJob(job)");
        int attackAt = src.indexOf("BotAttackConfig.resolve(job");
        int summonAt = src.indexOf("summonsForJobId(job.getId())");
        assertTrue(buffAt >= 0 && attackAt > buffAt && summonAt > attackAt,
                "表演顺序：增益 → 攻击 → 召唤兽");
    }
}
