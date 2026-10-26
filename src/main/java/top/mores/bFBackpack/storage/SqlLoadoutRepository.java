package top.mores.bFBackpack.storage;

import top.mores.bFBackpack.api.*;
import top.mores.bFBackpack.backpack.BackpackType;
import top.mores.bFBackpack.catalog.CatalogSnapshot;
import java.sql.*;
import java.util.*;

/** JDBC backend. All methods, including initialize(), run on Database.executor(). */
public final class SqlLoadoutRepository implements LoadoutRepository, UnlockRepository, WeaponCustomizationRepository {
    private final Database database;
    private final String prefix;
    private final String ignore;
    public SqlLoadoutRepository(Database database) {
        this.database = database; this.prefix = database.tablePrefix(); this.ignore = BackendSchema.insertIgnore(database.mysql());
    }
    public void initialize() throws SQLException {
        Connection c = database.connection();
        for (String ddl : BackendSchema.statements(prefix, database.mysql())) {
            try (Statement statement = c.createStatement()) { statement.execute(ddl); }
        }
        update(c, ignore + prefix + "backend_schema (id, schema_version) VALUES (?, ?)", 1, BackendSchema.VERSION);
        try (PreparedStatement s = c.prepareStatement("SELECT schema_version FROM " + prefix + "backend_schema WHERE id = 1"); ResultSet rs = s.executeQuery()) {
            if (!rs.next() || rs.getInt(1) != BackendSchema.VERSION) throw new SQLException("Unsupported backend database schema");
        }
    }
    @Override public LoadoutPlayerData loadPlayer(UUID player, CatalogSnapshot catalog) throws SQLException {
        return transaction(c -> {
            update(c, ignore + prefix + "player_settings (player_uuid, preferred_class, updated_at) VALUES (?, ?, ?)", player, BackpackType.ASSAULT, now());
            for (BackpackType type : BackpackType.values()) {
                ClassLoadout initial = new ClassLoadout(player, type, catalog.rules().defaults(type), 0);
                update(c, ignore + prefix + "player_loadout (player_uuid, soldier_class, schema_version, loadout_json, revision, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
                        player, type, LoadoutJson.VERSION, LoadoutJson.encode(initial), 0L, now());
            }
            Map<BackpackType, ClassLoadout> loadouts = new EnumMap<>(BackpackType.class);
            try (PreparedStatement s = prepared(c, "SELECT soldier_class, schema_version, loadout_json, revision FROM " + prefix + "player_loadout WHERE player_uuid = ?", player);
                 ResultSet rs = s.executeQuery()) {
                while (rs.next()) {
                    if (rs.getInt("schema_version") != LoadoutJson.VERSION) throw new SQLException("Unsupported loadout schema");
                    BackpackType type;
                    try { type = BackpackType.valueOf(rs.getString("soldier_class")); }
                    catch (IllegalArgumentException e) { throw new SQLException("Unknown stored soldier class", e); }
                    loadouts.put(type, LoadoutJson.loadout(player, type, rs.getString("loadout_json"), rs.getLong("revision")));
                }
            }
            BackpackType preferred;
            try (PreparedStatement s = prepared(c, "SELECT preferred_class FROM " + prefix + "player_settings WHERE player_uuid = ?", player); ResultSet rs = s.executeQuery()) {
                if (!rs.next()) throw new SQLException("Player settings missing");
                try { preferred = BackpackType.valueOf(rs.getString(1)); }
                catch (IllegalArgumentException e) { throw new SQLException("Invalid preferred class", e); }
            }
            return new LoadoutPlayerData(readUnlocks(c, player), loadouts, readCustomizations(c, player), preferred);
        });
    }
    @Override public ClassLoadout saveLoadout(ClassLoadout loadout) throws SQLException {
        checkRevision(loadout.revision());
        return transaction(c -> {
            int changed = update(c, "UPDATE " + prefix + "player_loadout SET loadout_json = ?, schema_version = ?, revision = revision + 1, updated_at = ? WHERE player_uuid = ? AND soldier_class = ? AND revision = ?",
                    LoadoutJson.encode(loadout), LoadoutJson.VERSION, now(), loadout.playerId(), loadout.soldierClass(), loadout.revision());
            if (changed != 1) throw new RevisionConflictException();
            return new ClassLoadout(loadout.playerId(), loadout.soldierClass(), loadout.equipment(), loadout.revision() + 1);
        });
    }
    @Override public WeaponCustomization saveCustomization(WeaponCustomization plan) throws SQLException {
        checkRevision(plan.revision());
        return transaction(c -> {
            if (plan.revision() == 0) {
                WeaponCustomization empty = new WeaponCustomization(plan.playerId(), plan.weaponId(), Map.of(), "", 0);
                update(c, ignore + prefix + "weapon_customization (player_uuid, weapon_id, customization_json, revision, updated_at) VALUES (?, ?, ?, ?, ?)",
                        plan.playerId(), plan.weaponId(), LoadoutJson.encode(empty), 0L, now());
            }
            int changed = update(c, "UPDATE " + prefix + "weapon_customization SET customization_json = ?, revision = revision + 1, updated_at = ? WHERE player_uuid = ? AND weapon_id = ? AND revision = ?",
                    LoadoutJson.encode(plan), now(), plan.playerId(), plan.weaponId(), plan.revision());
            if (changed != 1) throw new RevisionConflictException();
            return new WeaponCustomization(plan.playerId(), plan.weaponId(), plan.attachments(), plan.skinId(), plan.revision() + 1);
        });
    }
    @Override public Map<String, WeaponCustomization> getCustomizations(UUID player) throws SQLException { return readCustomizations(database.connection(), player); }
    private Map<String, WeaponCustomization> readCustomizations(Connection c, UUID player) throws SQLException {
        Map<String, WeaponCustomization> plans = new HashMap<>();
        try (PreparedStatement s = prepared(c, "SELECT weapon_id, customization_json, revision FROM " + prefix + "weapon_customization WHERE player_uuid = ?", player); ResultSet rs = s.executeQuery()) {
            while (rs.next()) {
                String id = rs.getString("weapon_id");
                plans.put(id, LoadoutJson.customization(player, id, rs.getString("customization_json"), rs.getLong("revision")));
            }
        }
        return Map.copyOf(plans);
    }
    @Override public Set<String> getPlayerUnlocks(UUID player) throws SQLException { return readUnlocks(database.connection(), player); }
    private Set<String> readUnlocks(Connection c, UUID player) throws SQLException {
        Set<String> ids = new HashSet<>();
        try (PreparedStatement s = prepared(c, "SELECT item_id FROM " + prefix + "player_unlock WHERE player_uuid = ?", player); ResultSet rs = s.executeQuery()) {
            while (rs.next()) ids.add(rs.getString(1));
        }
        return Set.copyOf(ids);
    }
    @Override public void grantUnlock(UUID player, String id, String source) throws SQLException {
        transaction(c -> { update(c, ignore + prefix + "player_unlock (player_uuid, item_id, source, unlocked_at) VALUES (?, ?, ?, ?)", player, id, source, now()); return null; });
    }
    @Override public void revokeUnlock(UUID player, String id) throws SQLException {
        transaction(c -> { update(c, "DELETE FROM " + prefix + "player_unlock WHERE player_uuid = ? AND item_id = ?", player, id); return null; });
    }
    @Override public void setPreferredClass(UUID player, BackpackType type) throws SQLException {
        transaction(c -> {
            update(c, ignore + prefix + "player_settings (player_uuid, preferred_class, updated_at) VALUES (?, ?, ?)", player, type, now());
            update(c, "UPDATE " + prefix + "player_settings SET preferred_class = ?, updated_at = ? WHERE player_uuid = ?", type, now(), player);
            return null;
        });
    }
    private <T> T transaction(SqlOperation<T> op) throws SQLException {
        Connection c = database.connection(); c.setAutoCommit(false);
        try {
            T value = op.run(c); c.commit(); return value;
        } catch (SQLException | RuntimeException e) {
            try { c.rollback(); } catch (SQLException rollback) { e.addSuppressed(rollback); }
            throw e;
        } finally { c.setAutoCommit(true); }
    }
    @FunctionalInterface private interface SqlOperation<T> { T run(Connection c) throws SQLException; }
    private static void checkRevision(long revision) throws RevisionConflictException {
        if (revision < 0 || revision == Long.MAX_VALUE) throw new RevisionConflictException();
    }
    private static long now() { return System.currentTimeMillis(); }
    private static PreparedStatement prepared(Connection c, String sql, Object... values) throws SQLException {
        PreparedStatement statement = c.prepareStatement(sql);
        try {
            for (int i = 0; i < values.length; i++) {
                Object value = values[i];
                if (value instanceof UUID || value instanceof Enum<?>) value = value.toString();
                statement.setObject(i + 1, value);
            }
            return statement;
        } catch (SQLException e) { statement.close(); throw e; }
    }
    private static int update(Connection c, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = prepared(c, sql, values)) { return statement.executeUpdate(); }
    }
}
