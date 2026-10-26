package top.mores.bFBackpack.service;

import top.mores.bFBackpack.api.*;
import top.mores.bFBackpack.backpack.BackpackType;
import top.mores.bFBackpack.catalog.*;
import top.mores.bFBackpack.storage.*;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.*;

/**
 * API orchestration. Database and confirmed-state updates share one ordered worker. Permissions are
 * captured on the Bukkit main thread asynchronously. Runtime provider verification runs on a separate
 * bounded {@link ValidationEngine}, so the database worker never executes or waits for Forge code.
 *
 * <h2>Deployment pipeline</h2>
 * <ol>
 *   <li>Bukkit main thread: capture permissions and stamp the join generation.</li>
 *   <li>Database worker: validate catalog/rules/unlocks and freeze an immutable {@link DeploymentPlan}.</li>
 *   <li>Validation pool: verify provider resources with a hard timeout.</li>
 * </ol>
 * The returned future completes on the validation or timeout thread, never on the database worker, and
 * the database worker never waits for the main thread.
 *
 * Shutdown rejects new calls, drops runtime validators, fails pending validations, drains accepted
 * database calls, then the owner closes Database. No dirty state and no periodic save are needed in this
 * write-through model.
 */
public final class LoadoutService implements BFBackpackAPI {
    /** Provider ID reserved for the deprecated unowned registration path. */
    public static final String LEGACY_PROVIDER_ID = "legacy-sync";

    private final WeaponCatalog catalog;
    private final LoadoutRepository repository;
    private final UnlockService unlocks;
    private final WeaponCustomizationService customizations;
    private final LoadoutValidator validator;
    private final ExecutorService worker;
    private final PermissionGateway permissions;
    private final ValidationEngine validation;
    private final long permissionSnapshotMaxAgeMs;
    private final Logger logger;
    private final Map<UUID, LoadoutSession> sessions = new ConcurrentHashMap<>();
    private final AtomicLong generations = new AtomicLong();
    private volatile boolean accepting = true;

    public LoadoutService(WeaponCatalog catalog, LoadoutRepository repository, UnlockRepository unlockRepository,
            WeaponCustomizationRepository customizationRepository, ExecutorService worker,
            PermissionGateway permissions, Logger logger, ValidationEngine validation, long permissionSnapshotMaxAgeMs) {
        this.catalog = catalog; this.repository = repository; this.worker = worker;
        this.permissions = permissions; this.logger = logger; this.validation = validation;
        this.permissionSnapshotMaxAgeMs = Math.max(0, permissionSnapshotMaxAgeMs);
        this.unlocks = new UnlockService(unlockRepository);
        this.customizations = new WeaponCustomizationService(customizationRepository, new AttachmentValidator(unlocks));
        this.validator = new LoadoutValidator(unlocks);
    }

    /** Runtime validation engine; owns provider registrations and the bounded validation pool. */
    public ValidationEngine validation() { return validation; }

    /** Install or replace the runtime provider; see {@link #registerValidators(String, ValidationProvider)}. */
    @Override public ValidationRegistration registerValidators(String providerId, ValidationProvider provider) {
        if (!accepting) throw new IllegalStateException("Backend shutting down");
        return validation.register(providerId, provider);
    }

    /**
     * Deprecated unowned registration: no provider ID, no handle, replaced by the next registration.
     * New bridges must use {@link #registerValidators(String, ValidationProvider)}.
     */
    @Override @Deprecated(since = "3", forRemoval = false)
    public void installProviderValidators(WeaponProviderValidator weapons, AttachmentCompatibilityValidator attachments) {
        if (!accepting) throw new IllegalStateException("Backend shutting down");
        validation.register(LEGACY_PROVIDER_ID, new SyncValidationProvider(weapons, attachments));
    }

    /** Called by the join listener; a fresh generation prevents late completions after quit/rejoin. */
    public CompletableFuture<LoadoutResult<Void>> loadPlayer(UUID player) {
        if (!accepting || player == null) return completed(ResultCode.NOT_READY, "Backend not accepting loads");
        LoadoutSession session = new LoadoutSession(generations.incrementAndGet()); sessions.put(player, session);
        return enqueue(() -> {
            if (sessions.get(player) != session) return LoadoutResult.fail(ResultCode.NOT_READY, "Player left during load");
            LoadoutPlayerData loaded = repository.loadPlayer(player, catalog.snapshot());
            if (sessions.get(player) != session) return LoadoutResult.fail(ResultCode.NOT_READY, "Player left during load");
            session.confirmed = loaded;
            return LoadoutResult.ok(null);
        });
    }

