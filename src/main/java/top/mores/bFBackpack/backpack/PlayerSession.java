package top.mores.bFBackpack.backpack;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 一名玩家的会话：四个背包 + 当前使用的兵种 + 一把独占锁。
 * <p>
 * 锁的粒度是"玩家"而不是"背包"，因为切换兵种、退出保存这类操作要同时读写多个背包，
 * 会话级锁能保证这些跨背包操作是一个整体。锁可重入，界面回调里再次进入不会自锁。
 */
public class PlayerSession {

    private final UUID uuid;
    private final String name;
    private final EnumMap<BackpackType, Backpack> backpacks;
    private final ReentrantLock lock = new ReentrantLock();
    private volatile BackpackType active;

    public PlayerSession(UUID uuid, String name, BackpackType active, Map<BackpackType, Backpack> backpacks) {
        this.uuid = uuid;
        this.name = name;
        this.active = active;
        this.backpacks = new EnumMap<>(BackpackType.class);
        this.backpacks.putAll(backpacks);
    }

    public UUID uuid() {
        return uuid;
    }

    public String name() {
        return name;
    }

    public ReentrantLock lock() {
        return lock;
    }

    public Backpack backpack(BackpackType type) {
        return backpacks.get(type);
    }

    public Collection<Backpack> backpacks() {
        return Collections.unmodifiableCollection(backpacks.values());
    }

    public BackpackType active() {
        return active;
    }

    public void setActive(BackpackType type) {
        this.active = type;
    }
}
