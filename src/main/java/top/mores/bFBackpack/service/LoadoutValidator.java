package top.mores.bFBackpack.service;

import top.mores.bFBackpack.api.*;
import top.mores.bFBackpack.backpack.BackpackType;
import top.mores.bFBackpack.catalog.CatalogSnapshot;
import java.util.*;

/** Final server-side equipment validation and explicit safe default resolution. */
public final class LoadoutValidator {
    private final UnlockService unlocks;
    public LoadoutValidator(UnlockService unlocks) { this.unlocks = unlocks; }
    public ResultCode validate(CatalogSnapshot catalog, Set<String> permanent, PermissionSnapshot permissions,
                               BackpackType type, LoadoutSlot slot, String id) {
        WeaponDefinition weapon = catalog.weapons().get(id);
        if (weapon == null || !weapon.enabled()) return ResultCode.NOT_FOUND;
        if (!catalog.rules().allows(type, slot, weapon)) return ResultCode.CLASS_DENIED;
        if (!permissions.has(weapon.requiredPermission())) return ResultCode.PERMISSION_DENIED;
        return unlocks.resolve(catalog, permanent, permissions, id).unlocked() ? ResultCode.OK : ResultCode.LOCKED;
    }
    public LoadoutSnapshot snapshot(CatalogSnapshot catalog, Set<String> permanent, PermissionSnapshot permissions, ClassLoadout saved) {
        Map<LoadoutSlot, String> effective = new EnumMap<>(LoadoutSlot.class);
        List<ValidationIssue> issues = new ArrayList<>();
        for (LoadoutSlot slot : LoadoutSlot.values()) {
            String id = saved.equipment().get(slot);
            String fallback = catalog.rules().defaultItem(saved.soldierClass(), slot);
            ResultCode result = id == null ? ResultCode.NOT_FOUND : validate(catalog, permanent, permissions, saved.soldierClass(), slot, id);
            if (result == ResultCode.OK) { effective.put(slot, id); continue; }
            if (fallback != null && validate(catalog, permanent, permissions, saved.soldierClass(), slot, fallback) == ResultCode.OK) effective.put(slot, fallback);
            else fallback = null;
            issues.add(new ValidationIssue(slot, id, result, "Saved equipment invalid; " + (fallback == null ? "no valid configured default" : "using configured default"), fallback));
        }
        return new LoadoutSnapshot(saved, effective, issues, catalog.revision());
    }
}
