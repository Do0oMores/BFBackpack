package top.mores.bFBackpack.storage;

import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 把 ItemStack[] 压成一段文本，方便存进 TEXT 列：
 * Bukkit 序列化 -> GZIP -> Base64。
 * <p>
 * 相比把 NBT 拼成 JSON，这种做法的好处是完整保留附魔、药水、自定义模型、容器内容等所有数据，
 * 升级服务端版本时也不需要维护字段映射。压缩之后 36 格背包通常只有几 KB。
 */
public final class ItemSerializer {

    private ItemSerializer() {
    }

    public static String encode(ItemStack[] contents) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(buffer);
             BukkitObjectOutputStream out = new BukkitObjectOutputStream(gzip)) {
            out.writeInt(contents.length);
            for (ItemStack item : contents) {
                out.writeObject(item);
            }
        }
        return Base64.getEncoder().encodeToString(buffer.toByteArray());
    }

    /**
     * @param slots 目标容量。库里存的格子数和当前配置不一致时以配置为准，超出的部分丢弃。
     */
    public static ItemStack[] decode(String data, int slots) throws IOException {
        ItemStack[] contents = new ItemStack[slots];
        if (data == null || data.isBlank()) {
            return contents;
        }
        try (BukkitObjectInputStream in = new BukkitObjectInputStream(
                new GZIPInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(data))))) {
            int length = in.readInt();
            for (int i = 0; i < length; i++) {
                Object element = in.readObject();
                if (i < slots && element instanceof ItemStack item) {
                    contents[i] = item;
                }
            }
        } catch (ClassNotFoundException e) {
            throw new IOException("背包数据反序列化失败：物品类型无法还原", e);
        }
        return contents;
    }
}
