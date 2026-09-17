package soloMapling.ArtificialPlayer.BotTownSystem;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the anchor-pull aggregation that decides where a town's stationed crowd concentrates.
 *
 * <p>Regression: the pull was SUMMED over every anchor, so a town's NPC cluster compounded its own
 * draw — five shopkeepers packed on one street gave that street a 5x weight and the whole cohort piled
 * there ("bot 堆积在 NPC 旁边"). The portal (0.45) then added a second, weaker hotspot on the door
 * ("堆在传送门门口"). The fixed rule is MAX, not sum: a ledge is anchored to its STRONGEST single nearby
 * anchor, so a dense cluster cannot compound, and the portal is weaker still.
 */
class TownPresenceSamplerAnchorTest {

    private static List<TownPresenceSampler.Anchor> anchors(TownPresenceSampler.Anchor... a) {
        return new ArrayList<>(List.of(a));
    }

    @Test
    void clusteredNpcsDoNotCompoundTheirPull() {
        // One NPC vs a tight cluster of five on the same pixel. Under the old summed rule the cluster
        // scored ~5x; the max rule must make them identical.
        double single = TownPresenceSampler.anchorPullAtX(1000, 0,
                anchors(new TownPresenceSampler.Anchor(1000, 0, 1.0)));
        double cluster = TownPresenceSampler.anchorPullAtX(1000, 0,
                anchors(new TownPresenceSampler.Anchor(1000, 0, 1.0),
                        new TownPresenceSampler.Anchor(1001, 0, 1.0),
                        new TownPresenceSampler.Anchor(1000, 1, 1.0),
                        new TownPresenceSampler.Anchor(999, 0, 1.0),
                        new TownPresenceSampler.Anchor(1000, -1, 1.0)));
        assertEquals(single, cluster, 1e-9,
                "a cluster of NPCs must not multiply the anchor pull (max, not sum)");
    }

    @Test
    void strongestAnchorWins() {
        // Two anchors of different strength: the pull is the stronger one, not their sum.
        double pull = TownPresenceSampler.anchorPullAtX(1000, 0,
                anchors(new TownPresenceSampler.Anchor(1000, 0, 1.0),
                        new TownPresenceSampler.Anchor(900, 0, 0.2)));
        double strongestAlone = TownPresenceSampler.anchorPullAtX(1000, 0,
                anchors(new TownPresenceSampler.Anchor(1000, 0, 1.0)));
        assertEquals(strongestAlone, pull, 1e-9, "the strongest anchor alone must define the pull");
    }

    @Test
    void emptyAnchorsPullNothing() {
        assertEquals(0.0, TownPresenceSampler.anchorPullAtX(1000, 0, anchors()), 1e-9);
    }

    @Test
    void portalIsMuchWeakerThanAnNpc() {
        // Same position, NPC vs portal strength: the portal must be a small fraction so a doorway reads
        // as mildly busy, never a spawn hotspot that draws the cohort onto the land-on pixel. Reads the
        // real constants so a retune that re-inflates the portal fails here.
        assertTrue(TownPresenceSampler.PORTAL_STRENGTH <= TownPresenceSampler.NPC_STRENGTH * 0.25,
                "portal pull must be a fraction of an NPC's (was a compounding hotspot)");
    }

    @Test
    void aPackedNpcClusterNoLongerOutweighsTheWholeMap() {
        // Effect-level check: five NPCs packed on a 400px street vs a 1200px plain ledge with no NPC
        // nearby. Under the old summed pull the street outweighed the plain 11x, so the cohort piled
        // onto it; the max rule caps that at ~1x. Weights use the documented shape span*(BASE_WEIGHT+pull)
        // with the real SIGMA_X, and the pull comes from the production anchorPullAtX so it cannot drift.
        double sigma = 260.0;
        double farGauss = Math.exp(-(1000.0 * 1000.0) / (2 * sigma * sigma)); // one NPC's pull 1000px away
        // The 5 NPCs sit on the 400px street; the 1200px plain ledge is ~1000px from all of them.
        double sumStreet = 5.0;      // old rule: the 5 pulls add up
        double maxStreet = TownPresenceSampler.anchorPullAtX(200, 0,
                anchors(new TownPresenceSampler.Anchor(200, 0, 1.0),
                        new TownPresenceSampler.Anchor(201, 0, 1.0),
                        new TownPresenceSampler.Anchor(199, 0, 1.0),
                        new TownPresenceSampler.Anchor(200, 1, 1.0),
                        new TownPresenceSampler.Anchor(200, -1, 1.0)));
        double sumPlain = 5 * farGauss;   // old rule on the plain
        double maxPlain = farGauss;       // new rule on the plain: just the strongest single NPC
        assertEquals(5.0, sumStreet, 1e-9);
        assertTrue(maxStreet > 0.99, "a co-located 5-NPC cluster still pulls ~1.0 (one NPC's worth)");

        double ratioBefore = (400 * (0.15 + sumStreet)) / (1200 * (0.15 + sumPlain));
        double ratioAfter = (400 * (0.50 + maxStreet)) / (1200 * (0.50 + maxPlain));
        assertTrue(ratioAfter < ratioBefore,
                "max-pull must reduce the hot street's dominance: " + ratioBefore + " -> " + ratioAfter);
        assertTrue(ratioAfter < 2.0,
                "a packed NPC street must not dominate the map: ratio=" + ratioAfter);
    }
}
