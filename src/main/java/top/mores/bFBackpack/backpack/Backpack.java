package top.mores.bFBackpack.backpack;

import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 单个背包的运行时模型。
 * <p>
 * 线程约定：{@code contents} 只在主线程被修改，且必须持有所属 {@link PlayerSession} 的锁；
 * 异步落盘只使用 {@link #copyContents()} 产生的独立副本，不会和主线程抢同一个数组。
 */
public class Backpack {

    private final UUID owner;
    private final BackpackType type;

    private ItemStack[] contents;
    private long revision;
    /** 每次内容变更自增，用于判断"保存完成时数据是否又被改过"。 */
    private int version;
    private volatile boolean dirty;

    public Backpack(UUID owner, BackpackType type, int slots) {
        this.owner = owner;
        this.type = type;
        this.contents = new ItemStack[slots];
    }

    public UUID owner() {
        return owner;
    }

    public BackpackType type() {
        return type;
    }

    public long revision() {
        return revision;
    }

    public int version() {
        return version;
    }

    public boolean dirty() {
        return dirty;
    }

    public int size() {
        return contents.length;
    }

    /** 深拷贝，供异步线程序列化或界面渲染使用。 */
    public ItemStack[] copyContents() {
        ItemStack[] copy = new ItemStack[contents.length];
        for (int i = 0; i < contents.length; i++) {
            copy[i] = contents[i] == null ? null : contents[i].clone();
        }
        return copy;
    }

    /** 由数据库装载，装载不算玩家改动，不置脏。 */
    public void load(ItemStack[] loaded, int slots, long revision) {
        ItemStack[] resized = new ItemStack[slots];
        int limit = Math.min(slots, loaded == null ? 0 : loaded.length);
        for (int i = 0; i < limit; i++) {
            resized[i] = loaded[i] == null ? null : loaded[i].clone();
        }
        this.contents = resized;
        this.revision = revision;
        this.version = 0;
        this.dirty = false;
    }

    public void setContents(ItemStack[] newContents) {
        ItemStack[] copy = new ItemStack[newContents.length];
        for (int i = 0; i < newContents.length; i++) {
            copy[i] = newContents[i] == null ? null : newContents[i].clone();
        }
        this.contents = copy;
        this.version++;
        this.dirty = true;
    }

    /**
     * 配置热重载后槽位可能变化，这里按新容量重建数组，返回放不下的物品由调用方退还给玩家。
     */
    public List<ItemStack> resize(int slots) {
        if (slots == contents.length) {
            return List.of();
        }
        ItemStack[] resized = new ItemStack[slots];
        List<ItemStack> overflow = new ArrayList<>();
        for (int i = 0; i < contents.length; i++) {
            if (contents[i] == null) {
                continue;
            }
            if (i < slots) {
                resized[i] = contents[i];
            } else {
                overflow.add(contents[i]);
            }
        }
        this.contents = resized;
        if (!overflow.isEmpty()) {
            this.version++;
            this.dirty = true;
        }
        return overflow;
    }

    /** @param savedVersion 落盘时快照的 version，只有内容没再变过才清除脏标记 */
    public void markSaved(long revision, int savedVersion) {
        this.revision = revision;
        if (this.version == savedVersion) {
            this.dirty = false;
        }
    }

    public int usedSlots() {
        int used = 0;
        for (ItemStack item : contents) {
            if (item != null && !item.getType().isAir()) {
                used++;
            }
        }
        return used;
    }
}
