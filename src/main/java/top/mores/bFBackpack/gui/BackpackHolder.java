package top.mores.bFBackpack.gui;

import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import top.mores.bFBackpack.backpack.BackpackType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 界面身份标识。Bukkit 的事件只给到 Inventory，靠 holder 才能判断：
 * 这是谁的界面、是哪个兵种的背包、是选择页还是内容页。
 * <p>
 * 所有点击都校验 holder.owner，避免通过背包共享类插件或客户端异常打开别人的背包。
 */
public class BackpackHolder implements InventoryHolder {

    private final UUID owner;
    private final BackpackType type;
    private final Map<Integer, BackpackType> boundSlots = new HashMap<>();
    private Inventory inventory;

    /** @param type null 表示兵种选择页 */
    public BackpackHolder(UUID owner, BackpackType type) {
        this.owner = owner;
        this.type = type;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    public UUID owner() {
        return owner;
    }

    public BackpackType type() {
        return type;
    }

    public boolean isSelector() {
        return type == null;
    }

    public boolean belongsTo(Player player) {
        return owner.equals(player.getUniqueId());
    }

    /** 选择页槽位与兵种的映射，避免依赖物品上的隐藏数据。 */
    public void bind(int slot, BackpackType type) {
        boundSlots.put(slot, type);
    }

    public BackpackType typeAt(int slot) {
        return boundSlots.get(slot);
    }
}