    /** Called on quit; accepted writes may finish but cannot restore this session to the cache. */
    public void unloadPlayer(UUID player) { sessions.remove(player); }

    /** Stop accepting calls and drop runtime validators before draining Database.executor(). */
    public void shutdown() {
        accepting = false;
        validation.close();
        try { worker.execute(sessions::clear); }
        catch (RejectedExecutionException e) { sessions.clear(); }
    }

    /** Atomic catalog refresh, ordered with saves and reads. Does not rewrite any player records. */
    public CompletableFuture<LoadoutResult<Long>> reloadCatalog(Path path) {
        return enqueue(() -> {
            try { return LoadoutResult.ok(catalog.reload(path).revision()); }
            catch (IOException e) { logger.log(Level.WARNING, "Catalog reload rejected; retaining previous snapshot", e); return LoadoutResult.fail(ResultCode.CONFIGURATION_ERROR, "目录配置无效，已保留旧目录；详见日志"); }
        });
    }

    public CatalogSnapshot catalogSnapshot() { return catalog.snapshot(); }

    @Override public CompletableFuture<LoadoutResult<List<WeaponCatalogEntry>>> getCatalog(UUID player, BackpackType type) {
        return getCatalog(player, type, null, Set.of());
    }

    @Override public CompletableFuture<LoadoutResult<List<WeaponCatalogEntry>>> getCatalog(UUID player, BackpackType type,
            LoadoutSlot slot, Set<String> requiredTags) {
        if (requiredTags == null || requiredTags.stream().anyMatch(Objects::isNull)) return completed(ResultCode.INVALID_INPUT, "Non-null TAG set is required");
        Set<String> tags = Set.copyOf(requiredTags);
        return online(player, (s, p, c) -> {
            if (type == null) return invalid();
            return LoadoutResult.ok(c.filter(type, slot, tags).stream().map(w -> new WeaponCatalogEntry(w,
                    unlocks.resolve(c, s.confirmed.unlocks(), p, w.id()),
                    !p.has(w.requiredPermission()) ? ResultCode.PERMISSION_DENIED
                            : unlocks.resolve(c, s.confirmed.unlocks(), p, w.id()).unlocked() ? ResultCode.OK : ResultCode.LOCKED)).toList());
        });
    }

    @Override public CompletableFuture<LoadoutResult<List<AttachmentCatalogEntry>>> getAttachmentCatalog(UUID player, String rawWeapon) {
        return online(player, (s, p, c) -> {
            String id = Ids.normalize(rawWeapon); ResultCode access = weaponAccess(s, p, c, id);
            if (access != ResultCode.OK) return LoadoutResult.fail(access, "Weapon unavailable");
            WeaponDefinition w = c.weapons().get(id);
            if (!w.attachmentsEnabled()) return LoadoutResult.ok(List.of());
            return LoadoutResult.ok(c.attachments().values().stream().filter(AttachmentDefinition::enabled)
                    .filter(a -> a.allowedWeapons().contains(id) || !Collections.disjoint(a.allowedWeaponTags(), w.tags()))
                    .sorted(Comparator.comparing(AttachmentDefinition::id))
                    .map(a -> new AttachmentCatalogEntry(a, unlocks.resolve(c, s.confirmed.unlocks(), p, a.id()))).toList());
        });
    }

    @Override public CompletableFuture<LoadoutResult<ClassLoadout>> getLoadout(UUID player, BackpackType type) {
        return online(player, (s, p, c) -> type == null ? invalid() : LoadoutResult.ok(s.confirmed.loadouts().get(type)));
    }

    @Override public CompletableFuture<LoadoutResult<LoadoutSnapshot>> validateLoadout(UUID player, BackpackType type) {
        return online(player, (s, p, c) -> type == null ? invalid() : LoadoutResult.ok(validator.snapshot(c, s.confirmed.unlocks(), p, s.confirmed.loadouts().get(type))));
    }

    @Override public CompletableFuture<LoadoutResult<ClassLoadout>> selectEquipment(UUID player, BackpackType type, LoadoutSlot slot, String rawId, long expected) {
        return online(player, true, (s, p, c) -> select(s, p, c, type, slot, Ids.normalize(rawId), expected));
    }

