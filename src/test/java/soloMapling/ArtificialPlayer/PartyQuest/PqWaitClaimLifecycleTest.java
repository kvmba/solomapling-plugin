package soloMapling.ArtificialPlayer.PartyQuest;

import org.junit.jupiter.api.Test;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the stage-NPC wait-spot claim table's lifecycle.
 *
 * <p>The table is keyed by a map INSTANCE's identity hash (two concurrent runs of the same quest
 * must not share a ring), and the release used to walk every set to find the bot's point without
 * ever dropping the emptied entry. Every room instance a run passed through therefore left an
 * empty set behind for the life of the process: identity hashes are not reused, so the table grew
 * while its entries held nothing. It now removes the point from the bot's OWN set and drops the
 * entry when it empties.
 *
 * <p>Driven through the package seams (recordWaitClaim / holdsWaitClaim / waitClaimsFor /
 * releaseWaitClaims) so the bookkeeping is covered without a live Character or MapleMap.
 */
class PqWaitClaimLifecycleTest {

    @Test
    void releaseDropsTheEmptiedInstanceEntry() {
        int key = 0x7E57_0001;
        Point spot = new Point(120, -215);
        PqActions.recordWaitClaim(4242, key, spot);
        try {
            assertTrue(PqActions.holdsWaitClaim(4242), "the bot must hold its claim");
            assertSame(spot, PqActions.waitClaimsFor(key).iterator().next());

            PqActions.releaseWaitClaims(4242);

            assertFalse(PqActions.holdsWaitClaim(4242), "the claim must be gone");
            assertNull(PqActions.waitClaimsFor(key),
                    "the emptied instance entry must be dropped, not left as a leak");
        } finally {
            PqActions.releaseWaitClaims(4242);
        }
    }

    @Test
    void releasingOneOfTwoClaimsKeepsTheSiblingSet() {
        int sharedKey = 0x7E57_0002;
        Point a = new Point(100, -215);
        Point b = new Point(124, -215);
        PqActions.recordWaitClaim(5001, sharedKey, a);
        PqActions.recordWaitClaim(5002, sharedKey, b);
        try {
            PqActions.releaseWaitClaims(5001);
            assertFalse(PqActions.holdsWaitClaim(5001));
            assertTrue(PqActions.holdsWaitClaim(5002), "the teammate's claim survives");
            assertTrue(PqActions.waitClaimsFor(sharedKey).contains(b));
            assertFalse(PqActions.waitClaimsFor(sharedKey).contains(a));

            PqActions.releaseWaitClaims(5002);
            assertNull(PqActions.waitClaimsFor(sharedKey),
                    "the last release drops the entry");
        } finally {
            PqActions.releaseWaitClaims(5001);
            PqActions.releaseWaitClaims(5002);
        }
    }

    @Test
    void releaseIsIdempotentAndSafeWithoutAClaim() {
        // A bot that never claimed (the stage-less rooms), or is released twice, must not throw
        // and must not disturb anyone else's table.
        int key = 0x7E57_0003;
        Point spot = new Point(1, 2);
        PqActions.recordWaitClaim(6001, key, spot);
        try {
            PqActions.releaseWaitClaims(6001);
            PqActions.releaseWaitClaims(6001); // second release: no claim, no key, no set
            assertFalse(PqActions.holdsWaitClaim(6001));
            assertNull(PqActions.waitClaimsFor(key));
            PqActions.releaseWaitClaims(777_777); // never claimed at all
        } finally {
            PqActions.releaseWaitClaims(6001);
        }
    }

    @Test
    void distinctInstancesKeepDistinctRings() {
        // The whole reason for the identity key: two concurrent runs must not see each other's
        // spots, so releasing one instance's claim must leave the other instance untouched.
        int runA = 0x7E57_0004;
        int runB = 0x7E57_0005;
        PqActions.recordWaitClaim(7001, runA, new Point(10, 10));
        PqActions.recordWaitClaim(7002, runB, new Point(10, 10)); // same pixel, other run
        try {
            PqActions.releaseWaitClaims(7001);
            assertNull(PqActions.waitClaimsFor(runA));
            assertTrue(PqActions.waitClaimsFor(runB).contains(new Point(10, 10)),
                    "the other run's identical spot must survive");
            assertEquals(1, PqActions.waitClaimsFor(runB).size());
        } finally {
            PqActions.releaseWaitClaims(7001);
            PqActions.releaseWaitClaims(7002);
        }
    }
}
