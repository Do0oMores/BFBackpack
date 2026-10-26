package top.mores.bFBackpack.storage;

import top.mores.bFBackpack.api.ClassLoadout;
import top.mores.bFBackpack.backpack.BackpackType;
import top.mores.bFBackpack.catalog.CatalogSnapshot;
import java.sql.SQLException;
import java.util.UUID;

/** Blocking persistence contract; initialized defaults and reads form one transaction. */
public interface LoadoutRepository {
    LoadoutPlayerData loadPlayer(UUID player, CatalogSnapshot catalog) throws SQLException;
    ClassLoadout saveLoadout(ClassLoadout loadout) throws SQLException;
    void setPreferredClass(UUID player, BackpackType type) throws SQLException;
}