    @Override public CompletableFuture<LoadoutResult<ClassLoadout>> resetSlot(UUID player, BackpackType type, LoadoutSlot slot, long expected) {
        return online(player, true, (s, p, c) -> {
            if (type == null || slot == null) return invalid();
            String id = c.rules().defaultItem(type, slot);
            return id == null ? LoadoutResult.fail(ResultCode.CONFIGURATION_ERROR, "Slot has no configured default") : select(s, p, c, type, slot, id, expected);
        });
    }

    private LoadoutResult<ClassLoadout> select(LoadoutSession s, PermissionSnapshot p, CatalogSnapshot c,
            BackpackType type, LoadoutSlot slot, String id, long expected) throws SQLException {
        if (type == null || slot == null) return invalid();
        ClassLoadout old = s.confirmed.loadouts().get(type);
        if (old.revision() != expected) return conflict();
        ResultCode code = validator.validate(c, s.confirmed.unlocks(), p, type, slot, id);
        if (code != ResultCode.OK) return LoadoutResult.fail(code, "Equipment selection rejected: " + id);
        Map<LoadoutSlot, String> equipment = new EnumMap<>(LoadoutSlot.class); equipment.putAll(old.equipment()); equipment.put(slot, id);
        ClassLoadout saved;
        try { saved = repository.saveLoadout(new ClassLoadout(old.playerId(), type, equipment, expected)); }
        catch (RevisionConflictException e) { refresh(s, old.playerId(), c); return conflict(); }
        Map<BackpackType, ClassLoadout> loadouts = new EnumMap<>(BackpackType.class); loadouts.putAll(s.confirmed.loadouts()); loadouts.put(type, saved);
        s.confirmed = new LoadoutPlayerData(s.confirmed.unlocks(), loadouts, s.confirmed.customizations(), s.confirmed.preferredClass());
        return LoadoutResult.ok(saved);
    }

    @Override public CompletableFuture<LoadoutResult<WeaponCustomization>> getWeaponCustomization(UUID player, String rawId) {
        return online(player, (s, p, c) -> {
            String id = Ids.normalize(rawId);
            ResultCode code = weaponAccess(s, p, c, id);
            return code == ResultCode.OK ? LoadoutResult.ok(customizations.get(player, id, s.confirmed.customizations())) : LoadoutResult.fail(code, "Weapon unavailable");
        });
    }

    @Override public CompletableFuture<LoadoutResult<WeaponCustomization>> selectAttachment(UUID player, String rawWeapon, String rawType, String rawId, long expected) {
        return attachment(player, rawWeapon, rawType, rawId, expected, false);
    }

    @Override public CompletableFuture<LoadoutResult<WeaponCustomization>> removeAttachment(UUID player, String rawWeapon, String rawType, long expected) {
        return attachment(player, rawWeapon, rawType, null, expected, true);
    }

    private CompletableFuture<LoadoutResult<WeaponCustomization>> attachment(UUID player, String rawWeapon, String rawType, String rawId, long expected, boolean remove) {
        return online(player, true, (s, p, c) -> {
            String weapon = Ids.normalize(rawWeapon), type = Ids.attachmentType(rawType);
            ResultCode access = weaponAccess(s, p, c, weapon);
            if (access != ResultCode.OK) return LoadoutResult.fail(access, "Weapon unavailable");
            if (!c.attachmentTypes().contains(type)) return LoadoutResult.fail(ResultCode.ATTACHMENT_INVALID, "Unknown attachment slot");
            WeaponCustomization old = customizations.get(player, weapon, s.confirmed.customizations());
            if (old.revision() != expected) return conflict();
            Map<String, String> map = new HashMap<>(old.attachments());
            if (remove) map.remove(type); else map.put(type, Ids.normalize(rawId));
            return savePlan(s, p, c, new WeaponCustomization(player, weapon, map, old.skinId(), expected));
        });
    }

    @Override public CompletableFuture<LoadoutResult<WeaponCustomization>> saveWeaponCustomization(WeaponCustomization plan) {
        if (plan == null) return completed(ResultCode.INVALID_INPUT, "Plan is required");
        return online(plan.playerId(), true, (s, p, c) -> {
            Map<String, String> map = new HashMap<>();
            for (var entry : plan.attachments().entrySet()) {
                if (map.put(Ids.attachmentType(entry.getKey()), Ids.normalize(entry.getValue())) != null) return invalid();
            }
            if (plan.skinId() == null) return invalid();
            return savePlan(s, p, c, new WeaponCustomization(plan.playerId(), Ids.normalize(plan.weaponId()), map, plan.skinId(), plan.revision()));
        });
    }

