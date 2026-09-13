package soloMapling.ArtificialPlayer.BotMountSystem;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the deterministic half of the mount system: which bots own a mount and which
 * kit (骑宠 + 鞍子 pair) they get, decided purely from the character id. This is the
 * contract that lets a persistent companion keep the same mount across restarts with no
 * extra storage, and keeps the population's mount share near {@link BotMount#OWN_CHANCE}.
 */
class BotMountDeterminismTest {

    @Test
    void ownershipIsStableForTheSameId() {
        for (int cid = 100; cid < 5_000; cid++) {
            assertEquals(BotMount.ownsMountForId(cid), BotMount.ownsMountForId(cid),
                    "same cid must always answer the same way");
            assertEquals(BotMount.kitIndexForId(cid), BotMount.kitIndexForId(cid),
                    "same cid must always map to the same kit");
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
    void kitIndexIsMinusOneExactlyForNonOwners() {
        for (int cid = 100; cid < 5_000; cid++) {
            boolean owns = BotMount.ownsMountForId(cid);
            int kit = BotMount.kitIndexForId(cid);
            if (owns) {
                assertTrue(kit >= 0, "owner cid " + cid + " must map to a real kit");
            } else {
                assertEquals(-1, kit, "non-owner cid " + cid + " must map to -1");
            }
        }
    }

    @Test
    void everyAllowListedMountIsReachable() {
        Set<Integer> seen = new HashSet<>();
        for (int cid = 100; cid < 100_000; cid++) {
            int kit = BotMount.kitIndexForId(cid);
            if (kit >= 0) {
                seen.add(kit);
            }
        }
        assertEquals(BotMount.mountIds().length, seen.size(),
                "every allow-listed mount kit should be reachable by some cid");
    }

    @Test
    void theAllowListOnlyCarriesRealMountItems() {
        // Guard the sanity of the shipped allow-list without a live WZ: a v83 mount item is
        // 1902xxx and its saddle is 1912xxx, both with a positive id.
        for (int mountId : BotMount.mountIds()) {
            assertTrue(mountId >= 1902000 && mountId < 1903000,
                    "mount id " + mountId + " is not a 1902xxx 骑宠 (TamingMob) item");
        }
        assertFalse(BotMount.mountIds().length == 0);
    }
}
