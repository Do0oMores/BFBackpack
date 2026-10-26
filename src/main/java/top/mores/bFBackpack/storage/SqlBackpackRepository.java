package top.mores.bFBackpack.storage;

import org.bukkit.inventory.ItemStack;
import top.mores.bFBackpack.backpack.BackpackType;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * JDBC 实现。所有方法都只在数据库单线程上被调用，因此内部缓存不需要加锁。
 */
public class SqlBackpackRepository implements BackpackRepository {

    private final Database database;
    private final Logger logger;
    private final String playerTable;
    private final String backpackTable;
    private final String insertIgnore;

    /** key = uuid|type，记录本进程已知的最新版本号，避免自己连续保存两次被误判成冲突。 */
    private final Map<String, Long> revisionCache = new HashMap<>();

    public SqlBackpackRepository(Database database, Logger logger) {
        this.database = database;
        this.logger = logger;
        this.playerTable = database.playerTable();
        this.backpackTable = database.backpackTable();
        this.insertIgnore = database.mysql() ? "INSERT IGNORE INTO " : "INSERT OR IGNORE INTO ";
    }

    @Override
    public PlayerSnapshot load(UUID uuid, String playerName) throws SQLException {
        Connection connection = database.connection();
        String key = uuid.toString();

        try (PreparedStatement statement = connection.prepareStatement(
                insertIgnore + playerTable + " (player_uuid, player_name, active_type, updated_at) VALUES (?, ?, ?, ?)")) {
            statement.setString(1, key);
            statement.setString(2, playerName);
            statement.setString(3, BackpackType.ASSAULT.name());
            statement.setLong(4, System.currentTimeMillis());
            statement.executeUpdate();
        }
        // 玩家改名后同步一次，方便在库里排查问题
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE " + playerTable + " SET player_name = ? WHERE player_uuid = ? AND player_name <> ?")) {
            statement.setString(1, playerName);
            statement.setString(2, key);
            statement.setString(3, playerName);
            statement.executeUpdate();
        }

        BackpackType active = BackpackType.ASSAULT;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT active_type FROM " + playerTable + " WHERE player_uuid = ?")) {
            statement.setString(1, key);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    active = BackpackType.fromId(resultSet.getString("active_type"))
                            .orElse(BackpackType.ASSAULT);
                }
            }
        }

        Map<BackpackType, StoredBackpack> backpacks = new EnumMap<>(BackpackType.class);
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT backpack_type, slots, contents, revision FROM " + backpackTable + " WHERE player_uuid = ?")) {
            statement.setString(1, key);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    Optional<BackpackType> type = BackpackType.fromId(resultSet.getString("backpack_type"));
                    if (type.isEmpty()) {
                        // 旧版本或已被删除的兵种，直接忽略，不影响其他背包
                        continue;
                    }
                    int slots = Math.max(9, resultSet.getInt("slots"));
                    ItemStack[] contents;
                    try {
                        contents = ItemSerializer.decode(resultSet.getString("contents"), slots);
                    } catch (IOException e) {
                        throw new SQLException("背包内容无法解析：" + key + "/" + type.get().id(), e);
                    }
                    long revision = resultSet.getLong("revision");
                    backpacks.put(type.get(), new StoredBackpack(contents, revision));
                    revisionCache.put(cacheKey(key, type.get()), revision);
                }
            }
        }
        return new PlayerSnapshot(active, backpacks);
    }

    @Override
    public long save(UUID uuid, BackpackType type, int slots, ItemStack[] contents, long expectedRevision)
            throws SQLException {
        Connection connection = database.connection();
        String key = uuid.toString();
        String payload;
        try {
            payload = isEmpty(contents) ? null : ItemSerializer.encode(contents);
        } catch (IOException e) {
            throw new SQLException("背包内容序列化失败", e);
        }

        long expected = Math.max(expectedRevision, revisionCache.getOrDefault(cacheKey(key, type), 0L));
        long now = System.currentTimeMillis();

        int updated;
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE " + backpackTable + " SET contents = ?, slots = ?, revision = revision + 1, updated_at = ?"
                        + " WHERE player_uuid = ? AND backpack_type = ? AND revision = ?")) {
            statement.setString(1, payload);
            statement.setInt(2, slots);
            statement.setLong(3, now);
            statement.setString(4, key);
            statement.setString(5, type.name());
            statement.setLong(6, expected);
            updated = statement.executeUpdate();
        }

        if (updated == 0) {
            Long current = readRevision(connection, key, type);
            if (current == null) {
                // 首次落盘：插入新行
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO " + backpackTable
                                + " (player_uuid, backpack_type, slots, contents, revision, updated_at)"
                                + " VALUES (?, ?, ?, ?, ?, ?)")) {
                    statement.setString(1, key);
                    statement.setString(2, type.name());
                    statement.setInt(3, slots);
                    statement.setString(4, payload);
                    statement.setLong(5, expected + 1);
                    statement.setLong(6, now);
                    statement.executeUpdate();
                }
            } else {
                // 别的实例写过这一行：在线玩家手里的才是最新数据，强制覆盖并留痕
                logger.warning("背包写入冲突 (" + key + "/" + type.id() + ")"
                        + " 库里版本 " + current + "，本地版本 " + expected + "，以在线玩家数据为准");
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE " + backpackTable + " SET contents = ?, slots = ?, revision = revision + 1, updated_at = ?"
                                + " WHERE player_uuid = ? AND backpack_type = ?")) {
                    statement.setString(1, payload);
                    statement.setInt(2, slots);
                    statement.setLong(3, now);
                    statement.setString(4, key);
                    statement.setString(5, type.name());
                    statement.executeUpdate();
                }
            }
        }

        Long revision = readRevision(connection, key, type);
        long latest = revision == null ? 0L : revision;
        revisionCache.put(cacheKey(key, type), latest);
        return latest;
    }

    @Override
    public void saveActiveType(UUID uuid, BackpackType type) throws SQLException {
        Connection connection = database.connection();
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE " + playerTable + " SET active_type = ?, updated_at = ? WHERE player_uuid = ?")) {
            statement.setString(1, type.name());
            statement.setLong(2, System.currentTimeMillis());
            statement.setString(3, uuid.toString());
            statement.executeUpdate();
        }
    }

    private Long readRevision(Connection connection, String key, BackpackType type) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT revision FROM " + backpackTable + " WHERE player_uuid = ? AND backpack_type = ?")) {
            statement.setString(1, key);
            statement.setString(2, type.name());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getLong(1) : null;
            }
        }
    }

    private static boolean isEmpty(ItemStack[] contents) {
        for (ItemStack item : contents) {
            if (item != null && !item.getType().isAir()) {
                return false;
            }
        }
        return true;
    }

    private static String cacheKey(String uuid, BackpackType type) {
        return uuid + "|" + type.name();
    }
}
