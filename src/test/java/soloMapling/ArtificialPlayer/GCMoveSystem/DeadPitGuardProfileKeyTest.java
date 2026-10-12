package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * DeadPitGuard 判定缓存桶的身份回归：verdict 的每个输入都来自调用方 —— movement profile
 * （跳跃顶点决定 arc 可达高度，行走步长决定 jump/down-jump 探测的横向步与绳跳抓取模拟）与
 * class abilities（法师传送、飞侠闪跳）。因此缓存桶必须是 (profile, capabilities) 组合本身：
 *
 * <ul>
 *   <li>此前只按 apex 分桶：同跳跃、不同速度（速度装备 / 队伍 Haste）的两个 profile 会互相
 *       借到对方的"死/活"结论；</li>
 *   <li>只按 profile 分桶同样不够：同档 stat 的战士与法师能力不同，法师能blink出去的坑对战士
 *       仍然是一向陷阱，绝不可共用同一答案。</li>
 * </ul>
 *
 * <p>用 record 的整值相等做桶身份（{@code BotMovementProfile} 的构造端已按 5 点步长分桶），
 * 故任意一个维度不同都必须落在不同桶。</p>
 */
class DeadPitGuardProfileKeyTest {

    @Test
    void sameProfileMapsToTheSameBucket() {
        BotMovementProfile profile = new BotMovementProfile(105, 100);
        assertEquals(DeadPitGuard.cacheKey(profile, BotMovementCapabilities.NONE),
                DeadPitGuard.cacheKey(profile, BotMovementCapabilities.NONE));
    }

    @Test
    void speedIsPartOfTheBucket() {
        BotMovementProfile normal = new BotMovementProfile(105, 100);
        BotMovementProfile fast = new BotMovementProfile(140, 100);
        assertNotEquals(DeadPitGuard.cacheKey(normal, BotMovementCapabilities.NONE),
                DeadPitGuard.cacheKey(fast, BotMovementCapabilities.NONE),
                "同跳跃、不同速度（walkStep 参与跳跃/绳跳模拟）必须落在不同桶");
    }

    @Test
    void jumpIsPartOfTheBucket() {
        BotMovementProfile base = new BotMovementProfile(105, 100);
        BotMovementProfile highJump = new BotMovementProfile(105, 123);
        assertNotEquals(DeadPitGuard.cacheKey(base, BotMovementCapabilities.NONE),
                DeadPitGuard.cacheKey(highJump, BotMovementCapabilities.NONE),
                "同速度、不同跳跃（arc 可达高度）必须落在不同桶");
    }

    @Test
    void snowShoesIsPartOfTheBucket() {
        BotMovementProfile plain = new BotMovementProfile(105, 100);
        BotMovementProfile snow = new BotMovementProfile(105, 100, true);
        assertNotEquals(DeadPitGuard.cacheKey(plain, BotMovementCapabilities.NONE),
                DeadPitGuard.cacheKey(snow, BotMovementCapabilities.NONE),
                "桶是完整 profile：雪鞋维度也不同桶（保守，宁多几个桶不跨档复用）");
    }

    @Test
    void classAbilitiesArePartOfTheBucket() {
        BotMovementProfile profile = new BotMovementProfile(105, 100);
        assertNotEquals(DeadPitGuard.cacheKey(profile, BotMovementCapabilities.NONE),
                DeadPitGuard.cacheKey(profile, new BotMovementCapabilities(true, 0f)),
                "法师的 blink 出口绝不能借给同档战士");
        assertNotEquals(DeadPitGuard.cacheKey(profile, BotMovementCapabilities.NONE),
                DeadPitGuard.cacheKey(profile, new BotMovementCapabilities(false, 1f)),
                "飞侠的 flash jump 出口绝不能借给同档战士");
        assertEquals(DeadPitGuard.cacheKey(profile, new BotMovementCapabilities(true, 0f)),
                DeadPitGuard.cacheKey(profile, new BotMovementCapabilities(true, 0f)),
                "同能力必须同桶");
    }

    @Test
    void nullProfileAndCapabilitiesNormalize() {
        // 引擎各调用点（calculateMaxJumpHeight / walkStep / 各模拟）对 null 的规范化是
        // profileOrBase(=base)；缓存桶必须用同一把尺子，能力 null 同理归一为 NONE。
        assertSame(BotMovementProfile.base(), DeadPitGuard.cacheKey(null, null).profile());
        assertSame(BotMovementCapabilities.NONE, DeadPitGuard.cacheKey(null, null).capabilities());
    }
}
