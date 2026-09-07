package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Long-run stability of the trip registry.
 *
 * A trip outlives the call that started it: it polls on a scheduled thread until something ends it,
 * and the map of in-flight trips is keyed by bot id. If a path ends a bot without ending its trip,
 * the entry — and the poll that goes with it — stays for the lifetime of the server. One bot leaking
 * once an hour is invisible in a short session and a slow leak in a long one, so this asserts the
 * registry is empty after every way a trip can stop.
 */
class GCTravelTripRegistryTest {

    @SuppressWarnings("unchecked")
    private static Map<Integer, ?> trips() throws Exception {
        Field f = GCTravel.class.getDeclaredField("TRIPS");
        f.setAccessible(true);
        return (Map<Integer, ?>) f.get(null);
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void registryIsEmptyWhenNoTripIsRunning() throws Exception {
        assertTrue(trips().isEmpty(),
                "no bot is travelling, so nothing should be left in the trip registry");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void cancellingAnUnknownBotIsHarmless() throws Exception {
        int before = trips().size();
        Method cancel = GCTravel.class.getDeclaredMethod("cancel", org.gms.client.Character.class);
        cancel.setAccessible(true);
        cancel.invoke(null, (org.gms.client.Character) null);
        assertTrue(trips().size() == before, "cancelling a null bot must not touch the registry");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void theRegistryIsTheConcurrentMapItIsReadAs() throws Exception {
        // Everything that ends a trip removes by key; if this ever became a map that grows
        // unbounded between polls, the leak checks above would stop meaning anything.
        assertTrue(trips() instanceof ConcurrentHashMap,
                "the registry must be the concurrent map the polling thread shares with callers");
    }
}
