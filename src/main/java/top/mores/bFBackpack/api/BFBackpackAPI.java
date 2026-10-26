package top.mores.bFBackpack.api;

import top.mores.bFBackpack.backpack.BackpackType;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Server-authoritative data API v2, safe to invoke from any thread. Futures never imply a Bukkit
 * callback thread. Do not join futures on the server thread. Mutations return only after commit.
 * Callers are trusted server integrations; never expose grant/revoke or arbitrary UUIDs to clients.
 * Link against the api classifier with provided scope and depend on BFBackpack in plugin.yml.
 */
public interface BFBackpackAPI {
    int API_VERSION = 2;
    int PROTOCOL_VERSION = 1;
    /** ABI version; compare before calling any other methods. */
    default int apiVersion() { return API_VERSION; }
    /** Snapshot of enabled class-legal weapons with current unlock/permission status. */
    CompletableFuture<LoadoutResult<List<WeaponCatalogEntry>>> getCatalog(UUID playerId, BackpackType type);
    /** Filter the visible catalog by slot and registered TAGs; unknown TAGs return INVALID_INPUT. */
    CompletableFuture<LoadoutResult<List<WeaponCatalogEntry>>> getCatalog(UUID playerId, BackpackType type,
            LoadoutSlot slot, Set<String> requiredTags);
    /** Attachments allowed by catalog rules for this unlocked weapon, including their unlock status. */
    CompletableFuture<LoadoutResult<List<AttachmentCatalogEntry>>> getAttachmentCatalog(UUID playerId, String weaponId);
    /** Confirmed saved loadout; ready players only. */
    CompletableFuture<LoadoutResult<ClassLoadout>> getLoadout(UUID playerId, BackpackType type);
    /** Validate against the current catalog, rules, unlocks and permissions without deleting saved data. */
    CompletableFuture<LoadoutResult<LoadoutSnapshot>> validateLoadout(UUID playerId, BackpackType type);
    /** Select an equipment ID using the exact revision returned by getLoadout. */
    CompletableFuture<LoadoutResult<ClassLoadout>> selectEquipment(UUID playerId, BackpackType type,
            LoadoutSlot slot, String equipmentId, long expectedRevision);
    /** Restore the explicitly configured default of a slot, subject to the same validation. */
    CompletableFuture<LoadoutResult<ClassLoadout>> resetSlot(UUID playerId, BackpackType type,
            LoadoutSlot slot, long expectedRevision);
    /** Get a weapon's confirmed plan; an unmodified weapon has an empty revision-zero plan. */
    CompletableFuture<LoadoutResult<WeaponCustomization>> getWeaponCustomization(UUID playerId, String weaponId);
    /** Set one catalog attachment. The string "remove" is a command convention, not an API ID. */
    CompletableFuture<LoadoutResult<WeaponCustomization>> selectAttachment(UUID playerId, String weaponId,
            String attachmentType, String attachmentId, long expectedRevision);
    /** Remove one attachment with optimistic concurrency. */
    CompletableFuture<LoadoutResult<WeaponCustomization>> removeAttachment(UUID playerId, String weaponId,
            String attachmentType, long expectedRevision);
    /** Replace the complete plan, validating every attachment. Nonempty skins require a later skin catalog. */
    CompletableFuture<LoadoutResult<WeaponCustomization>> saveWeaponCustomization(WeaponCustomization plan);
    /** Resolve default/permanent/temporary permission unlock; does not check class rules. */
    CompletableFuture<LoadoutResult<UnlockResult>> isUnlocked(UUID playerId, String itemId);
    /** Grant permanent access, including for an offline UUID. Trusted administrative operation. */
    CompletableFuture<LoadoutResult<UnlockResult>> grantUnlock(UUID playerId, String itemId);
    /** Remove only permanent access; default or permission access may remain. */
    CompletableFuture<LoadoutResult<UnlockResult>> revokeUnlock(UUID playerId, String itemId);
    /** Persistent item IDs, excluding permission/default grants; supports offline UUIDs. */
    CompletableFuture<LoadoutResult<Set<String>>> getPlayerUnlocks(UUID playerId);
    /** Get a fresh deployment description. PROVIDER_UNAVAILABLE may include an inspectable value. */
    CompletableFuture<LoadoutResult<DeploymentLoadout>> getDeploymentLoadout(UUID playerId, BackpackType type);
    /** Read the independently persisted preferred class. */
    CompletableFuture<LoadoutResult<BackpackType>> getPreferredClass(UUID playerId);
    /** Persist the preferred class without changing any class equipment. */
    CompletableFuture<LoadoutResult<BackpackType>> setPreferredClass(UUID playerId, BackpackType type);
    /**
     * Trusted server adapter hook, no implementation dependency required. Both validators must be
     * installed together. They run on the ordered worker; return UNVERIFIED for missing resources.
     * A bridge that needs Forge APIs must marshal to Forge's server thread without blocking Bukkit.
     */
    void installProviderValidators(WeaponProviderValidator weapons, AttachmentCompatibilityValidator attachments);
}
