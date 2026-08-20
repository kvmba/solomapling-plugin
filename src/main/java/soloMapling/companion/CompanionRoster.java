package soloMapling.companion;

import org.gms.util.DatabaseConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-local index of native character IDs managed as persistent companions.
 *
 * <p>The host's artificial-character classifier can be registered before the
 * database is ready because it consults this mutable index. The roster is
 * populated after {@code ServerReadyEvent} and can be refreshed by future GM
 * provisioning commands without replacing the classifier.</p>
 */
public final class CompanionRoster {

    private static final Logger log = LoggerFactory.getLogger(CompanionRoster.class);
    private static final Set<Integer> CHARACTER_IDS = ConcurrentHashMap.newKeySet();

    private CompanionRoster() {
    }

    public static boolean isCompanion(int characterId) {
        return CHARACTER_IDS.contains(characterId);
    }

    public static Set<Integer> characterIds() {
        return Set.copyOf(CHARACTER_IDS);
    }

    public static void register(int characterId) {
        if (characterId <= 0) {
            throw new IllegalArgumentException("characterId must be positive");
        }
        CHARACTER_IDS.add(characterId);
    }

    public static void unregister(int characterId) {
        CHARACTER_IDS.remove(characterId);
    }

    /**
     * Replaces the in-memory roster with enabled profiles from MySQL.
     *
     * @return number of enabled persistent companions
     * @throws SQLException when the profile table cannot be read
     */
    public static int refreshFromDatabase() throws SQLException {
        Set<Integer> loaded = ConcurrentHashMap.newKeySet();
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT character_id FROM bot_profiles WHERE enabled = TRUE");
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                int characterId = resultSet.getInt("character_id");
                if (characterId > 0) {
                    loaded.add(characterId);
                }
            }
        }

        CHARACTER_IDS.clear();
        CHARACTER_IDS.addAll(loaded);
        log.info("Loaded {} persistent companion profiles", loaded.size());
        return loaded.size();
    }

    /** Clears process state during plugin unload and tests. */
    public static void clear() {
        CHARACTER_IDS.clear();
    }
}
