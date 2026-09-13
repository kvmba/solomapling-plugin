package soloMapling.ArtificialPlayer.BotMountSystem;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the deterministic half of the mount system: which bots own a mount and which kit
 * (骑宠 + 鞍子 pair) they get, decided purely from the character id and level. This is the
 * contract that lets a persistent companion keep the same mount across restarts with no
 * extra storage, and keeps the population's mount share near {@link BotMount#OWN_CHANCE}.
 */
class BotMountDeterminismTest {

    @Test
    void ownershipIsStableForTheSameId() {
        for (int cid = 100; cid < 5_000; cid++) {
            assertEquals(BotMount.ownsMountForId(cid), BotMount.ownsMountForId(cid),
                    "same cid must always answer the same way");
        }
    }

    @Test
    void ownershipShareIsNearTheTargetRate() {
        int owners = 0;
        int total = 100_000;
        for (int cid = 100; cid < 100 + total; cid++) {
            if (BotMount.ownsMountForId(cid)) {
                owners++;
            }
        }
        double rate = owners / (double) total;
        // Wide tolerance: the hash spread is not a perfect uniform, but it must not be
        // wildly off (which would mean the mix() collapsed to a constant).
        assertTrue(rate > BotMount.OWN_CHANCE - 0.05 && rate < BotMount.OWN_CHANCE + 0.05,
                "owner share " + rate + " should sit near " + BotMount.OWN_CHANCE);
    }

    @Test
    void nonOwnersMapToMinusOneAtEveryLevel() {
        for (int cid = 100; cid < 5_000; cid++) {
            if (BotMount.ownsMountForId(cid)) {
                continue;
            }
            assertEquals(-1, BotMount.kitIndexForId(cid, 10), "non-owner cid " + cid);
            assertEquals(-1, BotMount.kitIndexForId(cid, 200), "non-owner cid " + cid);
        }
    }

    @Test
    void aLevel70OwnerAlwaysGetsAnEligibleKit() {
        // At level 70 only the Hog kit (reqLevel 70) is eligible; a selected index must exist.
        for (int cid = 100; cid < 20_000; cid++) {
            if (!BotMount.ownsMountForId(cid)) {
                continue;
            }
            int idx = BotMount.kitIndexForId(cid, 70);
            assertTrue(idx >= 0 && idx < BotMount.mountIds().length,
                    "level-70 owner cid " + cid + " must select a real kit, got " + idx);
        }
    }

    @Test
    void everyAllowListedMountIsReachableAtSomeLevel() {
        Set<Integer> seen = new HashSet<>();
        for (int cid = 100; cid < 200_000; cid++) {
            if (!BotMount.ownsMountForId(cid)) {
                continue;
            }
            for (int level : new int[]{70, 120, 200}) {
                int idx = BotMount.kitIndexForId(cid, level);
                if (idx >= 0) {
                    seen.add(idx);
                }
            }
        }
        assertEquals(BotMount.mountIds().length, seen.size(),
                "every allow-listed mount kit should be reachable by some (cid, level)");
    }

    @Test
    void theAllowListOnlyCarriesExplorerMountItems() {
        // v83 explorer mounts are 1902xxx (TamingMob); the Cygnus family (1912005 saddle /
        // 1902005-1902007 mounts) is a different, explorer-incompatible set and must not appear.
        for (int mountId : BotMount.mountIds()) {
            assertTrue(mountId >= 1902000 && mountId < 1903000,
                    "mount id " + mountId + " is not a 1902xxx 骑宠 (TamingMob) item");
        }
        assertFalse(BotMount.mountIds().length == 0);
    }
}
