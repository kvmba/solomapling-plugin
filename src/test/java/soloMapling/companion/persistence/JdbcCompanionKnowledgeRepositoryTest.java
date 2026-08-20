package soloMapling.companion.persistence;

import org.junit.jupiter.api.Test;

import javax.sql.rowset.CachedRowSet;
import javax.sql.rowset.RowSetMetaDataImpl;
import javax.sql.rowset.RowSetProvider;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JdbcCompanionKnowledgeRepositoryTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-20T10:00:00Z");
    private static final Instant UPDATED_AT = CREATED_AT.plusSeconds(30);

    @Test
    void mapsEveryV1116KnowledgeColumn() throws Exception {
        CachedRowSet row = RowSetProvider.newFactory().createCachedRowSet();
        RowSetMetaDataImpl metadata = new RowSetMetaDataImpl();
        metadata.setColumnCount(10);
        column(metadata, 1, "id", Types.BIGINT);
        column(metadata, 2, "character_id", Types.INTEGER);
        column(metadata, 3, "knowledge_key", Types.VARCHAR);
        column(metadata, 4, "category", Types.VARCHAR);
        column(metadata, 5, "content", Types.LONGVARCHAR);
        column(metadata, 6, "source", Types.VARCHAR);
        column(metadata, 7, "priority", Types.SMALLINT);
        column(metadata, 8, "enabled", Types.BOOLEAN);
        column(metadata, 9, "created_at", Types.TIMESTAMP);
        column(metadata, 10, "updated_at", Types.TIMESTAMP);
        row.setMetaData(metadata);
        row.moveToInsertRow();
        row.updateLong(1, 7);
        row.updateInt(2, 42);
        row.updateString(3, "map:0");
        row.updateString(4, "map");
        row.updateString(5, "The starting map");
        row.updateNull(6);
        row.updateInt(7, 12);
        row.updateBoolean(8, true);
        row.updateTimestamp(9, Timestamp.from(CREATED_AT));
        row.updateTimestamp(10, Timestamp.from(UPDATED_AT));
        row.insertRow();
        row.moveToCurrentRow();
        row.beforeFirst();
        row.next();

        CompanionKnowledge knowledge =
                JdbcCompanionKnowledgeRepository.readKnowledge(row);

        assertEquals(7, knowledge.id());
        assertEquals(42, knowledge.companionCharacterId());
        assertEquals("map:0", knowledge.knowledgeKey());
        assertEquals("map", knowledge.category());
        assertNull(knowledge.source());
        assertEquals(12, knowledge.priority());
        assertEquals(CREATED_AT, knowledge.createdAt());
        assertEquals(UPDATED_AT, knowledge.updatedAt());
    }

    @Test
    void validatesSchemaSizedParametersBeforeOpeningAConnection() {
        JdbcCompanionKnowledgeRepository repository =
                new JdbcCompanionKnowledgeRepository();

        assertThrows(IllegalArgumentException.class, () -> repository.upsert(
                0, "map:1", "map", "known", null, 0, true));
        assertThrows(IllegalArgumentException.class, () -> repository.upsert(
                1, "x".repeat(192), "map", "known", null, 0, true));
        assertThrows(IllegalArgumentException.class, () -> repository.upsert(
                1, "map:1", "map", "known", null, Short.MAX_VALUE + 1, true));
        assertThrows(IllegalArgumentException.class, () -> repository.find(
                1, true, " ", 0));
        assertThrows(IllegalArgumentException.class, () -> repository.disable(
                1, ""));
    }

    @Test
    void valueObjectRejectsImpossiblePersistedRows() {
        assertThrows(IllegalArgumentException.class, () -> new CompanionKnowledge(
                0, 1, "map:1", "map", "known", null, 0, true,
                CREATED_AT, UPDATED_AT));
        assertThrows(IllegalArgumentException.class, () -> new CompanionKnowledge(
                1, 1, "map:1", "map", "known", null, 0, true,
                UPDATED_AT, CREATED_AT));
    }

    private static void column(
            RowSetMetaDataImpl metadata, int index, String name, int type)
            throws Exception {
        metadata.setColumnName(index, name);
        metadata.setColumnLabel(index, name);
        metadata.setColumnType(index, type);
    }
}
