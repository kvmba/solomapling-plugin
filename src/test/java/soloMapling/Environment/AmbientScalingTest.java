package soloMapling.Environment;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@code scale} applies to the ambient bot counts that are still hard-coded in
 * EnvironmentManager (filler crowds, JQ/pet-park loiterers, gacha onlookers, OPQ lobby).
 *
 * <p>These counts are decorative crowds, so {@code scale} is a legitimate knob. It is
 * deliberately NOT applied to functional/seat-based bots (tutorial, game-zone hosts, blackjack
 * dealer + 5 seats) or to the probabilistic Free Market fill - see scaledAmbient's javadoc.
 */
class AmbientScalingTest {

    @AfterEach
    void resetConfig() {
        EnvironmentPopulationConfig.setConfigPath(null);
        EnvironmentPopulationConfig.reload();
    }

    private static void useScale(double scale) throws Exception {
        Path tmp = Files.createTempFile("env-scale-", ".yaml");
        Files.writeString(tmp, """
                version: 1
                scale: %s
                waves:
                  training:
                    enabled: false
                """.formatted(scale));
        EnvironmentPopulationConfig.setConfigPath(tmp.toString());
        EnvironmentPopulationConfig.reload();
        Files.deleteIfExists(tmp);
    }

    @Test
    void scaleOnePreservesTheHistoricalCounts() throws Exception {
        useScale(1.0);
        // Every hard-coded base must survive scale=1 untouched: 1->1, 15->15, 129->129.
        assertEquals(1, EnvironmentManager.scaledAmbient(1));
        assertEquals(3, EnvironmentManager.scaledAmbient(3));
        assertEquals(15, EnvironmentManager.scaledAmbient(15));
        assertEquals(129, EnvironmentManager.scaledAmbient(129));
    }

    @Test
    void scaleHalvesCrowds() throws Exception {
        useScale(0.5);
        assertEquals(8, EnvironmentManager.scaledAmbient(15));   // round(7.5) = 8
        assertEquals(2, EnvironmentManager.scaledAmbient(3));
        assertEquals(65, EnvironmentManager.scaledAmbient(129)); // round(64.5) = 65
    }

    @Test
    void smallScaleThinsButNeverEmptiesASpot() throws Exception {
        useScale(0.01);
        // An ambient spot keeps at least one bot - scale thins a crowd, it doesn't
        // delete the location. (Contrast PopulationPlan.scaled, which floors at 0.)
        assertEquals(1, EnvironmentManager.scaledAmbient(1));
        assertEquals(1, EnvironmentManager.scaledAmbient(15));
        assertEquals(1, EnvironmentManager.scaledAmbient(129));
        assertEquals(2, EnvironmentManager.scaledAmbient(150)); // round(1.5) = 2
    }

    @Test
    void zeroBaseStaysZeroAtEveryScale() throws Exception {
        for (double s : new double[]{0.0, 0.25, 1.0, 4.0}) {
            useScale(s);
            assertEquals(0, EnvironmentManager.scaledAmbient(0));
            assertEquals(0, EnvironmentManager.scaledAmbient(-5));
        }
    }

    @Test
    void scaleGrowsCrowds() throws Exception {
        useScale(2.0);
        assertEquals(30, EnvironmentManager.scaledAmbient(15));
        assertEquals(258, EnvironmentManager.scaledAmbient(129));
    }

    @Test
    void zeroScaleStillKeepsOneBot() throws Exception {
        // scale: 0 means "no YAML-driven counts", but ambient crowds keep their floor of 1
        // rather than producing a divide-by-zero-looking empty world.
        useScale(0.0);
        assertEquals(1, EnvironmentManager.scaledAmbient(15));
    }

    @Test
    void roundingIsHalfUpAcrossBases() throws Exception {
        useScale(0.5);
        // round() is half-up: 2.5 -> 3, 3.5 -> 4. Guards against a future switch to truncation,
        // which would silently shrink every crowd at fractional scales.
        assertEquals(3, EnvironmentManager.scaledAmbient(5));   // round(2.5)
        assertEquals(4, EnvironmentManager.scaledAmbient(7));   // round(3.5)
        assertEquals(1, EnvironmentManager.scaledAmbient(2));   // round(1.0)
        assertEquals(1, EnvironmentManager.scaledAmbient(1));   // round(0.5), floored at 1
    }

    @Test
    void totalAmbientCountScalesRoughlyLinearly() throws Exception {
        // The 36 randomizeCount bases across the five filler sites sum to 129.
        // Expectation over the +/-1 jitter must track the scale.
        int[] bases = {5, 2, 3, 3, 6, 6, 4, 8, 4, 3,          // Henesys
                4, 3, 3, 3, 3, 4, 5, 3, 3,                    // Market (commented line excluded)
                4, 4, 5, 2, 4, 1, 2, 1, 2, 4,                 // Park
                3, 2, 3,                                        // Potion shop
                4, 6, 3};                                       // Game zone (commented line excluded)
        int baseTotal = 0;
        for (int b : bases) {
            baseTotal += b;
        }

        useScale(1.0);
        int full = 0;
        for (int b : bases) {
            full += EnvironmentManager.scaledAmbient(b);
        }
        assertEquals(baseTotal, full, "scale=1 must reproduce the hard-coded total exactly");

        useScale(0.5);
        int half = 0;
        for (int b : bases) {
            half += EnvironmentManager.scaledAmbient(b);
        }
        // Summing rounded halves differs from half the sum by well under the base count.
        assertTrue(Math.abs(half - baseTotal / 2.0) <= bases.length,
                "half-scale total " + half + " should be near " + baseTotal / 2.0);
        assertTrue(half < full, "half scale must produce fewer bots than full scale");
    }
}