    private LoadoutResult<WeaponCustomization> savePlan(LoadoutSession s, PermissionSnapshot p, CatalogSnapshot c, WeaponCustomization plan) throws SQLException {
        ResultCode access = weaponAccess(s, p, c, plan.weaponId());
        if (access != ResultCode.OK) return LoadoutResult.fail(access, "Weapon unavailable");
        if (customizations.get(plan.playerId(), plan.weaponId(), s.confirmed.customizations()).revision() != plan.revision()) return conflict();
        ResultCode code = customizations.validate(c, s.confirmed.unlocks(), p, c.weapons().get(plan.weaponId()), plan);
        if (code != ResultCode.OK) return LoadoutResult.fail(code, "Attachment selection rejected");
        WeaponCustomization saved;
        try { saved = customizations.save(plan); }
        catch (RevisionConflictException e) { refresh(s, plan.playerId(), c); return conflict(); }
        Map<String, WeaponCustomization> plans = new HashMap<>(s.confirmed.customizations()); plans.put(plan.weaponId(), saved);
        s.confirmed = new LoadoutPlayerData(s.confirmed.unlocks(), s.confirmed.loadouts(), plans, s.confirmed.preferredClass());
        return LoadoutResult.ok(saved);
    }

    @Override public CompletableFuture<LoadoutResult<UnlockResult>> isUnlocked(UUID player, String rawId) {
        return online(player, (s, p, c) -> {
            String id = Ids.normalize(rawId);
            return unlocks.exists(c, id) ? LoadoutResult.ok(unlocks.resolve(c, s.confirmed.unlocks(), p, id)) : LoadoutResult.fail(ResultCode.NOT_FOUND, "Unknown item");
        });
    }

    @Override public CompletableFuture<LoadoutResult<UnlockResult>> grantUnlock(UUID player, String rawId) { return changeUnlock(player, rawId, true); }

    @Override public CompletableFuture<LoadoutResult<UnlockResult>> revokeUnlock(UUID player, String rawId) { return changeUnlock(player, rawId, false); }

    private CompletableFuture<LoadoutResult<UnlockResult>> changeUnlock(UUID player, String rawId, boolean grant) {
        if (player == null) return completed(ResultCode.INVALID_INPUT, "UUID is required");
        return withPermissions(player, sessions.get(player), p -> enqueue(() -> {
            String id = Ids.normalize(rawId); CatalogSnapshot c = catalog.snapshot();
            if (!unlocks.exists(c, id)) return LoadoutResult.fail(ResultCode.NOT_FOUND, "Unknown item");
            if (grant) unlocks.grant(player, id); else unlocks.revoke(player, id);
            LoadoutSession session = sessions.get(player);
            Set<String> stored;
            if (session != null && session.confirmed != null) {
                stored = new HashSet<>(session.confirmed.unlocks());
                if (grant) stored.add(id); else stored.remove(id);
                session.confirmed = new LoadoutPlayerData(stored, session.confirmed.loadouts(), session.confirmed.customizations(), session.confirmed.preferredClass());
            } else stored = unlocks.read(player);
            return LoadoutResult.ok(unlocks.resolve(c, stored, p, id));
        }));
    }

    @Override public CompletableFuture<LoadoutResult<Set<String>>> getPlayerUnlocks(UUID player) {
        if (player == null) return completed(ResultCode.INVALID_INPUT, "UUID is required");
        return enqueue(() -> LoadoutResult.ok(unlocks.read(player)));
    }

    @Override public CompletableFuture<LoadoutResult<BackpackType>> getPreferredClass(UUID player) {
        return online(player, (s, p, c) -> LoadoutResult.ok(s.confirmed.preferredClass()));
    }

    @Override public CompletableFuture<LoadoutResult<BackpackType>> setPreferredClass(UUID player, BackpackType type) {
        return online(player, true, (s, p, c) -> {
            if (type == null) return invalid();
            repository.setPreferredClass(player, type);
            s.confirmed = new LoadoutPlayerData(s.confirmed.unlocks(), s.confirmed.loadouts(), s.confirmed.customizations(), type);
            return LoadoutResult.ok(type);
        });
    }

