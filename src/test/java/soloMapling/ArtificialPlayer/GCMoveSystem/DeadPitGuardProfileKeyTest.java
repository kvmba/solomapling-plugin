package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * DeadPitGuard 判定缓存桶的身份回归：verdict 的每个输入都来自调用方的 movement profile ——
 * 跳跃顶点门控链式攀爬（jumpChainEscapes 的 apex 带），行走步长决定绳跳抓取模拟的横向
 * 迈步（canReachRopeFromGround / simulateGroundJumpRopeGrab 按 walkStep 迈步）。因此缓存
 * 桶必须是 profile 本身，而不是手挑的某个维度：此前只按 apex 分桶，同跳跃、不同速度
 * （速度装备 / 队伍 Haste）的两个 profile 会互相借到对方的“死/活”结论。
 *
 * <p>用 record 的整值相等做桶身份（{@code BotMovementProfile} 的构造端已按 5 点步长
 * 分桶），故任意一个维度不同都必须落在不同桶。</p>
 */
class DeadPitGuardProfileKeyTest {

    @Test
    void sameProfileMapsToTheSameBucket() {
        BotMovementProfile profile = new BotMovementProfile(105, 100);
        assertEquals(DeadPitGuard.cacheKey(profile), DeadPitGuard.cacheKey(profile));
    }

    @Test
    void speedIsPartOfTheBucket() {
        BotMovementProfile normal = new BotMovementProfile(105, 100);
        BotMovementProfile fast = new BotMovementProfile(140, 100);
        assertNotEquals(DeadPitGuard.cacheKey(normal), DeadPitGuard.cacheKey(fast),
                "同跳跃、不同速度（walkStep 参与绳跳模拟）必须落在不同桶");
    }

    @Test
    void jumpIsPartOfTheBucket() {
        BotMovementProfile base = new BotMovementProfile(105, 100);
        BotMovementProfile highJump = new BotMovementProfile(105, 123);
        assertNotEquals(DeadPitGuard.cacheKey(base), DeadPitGuard.cacheKey(highJump),
                "同速度、不同跳跃（apex 参与链式攀爬）必须落在不同桶");
    }

    @Test
    void snowShoesIsPartOfTheBucket() {
        BotMovementProfile plain = new BotMovementProfile(105, 100);
        BotMovementProfile snow = new BotMovementProfile(105, 100, true);
        assertNotEquals(DeadPitGuard.cacheKey(plain), DeadPitGuard.cacheKey(snow),
                "桶是完整 profile：雪鞋维度也不同桶（保守，宁多几个桶不跨档复用）");
    }

    @Test
    void nullProfileNormalizesToBase() {
        // 引擎各调用点（calculateMaxJumpHeight / walkStep / 各模拟）对 null 的规范化是
        // profileOrBase(=base)；缓存桶必须用同一把尺子，否则 null 调用查到 base 的另一格。
        assertSame(BotMovementProfile.base(), DeadPitGuard.cacheKey(null));
    }
}
