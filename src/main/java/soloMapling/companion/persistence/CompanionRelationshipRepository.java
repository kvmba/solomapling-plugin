package soloMapling.companion.persistence;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

public interface CompanionRelationshipRepository {

    Optional<CompanionRelationship> get(
            int companionCharacterId,
            int relatedCharacterId
    ) throws SQLException;

    void upsertInteraction(
            int companionCharacterId,
            int relatedCharacterId,
            String relationshipType,
            int familiarity,
            int trust,
            int affinity,
            String summary,
            Instant lastInteractionAt
    ) throws SQLException;
}