    /**
     * Two-phase deployment. Phase one runs on the database worker and returns an immutable plan; phase two
     * verifies provider resources on the validation pool with a hard timeout. The database worker never
     * executes or waits for Forge/TaCZ code and the main thread never blocks on the database.
     */
    @Override public CompletableFuture<LoadoutResult<DeploymentLoadout>> getDeploymentLoadout(UUID player, BackpackType type) {
        if (!accepting || player == null) return completed(ResultCode.NOT_READY, "Player/backend not ready");
        if (type == null) return completed(ResultCode.INVALID_INPUT, "Class is required");
        LoadoutSession session = sessions.get(player);
        if (session == null || session.confirmed == null) return completed(ResultCode.NOT_READY, "Player has not loaded");
        return capture(player, session)
                .thenCompose(permissions -> enqueue(() -> planDeployment(session, player, type, permissions)))
                .thenCompose(planned -> planned.successful()
                        ? validation.validate(planned.value())
                        : CompletableFuture.completedFuture(new LoadoutResult<DeploymentLoadout>(planned.code(), null, planned.message())))
                .exceptionally(error -> {
                    logger.log(Level.WARNING, "Deployment description unavailable", error);
                    return LoadoutResult.fail(ResultCode.NOT_READY, "部署描述不可用，请查看服务端日志");
                });
    }

    /** Phase one: catalog/rules/unlock checks and an immutable plan. No provider call happens here. */
    private LoadoutResult<DeploymentPlan> planDeployment(LoadoutSession session, UUID player, BackpackType type, PermissionSnapshot permissions) {
        LoadoutPlayerData data = session.confirmed;
        if (data == null) return LoadoutResult.fail(ResultCode.NOT_READY, "Player session changed");
        ClassLoadout saved = data.loadouts().get(type);
        if (saved == null) return LoadoutResult.fail(ResultCode.LOADOUT_INVALID, "Class has no saved loadout");
        CatalogSnapshot c = catalog.snapshot();
        LoadoutSnapshot snapshot = validator.snapshot(c, data.unlocks(), permissions, saved);
        Map<LoadoutSlot, DeploymentPlan.Slot> slots = new EnumMap<>(LoadoutSlot.class);
        for (var entry : snapshot.effectiveEquipment().entrySet()) {
            WeaponDefinition weapon = c.weapons().get(entry.getValue());
            if (weapon == null) continue;
            WeaponCustomization customization = customizations.get(player, weapon.id(), data.customizations());
            ResultCode check = customizations.validate(c, data.unlocks(), permissions, weapon, customization);
            Map<String, AttachmentDefinition> definitions = new HashMap<>();
            customization.attachments().forEach((attachmentSlot, id) -> {
                AttachmentDefinition definition = c.attachments().get(id);
                if (definition != null) definitions.put(attachmentSlot, definition);
            });
            slots.put(entry.getKey(), new DeploymentPlan.Slot(entry.getKey(), weapon, customization, definitions,
                    check == ResultCode.OK ? null : check,
                    check == ResultCode.OK ? "" : "Saved customization invalid: " + check));
        }
        ResultCode precheck = permissions.staleAfter(permissionSnapshotMaxAgeMs) ? ResultCode.PERMISSION_STALE : null;
        return LoadoutResult.ok(new DeploymentPlan(player, type, session, saved, snapshot, slots, c.revision(),
                () -> catalog.snapshot().revision(), permissions, precheck,
                () -> sessions.get(player) == session, PROTOCOL_VERSION));
    }

    private ResultCode weaponAccess(LoadoutSession s, PermissionSnapshot p, CatalogSnapshot c, String id) {
        WeaponDefinition w = c.weapons().get(id);
        if (w == null || !w.enabled()) return ResultCode.NOT_FOUND;
        if (!p.has(w.requiredPermission())) return ResultCode.PERMISSION_DENIED;
        return unlocks.resolve(c, s.confirmed.unlocks(), p, id).unlocked() ? ResultCode.OK : ResultCode.LOCKED;
    }

    private void refresh(LoadoutSession session, UUID player, CatalogSnapshot c) throws SQLException {
        try { session.confirmed = repository.loadPlayer(player, c); }
        catch (SQLException e) { session.confirmed = null; throw e; }
    }

