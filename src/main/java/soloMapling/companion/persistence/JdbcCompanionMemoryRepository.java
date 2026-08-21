package soloMapling.companion.persistence;

import org.gms.util.DatabaseConnection;
import soloMapling.companion.memory.MemoryRecord;
import soloMapling.companion.memory.MemoryType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class JdbcCompanionMemoryRepository implements CompanionMemoryRepository {

    private static final String COLUMNS = """
            id, memory_type, content, salience, strength, occurred_at,
            last_recalled_at, source_character_id, map_id, tags, archived
            """;

    @Override
    public long insert(
            int companionCharacterId,
            String role,
            int importance,
            Instant expiresAt,
            MemoryRecord memory
    ) throws SQLException {
        requirePositive(companionCharacterId, "companionCharacterId");
        role = requireText(role, "role", 32);
        if (importance < Short.MIN_VALUE || importance > Short.MAX_VALUE) {
            throw new IllegalArgumentException("importance must fit a SMALLINT");
        }
        Objects.requireNonNull(memory, "memory must not be null");
        Integer sourceCharacterId = parsePositiveInteger(memory.actorKey(), "actorKey");
        Integer mapId = parseInteger(memory.mapKey(), "mapKey");
        String tags = MemoryTagCodec.encode(memory.tags());
        if (tags.length() > 512) {
            throw new IllegalArgumentException("encoded tags must not exceed 512 characters");
        }

        String sql = """
                INSERT INTO bot_memories (
                    character_id, role, memory_type, source_character_id, map_id,
                    content, tags, importance, salience, strength, archived,
                    occurred_at, last_recalled_at, expires_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement =
                     connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setInt(1, companionCharacterId);
            statement.setString(2, role);
            statement.setString(3, memory.type().name().toLowerCase(Locale.ROOT));
            setNullableInteger(statement, 4, sourceCharacterId);
            setNullableInteger(statement, 5, mapId);
            statement.setString(6, memory.content());
            statement.setString(7, tags);
            statement.setInt(8, importance);
            statement.setDouble(9, memory.salience());
            statement.setDouble(10, memory.strength());
            statement.setBoolean(11, memory.archived());
            setInstant(statement, 12, memory.occurredAt());
            setInstant(statement, 13, memory.lastRecalledAt());
            setInstant(statement, 14, expiresAt);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Memory insert affected an unexpected number of rows");
            }
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("Memory insert did not return a generated id");
                }
                return keys.getLong(1);
            }
        }
    }

    @Override
    public List<MemoryRecord> findCandidates(
            int companionCharacterId,
            Integer sourceCharacterId,
            Integer mapId,
            MemoryType type,
            boolean archived,
            Instant occurredFrom,
            Instant occurredTo,
            int limit
    ) throws SQLException {
        requirePositive(companionCharacterId, "companionCharacterId");
        requireOptionalPositive(sourceCharacterId, "sourceCharacterId");
        if (occurredFrom != null && occurredTo != null && occurredFrom.isAfter(occurredTo)) {
            throw new IllegalArgumentException("occurredFrom must not be after occurredTo");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }

        StringBuilder sql = new StringBuilder("SELECT ")
                .append(COLUMNS)
                .append(" FROM bot_memories WHERE character_id = ? AND archived = ?")
                .append(" AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP(3))");
        List<Object> parameters = new ArrayList<>();
        parameters.add(companionCharacterId);
        parameters.add(archived);
        appendFilter(sql, parameters, "source_character_id", sourceCharacterId);
        appendFilter(sql, parameters, "map_id", mapId);
        appendFilter(sql, parameters, "memory_type",
                type == null ? null : type.name().toLowerCase(Locale.ROOT));
        appendRange(sql, parameters, "occurred_at", ">=", occurredFrom);
        appendRange(sql, parameters, "occurred_at", "<=", occurredTo);
        sql.append(" ORDER BY occurred_at DESC, id DESC LIMIT ?");
        parameters.add(limit);

        List<MemoryRecord> memories = new ArrayList<>();
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            bind(statement, parameters);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    memories.add(readMemory(resultSet));
                }
            }
        }
        return List.copyOf(memories);
    }

    @Override
    public void markRecalled(long memoryId, Instant recalledAt) throws SQLException {
        requirePositive(memoryId, "memoryId");
        Objects.requireNonNull(recalledAt, "recalledAt must not be null");
        updateOne(
                "UPDATE bot_memories SET last_recalled_at = ? WHERE id = ?",
                statement -> {
                    setInstant(statement, 1, recalledAt);
                    statement.setLong(2, memoryId);
                },
                memoryId);
    }

    @Override
    public void archive(long memoryId) throws SQLException {
        archiveIfActive(memoryId);
    }

    @Override
    public void archiveIfActive(long memoryId) throws SQLException {
        requirePositive(memoryId, "memoryId");
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement update = connection.prepareStatement(
                     "UPDATE bot_memories SET archived = TRUE"
                             + " WHERE id = ? AND archived = FALSE")) {
            update.setLong(1, memoryId);
            if (update.executeUpdate() == 1) {
                return;
            }
            try (PreparedStatement exists =
                         connection.prepareStatement("SELECT 1 FROM bot_memories WHERE id = ?")) {
                exists.setLong(1, memoryId);
                try (ResultSet resultSet = exists.executeQuery()) {
                    if (resultSet.next()) {
                        return;
                    }
                }
            }
        }
        throw new SQLException("Memory not found: " + memoryId);
    }

    @Override
    public void updateStrength(long memoryId, double strength) throws SQLException {
        requirePositive(memoryId, "memoryId");
        requireUnitInterval(strength, "strength");
        updateOne(
                "UPDATE bot_memories SET strength = ? WHERE id = ?",
                statement -> {
                    statement.setDouble(1, strength);
                    statement.setLong(2, memoryId);
                },
                memoryId);
    }

    @Override
    public boolean existsWithTag(int companionCharacterId, String tag) throws SQLException {
        requirePositive(companionCharacterId, "companionCharacterId");
        tag = requireText(tag, "tag", 128);
        String sql = """
                SELECT tags FROM bot_memories
                WHERE character_id = ? AND archived = FALSE
                  AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP(3))
                """;
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, companionCharacterId);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    if (MemoryTagCodec.decode(resultSet.getString("tags")).contains(tag)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    static MemoryRecord readMemory(ResultSet resultSet) throws SQLException {
        Integer sourceCharacterId = nullableInteger(resultSet, "source_character_id");
        Integer mapId = nullableInteger(resultSet, "map_id");
        return new MemoryRecord(
                Long.toString(resultSet.getLong("id")),
                MemoryType.valueOf(resultSet.getString("memory_type").toUpperCase(Locale.ROOT)),
                resultSet.getString("content"),
                resultSet.getDouble("salience"),
                resultSet.getDouble("strength"),
                getInstant(resultSet, "occurred_at"),
                getInstant(resultSet, "last_recalled_at"),
                sourceCharacterId == null ? null : sourceCharacterId.toString(),
                mapId == null ? null : mapId.toString(),
                MemoryTagCodec.decode(resultSet.getString("tags")),
                resultSet.getBoolean("archived"));
    }

    private static void updateOne(String sql, StatementBinder binder, long memoryId)
            throws SQLException {
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Memory not found: " + memoryId);
            }
        }
    }

    private static void appendFilter(
            StringBuilder sql,
            List<Object> parameters,
            String column,
            Object value
    ) {
        if (value != null) {
            sql.append(" AND ").append(column).append(" = ?");
            parameters.add(value);
        }
    }

    private static void appendRange(
            StringBuilder sql,
            List<Object> parameters,
            String column,
            String operator,
            Instant value
    ) {
        if (value != null) {
            sql.append(" AND ").append(column).append(' ').append(operator).append(" ?");
            parameters.add(Timestamp.from(value));
        }
    }

    private static void bind(PreparedStatement statement, List<Object> parameters)
            throws SQLException {
        for (int index = 0; index < parameters.size(); index++) {
            Object value = parameters.get(index);
            if (value instanceof Integer integer) {
                statement.setInt(index + 1, integer);
            } else if (value instanceof Boolean bool) {
                statement.setBoolean(index + 1, bool);
            } else if (value instanceof Timestamp timestamp) {
                statement.setTimestamp(index + 1, timestamp);
            } else {
                statement.setString(index + 1, (String) value);
            }
        }
    }

    private static Integer nullableInteger(ResultSet resultSet, String column)
            throws SQLException {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
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

    private static void setNullableInteger(
            PreparedStatement statement,
            int index,
            Integer value
    ) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.INTEGER);
        } else {
            statement.setInt(index, value);
        }
    }

    private static Integer parsePositiveInteger(String value, String field) {
        Integer parsed = parseInteger(value, field);
        if (parsed != null && parsed <= 0) {
            throw new IllegalArgumentException(field + " must be a positive integer");
        }
        return parsed;
    }

    private static Integer parseInteger(String value, String field) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(field + " must be an integer", exception);
        }
    }

    private static void requireOptionalPositive(Integer value, String field) {
        if (value != null) {
            requirePositive(value, field);
        }
    }

    private static void requirePositive(long value, String field) {
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

    private static void requireUnitInterval(double value, String field) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(field + " must be finite and within [0, 1]");
        }
    }

    @FunctionalInterface
    private interface StatementBinder {
        void bind(PreparedStatement statement) throws SQLException;
    }
}
