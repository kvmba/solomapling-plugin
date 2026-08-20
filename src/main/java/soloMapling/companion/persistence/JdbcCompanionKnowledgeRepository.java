package soloMapling.companion.persistence;

import org.gms.util.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** JDBC persistence matching the host V1.11.6 {@code bot_knowledge} schema. */
public final class JdbcCompanionKnowledgeRepository
        implements CompanionKnowledgeRepository {

    private static final String COLUMNS = """
            id, character_id, knowledge_key, category, content, source,
            priority, enabled, created_at, updated_at
            """;

    @Override
    public void upsert(
            int companionCharacterId,
            String knowledgeKey,
            String category,
            String content,
            String source,
            int priority,
            boolean enabled
    ) throws SQLException {
        requirePositive(companionCharacterId, "companionCharacterId");
        knowledgeKey = CompanionKnowledge.requireText(knowledgeKey, "knowledgeKey", 191);
        category = CompanionKnowledge.requireText(category, "category", 64);
        content = CompanionKnowledge.requireText(content, "content", Integer.MAX_VALUE);
        source = CompanionKnowledge.optionalText(source, "source", 255);
        CompanionKnowledge.requireSmallInt(priority, "priority");

        String sql = """
                INSERT INTO bot_knowledge (
                    character_id, knowledge_key, category, content, source,
                    priority, enabled
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    category = VALUES(category),
                    content = VALUES(content),
                    source = VALUES(source),
                    priority = VALUES(priority),
                    enabled = VALUES(enabled)
                """;
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, companionCharacterId);
            statement.setString(2, knowledgeKey);
            statement.setString(3, category);
            statement.setString(4, content);
            setNullableString(statement, 5, source);
            statement.setInt(6, priority);
            statement.setBoolean(7, enabled);
            if (statement.executeUpdate() == 0) {
                throw new SQLException("Knowledge upsert affected no rows");
            }
        }
    }

    @Override
    public List<CompanionKnowledge> find(
            int companionCharacterId,
            boolean enabled,
            String category,
            int minimumPriority
    ) throws SQLException {
        requirePositive(companionCharacterId, "companionCharacterId");
        category = CompanionKnowledge.requireText(category, "category", 64);
        CompanionKnowledge.requireSmallInt(minimumPriority, "minimumPriority");

        String sql = "SELECT " + COLUMNS
                + " FROM bot_knowledge"
                + " WHERE character_id = ? AND enabled = ?"
                + " AND category = ? AND priority >= ?"
                + " ORDER BY priority DESC, knowledge_key ASC, id ASC";
        List<CompanionKnowledge> knowledge = new ArrayList<>();
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, companionCharacterId);
            statement.setBoolean(2, enabled);
            statement.setString(3, category);
            statement.setInt(4, minimumPriority);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    knowledge.add(readKnowledge(resultSet));
                }
            }
        }
        return List.copyOf(knowledge);
    }

    @Override
    public void disable(int companionCharacterId, String knowledgeKey) throws SQLException {
        requirePositive(companionCharacterId, "companionCharacterId");
        knowledgeKey = CompanionKnowledge.requireText(knowledgeKey, "knowledgeKey", 191);

        String sql = """
                UPDATE bot_knowledge
                   SET enabled = FALSE
                 WHERE character_id = ? AND knowledge_key = ?
                """;
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, companionCharacterId);
            statement.setString(2, knowledgeKey);
            if (statement.executeUpdate() != 1) {
                throw new SQLException(
                        "Knowledge not found: " + companionCharacterId + "/" + knowledgeKey);
            }
        }
    }

    static CompanionKnowledge readKnowledge(ResultSet resultSet) throws SQLException {
        return new CompanionKnowledge(
                resultSet.getLong("id"),
                resultSet.getInt("character_id"),
                resultSet.getString("knowledge_key"),
                resultSet.getString("category"),
                resultSet.getString("content"),
                resultSet.getString("source"),
                resultSet.getInt("priority"),
                resultSet.getBoolean("enabled"),
                Objects.requireNonNull(getInstant(resultSet, "created_at"), "created_at"),
                Objects.requireNonNull(getInstant(resultSet, "updated_at"), "updated_at"));
    }

    private static Instant getInstant(ResultSet resultSet, String column) throws SQLException {
        Timestamp timestamp = resultSet.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static void setNullableString(
            PreparedStatement statement, int index, String value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }

    private static void requirePositive(int value, String field) {
        if (value <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }
}
