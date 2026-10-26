package top.mores.bFBackpack.storage;

/**
 * 数据库连接参数，由 config.yml 解析后注入，不依赖 Bukkit。
 */
public record DbSettings(
        boolean mysql,
        String sqliteFile,
        String host,
        int port,
        String database,
        String username,
        String password,
        String parameters,
        String tablePrefix
) {
}
