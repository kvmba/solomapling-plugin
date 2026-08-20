package soloMapling.companion.persistence;

import java.sql.SQLException;
import java.time.Instant;

public interface CompanionActivityRepository {

    long append(
            int companionCharacterId,
            String activityType,
            String outcome,
            String actorRole,
            Integer actorCharacterId,
            String summary,
            String details,
            Instant occurredAt
    ) throws SQLException;
}
