package soloMapling.companion.persistence;

import org.flywaydb.core.Flyway;
import org.gms.util.DatabaseConnection;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;

/**
 * Owns SoloMapling's schema lifecycle independently from host migrations.
 */
public final class CompanionSchemaMigrator {

    static final String HISTORY_TABLE = "flyway_solomapling_schema_history";
    static final String MIGRATION_LOCATION = "classpath:db/migration/solomapling";

    private CompanionSchemaMigrator() {
    }

    public static int migrate() {
        return configuredFlyway(new HostConnectionDataSource()).migrate().migrationsExecuted;
    }

    static Flyway configuredFlyway(DataSource dataSource) {
        return Flyway.configure(CompanionSchemaMigrator.class.getClassLoader())
                .dataSource(dataSource)
                .locations(MIGRATION_LOCATION)
                .table(HISTORY_TABLE)
                // The host schema is necessarily non-empty. Baseline at zero
                // so the plugin's V1 still runs on first installation.
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .baselineDescription("SoloMapling schema baseline")
                .validateMigrationNaming(true)
                .load();
    }

    /**
     * Adapts the host's pooled connection provider without exposing host
     * application-context or pool implementation details to the migration.
     */
    private static final class HostConnectionDataSource implements DataSource {

        private volatile PrintWriter logWriter;
        private volatile int loginTimeout;

        @Override
        public Connection getConnection() throws SQLException {
            return DatabaseConnection.getConnection();
        }

        @Override
        public Connection getConnection(String username, String password)
                throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException(
                    "SoloMapling migrations use host-managed credentials");
        }

        @Override
        public PrintWriter getLogWriter() {
            return logWriter;
        }

        @Override
        public void setLogWriter(PrintWriter out) {
            logWriter = out;
        }

        @Override
        public void setLoginTimeout(int seconds) {
            loginTimeout = seconds;
        }

        @Override
        public int getLoginTimeout() {
            return loginTimeout;
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getLogger("soloMapling.companion.persistence");
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            if (iface.isInstance(this)) {
                return iface.cast(this);
            }
            throw new SQLException("Not a wrapper for " + iface.getName());
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return iface.isInstance(this);
        }
    }
}
