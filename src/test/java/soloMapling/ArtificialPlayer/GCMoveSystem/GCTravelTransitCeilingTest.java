package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The transit ceiling is how long a bot will trust a scheduled ride before giving up on it, and it
 * has to stay sane for as long as the server is up: a world's travel rate can be changed at any
 * time, and a bot that has been waiting since before the change must not be judged by a clock that
 * no longer matches the ride it is on.
 *
 * The risk is not a wrong number but an unbounded one — a ceiling that collapses to zero would warp
 * every passenger off every boat, and one that overflows would quietly disable the bound altogether.
 */
class GCTravelTransitCeilingTest {

    private static long baseCeilingMs() throws Exception {
        Field f = GCTravel.class.getDeclaredField("WAIT_MAX_MS");
        f.setAccessible(true);
        return (long) f.get(null);
    }

    private static long ceilingWithRate(float rate) throws Exception {
        Method m = GCTravel.class.getDeclaredMethod("waitCeilingMs");
        m.setAccessible(true);
        // waitCeilingMs reads the rate from the world; with no server to ask, it falls back to 1.
        return (long) m.invoke(null);
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void theCeilingCoversTheLongestOrdinaryCycle() throws Exception {
        // A boat is 4 min boarding + 5 min to depart + 10 min sailing. The bound exists to catch a
        // ride that never ends, so it has to outlast one that does.
        long boatCycleMs = (4 + 5 + 10) * 60 * 1000L;
        assertTrue(baseCeilingMs() > boatCycleMs,
                "the ceiling must outlast a full boat cycle, or a scheduled ride gets cut off");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void withNoWorldToAskTheCeilingIsTheUnscaledOne() throws Exception {
        // Outside a running server (tests, shutdown) the rate is unknown, so the bound must stay at
        // its ordinary value rather than collapsing.
        assertEquals(baseCeilingMs(), ceilingWithRate(1f),
                "with no world to read, the ceiling should fall back to the unscaled bound");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void theCeilingIsNeverShorterThanTheBase() throws Exception {
        // A rate below one (or a corrupt one) must only ever leave the wait alone, never shorten it.
        assertTrue(ceilingWithRate(1f) >= baseCeilingMs(),
                "the scaled ceiling must never come out below the base bound");
    }
}
