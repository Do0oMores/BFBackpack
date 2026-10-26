package top.mores.bFBackpack.backpack;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 某个兵种背包的配置快照（不可变），由 config.yml 解析而来。
 *
 * @param type        背包类型
 * @param display     显示名，例如"突击"
 * @param description 功能定位说明，展示在兵种选择界面的说明文字里
 * @param icon        界面图标
 * @param slots       容量，必须是 9 的倍数，范围 9~54
 * @param denied      禁带物品（可以放进去，但会被拦下来）
 */
public record BackpackSettings(BackpackType type, String display, String description,
                               Material icon, int slots, Set<Material> denied) {

    public BackpackSettings {
        slots = Math.max(9, Math.min(54, slots / 9 * 9));
        denied = Set.copyOf(denied);
    }

    public boolean allows(ItemStack item) {
        return item == null || item.getType().isAir() || !denied.contains(item.getType());
    }

    /**
     * 把界面里的原始内容整理成可落库的内容：截断超出容量的部分、挑出禁带物品。
     * 返回的 rejected 需要退还给玩家，避免"界面上看得到、关掉就没了"。
     */
    public Sanitized sanitize(ItemStack[] raw) {
        ItemStack[] contents = new ItemStack[slots];
        List<ItemStack> rejected = new ArrayList<>();
        if (raw != null) {
            for (int i = 0; i < raw.length; i++) {
                ItemStack item = raw[i];
                if (item == null || item.getType().isAir()) {
                    continue;
                }
                ItemStack copy = item.clone();
                if (i < slots && allows(copy)) {
                    contents[i] = copy;
                } else {
                    rejected.add(copy);
                }
            }
        }
        return new Sanitized(contents, rejected);
    }

    public record Sanitized(ItemStack[] contents, List<ItemStack> rejected) {
    }
}
