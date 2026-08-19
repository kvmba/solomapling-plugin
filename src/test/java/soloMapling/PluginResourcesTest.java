package soloMapling;

import org.junit.jupiter.api.Test;
import soloMapling.Environment.PluginResources;

import java.io.BufferedReader;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginResourcesTest {

    @Test
    void opensIgnPoolFromClasspath() throws Exception {
        assertTrue(PluginResources.exists("FreeMarket/FMNameDesc/randomRealMaplestoryIGNs.txt"));
        try (BufferedReader r = new BufferedReader(PluginResources.openReader(
                "FreeMarket/FMNameDesc/randomRealMaplestoryIGNs.txt"))) {
            String line = r.readLine();
            assertTrue(line != null && !line.isBlank());
        }
    }

    @Test
    void listsMovementPacketCsvBasenames() {
        List<String> names = PluginResources.listBasenames(
                "ArtificialPlayer/BotMovementSystem/movementDataPackets/map910000000", ".csv");
        assertFalse(names.isEmpty());
        assertTrue(names.stream().anyMatch(n -> n.startsWith("m")));
    }
}
