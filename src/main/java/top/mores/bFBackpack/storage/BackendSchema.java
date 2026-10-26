package top.mores.bFBackpack.storage;

import java.util.List;

/** Dialect-specific schema, version 1. No legacy data is imported or removed. */
public final class BackendSchema {
    public static final int VERSION = 1;
    private BackendSchema() {}
    public static List<String> statements(String prefix, boolean mysql) {
        if (prefix == null || !prefix.matches("(?:[A-Za-z_][A-Za-z0-9_]{0,15})?")) throw new IllegalArgumentException("Invalid table prefix");
        String text = mysql ? "LONGTEXT" : "TEXT";
        String suffix = mysql ? " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin" : "";
        return List.of(
            "CREATE TABLE IF NOT EXISTS " + prefix + "backend_schema (id INT NOT NULL PRIMARY KEY, schema_version INT NOT NULL)" + suffix,
            "CREATE TABLE IF NOT EXISTS " + prefix + "player_unlock (player_uuid CHAR(36) NOT NULL, item_id VARCHAR(128) NOT NULL, source VARCHAR(64) NOT NULL, unlocked_at BIGINT NOT NULL, PRIMARY KEY (player_uuid, item_id))" + suffix,
            "CREATE TABLE IF NOT EXISTS " + prefix + "weapon_customization (player_uuid CHAR(36) NOT NULL, weapon_id VARCHAR(128) NOT NULL, customization_json " + text + " NOT NULL, revision BIGINT NOT NULL, updated_at BIGINT NOT NULL, PRIMARY KEY (player_uuid, weapon_id))" + suffix,
            "CREATE TABLE IF NOT EXISTS " + prefix + "player_loadout (player_uuid CHAR(36) NOT NULL, soldier_class VARCHAR(16) NOT NULL, schema_version INT NOT NULL, loadout_json " + text + " NOT NULL, revision BIGINT NOT NULL, updated_at BIGINT NOT NULL, PRIMARY KEY (player_uuid, soldier_class))" + suffix,
            "CREATE TABLE IF NOT EXISTS " + prefix + "player_settings (player_uuid CHAR(36) NOT NULL PRIMARY KEY, preferred_class VARCHAR(16) NOT NULL, updated_at BIGINT NOT NULL)" + suffix);
    }
    public static String insertIgnore(boolean mysql) { return mysql ? "INSERT IGNORE INTO " : "INSERT OR IGNORE INTO "; }
}
