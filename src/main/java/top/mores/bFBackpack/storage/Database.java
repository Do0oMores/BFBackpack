package top.mores.bFBackpack.storage;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 连接管理与建表。
 * <p>
 * 设计要点：
 * <ul>
 *   <li>插件自己持有 Driver 实例并通过 {@code driver.connect(...)} 取连接，
 *       绕开 DriverManager 在插件 ClassLoader 下的可见性问题（shade 之后很常见）。</li>
 *   <li>只保留<b>一条</b>连接，由<b>单线程</b>执行器串行使用。
 *       这样 SQLite 不会有 SQLITE_BUSY，MySQL 也不会出现同一玩家两次写入乱序。</li>
 *   <li>SQLite 打开 WAL + busy_timeout，兼顾读写并发和异常退出后的恢复。</li>
 * </ul>
 */
public class Database implements AutoCloseable {

    private final Logger logger;
    private final DbSettings settings;
    private final ExecutorService executor;
    private final String playerTable;
    private final String backpackTable;

    private Connection connection;

    public Database(Logger logger, DbSettings settings) {
        if (settings.tablePrefix() == null || !settings.tablePrefix().matches("(?:[A-Za-z_][A-Za-z0-9_]{0,15})?"))
            throw new IllegalArgumentException("Invalid database table prefix");
        this.logger = logger;
        this.settings = settings;
        this.playerTable = settings.tablePrefix() + "player";
        this.backpackTable = settings.tablePrefix() + "backpack";
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "BFBackpack-DB");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void connect() throws Exception {
        open();
        // Backend tables are initialized by SqlLoadoutRepository on the same worker.
    }

    public ExecutorService executor() {
        return executor;
    }

    public String playerTable() {
        return playerTable;
    }

    public String backpackTable() {
        return backpackTable;
    }

    public boolean mysql() {
        return settings.mysql();
    }

    public String tablePrefix() { return settings.tablePrefix(); }

    /** 取连接，失效时自动重连（MySQL 的 wait_timeout 会掐掉长时间空闲的连接）。 */
    public synchronized Connection connection() throws SQLException {
        try {
            if (connection == null || connection.isClosed() || !connection.isValid(3)) {
                logger.warning("数据库连接不可用，正在尝试重连…");
                open();
            }
        } catch (Exception e) {
            throw new SQLException("数据库连接不可用", e);
        }
        return connection;
    }

    private synchronized void open() throws Exception {
        String driverClass = settings.mysql() ? "com.mysql.cj.jdbc.Driver" : "org.sqlite.JDBC";
        Driver driver = (Driver) Class.forName(driverClass).getDeclaredConstructor().newInstance();

        Properties properties = new Properties();
        String url;
        if (settings.mysql()) {
            url = "jdbc:mysql://" + settings.host() + ":" + settings.port() + "/"
                    + settings.database() + "?" + settings.parameters();
            properties.setProperty("user", settings.username());
            properties.setProperty("password", settings.password());
        } else {
            url = "jdbc:sqlite:" + settings.sqliteFile();
        }

        Connection opened = driver.connect(url, properties);
        if (opened == null) {
            throw new SQLException("驱动无法识别数据库连接地址");
        }
        if (connection != null) {
            closeQuietly(connection);
        }
        connection = opened;

        if (!settings.mysql()) {
            applySqlitePragmas();
        }
    }

    private void applySqlitePragmas() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute("PRAGMA busy_timeout=5000");
            statement.execute("PRAGMA temp_store=MEMORY");
        }
    }

    /** Optional retained legacy schema; v2 deliberately never initializes these inventory tables. */
    @Deprecated
    public void initializeLegacyTables() throws SQLException {
        connection();
        // SQLite 的 TEXT 足够放压缩后的背包数据；MySQL 用 LONGTEXT 防止极端 NBT 撑爆 64KB
        String contentsColumn = settings.mysql() ? "LONGTEXT" : "TEXT";
        String suffix = settings.mysql() ? " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4" : "";

        execute("CREATE TABLE IF NOT EXISTS " + playerTable + " ("
                + "player_uuid CHAR(36) NOT NULL,"
                + "player_name VARCHAR(32) NOT NULL,"
                + "active_type VARCHAR(16) NOT NULL,"
                + "updated_at BIGINT NOT NULL,"
                + "PRIMARY KEY (player_uuid)"
                + ")" + suffix);

        execute("CREATE TABLE IF NOT EXISTS " + backpackTable + " ("
                + "player_uuid CHAR(36) NOT NULL,"
                + "backpack_type VARCHAR(16) NOT NULL,"
                + "slots INT NOT NULL,"
                + "contents " + contentsColumn + " NULL,"
                + "revision BIGINT NOT NULL DEFAULT 0,"
                + "updated_at BIGINT NOT NULL,"
                + "PRIMARY KEY (player_uuid, backpack_type)"
                + ")" + suffix);
    }

    private void execute(String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private void closeQuietly(Connection target) {
        try {
            target.close();
        } catch (SQLException e) {
            logger.log(Level.FINE, "关闭旧连接失败", e);
        }
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(20, TimeUnit.SECONDS)) {
                logger.warning("等待数据库写入超时，可能有未落盘的数据");
                // Do not close a connection still used by an accepted write.
                // A finalizer drains the worker and closes it after termination.
                Thread finalizer = new Thread(this::closeAfterDrain, "BFBackpack-DB-close");
                finalizer.setDaemon(false);
                finalizer.start();
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Thread finalizer = new Thread(this::closeAfterDrain, "BFBackpack-DB-close");
            finalizer.setDaemon(false);
            finalizer.start();
            return;
        }
        closeConnection();
    }

    private void closeAfterDrain() {
        boolean interrupted = false;
        while (!executor.isTerminated()) {
            try { executor.awaitTermination(1, TimeUnit.SECONDS); }
            catch (InterruptedException e) { interrupted = true; }
        }
        closeConnection();
        if (interrupted) Thread.currentThread().interrupt();
    }

    private synchronized void closeConnection() {
        if (connection != null) {
            closeQuietly(connection);
            connection = null;
        }
    }
}
