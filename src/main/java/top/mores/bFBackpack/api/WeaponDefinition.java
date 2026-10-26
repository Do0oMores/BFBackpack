package top.mores.bFBackpack.api;

import top.mores.bFBackpack.backpack.BackpackType;
import java.util.Set;

/** Catalog equipment, including guns and gadgets. id and gunId have independent namespaces. */
public record WeaponDefinition(String id, String provider, String gunId, String displayName,
        String category, Set<String> tags, Set<LoadoutSlot> slots, boolean defaultUnlocked,
        String unlockPermission, boolean attachmentsEnabled, boolean enabled,
        Set<BackpackType> allowedClasses, Set<BackpackType> deniedClasses, String requiredPermission) {
    public WeaponDefinition {
        tags = Set.copyOf(tags); slots = Set.copyOf(slots);
        allowedClasses = Set.copyOf(allowedClasses); deniedClasses = Set.copyOf(deniedClasses);
    }
}
