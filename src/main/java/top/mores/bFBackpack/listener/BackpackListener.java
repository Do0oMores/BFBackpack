package top.mores.bFBackpack.listener;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import top.mores.bFBackpack.BFBackpack;
import top.mores.bFBackpack.backpack.BackpackService;
import top.mores.bFBackpack.backpack.BackpackSettings;
import top.mores.bFBackpack.backpack.BackpackType;
import top.mores.bFBackpack.gui.BackpackHolder;
import top.mores.bFBackpack.util.Message;

import java.util.Map;
import java.util.Objects;

public class BackpackListener implements Listener {

    private final BFBackpack plugin;
    private final BackpackService service;

    public BackpackListener(BFBackpack plugin, BackpackService service) {
        this.plugin = plugin;
        this.service = service;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        service.loadAsync(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        service.handleQuit(event.getPlayer());
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof BackpackHolder holder) || holder.isSelector()) {
            return;
        }
        if (!(event.getPlayer() instanceof Player player) || !holder.belongsTo(player)) {
            return;
        }
        service.updateFromInventory(player, holder.type(), top.getContents());
    }

    /** 玩家死亡时界面会被客户端关掉，这里补一次服务端关闭，确保内容一定落盘。 */
    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (player.getOpenInventory().getTopInventory().getHolder() instanceof BackpackHolder) {
            plugin.getServer().getScheduler().runTask(plugin, player::closeInventory);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof BackpackHolder holder)) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        // 别人的界面一律拒绝并强制关闭，杜绝跨玩家读写
        if (!holder.belongsTo(player)) {
            event.setCancelled(true);
            Message.send(player, "&c这不是你的背包");
            player.closeInventory();
            return;
        }
        if (holder.isSelector()) {
            handleSelectorClick(event, player, holder);
            return;
        }
        if (!plugin.config().restrictItems()) {
            return;
        }
        BackpackSettings settings = plugin.config().backpack(holder.type());
        ItemStack entering = itemEnteringBackpack(event, top);
        if (entering != null && !settings.allows(entering)) {
            event.setCancelled(true);
            Message.send(player, "&c" + settings.display() + "背包不能携带该物品");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof BackpackHolder holder) || holder.isSelector()) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player) || !holder.belongsTo(player)) {
            event.setCancelled(true);
            return;
        }
        if (!plugin.config().restrictItems()) {
            return;
        }
        BackpackSettings settings = plugin.config().backpack(holder.type());
        int topSize = top.getSize();
        for (Map.Entry<Integer, ItemStack> entry : event.getNewItems().entrySet()) {
            // raw slot 大于等于 topSize 说明落在玩家自己背包里，不需要校验
            if (entry.getKey() >= topSize) {
                continue;
            }
            if (!settings.allows(entry.getValue())) {
                event.setCancelled(true);
                Message.send(player, "&c" + settings.display() + "背包不能携带该物品");
                return;
            }
        }
    }

    private void handleSelectorClick(InventoryClickEvent event, Player player, BackpackHolder holder) {
        event.setCancelled(true);
        if (!Objects.equals(event.getClickedInventory(), event.getView().getTopInventory())) {
            return;
        }
        BackpackType type = holder.typeAt(event.getRawSlot());
        if (type == null) {
            return;
        }
        player.closeInventory();
        // 下一 tick 再开新界面，避免在点击事件里连续操作容器导致的客户端不同步
        plugin.getServer().getScheduler().runTask(plugin, () -> service.switchAndOpen(player, type));
    }

    /**
     * 判断本次点击"将要放进背包"的是哪件物品：
     * 点击背包格子时是光标物品，从玩家背包 shift 点击时是那一格的物品，
     * 数字键/副手交换则取对应的快捷栏/副手物品。
     */
    private ItemStack itemEnteringBackpack(InventoryClickEvent event, Inventory top) {
        boolean clickedTop = Objects.equals(event.getClickedInventory(), top);
        ClickType click = event.getClick();

        if (clickedTop && click == ClickType.NUMBER_KEY) {
            int button = event.getHotbarButton();
            return button < 0 ? null : event.getWhoClicked().getInventory().getItem(button);
        }
        if (clickedTop && click == ClickType.SWAP_OFFHAND) {
            return event.getWhoClicked().getInventory().getItemInOffHand();
        }
        if (clickedTop) {
            // shift 点击背包格子是往玩家背包挪，不涉及限制
            return click.isShiftClick() ? null : event.getCursor();
        }
        return click.isShiftClick() ? event.getCurrentItem() : null;
    }
}
