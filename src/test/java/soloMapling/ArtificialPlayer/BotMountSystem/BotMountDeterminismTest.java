package soloMapling.ArtificialPlayer.BotMountSystem;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the deterministic half of the mount system: which bots own a mount and which
 * one, decided purely from the character id. This is the contract that lets a
 * persistent companion keep the same mount across restarts with no extra storage, and
 * keeps the population's mount share near {@link BotMount#OWN_CHANCE}.
 */
class BotMountDeterminismTest {

    @Test
    void ownershipIsStableForTheSameId() {
        for (int cid = 100; cid < 5_000; cid++) {
            assertEquals(BotMount.ownsMountForId(cid), BotMount.ownsMountForId(cid),
                    "same cid must always answer the same way");
            assertEquals(BotMount.mountItemForId(cid), BotMount.mountItemForId(cid),
                    "same cid must always map to the same mount");
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
    void everyOwnedMountComesFromTheAllowList() {
        Set<Integer> allowed = new HashSet<>();
        for (int id : BotMount.mountIds()) {
            allowed.add(id);
        }
        assertFalse(allowed.isEmpty());
        for (int cid = 100; cid < 5_000; cid++) {
            assertTrue(allowed.contains(BotMount.mountItemForId(cid)),
                    "cid " + cid + " mapped to a mount outside the allow-list");
        }
    }

    @Test
    void theAllowListSpreadsAcrossItsItems() {
        // Every allowed mount must be reachable, or an entry is dead weight (and a
        // typo'd id would silently never appear).
        Set<Integer> seen = new HashSet<>();
        for (int cid = 100; cid < 100_000; cid++) {
            seen.add(BotMount.mountItemForId(cid));
        }
        assertEquals(BotMount.mountIds().length, seen.size(),
                "every allow-listed mount should be reachable by some cid");
    }
}
