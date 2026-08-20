package soloMapling.companion.provisioning;

import org.gms.util.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class JdbcCompanionAdminRepository implements CompanionAdminRepository {

    private static final String SELECT = """
            SELECT bp.character_id, bp.account_id, bp.display_name, bp.status, bp.enabled,
                   bp.persona_seed, bp.growth_stage, bp.current_mode, bp.created_at, bp.updated_at,
                   (a.id IS NOT NULL) AS account_present,
                   (c.id IS NOT NULL) AS character_present,
                   (c.accountid = bp.account_id) AS ownership_matches
              FROM bot_profiles bp
              LEFT JOIN accounts a ON a.id = bp.account_id
              LEFT JOIN characters c ON c.id = bp.character_id
            """;

    @Override
    public List<CompanionAdminView> findAll() throws SQLException {
        return findAll(Integer.MAX_VALUE);
    }

    @Override
    public List<CompanionAdminView> findAll(int limit) throws SQLException {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        List<CompanionAdminView> views = new ArrayList<>();
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     SELECT + " ORDER BY bp.character_id LIMIT ?")) {
            statement.setInt(1, limit);
            try (ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                views.add(read(resultSet));
            }
            }
        }
        return List.copyOf(views);
    }

    @Override
    public Optional<CompanionAdminView> findByCharacterId(int characterId) throws SQLException {
        if (characterId <= 0) {
            throw new IllegalArgumentException("characterId must be positive");
        }
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     SELECT + " WHERE bp.character_id = ?")) {
            statement.setInt(1, characterId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(read(resultSet)) : Optional.empty();
            }
        }
    }

    private static CompanionAdminView read(ResultSet resultSet) throws SQLException {
        return new CompanionAdminView(
                resultSet.getInt("character_id"),
                resultSet.getInt("account_id"),
                resultSet.getString("display_name"),
                resultSet.getString("status"),
                resultSet.getBoolean("enabled"),
                resultSet.getLong("persona_seed"),
                resultSet.getString("growth_stage"),
                resultSet.getString("current_mode"),
                instant(resultSet, "created_at"),
                instant(resultSet, "updated_at"),
                resultSet.getBoolean("account_present"),
                resultSet.getBoolean("character_present"),
                resultSet.getBoolean("ownership_matches")
        );
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        Timestamp value = resultSet.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
