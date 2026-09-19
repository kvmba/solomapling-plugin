package soloMapling.ArtificialPlayer.BotAttackSystem;

import org.gms.constants.id.ItemId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the gun-bullet tier ladder: six tiers spread evenly across the level range, plain Bullets up
 * to 49 and Eternal Bullets from 150.
 */
class GunBulletSelectorTest {

    @Test
    void picksTheBestTierForEachLevelBand() {
        // Plain below 50, then +1 tier every 25 levels (50/75/100/125/150).
        assertEquals(2330000, GunBulletSelector.forLevel(1));
        assertEquals(2330000, GunBulletSelector.forLevel(49));
        assertEquals(2330001, GunBulletSelector.forLevel(50));
        assertEquals(2330001, GunBulletSelector.forLevel(74));
        assertEquals(2330002, GunBulletSelector.forLevel(75));
        assertEquals(2330002, GunBulletSelector.forLevel(99));
        assertEquals(2330003, GunBulletSelector.forLevel(100));
        assertEquals(2330003, GunBulletSelector.forLevel(124));
        assertEquals(2330004, GunBulletSelector.forLevel(125));
        assertEquals(2330004, GunBulletSelector.forLevel(149));
        assertEquals(2330005, GunBulletSelector.forLevel(150));
        assertEquals(2330005, GunBulletSelector.forLevel(200));
    }

    @Test
    void startsAtThePlainBulletBelowFifty() {
        // Everything up to level 49 fires the base Bullet constant.
        assertEquals(ItemId.BULLET, GunBulletSelector.forLevel(1));
        assertEquals(ItemId.BULLET, GunBulletSelector.forLevel(10));
        assertEquals(ItemId.BULLET, GunBulletSelector.forLevel(49));
    }

    @Test
    void tierIsMonotonicNonDecreasingWithLevel() {
        // A bullet never gets weaker as the bot levels (a ladder, unlike the star pool).
        int prev = GunBulletSelector.forLevel(1);
        for (int lv = 1; lv <= 200; lv++) {
            int cur = GunBulletSelector.forLevel(lv);
            assertTrue(cur >= prev, "bullet tier regressed at level " + lv);
            prev = cur;
        }
    }
}
