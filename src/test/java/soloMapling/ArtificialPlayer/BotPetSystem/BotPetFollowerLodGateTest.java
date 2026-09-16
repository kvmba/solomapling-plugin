package soloMapling.ArtificialPlayer.BotPetSystem;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the pet follower's LOD gate — the decision that keeps the whole-world pet tick nearly
 * free while no real player is watching.
 *
 * <p>Context: the follower runs one shared 200ms tick over every pet-carrying bot and, with the
 * observability gate removed (a fix for a spawn flash on map entry), used to resolve every pet's
 * ground foothold on every tick of every map — a host {@code FootholdTree.findBelow} per pet per
 * tick. With thousands of bots that was a measurable CPU regression with no players online. The
 * fix skips that lookup when nobody can see it; the pet still glides (position stays fresh), only
 * the foothold query is gated.
 *
 * <p>{@code shouldLookUpFoothold} is the pure heart of that gate: query only when the pet is
 * landed (gravity applies) AND the map is observed. It is the exact predicate the two lookup sites
 * in {@code moveTowards} call, so pinning it pins the regression.
 *
 * <p>Note: on an unobserved map the tick still keeps each pet's POSITION fresh with pure arithmetic
 * (no host query, no packet — see {@code syncUnobservedPositions}), so a joining player never sees a
 * pet frozen where it stood when the map went dark. The gate here covers only the costly
 * foothold/gravity lookup, which stays off while unwatched.
 */
class BotPetFollowerLodGateTest {

    @Test
    void looksUpOnlyWhenLandedAndObserved() {
        assertTrue(BotPetFollower.shouldLookUpFoothold(false, true),
                "landed on an observed map: resolve the foothold");
    }

    @Test
    void skipsLookupWhenUnobserved() {
        // The regression: an unobserved map (the whole world with no players) must not pay the
        // per-pet foothold query, even though the pet is landed.
        assertFalse(BotPetFollower.shouldLookUpFoothold(false, false),
                "landed but unobserved: no player can see the pet's foothold, so skip the query");
    }

    @Test
    void skipsLookupWhenFloating() {
        // Water / airborne / rope: the pet floats with fh 0, so a ground query would be wrong even
        // on an observed map.
        assertFalse(BotPetFollower.shouldLookUpFoothold(true, true),
                "floating on an observed map: no ground foothold applies");
        assertFalse(BotPetFollower.shouldLookUpFoothold(true, false),
                "floating and unwatched: no lookup");
    }
}
