package soloMapling.FreeMarket.ShopOfferSystem;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A visitor's chat counter is read only while they are still in the shop, and nothing clears it when
 * they leave — so without a bound the maps grow by one entry per visitor forever. A long-lived
 * server meets a lot of visitors.
 */
class ShopOfferWelcomeGrowthTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Integer> counts() throws Exception {
        Field f = ShopOfferWelcome.class.getDeclaredField("playerMessageCounts");
        f.setAccessible(true);
        return (Map<String, Integer>) f.get(null);
    }

    @SuppressWarnings("unchecked")
    private static Set<String> hinted() throws Exception {
        Field f = ShopOfferWelcome.class.getDeclaredField("hintedPlayers");
        f.setAccessible(true);
        return (Set<String>) f.get(null);
    }

    private static int maxTracked() throws Exception {
        Field f = ShopOfferWelcome.class.getDeclaredField("MAX_TRACKED_VISITS");
        f.setAccessible(true);
        return (int) f.get(null);
    }

    private static void guard() throws Exception {
        Method m = ShopOfferWelcome.class.getDeclaredMethod("preventUnboundedGrowth");
        m.setAccessible(true);
        m.invoke(null);
    }

    @Test
    void entriesWellUnderTheBoundAreKept() throws Exception {
        Map<String, Integer> counts = counts();
        counts.clear();
        hinted().clear();
        try {
            counts.put("1_100", 1);
            guard();
            assertTrue(counts.containsKey("1_100"), "a normal number of visitors must not be swept");
        } finally {
            counts.clear();
            hinted().clear();
        }
    }

    @Test
    void pastTheBoundTheMapsAreEmptied() throws Exception {
        Map<String, Integer> counts = counts();
        counts.clear();
        hinted().clear();
        try {
            int limit = maxTracked();
            for (int i = 0; i < limit + 1; i++) {
                counts.put("1_" + i, 1);
            }
            guard();
            assertTrue(counts.size() <= 1,
                    "past the high-water mark the counters must be cleared, not left to grow");
            assertFalse(counts.size() > limit, "the map must never sit above its bound");
        } finally {
            counts.clear();
            hinted().clear();
        }
    }
}
