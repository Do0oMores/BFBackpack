package top.mores.bFBackpack.api;

import top.mores.bFBackpack.backpack.BackpackType;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Server-authoritative data API v3, safe to invoke from any thread. Futures never imply a Bukkit
 * callback thread. Do not join futures on the server thread. Mutations return only after commit.
 * Callers are trusted server integrations; never expose grant/revoke or arbitrary UUIDs to clients.
 * Link against the api classifier with provided scope and depend on BFBackpack in plugin.yml.
 *
 * <h2>Thread boundaries</h2>
 * Database work runs on one ordered worker thread. Bukkit permissions are only read on the main
 * thread through an asynchronous gateway. Runtime provider validation runs on a bounded, time-limited
 * validation pool, so the database worker never executes and never waits for Forge/TaCZ code.
 * Deployment futures therefore complete on a validation or timeout thread, not on the database worker.
 */
public interface BFBackpackAPI {
    int API_VERSION = 3;
    int PROTOCOL_VERSION = 2;
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
    /**
     * Two-phase deployment description. Phase one reads the database worker and returns an immutable
     * plan; phase two verifies provider resources on the validation pool with a hard timeout. The future
     * completes on a validation/timeout thread. A failure still carries a safe read-only description;
     * never deploy unless {@code successful() && value.deployable()}.
     */
    CompletableFuture<LoadoutResult<DeploymentLoadout>> getDeploymentLoadout(UUID playerId, BackpackType type);
    /** Read the independently persisted preferred class. */
    CompletableFuture<LoadoutResult<BackpackType>> getPreferredClass(UUID playerId);
    /** Persist the preferred class without changing any class equipment. */
    CompletableFuture<LoadoutResult<BackpackType>> setPreferredClass(UUID playerId, BackpackType type);
    /**
     * Install or replace the runtime validator. Registration is process-wide: a second call replaces the
     * previous provider and the previous handle becomes inactive. Closing the returned handle removes
     * only that registration.
     *
     * @param providerId stable lowercase ID of the bridge, {@code [a-z0-9_.-]{1,48}}
     * @param provider   adapter whose {@link ValidationProvider#apiVersion()} matches {@link #API_VERSION}
     * @throws IllegalArgumentException for an invalid ID or an incompatible provider ABI
     * @throws IllegalStateException    when the backend is closed
     */
    ValidationRegistration registerValidators(String providerId, ValidationProvider provider);
    /**
     * Install two synchronous validators without a provider ID.
     *
     * @deprecated since API v3: use {@link #registerValidators(String, ValidationProvider)} so the
     * registration can be replaced and closed. The legacy registration is synchronous, has no owner and
     * cannot be unregistered; it is replaced by the next legacy or typed registration.
     */
    @Deprecated(since = "3", forRemoval = false)
    void installProviderValidators(WeaponProviderValidator weapons, AttachmentCompatibilityValidator attachments);
}
