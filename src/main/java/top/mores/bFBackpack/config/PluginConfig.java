package top.mores.bFBackpack.config;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import top.mores.bFBackpack.backpack.BackpackSettings;
import top.mores.bFBackpack.backpack.BackpackType;
import top.mores.bFBackpack.storage.DbSettings;

import java.io.File;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * config.yml 的不可变快照。reload 时整体替换，运行中的会话继续用旧快照直到下次打开界面，
 * 因此不会出现"改了一半"的中间状态。
 */
public final class PluginConfig {

    private final DbSettings database;
    private final int switchCooldownMs;
    private final boolean openOnSwitch;
    private final int autoSaveSeconds;
    private final boolean restrictItems;
    private final boolean ejectIllegalItems;
    private final Map<BackpackType, BackpackSettings> backpacks;

    private PluginConfig(DbSettings database, int switchCooldownMs, boolean openOnSwitch, int autoSaveSeconds,
                         boolean restrictItems, boolean ejectIllegalItems,
                         Map<BackpackType, BackpackSettings> backpacks) {
        this.database = database;
        this.switchCooldownMs = switchCooldownMs;
        this.openOnSwitch = openOnSwitch;
        this.autoSaveSeconds = autoSaveSeconds;
        this.restrictItems = restrictItems;
        this.ejectIllegalItems = ejectIllegalItems;
        this.backpacks = backpacks;
    }

    public static PluginConfig load(FileConfiguration config, File dataFolder, Logger logger) {
        String storageType = config.getString("database.type", "sqlite").trim();
        if (!storageType.equalsIgnoreCase("mysql") && !storageType.equalsIgnoreCase("sqlite"))
            throw new IllegalArgumentException("database.type 必须为 sqlite 或 mysql");
        boolean mysql = "mysql".equalsIgnoreCase(storageType);

        String prefix = config.getString("database.table-prefix", "bf_");
        if (prefix == null || !prefix.matches("(?:[A-Za-z_][A-Za-z0-9_]{0,15})?")) {
            logger.warning("database.table-prefix 需以字母/下划线开头、最多16位，仅包含字母/数字/下划线，已回退为 bf_");
            prefix = "bf_";
        }

        String sqliteFile = new File(dataFolder, config.getString("database.sqlite.file", "backpacks.db"))
                .getAbsolutePath();

        DbSettings database = new DbSettings(
                mysql,
                sqliteFile,
                config.getString("database.mysql.host", "127.0.0.1"),
                config.getInt("database.mysql.port", 3306),
                config.getString("database.mysql.database", "minecraft"),
                config.getString("database.mysql.username", "root"),
                config.getString("database.mysql.password", ""),
                config.getString("database.mysql.parameters",
                        "useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&connectionTimeZone=UTC"),
                prefix);

        Map<BackpackType, BackpackSettings> backpacks = new EnumMap<>(BackpackType.class);
        for (BackpackType type : BackpackType.values()) {
            String base = "backpacks." + type.name();

            String display = config.getString(base + ".display", type.defaultDisplay());
            String description = config.getString(base + ".description", "");

            Material icon = Material.matchMaterial(config.getString(base + ".icon", type.defaultIcon()));
            if (icon == null) {
                logger.warning("未知的图标材质: " + config.getString(base + ".icon") + "，已回退为默认图标");
                icon = Material.valueOf(type.defaultIcon());
            }

            int slots = config.getInt(base + ".slots", type.defaultSlots());
            if (slots % 9 != 0 || slots < 9 || slots > 54) {
                logger.warning(base + ".slots 必须是 9 的倍数且位于 9~54，已修正");
            }

            Set<Material> denied = new LinkedHashSet<>();
            List<String> denyList = config.getStringList(base + ".deny-materials");
            for (String raw : denyList) {
                Material material = Material.matchMaterial(raw);
                if (material == null) {
                    logger.warning(base + ".deny-materials 中存在未知材质: " + raw);
                    continue;
                }
                denied.add(material);
            }

            backpacks.put(type, new BackpackSettings(type, display, description, icon, slots, denied));
        }

        return new PluginConfig(
                database,
                Math.max(0, config.getInt("settings.switch-cooldown-ms", 500)),
                config.getBoolean("settings.open-on-switch", true),
                Math.max(0, config.getInt("settings.auto-save-seconds", 300)),
                config.getBoolean("settings.restrict-items", true),
                config.getBoolean("settings.eject-illegal-items", true),
                backpacks);
    }

    public DbSettings database() {
        return database;
    }

    public int switchCooldownMs() {
        return switchCooldownMs;
    }

    public boolean openOnSwitch() {
        return openOnSwitch;
    }

    public int autoSaveSeconds() {
        return autoSaveSeconds;
    }

    public boolean restrictItems() {
        return restrictItems;
    }

    public boolean ejectIllegalItems() {
        return ejectIllegalItems;
    }

    public BackpackSettings backpack(BackpackType type) {
        return backpacks.get(type);
    }

    public String describe() {
        return "存储=" + (database.mysql() ? "MySQL" : "SQLite")
                + "，前缀=" + database.tablePrefix()
                + "，冷却=" + switchCooldownMs + "ms"
                + "，自动保存=" + (autoSaveSeconds == 0 ? "关闭" : autoSaveSeconds + "s");
    }

    @Override
    public String toString() {
        return "PluginConfig[" + describe() + "]";
    }
}
