package soloMapling.ArtificialPlayer.BotChatterSystem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCTransit;
import soloMapling.Environment.SoloMaplingLanguageConfig;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the scene routing in {@link BotChatter#pickExchange(int)}: a vehicle map must draw from the
 * {@code vehicle} section and an ordinary map from the {@code exchanges} section, so a crossing never
 * plays a town line and a town never plays a crossing line.
 *
 * <p>Membership is compared against the very lists {@code pickExchange} draws from
 * ({@link TownChatterLines#vehicleExchanges()} / {@link TownChatterLines#exchanges()}), so the check
 * is language-agnostic (those caches are shared) and does not depend on which language loaded first.
 */
class BotChatterPickExchangeTest {

    private static final int VEHICLE_MAP = 200090000;  // boat deck (GCTransit.VEHICLE_MAPS)
    private static final int TOWN_MAP = 100000000;     // Henesys (an ordinary town)

    @AfterEach
    void reset() {
        SoloMaplingLanguageConfig.setLanguageTag(SoloMaplingLanguageConfig.DEFAULT);
    }

    @Test
    void vehicleMapDrawsFromTheVehicleSection() throws Exception {
        assertTrue(GCTransit.isVehicleMap(VEHICLE_MAP), "test fixture: must be a vehicle map");
        List<String> picked = pickExchange(VEHICLE_MAP);
        assertTrue(TownChatterLines.vehicleExchanges().contains(picked),
                "a vehicle map must draw from the vehicle section, got: " + picked);
    }

    @Test
    void townMapDrawsFromTheTownSection() throws Exception {
        assertTrue(!GCTransit.isVehicleMap(TOWN_MAP), "test fixture: must not be a vehicle map");
        List<String> picked = pickExchange(TOWN_MAP);
        assertTrue(TownChatterLines.exchanges().contains(picked),
                "a town map must draw from the town section, got: " + picked);
    }

    @SuppressWarnings("unchecked")
    private static List<String> pickExchange(int mapId) throws Exception {
        Method m = BotChatter.class.getDeclaredMethod("pickExchange", int.class);
        m.setAccessible(true);
        return (List<String>) m.invoke(null, mapId);
    }
}
