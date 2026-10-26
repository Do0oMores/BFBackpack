import top.mores.bFBackpack.api.*;
import top.mores.bFBackpack.backpack.BackpackType;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Compiles against the API jar alone. Inject the ready service from a Bukkit-side bridge. */
public final class BattlefieldApiExample {
    private final BFBackpackAPI api;
    public BattlefieldApiExample(BFBackpackAPI api) {
        var checked = new BFBackpackAPIProvider(api).requireVersion(2);
        if (!checked.successful()) throw new IllegalArgumentException(checked.message());
        this.api = checked.value();
    }
    public CompletableFuture<LoadoutResult<List<WeaponCatalogEntry>>> assaultWeapons(UUID player) {
        return api.getCatalog(player, BackpackType.ASSAULT, LoadoutSlot.PRIMARY, Set.of());
    }
    public CompletableFuture<LoadoutResult<UnlockResult>> unlocked(UUID player, String itemId) {
        return api.isUnlocked(player, itemId);
    }
    public CompletableFuture<List<LoadoutResult<ClassLoadout>>> allClasses(UUID player) {
        var futures = Arrays.stream(BackpackType.values()).map(type -> api.getLoadout(player, type)).toList();
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> futures.stream().map(CompletableFuture::join).toList()); // already completed
    }
    public CompletableFuture<LoadoutResult<ClassLoadout>> selectPrimary(UUID player, BackpackType type, String id) {
        return api.getLoadout(player, type).thenCompose(read -> read.successful()
                ? api.selectEquipment(player, type, LoadoutSlot.PRIMARY, id, read.value().revision())
                : CompletableFuture.completedFuture(read));
    }
    public CompletableFuture<LoadoutResult<List<AttachmentCatalogEntry>>> attachments(UUID player, String weaponId) {
        return api.getAttachmentCatalog(player, weaponId);
    }
    public CompletableFuture<LoadoutResult<WeaponCustomization>> selectScope(UUID player, String weaponId, String attachmentId) {
        return api.getWeaponCustomization(player, weaponId).thenCompose(read -> read.successful()
                ? api.selectAttachment(player, weaponId, "SCOPE", attachmentId, read.value().revision())
                : CompletableFuture.completedFuture(read));
    }
    public CompletableFuture<LoadoutResult<DeploymentLoadout>> deployment(UUID player, BackpackType type) {
        return api.getDeploymentLoadout(player, type);
        // Require result.successful() AND value.deployable() at the final adapter boundary.
        // Marshal to Forge's server thread and validate current resources before constructing items.
    }
}
