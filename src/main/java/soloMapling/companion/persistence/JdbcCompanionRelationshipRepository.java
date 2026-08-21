package soloMapling.companion.persistence;

import org.gms.util.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public final class JdbcCompanionRelationshipRepository
        implements CompanionRelationshipRepository {

    private static final String COLUMNS = """
            id, character_id, related_character_id, relationship_type,
            familiarity, trust, affinity, interaction_count, summary,
            last_interaction_at, created_at, updated_at
            """;

    @Override
    public Optional<CompanionRelationship> get(
            int companionCharacterId,
            int relatedCharacterId
    ) throws SQLException {
        requirePositive(companionCharacterId, "companionCharacterId");
        requirePositive(relatedCharacterId, "relatedCharacterId");
        String sql = "SELECT " + COLUMNS
                + " FROM bot_relationships"
                + " WHERE character_id = ? AND related_character_id = ?";
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, companionCharacterId);
            statement.setInt(2, relatedCharacterId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? Optional.of(readRelationship(resultSet))
                        : Optional.empty();
            }
        }
    }

    /**
     * One atomic MySQL upsert is the transaction boundary. Concurrent calls
     * cannot lose increments because the duplicate-key branch increments in SQL.
     */
    @Override
    public void upsertInteraction(
            int companionCharacterId,
            int relatedCharacterId,
            String relationshipType,
            int familiarity,
            int trust,
            int affinity,
            String summary,
            Instant lastInteractionAt
    ) throws SQLException {
        requirePositive(companionCharacterId, "companionCharacterId");
        requirePositive(relatedCharacterId, "relatedCharacterId");
        relationshipType = requireText(relationshipType, "relationshipType", 32);
        requireSmallInt(familiarity, "familiarity");
        requireSmallInt(trust, "trust");
        requireSmallInt(affinity, "affinity");
        if (summary != null && summary.length() > 65_535) {
            throw new IllegalArgumentException("summary is too long for TEXT");
        }

        String sql = """
                INSERT INTO bot_relationships (
                    character_id, related_character_id, relationship_type,
                    familiarity, trust, affinity, interaction_count, summary,
                    last_interaction_at
                ) VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?)
                ON DUPLICATE KEY UPDATE
                    relationship_type = VALUES(relationship_type),
                    familiarity = LEAST(1000, GREATEST(0, familiarity + VALUES(familiarity))),
                    trust = LEAST(1000, GREATEST(0, trust + VALUES(trust))),
                    affinity = LEAST(1000, GREATEST(0, affinity + VALUES(affinity))),
                    interaction_count = interaction_count + 1,
                    summary = VALUES(summary),
                    last_interaction_at = VALUES(last_interaction_at)
                """;
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, companionCharacterId);
            statement.setInt(2, relatedCharacterId);
            statement.setString(3, relationshipType);
            statement.setInt(4, familiarity);
            statement.setInt(5, trust);
            statement.setInt(6, affinity);
            statement.setString(7, summary);
            setInstant(statement, 8, lastInteractionAt);
            if (statement.executeUpdate() == 0) {
                throw new SQLException("Relationship upsert affected no rows");
            }
        }
    }

    static CompanionRelationship readRelationship(ResultSet resultSet) throws SQLException {
        return new CompanionRelationship(
                resultSet.getLong("id"),
                resultSet.getInt("character_id"),
                resultSet.getInt("related_character_id"),
                resultSet.getString("relationship_type"),
                resultSet.getInt("familiarity"),
                resultSet.getInt("trust"),
                resultSet.getInt("affinity"),
                resultSet.getLong("interaction_count"),
                resultSet.getString("summary"),
                getInstant(resultSet, "last_interaction_at"),
                Objects.requireNonNull(getInstant(resultSet, "created_at"), "created_at"),
                Objects.requireNonNull(getInstant(resultSet, "updated_at"), "updated_at"));
    }

    private static Instant getInstant(ResultSet resultSet, String column) throws SQLException {
        Timestamp timestamp = resultSet.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static void setInstant(PreparedStatement statement, int index, Instant instant)
            throws SQLException {
        if (instant == null) {
            statement.setNull(index, Types.TIMESTAMP);
        } else {
            statement.setTimestamp(index, Timestamp.from(instant));
        }
    }

    private static void requirePositive(int value, String field) {
        if (value <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }

    private static void requireSmallInt(int value, String field) {
        if (value < Short.MIN_VALUE || value > Short.MAX_VALUE) {
            throw new IllegalArgumentException(field + " must fit a SMALLINT");
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
