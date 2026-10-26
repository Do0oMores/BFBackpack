package top.mores.bFBackpack.api;

import top.mores.bFBackpack.backpack.BackpackType;
import java.util.Map;
import java.util.UUID;

/** One confirmed current plan per UUID and soldier class; no ItemStack or preset number. */
public record ClassLoadout(UUID playerId, BackpackType soldierClass,
                           Map<LoadoutSlot, String> equipment, long revision) {
    public ClassLoadout { equipment = Map.copyOf(equipment); }
}
