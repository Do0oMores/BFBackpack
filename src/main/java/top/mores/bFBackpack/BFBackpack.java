package top.mores.bFBackpack;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import top.mores.bFBackpack.backpack.BackpackService;
import top.mores.bFBackpack.command.BackpackCommand;
import top.mores.bFBackpack.config.PluginConfig;
import top.mores.bFBackpack.listener.BackpackListener;
import top.mores.bFBackpack.storage.BackpackRepository;
import top.mores.bFBackpack.storage.Database;
import top.mores.bFBackpack.storage.SqlBackpackRepository;

import java.util.logging.Level;

public final class BFBackpack extends JavaPlugin {

    private static BFBackpack instance;

    private volatile PluginConfig config;
    private Database database;
    private BackpackService service;

    @Override
    public void onEnable() {
        instance = this;

        saveDefaultConfig();
        this.config = PluginConfig.load(getConfig(), getDataFolder(), getLogger());

        this.database = new Database(getLogger(), config.database());
        try {
            database.connect();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "数据库初始化失败，插件将被停用", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        BackpackRepository repository = new SqlBackpackRepository(database, getLogger());
        this.service = new BackpackService(this, repository, database.executor());

        getServer().getPluginManager().registerEvents(new BackpackListener(this, service), this);

        BackpackCommand command = new BackpackCommand(this, service);
        PluginCommand pluginCommand = getCommand("bfbackpack");
        if (pluginCommand != null) {
            pluginCommand.setExecutor(command);
            pluginCommand.setTabCompleter(command);
        }

        service.startAutoSave();
        getLogger().info("BFBackpack 已启用（" + config.describe() + "）");
    }

    @Override
    public void onDisable() {
        if (service != null) {
            service.shutdown();
        }
        if (database != null) {
            database.close();
        }
        instance = null;
        getLogger().info("BFBackpack 已停用");
    }

    public void reloadPluginConfig() {
        reloadConfig();
        this.config = PluginConfig.load(getConfig(), getDataFolder(), getLogger());
    }

    public PluginConfig config() {
        return config;
    }

    public static BFBackpack getInstance() {
        return instance;
    }
}
