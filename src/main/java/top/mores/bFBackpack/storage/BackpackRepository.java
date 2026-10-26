package top.mores.bFBackpack.storage;

import org.bukkit.inventory.ItemStack;
import top.mores.bFBackpack.backpack.BackpackType;

import java.sql.SQLException;
import java.util.UUID;

/**
 * 背包持久化接口。实现类只做 SQL，不关心线程调度：
 * 所有调用都由 {@link BackpackService} 投递到单线程执行器上串行执行。
 */
public interface BackpackRepository {

    PlayerSnapshot load(UUID uuid, String playerName) throws SQLException;

    /**
     * 写入一个背包。
     *
     * @param expectedRevision 装载时读到的版本号；与库里不一致说明有别的实例同时写过
     * @return 写入后的最新版本号
     */
    long save(UUID uuid, BackpackType type, int slots, ItemStack[] contents, long expectedRevision) throws SQLException;

    void saveActiveType(UUID uuid, BackpackType type) throws SQLException;
}
