package soloMapling.companion.persistence;

import org.junit.jupiter.api.Test;
import soloMapling.companion.progression.CompanionCareerBuild;

import javax.sql.DataSource;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionSchemaMigratorTest {

    @Test
    void ownsPackagedCompanionSchemaInAnIsolatedLocation() throws Exception {
        assertEquals("flyway_solomapling_schema_history",
                CompanionSchemaMigrator.HISTORY_TABLE);
        assertEquals("classpath:db/migration/solomapling",
                CompanionSchemaMigrator.MIGRATION_LOCATION);
        var configuration = CompanionSchemaMigrator.configuredFlyway(noConnectionDataSource())
                .getConfiguration();
        assertTrue(configuration.isBaselineOnMigrate());
        assertEquals("0", configuration.getBaselineVersion().getVersion());

        String resource = "db/migration/solomapling/V1__create_companion_tables.sql";
        try (InputStream stream = CompanionSchemaMigrator.class.getClassLoader()
                .getResourceAsStream(resource)) {
            assertNotNull(stream, "Companion migration must be packaged in the plugin");
            String sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            for (String table : List.of(
                    "bot_profiles",
                    "bot_relationships",
                    "bot_memories",
                    "bot_knowledge",
                    "bot_activity_log")) {
                assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS `" + table + "`"),
                        () -> "Missing table migration: " + table);
            }
        }

        String careerMigration =
                "db/migration/solomapling/V2__add_companion_career_build.sql";
        try (InputStream stream = CompanionSchemaMigrator.class.getClassLoader()
                .getResourceAsStream(careerMigration)) {
            assertNotNull(stream, "Career build migration must be packaged in the plugin");
            String sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(sql.contains("`career_build`"));
            assertTrue(sql.contains("NOT NULL DEFAULT ''"));
        }
    }

    @Test
    void adoptsExistingManualJobsWithoutChangingTheirBranch() {
        assertEquals(CompanionCareerBuild.BOWMASTER,
                CompanionSchemaMigrator.careerForExisting(311, 42L));
        assertEquals(CompanionCareerBuild.BISHOP,
                CompanionSchemaMigrator.careerForExisting(232, 7L));
        assertEquals(100,
                CompanionSchemaMigrator.careerForExisting(100, 99L).firstJobId());
    }

    private static DataSource noConnectionDataSource() {
        return new DataSource() {
            @Override
            public Connection getConnection() throws SQLException {
                throw new SQLException("not used");
            }

            @Override
            public Connection getConnection(String username, String password) throws SQLException {
                throw new SQLException("not used");
            }

            @Override
            public PrintWriter getLogWriter() {
                return null;
            }

            @Override
            public void setLogWriter(PrintWriter out) {
            }

            @Override
            public void setLoginTimeout(int seconds) {
            }

            @Override
            public int getLoginTimeout() {
                return 0;
            }

            @Override
            public Logger getParentLogger() throws SQLFeatureNotSupportedException {
                throw new SQLFeatureNotSupportedException();
            }

            @Override
            public <T> T unwrap(Class<T> iface) throws SQLException {
                throw new SQLException("not a wrapper");
            }

            @Override
            public boolean isWrapperFor(Class<?> iface) {
                return false;
            }
        };
    }
}
