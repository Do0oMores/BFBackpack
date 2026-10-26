package top.mores.bFBackpack.storage;

import top.mores.bFBackpack.api.*;
import top.mores.bFBackpack.backpack.BackpackType;
import java.util.*;

/** Confirmed state loaded in one transaction; no mutable collections escape. */
public record LoadoutPlayerData(Set<String> unlocks, Map<BackpackType, ClassLoadout> loadouts,
        Map<String, WeaponCustomization> customizations, BackpackType preferredClass) {
    public LoadoutPlayerData {
        unlocks = Set.copyOf(unlocks); loadouts = Map.copyOf(loadouts); customizations = Map.copyOf(customizations);
    }
}
