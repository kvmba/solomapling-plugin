package soloMapling.ArtificialPlayer.BotTownSystem;

import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the anchor-weighted town scatter can't stack a whole cohort on one platform.
 *
 * <p>Before the capacity term, {@code sample()} drew ledges purely by anchor weight (NPC/portal pull
 * over a tiny floor), so a hot floor near a shop drew every pick and the cohort piled onto it. The fix
 * caps each ledge at a width-derived number of hosted bots ({@link TownPresenceSampler#ledgeCapacity})
 * and skips full ledges in the draw. These tests pin that contract: the spatial distribution is
 * otherwise free to change, but "no ledge absorbs more than it can hold while room exists elsewhere"
 * must always hold.
 */
class TownPresenceSamplerCapacityTest {

    private static GCMovement.Ledge ledge(int regionId, int minX, int maxX) {
        int centerX = (minX + maxX) / 2;
        return new GCMovement.Ledge(regionId, minX, maxX, centerX, 0);
    }

    @Test
    void capacityIsOnePerBandWidthAndAtLeastOne() {
        // 200px = one band, 400 = two, 1000 = five; anything under one band still hosts exactly one.
        assertEquals(1, TownPresenceSampler.ledgeCapacity(ledge(1, 0, 199)));
        assertEquals(1, TownPresenceSampler.ledgeCapacity(ledge(1, 0, 200)));
        assertEquals(2, TownPresenceSampler.ledgeCapacity(ledge(1, 0, 400)));
        assertEquals(5, TownPresenceSampler.ledgeCapacity(ledge(1, 0, 1000)));
        assertEquals(1, TownPresenceSampler.ledgeCapacity(ledge(1, 50, 50))); // zero-width ledge
    }

    @Test
    void neverPicksAFullLedgeWhileAnotherHasRoom() {
        // One wide hot ledge (capacity 5) next to a narrow one (capacity 1), with the hot ledge weighted
        // so heavily it would pre-fix take every draw. Sample exactly the total capacity (6): every pick
        // must land on a ledge that still had room, and none may exceed its cap.
        GCMovement.Ledge hot = ledge(1, 0, 1000);   // capacity 5
        GCMovement.Ledge cold = ledge(2, 0, 50);    // capacity 1
        List<GCMovement.Ledge> ledges = List.of(hot, cold);
        double[] weights = {1000.0, 1.0};
        int totalCapacity = TownPresenceSampler.ledgeCapacity(hot) + TownPresenceSampler.ledgeCapacity(cold);

        Map<Integer, List<Integer>> occupied = new HashMap<>();
        for (int i = 0; i < totalCapacity; i++) {
            GCMovement.Ledge picked = TownPresenceSampler.pickLedge(ledges, weights, occupied);
            int holders = occupied.getOrDefault(picked.regionId(), List.of()).size();
            assertTrue(holders < TownPresenceSampler.ledgeCapacity(picked),
                    "picked a full ledge (region " + picked.regionId() + ", holders=" + holders
                            + ") while sampling #" + i + " with room still available");
            occupied.computeIfAbsent(picked.regionId(), k -> new ArrayList<>()).add(0);
        }
        assertEquals(totalCapacity, occupied.values().stream().mapToInt(List::size).sum(),
                "capacity slots all used up");
    }

    @Test
    void spreadsAcrossLedgesInsteadOfStacking() {
        // A field of identical-width ledges with one heavily weighted: the cap must force the cohort to
        // fan out, so no single ledge ends up holding (nearly) everything.
        List<GCMovement.Ledge> ledges = new ArrayList<>();
        ledges.add(ledge(1, 0, 400)); // capacity 2, the "hot" one
        for (int id = 2; id <= 8; id++) {
            ledges.add(ledge(id, 0, 400));
        }
        double[] weights = new double[ledges.size()];
        weights[0] = 500.0;                 // hot ledge dominates the raw weight
        for (int i = 1; i < ledges.size(); i++) {
            weights[i] = 1.0;
        }

        Map<Integer, List<Integer>> occupied = new HashMap<>();
        int n = 14; // 7 ledges x capacity 2 = exactly enough
        for (int i = 0; i < n; i++) {
            GCMovement.Ledge picked = TownPresenceSampler.pickLedge(ledges, weights, occupied);
            occupied.computeIfAbsent(picked.regionId(), k -> new ArrayList<>()).add(0);
        }

        assertEquals(n, occupied.values().stream().mapToInt(List::size).sum(), "every slot lands somewhere");
        int hotHolders = occupied.getOrDefault(1, List.of()).size();
        assertEquals(2, hotHolders, "the hot ledge is capped at its capacity, not the whole cohort");
        assertTrue(occupied.size() >= 2, "the cohort spread onto more than one ledge");
    }
}
