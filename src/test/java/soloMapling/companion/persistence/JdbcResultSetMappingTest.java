package soloMapling.companion.persistence;

import org.junit.jupiter.api.Test;
import soloMapling.companion.memory.MemoryRecord;
import soloMapling.companion.memory.MemoryType;

import javax.sql.rowset.CachedRowSet;
import javax.sql.rowset.RowSetMetaDataImpl;
import javax.sql.rowset.RowSetProvider;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class JdbcResultSetMappingTest {

    @Test
    void mapsMemoryColumnsAndNullableKeys() throws Exception {
        Instant occurredAt = Instant.parse("2026-08-20T10:00:00Z");
        CachedRowSet row = row(
                columns(
                        column("id", Types.BIGINT),
                        column("memory_type", Types.VARCHAR),
                        column("content", Types.LONGVARCHAR),
                        column("salience", Types.DECIMAL),
                        column("strength", Types.DECIMAL),
                        column("occurred_at", Types.TIMESTAMP),
                        column("last_recalled_at", Types.TIMESTAMP),
                        column("source_character_id", Types.INTEGER),
                        column("map_id", Types.INTEGER),
                        column("tags", Types.VARCHAR),
                        column("archived", Types.BOOLEAN)),
                81L, "semantic", "Met the player", 0.75, 0.9,
                Timestamp.from(occurredAt), null, null, 100000000,
                MemoryTagCodec.encode(Set.of("npc:talk", "town")), false);

        MemoryRecord memory = JdbcCompanionMemoryRepository.readMemory(row);

        assertEquals("81", memory.id());
        assertEquals(MemoryType.SEMANTIC, memory.type());
        assertEquals(occurredAt, memory.occurredAt());
        assertNull(memory.lastRecalledAt());
        assertNull(memory.actorKey());
        assertEquals("100000000", memory.mapKey());
        assertEquals(Set.of("npc:talk", "town"), memory.tags());
        assertFalse(memory.archived());
    }

    @Test
    void mapsRelationshipColumnsIncludingNullableTimestampAndSummary() throws Exception {
        Instant createdAt = Instant.parse("2026-08-20T10:00:00Z");
        Instant updatedAt = createdAt.plusSeconds(5);
        CachedRowSet row = row(
                columns(
                        column("id", Types.BIGINT),
                        column("character_id", Types.INTEGER),
                        column("related_character_id", Types.INTEGER),
                        column("relationship_type", Types.VARCHAR),
                        column("familiarity", Types.SMALLINT),
                        column("trust", Types.SMALLINT),
                        column("affinity", Types.SMALLINT),
                        column("interaction_count", Types.INTEGER),
                        column("summary", Types.LONGVARCHAR),
                        column("last_interaction_at", Types.TIMESTAMP),
                        column("created_at", Types.TIMESTAMP),
                        column("updated_at", Types.TIMESTAMP)),
                9L, 20, 30, "friend", 4, 5, 6, 7L, null, null,
                Timestamp.from(createdAt), Timestamp.from(updatedAt));

        CompanionRelationship relationship =
                JdbcCompanionRelationshipRepository.readRelationship(row);

        assertEquals(9L, relationship.id());
        assertEquals(7L, relationship.interactionCount());
        assertEquals("", relationship.summary());
        assertNull(relationship.lastInteractionAt());
        assertEquals(updatedAt, relationship.updatedAt());
    }

    private static CachedRowSet row(RowSetMetaDataImpl metadata, Object... values)
            throws Exception {
        CachedRowSet rowSet = RowSetProvider.newFactory().createCachedRowSet();
        rowSet.setMetaData(metadata);
        rowSet.moveToInsertRow();
        for (int index = 0; index < values.length; index++) {
            if (values[index] == null) {
                rowSet.updateNull(index + 1);
            } else {
                rowSet.updateObject(index + 1, values[index]);
            }
        }
        rowSet.insertRow();
        rowSet.moveToCurrentRow();
        rowSet.beforeFirst();
        rowSet.next();
        return rowSet;
    }

    private static RowSetMetaDataImpl columns(Column... columns) throws Exception {
        RowSetMetaDataImpl metadata = new RowSetMetaDataImpl();
        metadata.setColumnCount(columns.length);
        for (int index = 0; index < columns.length; index++) {
            metadata.setColumnName(index + 1, columns[index].name());
            metadata.setColumnLabel(index + 1, columns[index].name());
            metadata.setColumnType(index + 1, columns[index].type());
        }
        return metadata;
    }

    private static Column column(String name, int type) {
        return new Column(name, type);
    }

    private record Column(String name, int type) {
    }
}
