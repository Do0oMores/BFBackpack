package top.mores.bFBackpack.gui;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import top.mores.bFBackpack.backpack.Backpack;
import top.mores.bFBackpack.backpack.BackpackSettings;
import top.mores.bFBackpack.backpack.BackpackType;
import top.mores.bFBackpack.backpack.PlayerSession;
import top.mores.bFBackpack.config.PluginConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * 背包界面构建：兵种选择页 + 具体背包页。
 * 只做"画界面"，切换/保存等业务一律交给 Service。
 */
public final class BackpackMenu {

    /** 四个兵种在选择页里的固定位置，左右对称。 */
    private static final int[] SELECTOR_SLOTS = {10, 12, 14, 16};

    private BackpackMenu() {
    }

    public static void openSelector(Player player, PlayerSession session, PluginConfig config) {
        BackpackHolder holder = new BackpackHolder(player.getUniqueId(), null);
        Inventory inventory = Bukkit.createInventory(holder, 27, "§8兵种选择 · 背包");
        holder.setInventory(inventory);

        ItemStack filler = filler();
        for (int i = 0; i < inventory.getSize(); i++) {
            inventory.setItem(i, filler);
        }

        BackpackType[] types = BackpackType.values();
        for (int i = 0; i < types.length && i < SELECTOR_SLOTS.length; i++) {
            BackpackType type = types[i];
            int slot = SELECTOR_SLOTS[i];
            inventory.setItem(slot, icon(config, session, type, session.active() == type));
            holder.bind(slot, type);
        }

        player.openInventory(inventory);
    }

    public static void openBackpack(Player player, Backpack backpack, BackpackSettings settings) {
        int size = Math.max(9, Math.min(54, backpack.size()));
        BackpackHolder holder = new BackpackHolder(player.getUniqueId(), settings.type());
        Inventory inventory = Bukkit.createInventory(holder, size, "§8" + settings.display() + "背包");
        holder.setInventory(inventory);

        inventory.setContents(backpack.copyContents());
        player.openInventory(inventory);
    }

    private static ItemStack icon(PluginConfig config, PlayerSession session, BackpackType type, boolean active) {
        BackpackSettings settings = config.backpack(type);
        Backpack backpack = session.backpack(type);

        ItemStack item = new ItemStack(settings.icon());
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }

        meta.setDisplayName((active ? "§a" : "§f") + settings.display() + "背包");

        List<String> lore = new ArrayList<>();
        if (!settings.description().isBlank()) {
            lore.add("§7定位：§f" + settings.description());
        }
        lore.add("§7槽位：§f" + settings.slots());
        lore.add("§7已用：§f" + backpack.usedSlots() + " §7/ §f" + settings.slots());
        lore.add("§7禁带：§f" + deniedText(settings));
        lore.add("");
        lore.add(active ? "§a▶ 当前使用中" : "§e点击切换并打开");
        meta.setLore(lore);

        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
        if (active) {
            meta.addEnchant(Enchantment.DURABILITY, 1, true);
        }
        item.setItemMeta(meta);
        return item;
    }

    private static String deniedText(BackpackSettings settings) {
        if (settings.denied().isEmpty()) {
            return "无";
        }
        StringBuilder builder = new StringBuilder();
        int index = 0;
        for (Material material : settings.denied()) {
            if (index >= 4) {
                builder.append(" 等 ").append(settings.denied().size()).append(" 项");
                break;
            }
            if (index > 0) {
                builder.append("、");
            }
            builder.append(material.name().toLowerCase());
            index++;
        }
        return builder.toString();
    }

    private static ItemStack filler() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§7");
            item.setItemMeta(meta);
        }
        return item;
    }
}
