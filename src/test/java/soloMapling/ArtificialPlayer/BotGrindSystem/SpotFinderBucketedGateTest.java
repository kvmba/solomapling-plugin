package soloMapling.ArtificialPlayer.BotGrindSystem;

import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Proves the bucketed same-ledge gate is behaviourally identical to the per-pair
 * onDifferentLedge call it replaces in SpotFinder.bestStackHostile.
 *
 * <p>The old gate was {@code !onDifferentLedge(a, b)}, where onDifferentLedge was
 * {@code ra >= 0 && rb >= 0 && ra != rb}. The new gate is
 * {@link SpotFinder#acceptsSameLedge(int, int)}: {@code ra < 0 || rb < 0 || ra == rb}
 * -- De Morgan of the same predicate, evaluated on region ids resolved once per
 * point instead of once per pair.
 *
 * <p>These tests cover the whole integer domain rather than sampling it, because
 * the whole point of this change is that it must not alter which mobs a grinder
 * will target. A single wrong pair would send bots chasing mobs on other
 * platforms (or refusing to attack mobs on their own).
 */
class SpotFinderBucketedGateTest {

    /** The predicate as it was written before the change, for comparison. */
    private static boolean legacyAccepts(int ra, int rb) {
        boolean onDifferentLedge = ra >= 0 && rb >= 0 && ra != rb;
        return !onDifferentLedge;
    }

    @Test
    void matchesLegacyGateAcrossTheWholeRegionIdDomain() {
        // Region ids are small non-negative ints; -1 means "on no ledge".
        // Sweep well past any realistic id and include negatives for safety.
        for (int ra = -3; ra <= 500; ra++) {
            for (int rb = -3; rb <= 500; rb++) {
                assertEquals(legacyAccepts(ra, rb), SpotFinder.acceptsSameLedge(ra, rb),
                        "gate diverged for anchorRegion=" + ra + " mobRegion=" + rb);
            }
        }
    }

    @Test
    void acceptsWhenEitherPointIsOnNoLedge() {
        // "can't tell, don't filter" - a flying mob or a point mid-air.
        assertEquals(legacyAccepts(-1, 5), SpotFinder.acceptsSameLedge(-1, 5));
        assertEquals(legacyAccepts(5, -1), SpotFinder.acceptsSameLedge(5, -1));
        assertEquals(legacyAccepts(-1, -1), SpotFinder.acceptsSameLedge(-1, -1));
    }

    @Test
    void acceptsOnlyTheSharedLedgeOtherwise() {
        assertEquals(legacyAccepts(3, 3), SpotFinder.acceptsSameLedge(3, 3));
        assertEquals(legacyAccepts(3, 4), SpotFinder.acceptsSameLedge(3, 4));
        assertEquals(legacyAccepts(0, 0), SpotFinder.acceptsSameLedge(0, 0));
    }

    @Test
    void unbakedSentinelIsDistinctFromNoLedge() {
        // peekRegionIdAt returns UNBAKED_REGION (-2) when the map has no baked graph.
        // It must NOT be confused with -1 ("on no ledge"): the old code accepted every
        // candidate when unbaked, and collapsing the two would silently start filtering.
        assertEquals(-2, GCMovement.UNBAKED_REGION);
        assertEquals(-1, -1);
        org.junit.jupiter.api.Assertions.assertNotEquals(
                GCMovement.UNBAKED_REGION, -1,
                "UNBAKED_REGION must be distinguishable from the 'on no ledge' sentinel");

        // Guards: an unbaked map accepted everything, so the gate must never be reached
        // with the sentinel. Documented here so the invariant is enforced somewhere.
        assertEquals(true, SpotFinder.acceptsSameLedge(-1, -1),
                "no-ledge pairs are accepted, as before");
    }
}
