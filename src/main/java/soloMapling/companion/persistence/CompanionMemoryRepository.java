package soloMapling.companion.persistence;

import soloMapling.companion.memory.MemoryRecord;
import soloMapling.companion.memory.MemoryType;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public interface CompanionMemoryRepository {

    long insert(
            int companionCharacterId,
            String role,
            int importance,
            Instant expiresAt,
            MemoryRecord memory
    ) throws SQLException;

    List<MemoryRecord> findCandidates(
            int companionCharacterId,
            Integer sourceCharacterId,
            Integer mapId,
            MemoryType type,
            boolean archived,
            Instant occurredFrom,
            Instant occurredTo,
            int limit
    ) throws SQLException;

    void markRecalled(long memoryId, Instant recalledAt) throws SQLException;

    void archive(long memoryId) throws SQLException;

    /**
     * Archives a memory, succeeding as a no-op when it is already archived.
     * Implementations may override this for true storage-level idempotency.
     */
    default void archiveIfActive(long memoryId) throws SQLException {
        archive(memoryId);
    }

    void updateStrength(long memoryId, double strength) throws SQLException;

    /**
     * Checks whether an active memory already carries an exact tag. The default
     * implementation preserves compatibility with non-JDBC repositories.
     */
    default boolean existsWithTag(int companionCharacterId, String tag) throws SQLException {
        Objects.requireNonNull(tag, "tag must not be null");
        return findCandidates(
                        companionCharacterId,
                        null,
                        null,
                        null,
                        false,
                        null,
                        null,
                        Integer.MAX_VALUE)
                .stream()
                .anyMatch(memory -> memory.tags().contains(tag));
    }
}
