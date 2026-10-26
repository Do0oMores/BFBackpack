package top.mores.bFBackpack.service;

import top.mores.bFBackpack.api.*;
import top.mores.bFBackpack.catalog.CatalogSnapshot;
import top.mores.bFBackpack.storage.UnlockRepository;
import java.sql.SQLException;
import java.util.*;

/** Default + persistent + temporary permission access; deliberately independent of class rules. */
public final class UnlockService {
    private final UnlockRepository repository;
    public UnlockService(UnlockRepository repository) { this.repository = repository; }
    public UnlockResult resolve(CatalogSnapshot catalog, Set<String> permanent, PermissionSnapshot permissions, String id) {
        WeaponDefinition w = catalog.weapons().get(id);
        AttachmentDefinition a = catalog.attachments().get(id);
        boolean defaultUnlocked = w != null ? w.defaultUnlocked() : a != null && a.defaultUnlocked();
        String node = w != null ? w.unlockPermission() : a != null ? a.unlockPermission() : "";
        boolean granted = !node.isEmpty() && permissions.has(node);
        boolean stored = permanent.contains(id);
        return new UnlockResult(id, defaultUnlocked || stored || granted, stored,
                stored ? "DATABASE" : defaultUnlocked ? "DEFAULT" : granted ? "PERMISSION" : "LOCKED");
    }
    public Set<String> read(UUID player) throws SQLException { return repository.getPlayerUnlocks(player); }
    public void grant(UUID player, String id) throws SQLException { repository.grantUnlock(player, id, "ADMIN_API"); }
    public void revoke(UUID player, String id) throws SQLException { repository.revokeUnlock(player, id); }
    public boolean exists(CatalogSnapshot catalog, String id) {
        WeaponDefinition w = catalog.weapons().get(id); AttachmentDefinition a = catalog.attachments().get(id);
        return (w != null && w.enabled()) || (a != null && a.enabled());
    }
}
