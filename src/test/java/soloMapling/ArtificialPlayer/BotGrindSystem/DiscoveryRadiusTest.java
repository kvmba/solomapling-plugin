package soloMapling.ArtificialPlayer.BotGrindSystem;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertTrue;

/*
 * Guards TrainingMapChooser.MAX_HOPS — the "anywhere" discovery radius a max-level training bot uses
 * to find a hunting ground.
 *
 * It was 20, from when a bot's continent was assumed to end at the coast. On the live world graph the
 * PORTAL-ONLY path (GCMovement.mapsWithinHopsByDepth — discovery deliberately excludes taxi/ferry, so
 * a bot can't "discover" another continent across a boat) joins a continent end to end, and the far
 * content sits well past 20: Ludibrium town -> 怪兽地区 (221030601) is 57 hops, because the only route
 * climbs the whole 玩具塔 one floor at a time (221020000 -> ... -> 221024400); Orbis -> its far fields
 * is 44. At 20 a high bot never saw the far half of the continent it stood on.
 *
 * These pin the two invariants, so a future "tighten it back down" has to argue with the numbers.
 * Both fields are private constants; there is no server or WZ dependency here.
 */
class DiscoveryRadiusTest {

    // Deepest measured intra-continent span on the live portal-only graph: Ludibrium -> 怪兽地区,
    // across the whole 玩具塔 staircase. Pinned as a floor, so raising the radius stays allowed.
    private static final int DEEPEST_INTRA_CONTINENT_SPAN = 57;

    private static int constant(String className, String field) throws Exception {
        Field f = Class.forName(className).getDeclaredField(field);
        f.setAccessible(true);
        return f.getInt(null);
    }

    private static int discoveryRadius() throws Exception {
        return constant("soloMapling.ArtificialPlayer.BotGrindSystem.TrainingMapChooser", "MAX_HOPS");
    }

    @Test
    void coversTheDeepestIntraContinentSpan() throws Exception {
        assertTrue(discoveryRadius() >= DEEPEST_INTRA_CONTINENT_SPAN,
                "discovery radius (" + discoveryRadius() + ") must reach the deepest intra-continent "
                        + "content (" + DEEPEST_INTRA_CONTINENT_SPAN + " hops), or a high bot never "
                        + "sees the far half of its own continent");
    }

    @Test
    void staysWithinTheRouteCeiling() throws Exception {
        int discovery = discoveryRadius();
        int travel = constant("soloMapling.ArtificialPlayer.GCMoveSystem.GCTravel", "MAX_HOPS");
        assertTrue(discovery <= travel,
                "a discovered map (" + discovery + ") must stay routable (" + travel + ")");
    }
}
