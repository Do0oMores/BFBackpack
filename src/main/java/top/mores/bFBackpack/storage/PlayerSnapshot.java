package top.mores.bFBackpack.storage;

import top.mores.bFBackpack.backpack.BackpackType;

import java.util.Map;

/**
 * 一次装载的结果：当前兵种 + 已存在的背包行（没有行代表该背包还是空的）。
 */
public record PlayerSnapshot(BackpackType activeType, Map<BackpackType, StoredBackpack> backpacks) {
}
