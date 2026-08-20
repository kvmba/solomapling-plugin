package soloMapling.companion.persistence;

import org.gms.util.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.Objects;

public final class JdbcCompanionActivityRepository implements CompanionActivityRepository {

    @Override
    public long append(
            int companionCharacterId,
            String activityType,
            String outcome,
            String actorRole,
            Integer actorCharacterId,
            String summary,
            String details,
            Instant occurredAt
    ) throws SQLException {
        requirePositive(companionCharacterId, "companionCharacterId");
        activityType = requireText(activityType, "activityType", 64);
        outcome = requireText(outcome, "outcome", 32);
        actorRole = requireText(actorRole, "actorRole", 32);
        if (actorCharacterId != null) {
            requirePositive(actorCharacterId, "actorCharacterId");
        }
        summary = Objects.requireNonNull(summary, "summary must not be null").trim();
        if (summary.length() > 512) {
            throw new IllegalArgumentException("summary must not exceed 512 characters");
        }
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");

        String sql = """
                INSERT INTO bot_activity_log (
                    character_id, activity_type, outcome, actor_role,
                    actor_character_id, summary, details, occurred_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement =
                     connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setInt(1, companionCharacterId);
            statement.setString(2, activityType);
            statement.setString(3, outcome);
            statement.setString(4, actorRole);
            if (actorCharacterId == null) {
                statement.setNull(5, Types.INTEGER);
            } else {
                statement.setInt(5, actorCharacterId);
            }
            statement.setString(6, summary);
            statement.setString(7, details);
            statement.setTimestamp(8, Timestamp.from(occurredAt));
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Activity append affected an unexpected number of rows");
            }
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("Activity append did not return a generated id");
                }
                return keys.getLong(1);
            }
        }
    }

    private static void requirePositive(int value, String field) {
        if (value <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }

    private static String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " must not exceed " + maxLength + " characters");
        }
        return normalized;
    }
}
