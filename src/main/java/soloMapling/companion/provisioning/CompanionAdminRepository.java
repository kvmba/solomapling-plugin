package soloMapling.companion.provisioning;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

public interface CompanionAdminRepository {

    List<CompanionAdminView> findAll() throws SQLException;

    default List<CompanionAdminView> findAll(int limit) throws SQLException {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        return findAll().stream().limit(limit).toList();
    }

    Optional<CompanionAdminView> findByCharacterId(int characterId) throws SQLException;
}
