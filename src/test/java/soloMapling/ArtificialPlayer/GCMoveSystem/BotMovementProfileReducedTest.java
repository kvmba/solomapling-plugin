package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Guards {@link BotMovementProfile#reducedBy} — the follower profile the pet system uses to make
 * pet N a touch slower / lower-jumping than its owner by {@code (index + 1) * 2} stat points.
 *
 * <p>The value must be EXACT (pet 1 on a 105-speed owner is 103, not re-rounded to 105) yet never
 * drop below the base stat, and the bot builders must still bucket their own profiles so a bot's
 * navigation-graph cache key is unchanged.</p>
 */
class BotMovementProfileReducedTest {

    @Test
    void reducedBySubtractsExactly() {
        BotMovementProfile owner = new BotMovementProfile(125, 123);
        BotMovementProfile pet1 = owner.reducedBy(2);
        assertEquals(123, pet1.totalSpeedStat(), "pet 1 speed = owner - 2 (exact, not bucketed)");
        assertEquals(121, pet1.totalJumpStat(), "pet 1 jump = owner - 2 (exact)");

        BotMovementProfile pet2 = owner.reducedBy(4);
        assertEquals(121, pet2.totalSpeedStat());
        assertEquals(119, pet2.totalJumpStat());

        BotMovementProfile pet3 = owner.reducedBy(6);
        assertEquals(119, pet3.totalSpeedStat());
        assertEquals(117, pet3.totalJumpStat());
    }

    @Test
    void reducedByFloorsAtTheBaseStat() {
        // A weak owner (only 1 above base) must not push a pet below an unbuffed character.
        BotMovementProfile weak = new BotMovementProfile(101, 101);
        BotMovementProfile pet3 = weak.reducedBy(6);
        assertEquals(100, pet3.totalSpeedStat(), "floored at the base stat");
        assertEquals(100, pet3.totalJumpStat(), "floored at the base stat");

        // Even pet 1 on a 101-speed owner must not dip under base.
        BotMovementProfile pet1 = weak.reducedBy(2);
        assertEquals(101 - 2 < 100 ? 100 : 101 - 2, pet1.totalSpeedStat());
    }

    @Test
    void canonicalConstructorPreservesExactStats() {
        // The canonical constructor clamps but no longer buckets (the bot builders bucket instead),
        // so a follower's exact reduced value survives — and a plain exact value is kept as-is.
        assertEquals(105, new BotMovementProfile(105, 105).totalSpeedStat(), "exact value preserved");
        assertEquals(123, new BotMovementProfile(123, 123).totalJumpStat(), "cap value preserved");
        assertEquals(100, new BotMovementProfile(100, 100).reducedBy(0).totalSpeedStat());
    }
}
