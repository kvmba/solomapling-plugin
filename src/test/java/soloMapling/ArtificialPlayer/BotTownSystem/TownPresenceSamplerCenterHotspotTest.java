package soloMapling.ArtificialPlayer.BotTownSystem;

import org.gms.server.maps.Foothold;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the centre-hot, anchor-free town scatter contract.
 *
 * <p>Regression: the sampler used to weight every ledge by an NPC/portal gaussian pull, so a town's
 * stationed crowd piled onto the shop street / doorway ("bot 堆积在 NPC 旁边"). The rule is now
 * anchor-free and centre-hot: a ledge's weight is its walkable WIDTH times its centrality in X
 * ({@link TownPresenceSampler#centerHotspotWeight}), so no ledge is favoured by a nearby NPC, shop or
 * portal, and the crowd thickens toward the map's horizontal middle (~4.4x) rather than over the ends.
 */
class TownPresenceSamplerCenterHotspotTest {

    private static GCMovement.Ledge ledge(int regionId, int minX, int maxX) {
        int centerX = (minX + maxX) / 2;
        return new GCMovement.Ledge(regionId, minX, maxX, centerX, 0);
    }

    @Test
    void weightIsProportionalToWalkableWidth() {
        // Equal span -> equal weight; a wider ledge weighs proportionally more. No map context (no NPCs,
        // no portals) can change this, which is the whole point: placement cannot favour a hot street.
        double narrow = TownPresenceSampler.ledgeWeight(ledge(1, 0, 200), TownOverrides.EMPTY);
        double wide = TownPresenceSampler.ledgeWeight(ledge(2, 0, 1000), TownOverrides.EMPTY);
        assertEquals(200.0, narrow, 1e-9);
        assertEquals(1000.0, wide, 1e-9);
    }

    @Test
    void banZoneZeroesAndBoostZoneMultipliesTheWeight() {
        // A ledge whose centre sits in a ban zone is dropped entirely; a boost zone multiplies the weight.
        GCMovement.Ledge l = ledge(1, 0, 400); // centreX 200
        TownOverrides ban = new TownOverrides(
                List.of(new TownOverrides.Zone(100, -50, 300, 50, 1.0)), List.of(), List.of());
        TownOverrides boost = new TownOverrides(
                List.of(), List.of(new TownOverrides.Zone(100, -50, 300, 50, 2.5)), List.of());
        assertEquals(0.0, TownPresenceSampler.ledgeWeight(l, ban), 1e-9, "banned ledge weighs nothing");
        assertEquals(1000.0, TownPresenceSampler.ledgeWeight(l, boost), 1e-9, "400px * 2.5 boost");
    }

    @Test
    void noLedgeIsFavouredByADenseNpcCluster() {
        // Effect-level: the sampler no longer reads NPCs at all, so a 5-NPC cluster on a 400px street can
        // no longer out-draw a 1200px plain ledge. Under the old anchor pull the street scored ~5x (or
        // ~3x with the max rule); now the plain simply outweighs the street by its width (3x) and takes
        // 3x the picks - the opposite of clustering.
        double street = TownPresenceSampler.ledgeWeight(ledge(1, 0, 400), TownOverrides.EMPTY);
        double plain = TownPresenceSampler.ledgeWeight(ledge(2, 0, 1200), TownOverrides.EMPTY);
        assertEquals(400.0, street, 1e-9);
        assertEquals(1200.0, plain, 1e-9);
        assertTrue(plain > street,
                "the wider plain ledge must outweigh the narrower street regardless of any NPCs on it");
    }

    @Test
    void wideDrawSpreadsAcrossManyLedges() {
        // One field of equal-width ledges with equal weights: the draw touches each once (capacity 1)
        // rather than piling onto a single ledge, before the capacity filter spills any remainder.
        List<GCMovement.Ledge> ledges = new ArrayList<>();
        for (int id = 1; id <= 10; id++) {
            ledges.add(ledge(id, 0, 200)); // capacity 1 each
        }
        double[] weights = new double[ledges.size()];
        for (int i = 0; i < weights.length; i++) {
            weights[i] = TownPresenceSampler.ledgeWeight(ledges.get(i), TownOverrides.EMPTY);
        }

        java.util.Map<Integer, List<Integer>> occupied = new java.util.HashMap<>();
        int n = 10;
        for (int i = 0; i < n; i++) {
            GCMovement.Ledge picked = TownPresenceSampler.pickLedge(ledges, weights, occupied);
            occupied.computeIfAbsent(picked.regionId(), k -> new ArrayList<>()).add(0);
        }
        assertEquals(n, occupied.values().stream().mapToInt(List::size).sum(), "every slot lands somewhere");
        assertEquals(n, occupied.size(),
                "equal-width ledges each take one bot - the draw does not favour any single ledge");
    }

    @Test
    void footholdFloorBandKeepsOnlyTheLowestPlatforms() {
        // GachaBot's floor-only contract, on the terrain (foothold) twin: a low flat floor plus a platform
        // one step up (a sloped ramp within tolerance) stay; a genuinely higher platform is dropped.
        // Y grows downward, so larger Y is lower.
        Foothold floor = new Foothold(new Point(0, 300), new Point(400, 300), 1);
        Foothold step = new Foothold(new Point(500, 300), new Point(700, 330), 2);  // sloped, mid 315
        Foothold high = new Foothold(new Point(0, 50), new Point(400, 50), 3);      // way above the floor

        List<Foothold> band = TownPresenceSampler.footholdFloorBand(List.of(floor, step, high));
        assertTrue(band.contains(floor), "the floor itself is in the band");
        assertTrue(band.contains(step), "a sloped platform a step up is still floor band");
        assertTrue(!band.contains(high), "a platform far above the floor is excluded");
        assertEquals(2, band.size());
    }

    @Test
    void footholdFloorBandOfAnEmptyListIsEmpty() {
        assertTrue(TownPresenceSampler.footholdFloorBand(List.of()).isEmpty());
    }

    @Test
    void centralityPeaksAtTheCentreAndThinsToTheEnds() {
        // X only: 1.0 at the map's horizontal midpoint, and ~0.226 (1 / ~4.4x) at either extreme end.
        assertEquals(1.0, TownPresenceSampler.centerHotspotWeight(500, 0, 1000), 1e-9, "centre is full weight");
        double end = TownPresenceSampler.centerHotspotWeight(0, 0, 1000);
        assertEquals(0.2262, end, 1e-3, "an extreme end is ~1/4.4 of the centre");
        assertEquals(end, TownPresenceSampler.centerHotspotWeight(1000, 0, 1000), 1e-9, "both ends are symmetric");
        // Monotone: nearer the centre is never lighter than farther out.
        assertTrue(TownPresenceSampler.centerHotspotWeight(400, 0, 1000)
                > TownPresenceSampler.centerHotspotWeight(100, 0, 1000));
    }

    @Test
    void centralityIsFlatOnAZeroWidthSpan() {
        // A degenerate span (a single column) has no centre to bias toward - must not divide by zero.
        assertEquals(1.0, TownPresenceSampler.centerHotspotWeight(50, 50, 50), 1e-9);
    }
}
