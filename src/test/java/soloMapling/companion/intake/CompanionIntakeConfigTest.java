package soloMapling.companion.intake;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionIntakeConfigTest {

    /**
     * The packaged file is the one that ships, so it must parse at all. A typo
     * in a shipped yaml is otherwise silent.
     *
     * <p>It ships switched off: a world should not start filling itself with
     * companions until somebody decides it should. What this pins down is that
     * the file is readable and that the rest of the shipped values are sane for
     * whoever turns it on.</p>
     */
    @Test
    void packagedFileLoadsAndShipsDisabled() {
        CompanionIntakeConfig config = CompanionIntakeConfig.load();

        assertFalse(config.enabled(),
                "intake should ship off, got interval-seconds=" + config.intervalSeconds());
        assertTrue(config.maxTotal() > 0, "max-total must be positive");
        assertTrue(config.worldId() >= 0, "world-id must not be negative");
        assertEquals("Asia/Shanghai", config.timezone(),
                "the default zone is the one its sessions read against");
        assertTrue(config.source().contains(CompanionIntakeConfig.RESOURCE_PATH),
                "source should name the file it read: " + config.source());
    }

    /** The defaults a missing or broken file falls back to must also be off. */
    @Test
    void defaultsMatchTheShippedFile() {
        CompanionIntakeConfig defaults = CompanionIntakeConfig.disabled("(test)");
        CompanionIntakeConfig shipped = CompanionIntakeConfig.load();
        assertEquals(defaults.intervalSeconds(), shipped.intervalSeconds());
        assertEquals(defaults.maxTotal(), shipped.maxTotal());
        assertEquals(defaults.worldId(), shipped.worldId());
        assertEquals(defaults.timezone(), shipped.timezone());
    }

    @Test
    void zeroIntervalMeansDisabled() {
        CompanionIntakeConfig config = new CompanionIntakeConfig(0, 2000, 0, "UTC", "test");
        assertFalse(config.enabled());
    }

    @Test
    void missingOrBrokenFileFallsBackToDisabled() {
        // A config file that cannot be read must not stop the server: intake is
        // a convenience, and the rest of the plugin still has to come up.
        CompanionIntakeConfig disabled = CompanionIntakeConfig.disabled("(test)");
        assertFalse(disabled.enabled());
        assertEquals(2000, disabled.maxTotal());
        assertEquals(0, disabled.worldId());
    }

    /**
     * The override copy wins over the packaged one. It is the only way an
     * operator changes these without rebuilding the plugin, so it is worth
     * pinning down that the resolution order reaches it.
     */
    @Test
    void overrideCopyIsReadInsteadOfThePackagedOne() throws Exception {
        Path override = Path.of(
                soloMapling.Environment.PluginResources.OVERRIDE_FS_ROOT
                        + CompanionIntakeConfig.RESOURCE_PATH);
        boolean existed = Files.isRegularFile(override);
        byte[] previous = existed ? Files.readAllBytes(override) : null;
        try {
            Files.createDirectories(override.getParent());
            Files.writeString(override, """
                    interval-seconds: 7
                    max-total: 3
                    world-id: 1
                    timezone: UTC
                    """, StandardCharsets.UTF_8);

            CompanionIntakeConfig config = CompanionIntakeConfig.load();
            assertEquals(7, config.intervalSeconds());
            assertEquals(3, config.maxTotal());
            assertEquals(1, config.worldId());
            assertEquals("UTC", config.timezone());
            assertTrue(config.source().contains("override"),
                    "the startup line should say the override won: " + config.source());
        } finally {
            if (existed) {
                Files.write(override, previous);
            } else {
                Files.deleteIfExists(override);
            }
        }
    }
}
