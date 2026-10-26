package top.mores.bFBackpack.storage;

import org.bukkit.inventory.ItemStack;

/**
 * 一行背包记录：内容 + 乐观锁版本号。
 */
public record StoredBackpack(ItemStack[] contents, long revision) {
}
