package soloMapling.companion.persistence;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface CompanionProfileRepository {

    Optional<CompanionProfile> findByCharacterId(int characterId) throws SQLException;

    List<CompanionProfile> findEnabled() throws SQLException;

    void updateRuntimeState(
            int characterId,
            String currentMode,
            Instant lastOnlineAt,
            Instant lastSettledAt
    ) throws SQLException;
}
