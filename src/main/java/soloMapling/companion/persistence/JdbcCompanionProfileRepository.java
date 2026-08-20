package soloMapling.companion.persistence;

import org.gms.util.DatabaseConnection;
import soloMapling.companion.progression.CompanionCareerBuild;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class JdbcCompanionProfileRepository implements CompanionProfileRepository {

    private static final String COLUMNS = """
            character_id, account_id, display_name, status, enabled,
            persona_seed, career_build, persona, system_prompt, greeting,
            routine_timezone, routine_profile, growth_stage, current_mode,
            last_online_at, last_settled_at, created_at, updated_at
            """;

    @Override
    public Optional<CompanionProfile> findByCharacterId(int characterId) throws SQLException {
        if (characterId <= 0) {
            throw new IllegalArgumentException("characterId must be positive");
        }
        String sql = "SELECT " + COLUMNS + " FROM bot_profiles WHERE character_id = ?";
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, characterId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? Optional.of(readProfile(resultSet))
                        : Optional.empty();
            }
        }
    }

    @Override
    public List<CompanionProfile> findEnabled() throws SQLException {
        String sql = "SELECT " + COLUMNS
                + " FROM bot_profiles WHERE enabled = TRUE AND status = 'active'"
                + " ORDER BY character_id";
        List<CompanionProfile> profiles = new ArrayList<>();
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                profiles.add(readProfile(resultSet));
            }
        }
        return List.copyOf(profiles);
    }

    @Override
    public void updateRuntimeState(
            int characterId,
            String currentMode,
            Instant lastOnlineAt,
            Instant lastSettledAt
    ) throws SQLException {
        if (characterId <= 0) {
            throw new IllegalArgumentException("characterId must be positive");
        }
        if (currentMode == null || currentMode.isBlank()) {
            throw new IllegalArgumentException("currentMode must not be blank");
        }

        String sql = """
                UPDATE bot_profiles
                   SET current_mode = ?,
                       last_online_at = COALESCE(?, last_online_at),
                       last_settled_at = COALESCE(?, last_settled_at)
                 WHERE character_id = ?
                """;
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, currentMode.trim());
            setInstant(statement, 2, lastOnlineAt);
            setInstant(statement, 3, lastSettledAt);
            statement.setInt(4, characterId);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Companion profile not found: " + characterId);
            }
        }
    }

    private static CompanionProfile readProfile(ResultSet resultSet) throws SQLException {
        long personaSeed = resultSet.getLong("persona_seed");
        String careerBuild = resultSet.getString("career_build");
        if (careerBuild == null || careerBuild.isBlank()) {
            careerBuild = CompanionCareerBuild.fromSeed(personaSeed).id();
        }
        return new CompanionProfile(
                resultSet.getInt("character_id"),
                resultSet.getInt("account_id"),
                resultSet.getString("display_name"),
                resultSet.getString("status"),
                resultSet.getBoolean("enabled"),
                personaSeed,
                careerBuild,
                resultSet.getString("persona"),
                resultSet.getString("system_prompt"),
                resultSet.getString("greeting"),
                resultSet.getString("routine_timezone"),
                resultSet.getString("routine_profile"),
                resultSet.getString("growth_stage"),
                resultSet.getString("current_mode"),
                getInstant(resultSet, "last_online_at"),
                getInstant(resultSet, "last_settled_at"),
                getInstant(resultSet, "created_at"),
                getInstant(resultSet, "updated_at")
        );
    }

    private static Instant getInstant(ResultSet resultSet, String column) throws SQLException {
        Timestamp timestamp = resultSet.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static void setInstant(PreparedStatement statement, int index, Instant instant)
            throws SQLException {
        statement.setTimestamp(index, instant == null ? null : Timestamp.from(instant));
    }
}
