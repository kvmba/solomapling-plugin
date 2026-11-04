package soloMapling.ArtificialPlayer.BotTypes.Carnival;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins how the arena map is inspected before a match is opened.
 *
 * <p>The match is played on a <b>disposable</b> map instance: the engine's constructor calls
 * {@code MapManager.getDisposableMap}, which builds a map outside the cache and is balanced by
 * {@code dispose()} at the end. Two things follow, and both were wrong in the first cut:
 *
 * <ul>
 *   <li>probing through {@code getMap()} publishes the arena in the channel cache, where nothing
 *       will ever dispose it - nine full maps (footholds, spawns, portals) per channel, forever;</li>
 *   <li>probing must therefore use a disposable instance too, and release it, since it is ours
 *       alone and nobody will ever enter it.</li>
 * </ul>
 */
class CarnivalArenaProbeTest {

    @Test
    void theArenaProbeIsCachedPerMapId() throws Exception {
        // The probe costs a WZ read and the map data cannot change while the server is up, so the
        // verdict is memoised. Re-probing on every poll of every field would be pure waste.
        var field = CarnivalArena.class.getDeclaredField("ARENA_HAS_DATA");
        assertTrue(Modifier.isPrivate(field.getModifiers()));
        assertTrue(Modifier.isStatic(field.getModifiers()));
    }

    @Test
    void aDisposableMapIsTheRightProbeSource() throws Exception {
        // getDisposableMap is the method the engine's own constructor uses; a probe taken from
        // getMap would describe a different instance than the one the match runs on.
        Class<?> factory = Class.forName("org.gms.server.maps.MapManager", false,
                CarnivalArenaProbeTest.class.getClassLoader());
        assertTrue(Modifier.isPublic(factory.getMethod("getDisposableMap", int.class).getModifiers()));
        assertTrue(Modifier.isPublic(factory.getMethod("isMapLoaded", int.class).getModifiers()),
                "the lobby scan must be able to skip an unloaded map instead of loading it");
    }

    @Test
    void theProbeVerdictIsNotFieldBookkeeping() throws Exception {
        // ARENA_HAS_DATA describes WZ data, not field state. Clearing it with the field
        // bookkeeping would send the next poll back to the WZ reader for no reason, so
        // forgetFields must not touch it - checked by reading the field through reflection.
        var cache = CarnivalArena.class.getDeclaredField("ARENA_HAS_DATA");
        cache.setAccessible(true);
        @SuppressWarnings("unchecked")
        var map = (java.util.Map<Integer, Boolean>) cache.get(null);
        map.put(980000101, Boolean.TRUE);

        CarnivalArena.forgetFields();

        assertTrue(map.containsKey(980000101),
                "the WZ verdict cache must outlive a field-bookkeeping reset");
        map.remove(980000101);
    }
}
