package top.mores.bFBackpack.storage;

import top.mores.bFBackpack.api.WeaponCustomization;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;

/** Persists one immutable customization per (UUID, weapon ID). */
public interface WeaponCustomizationRepository {
    Map<String, WeaponCustomization> getCustomizations(UUID player) throws SQLException;
    WeaponCustomization saveCustomization(WeaponCustomization plan) throws SQLException;
}
