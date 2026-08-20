package soloMapling.companion.persistence;

import java.sql.SQLException;
import java.util.List;

public interface CompanionKnowledgeRepository {

    void upsert(
            int companionCharacterId,
            String knowledgeKey,
            String category,
            String content,
            String source,
            int priority,
            boolean enabled
    ) throws SQLException;

    /**
     * Returns rows matching all filters, ordered by descending priority and
     * then stable key/id tie-breakers.
     */
    List<CompanionKnowledge> find(
            int companionCharacterId,
            boolean enabled,
            String category,
            int minimumPriority
    ) throws SQLException;

    void disable(int companionCharacterId, String knowledgeKey) throws SQLException;
}
