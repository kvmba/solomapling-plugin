package soloMapling.ArtificialPlayer.BotAttackSystem;

import org.gms.constants.id.ItemId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the gun-bullet tier ladder: a gun bot fires the best bullet it has out-levelled, so a 110+
 * gunslinger shows Eternal Bullets and a fresh one shows plain Bullets.
 */
class GunBulletSelectorTest {

    @Test
    void picksTheBestTierForEachLevelBand() {
        // v83 bullet reqLevels: 2330000=10, 2330001=30, 2330002=50, 2330003=70, 2330004=90, 2330005=110.
        assertEquals(2330000, GunBulletSelector.forLevel(10));
        assertEquals(2330000, GunBulletSelector.forLevel(29));
        assertEquals(2330001, GunBulletSelector.forLevel(30));
        assertEquals(2330001, GunBulletSelector.forLevel(49));
        assertEquals(2330002, GunBulletSelector.forLevel(50));
        assertEquals(2330003, GunBulletSelector.forLevel(70));
        assertEquals(2330004, GunBulletSelector.forLevel(90));
        assertEquals(2330005, GunBulletSelector.forLevel(110));
        assertEquals(2330005, GunBulletSelector.forLevel(200));
    }

    @Test
    void startsAtThePlainBulletBelowTheFirstBand() {
        // Below the lowest bullet reqLevel the bot still fires the base Bullet constant.
        assertEquals(ItemId.BULLET, GunBulletSelector.forLevel(1));
        assertEquals(ItemId.BULLET, GunBulletSelector.forLevel(9));
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
