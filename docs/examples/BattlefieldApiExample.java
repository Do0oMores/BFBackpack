import top.mores.bFBackpack.api.*;
import top.mores.bFBackpack.backpack.BackpackType;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Compiles against the API jar alone. Inject the ready service from a Bukkit-side bridge. */
public final class BattlefieldApiExample {
    private final BFBackpackAPI api;
    private ValidationRegistration registration;

    public BattlefieldApiExample(BFBackpackAPI api) {
        var checked = new BFBackpackAPIProvider(api).requireVersion(3);
        if (!checked.successful()) throw new IllegalArgumentException(checked.message());
        this.api = checked.value();
    }

    /**
     * Runtime validator that marshals to the Forge/TaCZ thread.
     * <p>BFBackpack never blocks its database worker on this call: the adapter owns the hop and completes
     * the future from the thread that can actually inspect the resources. Returning UNVERIFIED is always
     * safe; it only makes the description non-deployable.</p>
     */
    public static final class TaCzValidator implements ValidationProvider {
        private final Executor forgeThread;

        public TaCzValidator(Executor forgeThread) { this.forgeThread = Objects.requireNonNull(forgeThread); }

        @Override public CompletableFuture<CompatibilityInfo> validateWeapon(WeaponDefinition weapon) {
            return CompletableFuture.supplyAsync(() -> {
                if (!"TACZ".equals(weapon.provider()))
                    return CompatibilityInfo.unverified(ResultCode.RESOURCE_NOT_FOUND,
                            "Provider " + weapon.provider() + " is not managed by this bridge");
                boolean exists = false;   // TODO: look the gunId up in the loaded TaCZ gun index
                return exists
                        ? CompatibilityInfo.verified("resolved " + weapon.gunId())
                        : CompatibilityInfo.unverified(ResultCode.RESOURCE_NOT_FOUND, "Missing " + weapon.gunId());
            }, forgeThread);
        }

        @Override public CompletableFuture<CompatibilityInfo> validateAttachments(WeaponDefinition weapon, WeaponCustomization customization) {
            return CompletableFuture.supplyAsync(() -> {
                if (!customization.skinId().isEmpty())
                    return CompatibilityInfo.rejected(ResultCode.ATTACHMENT_INVALID, "Skin catalog is not implemented");
                // TODO: check each attachment slot against the real TaCZ attachment index.
                return CompatibilityInfo.unverified(ResultCode.RESOURCE_NOT_FOUND, "Attachment index not wired yet");
            }, forgeThread);
        }
    }

    /** Call from the bridge plugin's onEnable, after the service is discovered. */
    public ValidationRegistration installValidator(Executor forgeThread) {
        registration = api.registerValidators("battlefield-bridge", new TaCzValidator(forgeThread));
        return registration;
    }

    /** Call from the bridge plugin's onDisable; the registration is identity-bound and idempotent. */
    public void uninstallValidator() {
        if (registration != null) { registration.close(); registration = null; }
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

    /**
     * Two-phase deployment. The future completes on a validation or timeout thread; never join it on the
     * server thread. Require successful() AND deployable() AND binding().stillCurrent() at the adapter
     * boundary, then revalidate the current state before constructing any item.
     */
    public CompletableFuture<LoadoutResult<DeploymentLoadout>> deployment(UUID player, BackpackType type) {
        return api.getDeploymentLoadout(player, type).thenApply(result -> {
            if (!result.successful() || result.value() == null || !result.value().deployable()) {
                // result.code() distinguishes VALIDATION_TIMEOUT, VALIDATION_BUSY, RESOURCE_NOT_FOUND,
                // ATTACHMENT_INVALID, PERMISSION_STALE, LOADOUT_INVALID and PROVIDER_UNAVAILABLE.
                // result.value() may still carry a read-only description for the GUI.
                return result;
            }
            DeploymentBinding binding = result.value().binding();
            if (!binding.stillCurrent()) return result;   // the loadout or catalog moved; request again
            return result;
        });
    }
}
