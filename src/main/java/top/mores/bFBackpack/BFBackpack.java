package top.mores.bFBackpack;

import com.google.gson.Gson;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import top.mores.bFBackpack.api.*;
import top.mores.bFBackpack.backpack.BackpackType;
import top.mores.bFBackpack.catalog.WeaponCatalog;
import top.mores.bFBackpack.command.LoadoutCommand;
import top.mores.bFBackpack.config.PluginConfig;
import top.mores.bFBackpack.listener.*;
import top.mores.bFBackpack.service.LoadoutService;
import top.mores.bFBackpack.storage.*;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.logging.Level;

/** Bukkit lifecycle owner; all SQL, including startup/schema creation, runs on the DB worker. */
public final class BFBackpack extends JavaPlugin {
    private volatile PluginConfig config;
    private Database database;
    private volatile LoadoutService service;
    private BukkitPermissionGateway permissions;
    private volatile boolean stopping;
    private final Gson json = new Gson();

    @Override public void onEnable() {
        saveDefaultConfig();
        try { config = PluginConfig.load(getConfig(), getDataFolder(), getLogger()); }
        catch (IllegalArgumentException e) {
            getLogger().log(Level.SEVERE, "config.yml 无效，插件将停用", e);
            getServer().getPluginManager().disablePlugin(this); return;
        }
        for (String file : new String[]{"weapons.yml", "attachments.yml", "class-rules.yml"}) {
            if (!new java.io.File(getDataFolder(), file).exists()) saveResource(file, false);
        }
        permissions = new BukkitPermissionGateway(this);
        database = new Database(getLogger(), config.database());
        PluginCommand command = getCommand("bfbackpack");
        if (command == null) throw new IllegalStateException("bfbackpack missing from plugin.yml");
        LoadoutCommand commands = new LoadoutCommand(this);
        command.setExecutor(commands); command.setTabCompleter(commands);
        CompletableFuture.supplyAsync(() -> {
            try {
                WeaponCatalog catalog = new WeaponCatalog(getLogger()::warning);
                catalog.reload(getDataFolder().toPath());
                database.connect();
                SqlLoadoutRepository repository = new SqlLoadoutRepository(database);
                repository.initialize();
                return new LoadoutService(catalog, repository, repository, repository, database.executor(), permissions, getLogger());
            } catch (Exception e) { throw new CompletionException(e); }
        }, database.executor()).whenComplete((backend, error) -> {
            if (stopping) return;
            try {
                getServer().getScheduler().runTask(this, () -> {
                    if (stopping) return;
                    if (error != null) {
                        getLogger().log(Level.SEVERE, "BFBackpack 2.0 初始化失败", error);
                        getServer().getPluginManager().disablePlugin(this); return;
                    }
                    service = backend;
                    getServer().getServicesManager().register(BFBackpackAPI.class, backend, this, ServicePriority.Normal);
                    getServer().getPluginManager().registerEvents(new LoadoutListener(backend), this);
                    getServer().getOnlinePlayers().forEach(p -> backend.loadPlayer(p.getUniqueId()));
                    getLogger().info("BFBackpack 2.0 已就绪，API v" + BFBackpackAPI.API_VERSION);
                });
            } catch (IllegalStateException e) {
                if (!stopping) getLogger().log(Level.WARNING, "无法完成插件初始化", e);
            }
        });
    }
    @Override public void onDisable() {
        stopping = true;
        getServer().getServicesManager().unregisterAll(this);
        if (service != null) service.shutdown();
        if (permissions != null) permissions.close();
        if (database != null) database.close();
        service = null;
        getLogger().info("BFBackpack 已停用（已提交写入按序排空后关闭连接）");
    }
    /** Legacy configuration access; database settings apply on restart. */
    public PluginConfig config() { return config; }
    /** Retained for the inactive legacy command implementation. */
    public void reloadPluginConfig() {
        reloadConfig(); config = PluginConfig.load(getConfig(), getDataFolder(), getLogger());
    }
    /** Ready backend or null while startup is in progress. */
    public LoadoutService backend() { return service; }
    /** Classloader-neutral compatibility handshake. */
    public int getApiVersion() { return BFBackpackAPI.API_VERSION; }
    /** Classloader-neutral JSON protocol version. */
    public int getProtocolVersion() { return BFBackpackAPI.PROTOCOL_VERSION; }
    /**
     * Reflection bridge using only bootstrap/JDK types. JSON contains code, value and message.
     * Future completion is not on the Bukkit thread. Never join on a game thread.
     * Other operations use the API classifier in a Bukkit bridge sharing the API classloader.
     */
    public CompletableFuture<String> getDeploymentJson(String uuid, String type, int protocolVersion) {
        if (protocolVersion != getProtocolVersion()) return CompletableFuture.completedFuture(json.toJson(LoadoutResult.fail(ResultCode.API_INCOMPATIBLE, "Protocol version mismatch")));
        LoadoutService current = service;
        if (current == null) return CompletableFuture.completedFuture(json.toJson(LoadoutResult.fail(ResultCode.NOT_READY, "Backend initializing")));
        try { return current.getDeploymentLoadout(UUID.fromString(uuid), BackpackType.valueOf(type)).thenApply(json::toJson); }
        catch (IllegalArgumentException | NullPointerException e) { return CompletableFuture.completedFuture(json.toJson(LoadoutResult.fail(ResultCode.INVALID_INPUT, "Invalid UUID/class"))); }
    }
    /** Install second-stage validators through a Bukkit bridge with a shared API classloader. */
    public void installProviderValidators(WeaponProviderValidator weapons, AttachmentCompatibilityValidator attachments) {
        LoadoutService current = service;
        if (current == null) throw new IllegalStateException("Backend not ready");
        current.installProviderValidators(weapons, attachments);
    }
}
