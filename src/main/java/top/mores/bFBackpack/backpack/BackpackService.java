package top.mores.bFBackpack.backpack;

import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import top.mores.bFBackpack.BFBackpack;
import top.mores.bFBackpack.config.PluginConfig;
import top.mores.bFBackpack.gui.BackpackHolder;
import top.mores.bFBackpack.gui.BackpackMenu;
import top.mores.bFBackpack.storage.BackpackRepository;
import top.mores.bFBackpack.storage.PlayerSnapshot;
import top.mores.bFBackpack.storage.StoredBackpack;
import top.mores.bFBackpack.util.Message;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * 背包业务层：装载 / 保存 / 切换 / 界面内容回收。
 * <p>
 * 线程模型（这是并发安全的核心）：
 * <ol>
 *   <li><b>Bukkit API 只在主线程调用</b>：开界面、发消息、克隆物品全部在主线程完成，
 *       异步线程只接触已经复制好的 {@code ItemStack[]} 副本。</li>
 *   <li><b>每个玩家一把 ReentrantLock</b>：装载、切换、关闭界面、退出保存都先拿锁，
 *       保证"读界面 -> 改模型 -> 提交落盘"这一串动作不会被同一玩家的另一个操作打断。</li>
 *   <li><b>所有 SQL 走单线程执行器</b>：写入天然按提交顺序串行执行，不会出现同一玩家
 *       两次保存乱序、也不会触发 SQLite 的 SQLITE_BUSY。</li>
 *   <li><b>会话以 UUID 为键</b>：玩家之间不共享任何可变对象，隔离由数据结构本身保证。</li>
 * </ol>
 */
public class BackpackService {

    private final BFBackpack plugin;
    private final BackpackRepository repository;
    private final ExecutorService dbExecutor;

    private final Map<UUID, PlayerSession> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastSwitchAt = new ConcurrentHashMap<>();
    /** 正在异步装载的玩家，防止重复 join 触发重复查询。 */
    private final java.util.Set<UUID> loadingPlayers = ConcurrentHashMap.newKeySet();

    public BackpackService(BFBackpack plugin, BackpackRepository repository, ExecutorService dbExecutor) {
        this.plugin = plugin;
        this.repository = repository;
        this.dbExecutor = dbExecutor;
    }

    // ------------------------------------------------------------------ 装载

