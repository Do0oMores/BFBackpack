package top.mores.bFBackpack.storage;

import java.sql.SQLException;
import java.util.Set;
import java.util.UUID;

/** Blocking repository contract, called exclusively on the ordered database worker. */
public interface UnlockRepository {
    Set<String> getPlayerUnlocks(UUID player) throws SQLException;
    void grantUnlock(UUID player, String itemId, String source) throws SQLException;
    void revokeUnlock(UUID player, String itemId) throws SQLException;
}
