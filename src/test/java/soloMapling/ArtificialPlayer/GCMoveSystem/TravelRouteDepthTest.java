package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertTrue;

/*
 * Guards GCTravel.MAX_HOPS — the depth cap on GCWorldGraph.route().
 *
 * The cap matters because a failed route is NOT a no-op: when route() returns null, GCTravel
 * bare-warps the bot the whole rest of the way. So a ceiling that is too small does not merely
 * "avoid a long walk" — it teleports the bot across continents, skipping every boat, train and
 * elevator on the way (the observed "bots cross the world instantly and never ride anything").
 *
 * It was 20, sized for Victoria Island. It is now 64, sized for the whole connected world. These
 * tests pin the two invariants that justify a value this large, so a future "tighten it back down"
 * has to argue with the numbers:
 *
 *   1. It must clear the widest REAL crossing. On the live graph that is 地球防御本部 -> 玩具城 =
 *      49 hops, because the only way is to climb the whole 玩具塔 one floor at a time
 *      (221020000 -> 221020001 ... -> 221024400). Anything below ~50 bare-warps that trip.
 *   2. It must stay >= the discovery radius (TrainingMapChooser.MAX_HOPS), so anything a bot can
 *      DISCOVER it can also ROUTE to — the contract stated on the field itself.
 *
 * Both values are private/package-private constants, so this reads them by reflection; there is no
 * server or WZ dependency.
 */
class TravelRouteDepthTest {

    // The widest real crossing measured on the live world graph: 地球防御本部 -> 玩具城, across the
    // whole 玩具塔 staircase. Pinned as a floor, not an equality, so raising the cap stays allowed.
    private static final int WIDEST_REAL_CROSSING_HOPS = 49;

    private static int constant(String className, String field) throws Exception {
        Field f = Class.forName(className).getDeclaredField(field);
        f.setAccessible(true);
        return f.getInt(null);
    }

    private static int travelCeiling() throws Exception {
        return constant("soloMapling.ArtificialPlayer.GCMoveSystem.GCTravel", "MAX_HOPS");
    }

    @Test
    void clearsTheWidestRealWorldCrossing() throws Exception {
        assertTrue(travelCeiling() > WIDEST_REAL_CROSSING_HOPS,
                "route ceiling (" + travelCeiling() + ") must exceed the widest real crossing ("
                        + WIDEST_REAL_CROSSING_HOPS + "), or that trip bare-warps the bot");
    }

    @Test
    void stillCoversTheDiscoveryRadius() throws Exception {
        int travel = travelCeiling();
        int discovery = constant("soloMapling.ArtificialPlayer.BotGrindSystem.TrainingMapChooser", "MAX_HOPS");
        assertTrue(travel >= discovery,
                "anything a bot can DISCOVER (" + discovery + ") must be ROUTABLE (" + travel + ")");
    }
}
