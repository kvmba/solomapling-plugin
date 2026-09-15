package soloMapling.ArtificialPlayer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the town occupancy contract every pause-point picker now reads.
 *
 * <p>The huddling fix routes the stationed (SocialBot) and roaming (TownWandererBot) crowds through one
 * shared registry: a ledge is "full" at {@link BotSpotClaims#TOWN_LEDGE_CAPACITY}, and the pause-point
 * pickers ({@code TownPresenceSampler.sample(..., avoidCrowdedLedges)} and {@code BotSpotPicker} /
 * {@code BotWanderSystem.pickStroll}) skip full ledges so a pause point lands on open ground.
 * {@link #isFullTrulyMeansAtCapacity()} is the load-bearing case: if the full-flag ever disagreed with
 * where claim() starts refusing, a crowd would either over-pack a ledge or leave one needlessly empty -
 * exactly the visible clumping this exists to prevent.
 */
class BotSpotClaimsTest {

    @Test
    void claimHandsOutSequentialSlotsThenRefusesAtCapacity() {
        int cap = BotSpotClaims.TOWN_LEDGE_CAPACITY;
        // Distinct spots per test so parallel test classes sharing the static registry can't collide.
        int mapId = 900_001;
        int spotId = 1;

        for (int s = 0; s < cap; s++) {
            assertEquals(s, BotSpotClaims.claim(mapId, spotId, cap, 1000 + s), "slots fill 0..cap-1 in order");
        }
        assertEquals(-1, BotSpotClaims.claim(mapId, spotId, cap, 1000 + cap), "the (cap+1)th claim is refused");
        assertEquals(cap, BotSpotClaims.holders(mapId, spotId), "exactly cap holders, no overshoot");

        for (int s = 0; s < cap; s++) {
            BotSpotClaims.release(mapId, spotId, 1000 + s);
        }
        assertEquals(0, BotSpotClaims.holders(mapId, spotId), "a full release empties the spot");
    }

    @Test
    void isFullTrulyMeansAtCapacity() {
        int cap = BotSpotClaims.TOWN_LEDGE_CAPACITY;
        int mapId = 900_002;
        int spotId = 7;

        assertFalse(BotSpotClaims.isFull(mapId, spotId), "an unclaimed spot is never full");
        for (int s = 0; s < cap - 1; s++) {
            BotSpotClaims.claim(mapId, spotId, cap, 2000 + s);
            assertFalse(BotSpotClaims.isFull(mapId, spotId), "still room below the cap");
        }
        BotSpotClaims.claim(mapId, spotId, cap, 2000 + cap - 1);
        assertTrue(BotSpotClaims.isFull(mapId, spotId), "full the moment the cap-th holder claims");

        BotSpotClaims.release(mapId, spotId, 2000);
        assertFalse(BotSpotClaims.isFull(mapId, spotId), "a release reopens the ledge");
    }

    @Test
    void reClaimIsIdempotent() {
        int mapId = 900_003;
        int spotId = 3;
        int first = BotSpotClaims.claim(mapId, spotId, BotSpotClaims.TOWN_LEDGE_CAPACITY, 3000);
        assertEquals(first, BotSpotClaims.claim(mapId, spotId, BotSpotClaims.TOWN_LEDGE_CAPACITY, 3000),
                "a holder keeps its slot on re-claim");
        assertEquals(1, BotSpotClaims.holders(mapId, spotId), "re-claim does not spend a second slot");
        BotSpotClaims.release(mapId, spotId, 3000);
    }

    @Test
    void sectionPartitionsTheSpanWithoutOverlap() {
        // Two holders on a [0,400] ledge get disjoint half-open ranges whose union is the whole span.
        int[] s0 = BotSpotClaims.section(0, 400, 0, 2);
        int[] s1 = BotSpotClaims.section(0, 400, 1, 2);
        assertEquals(0, s0[0]);
        assertEquals(200, s0[1]);
        assertEquals(200, s1[0]);
        assertEquals(400, s1[1]);
        // A single holder (capacity 1) owns the entire span.
        int[] whole = BotSpotClaims.section(0, 400, 0, 1);
        assertEquals(0, whole[0]);
        assertEquals(400, whole[1]);
    }
}
