package top.mores.bFBackpack.service;

import top.mores.bFBackpack.api.*;
import top.mores.bFBackpack.catalog.CatalogSnapshot;
import top.mores.bFBackpack.storage.WeaponCustomizationRepository;
import java.sql.SQLException;
import java.util.*;

/** Isolated customization operations. Persistence finishes before callers publish confirmed state. */
public final class WeaponCustomizationService {
    private final WeaponCustomizationRepository repository;
    private final AttachmentValidator validator;
    public WeaponCustomizationService(WeaponCustomizationRepository repository, AttachmentValidator validator) {
        this.repository = repository; this.validator = validator;
    }
    public WeaponCustomization get(UUID player, String weapon, Map<String, WeaponCustomization> plans) {
        return plans.getOrDefault(weapon, new WeaponCustomization(player, weapon, Map.of(), "", 0));
    }
    public ResultCode validate(CatalogSnapshot catalog, Set<String> permanent, PermissionSnapshot permissions,
                               WeaponDefinition weapon, WeaponCustomization plan) {
        return validator.validate(catalog, permanent, permissions, weapon, plan);
    }
    public WeaponCustomization save(WeaponCustomization plan) throws SQLException { return repository.saveCustomization(plan); }
}