    public void loadAsync(Player player) {
        UUID uuid = player.getUniqueId();
        if (sessions.containsKey(uuid) || !loadingPlayers.add(uuid)) {
            return;
        }
        String name = player.getName();

        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        return repository.load(uuid, name);
                    } catch (SQLException e) {
                        throw new CompletionException(e);
                    }
                }, dbExecutor)
                .whenComplete((snapshot, error) -> {
                    if (!plugin.isEnabled()) {
                        loadingPlayers.remove(uuid);
                        return;
                    }
                    try {
                        plugin.getServer().getScheduler().runTask(plugin,
                                () -> finishLoad(uuid, name, snapshot, error));
                    } catch (IllegalStateException e) {
                        loadingPlayers.remove(uuid);
                    }
                });
    }

    private void finishLoad(UUID uuid, String name, PlayerSnapshot snapshot, Throwable error) {
        loadingPlayers.remove(uuid);

        Player player = plugin.getServer().getPlayer(uuid);
        if (player == null || !player.isOnline()) {
            // 装载期间玩家已离线：数据没有任何改动，直接丢弃，避免残留会话
            return;
        }
        if (error != null) {
            plugin.getLogger().log(Level.SEVERE, "装载玩家背包失败: " + name, error);
            Message.send(player, "&c背包数据加载失败，请联系管理员（详见后台日志）");
            return;
        }
        sessions.putIfAbsent(uuid, buildSession(uuid, name, snapshot));
    }

    private PlayerSession buildSession(UUID uuid, String name, PlayerSnapshot snapshot) {
        PluginConfig config = plugin.config();
        EnumMap<BackpackType, Backpack> backpacks = new EnumMap<>(BackpackType.class);
        for (BackpackType type : BackpackType.values()) {
            BackpackSettings settings = config.backpack(type);
            Backpack backpack = new Backpack(uuid, type, settings.slots());
            StoredBackpack stored = snapshot.backpacks().get(type);
            if (stored != null) {
                backpack.load(stored.contents(), settings.slots(), stored.revision());
            }
            backpacks.put(type, backpack);
        }
        return new PlayerSession(uuid, name, snapshot.activeType(), backpacks);
    }

    public PlayerSession session(Player player) {
        return sessions.get(player.getUniqueId());
    }

    // ------------------------------------------------------------------ 打开

    public void openActive(Player player) {
        PlayerSession session = sessions.get(player.getUniqueId());
        if (session == null) {
            Message.send(player, "&c背包数据还没准备好，请稍后再试");
            return;
        }
        openBackpack(player, session.active());
    }

    public void openSelector(Player player) {
        PlayerSession session = sessions.get(player.getUniqueId());
        if (session == null) {
            Message.send(player, "&c背包数据还没准备好，请稍后再试");
            return;
        }
        closeBackpackView(player);
        BackpackMenu.openSelector(player, session, plugin.config());
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.2f);
    }

    public boolean openBackpack(Player player, BackpackType type) {
        PlayerSession session = sessions.get(player.getUniqueId());
        if (session == null) {
            Message.send(player, "&c背包数据还没准备好，请稍后再试");
            return false;
        }
        BackpackSettings settings = plugin.config().backpack(type);
        closeBackpackView(player);

        Collection<ItemStack> overflow;
        session.lock().lock();
        try {
            Backpack backpack = session.backpack(type);
            // 配置热重载后容量可能变了，先按新容量对齐
            overflow = backpack.resize(settings.slots());
            BackpackMenu.openBackpack(player, backpack, settings);
        } finally {
            session.lock().unlock();
        }
        giveBack(player, overflow);
        player.playSound(player.getLocation(), Sound.ITEM_ARMOR_EQUIP_LEATHER, 0.5f, 1.4f);
        return true;
    }

    // ------------------------------------------------------------------ 切换

    /**
     * 切换到指定兵种背包。顺序很重要：先关界面（触发一次保存），再改当前兵种，
     * 最后才打开新界面，任何一步失败都不会把内容留在旧界面上丢失。
     */
    public void switchAndOpen(Player player, BackpackType type) {
        PlayerSession session = sessions.get(player.getUniqueId());
        if (session == null) {
            Message.send(player, "&c背包数据还没准备好，请稍后再试");
            return;
        }
        PluginConfig config = plugin.config();
        BackpackSettings settings = config.backpack(type);

        if (session.active() == type) {
            Message.send(player, "&7当前已经在使用 &f" + settings.display() + "&7 背包");
            openBackpack(player, type);
            return;
        }

        long now = System.currentTimeMillis();
        long cooldown = config.switchCooldownMs();
        Long last = lastSwitchAt.get(player.getUniqueId());
        if (cooldown > 0 && last != null && now - last < cooldown) {
            Message.send(player, "&c切换太频繁了，请稍后再试");
            return;
        }
        lastSwitchAt.put(player.getUniqueId(), now);

        closeBackpackView(player);

        session.lock().lock();
        try {
            session.setActive(type);
        } finally {
            session.lock().unlock();
        }
        submitActiveType(session.uuid(), type);

        Message.actionBar(player, "&a已切换到 " + settings.display() + " 背包");
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.8f, 1.2f);

        if (config.openOnSwitch()) {
            openBackpack(player, type);
        }
    }

    // ------------------------------------------------------------------ 保存

    /** 界面关闭时回收内容：整理非法物品/溢出物品 -> 更新模型 -> 异步落盘。 */
    public void updateFromInventory(Player player, BackpackType type, ItemStack[] raw) {
        PlayerSession session = sessions.get(player.getUniqueId());
        if (session == null) {
            // 会话已释放（退出时的极端时序），宁可还给玩家也不能吞掉
            giveBack(player, Arrays.asList(raw));
            return;
        }

        BackpackSettings settings = plugin.config().backpack(type);
        BackpackSettings.Sanitized sanitized = settings.sanitize(raw);

        session.lock().lock();
        try {
            Backpack backpack = session.backpack(type);
            backpack.setContents(sanitized.contents());
            submitSave(session, backpack);
        } finally {
            session.lock().unlock();
        }

        if (plugin.config().ejectIllegalItems()) {
            giveBack(player, sanitized.rejected());
        }
    }

    /** 退出登录：先把界面内容落盘，再释放会话。 */
    public void handleQuit(Player player) {
        closeBackpackView(player);

        PlayerSession session = sessions.remove(player.getUniqueId());
        lastSwitchAt.remove(player.getUniqueId());
        if (session == null) {
            return;
        }
        session.lock().lock();
        try {
            for (Backpack backpack : session.backpacks()) {
                if (backpack.dirty()) {
                    submitSave(session, backpack);
                }
            }
            submitActiveType(session.uuid(), session.active());
        } finally {
            session.lock().unlock();
        }
    }

    public void startAutoSave() {
        int seconds = plugin.config().autoSaveSeconds();
        if (seconds <= 0) {
            return;
        }
        long ticks = 20L * seconds;
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::flushDirty, ticks, ticks);
    }

    private void flushDirty() {
        for (PlayerSession session : sessions.values()) {
            session.lock().lock();
            try {
                for (Backpack backpack : session.backpacks()) {
                    if (backpack.dirty()) {
                        submitSave(session, backpack);
                    }
                }
            } finally {
                session.lock().unlock();
            }
        }
    }

    /** 关服：把在线会话的脏数据全部提交，等待执行器排空由 Database 负责。 */
    public void shutdown() {
        for (PlayerSession session : new ArrayList<>(sessions.values())) {
            session.lock().lock();
            try {
                for (Backpack backpack : session.backpacks()) {
                    if (backpack.dirty()) {
                        submitSave(session, backpack);
                    }
                }
                submitActiveType(session.uuid(), session.active());
            } finally {
                session.lock().unlock();
            }
        }
        sessions.clear();
        lastSwitchAt.clear();
    }

    /**
     * 提交一次保存。contents 是主线程复制出来的独立数组，异步线程不会再碰到模型对象；
     * 失败重试三次，仍失败则留 SEVERE 日志（脏标记保留，自动保存会再试）。
     */
    private void submitSave(PlayerSession session, Backpack backpack) {
        ItemStack[] contents = backpack.copyContents();
        int version = backpack.version();
        long expected = backpack.revision();
        int slots = contents.length;
        UUID uuid = session.uuid();
        String name = session.name();
        BackpackType type = backpack.type();

        executeDb(() -> {
            SQLException lastError = null;
            for (int attempt = 1; attempt <= 3; attempt++) {
                try {
                    long revision = repository.save(uuid, type, slots, contents, expected);
                    applySavedRevision(uuid, type, revision, version);
                    return;
                } catch (SQLException e) {
                    lastError = e;
                    sleepQuietly(150L * attempt);
                }
            }
            plugin.getLogger().log(Level.SEVERE, "保存背包失败: " + name + "/" + type.id(), lastError);
        });
    }

    private void applySavedRevision(UUID uuid, BackpackType type, long revision, int savedVersion) {
        if (!plugin.isEnabled()) {
            return;
        }
        try {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                PlayerSession session = sessions.get(uuid);
                if (session == null) {
                    return;
                }
                session.lock().lock();
                try {
                    Backpack backpack = session.backpack(type);
                    if (backpack != null) {
                        backpack.markSaved(revision, savedVersion);
                    }
                } finally {
                    session.lock().unlock();
                }
            });
        } catch (IllegalStateException ignored) {
            // 插件正在卸载，内存状态不再需要维护
        }
    }

    private void submitActiveType(UUID uuid, BackpackType type) {
        executeDb(() -> {
            try {
                repository.saveActiveType(uuid, type);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "保存当前兵种失败: " + uuid, e);
            }
        });
    }

    // ------------------------------------------------------------------ 工具

    /** 关闭当前打开的背包内容页（会触发 InventoryCloseEvent 完成保存）。 */
    private void closeBackpackView(Player player) {
        Inventory top = player.getOpenInventory().getTopInventory();
        if (top.getHolder() instanceof BackpackHolder holder && !holder.isSelector()) {
            player.closeInventory();
        }
    }

    private void giveBack(Player player, Collection<ItemStack> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        for (ItemStack item : items) {
            if (item == null || item.getType().isAir()) {
                continue;
            }
            Map<Integer, ItemStack> leftovers = player.getInventory().addItem(item.clone());
            for (ItemStack leftover : leftovers.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), leftover);
            }
        }
    }

    private void executeDb(Runnable task) {
        try {
            dbExecutor.execute(task);
        } catch (RejectedExecutionException e) {
            plugin.getLogger().warning("数据库线程已关闭，本次写入被丢弃");
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            TimeUnit.MILLISECONDS.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