    @FunctionalInterface private interface OnlineOperation<T> { LoadoutResult<T> run(LoadoutSession s, PermissionSnapshot p, CatalogSnapshot c) throws SQLException; }
    @FunctionalInterface private interface Operation<T> { LoadoutResult<T> run() throws SQLException; }

    private <T> CompletableFuture<LoadoutResult<T>> online(UUID player, OnlineOperation<T> operation) {
        return online(player, false, operation);
    }

    /**
     * @param mutation true for state-changing calls: a permission snapshot that aged past
     *                 permissions.max-snapshot-age-ms is rejected instead of authorizing the write
     */
    private <T> CompletableFuture<LoadoutResult<T>> online(UUID player, boolean mutation, OnlineOperation<T> operation) {
        if (!accepting || player == null) return completed(ResultCode.NOT_READY, "Player/backend not ready");
        LoadoutSession expected = sessions.get(player);
        if (expected == null || expected.confirmed == null) return completed(ResultCode.NOT_READY, "Player has not loaded");
        return withPermissions(player, expected, p -> enqueue(() -> {
            if (!p.online() || sessions.get(player) != expected || expected.confirmed == null) return LoadoutResult.fail(ResultCode.NOT_READY, "Player session changed");
            if (mutation && p.staleAfter(permissionSnapshotMaxAgeMs)) return LoadoutResult.fail(ResultCode.PERMISSION_STALE, "权限快照已过期，请重试");
            return operation.run(expected, p, catalog.snapshot());
        }));
    }

    private <T> CompletableFuture<LoadoutResult<T>> withPermissions(UUID player, LoadoutSession session,
            java.util.function.Function<PermissionSnapshot, CompletableFuture<LoadoutResult<T>>> next) {
        if (!accepting) return completed(ResultCode.NOT_READY, "Backend shutting down");
        try {
            return capture(player, session).thenCompose(next)
                    .exceptionally(e -> { logger.log(Level.WARNING, "Permission snapshot unavailable", e); return LoadoutResult.fail(ResultCode.NOT_READY, "Permission snapshot unavailable"); });
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Permission snapshot scheduling failed", e);
            return completed(ResultCode.NOT_READY, "Permission scheduler unavailable");
        }
    }

    /** Async main-thread capture bound to the join generation it was requested for. */
    private CompletableFuture<PermissionSnapshot> capture(UUID player, LoadoutSession session) {
        long generation = session == null ? 0 : session.generation;
        return permissions.capture(player, catalog.snapshot().permissions())
                .thenApply(snapshot -> snapshot.withGeneration(generation));
    }

    private <T> CompletableFuture<LoadoutResult<T>> enqueue(Operation<T> operation) {
        if (!accepting) return completed(ResultCode.NOT_READY, "Backend shutting down");
        CompletableFuture<LoadoutResult<T>> future = new CompletableFuture<>();
        try {
            worker.execute(() -> {
                try { future.complete(operation.run()); }
                catch (RevisionConflictException e) { future.complete(LoadoutResult.fail(ResultCode.REVISION_CONFLICT, "Revision conflict; reread before retrying")); }
                catch (IllegalArgumentException e) { future.complete(LoadoutResult.fail(ResultCode.INVALID_INPUT, e.getMessage())); }
                catch (SQLException e) { logger.log(Level.SEVERE, "Loadout database operation failed", e); future.complete(LoadoutResult.fail(ResultCode.DATABASE_ERROR, "数据库操作失败，请查看服务端日志")); }
                catch (RuntimeException e) { logger.log(Level.SEVERE, "Loadout operation failed", e); future.complete(LoadoutResult.fail(ResultCode.PROVIDER_UNAVAILABLE, "Backend operation failed; see server log")); }
            });
        } catch (RejectedExecutionException e) { future.complete(LoadoutResult.fail(ResultCode.NOT_READY, "Database worker closed")); }
        return future;
    }

    private static <T> LoadoutResult<T> invalid() { return LoadoutResult.fail(ResultCode.INVALID_INPUT, "Invalid input"); }
    private static <T> LoadoutResult<T> conflict() { return LoadoutResult.fail(ResultCode.REVISION_CONFLICT, "Revision conflict; reread before retrying"); }
    private static <T> CompletableFuture<LoadoutResult<T>> completed(ResultCode code, String message) { return CompletableFuture.completedFuture(LoadoutResult.fail(code, message)); }
}
