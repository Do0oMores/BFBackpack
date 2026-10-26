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
import java.util.logging.*;

/**
 * API orchestration. Database and confirmed-state updates share one ordered worker. Permissions
 * cross an explicit main-thread gateway. Shutdown rejects new calls, drains accepted calls, then
 * the owner closes Database. No dirty state and no periodic save are needed in this write-through model.
 */
public final class LoadoutService implements BFBackpackAPI {
    private final WeaponCatalog catalog;
    private final LoadoutRepository repository;
    private final UnlockService unlocks;
    private final WeaponCustomizationService customizations;
    private final LoadoutValidator validator;
    private final ExecutorService worker;
    private final PermissionGateway permissions;
    private final Logger logger;
    private final Map<UUID, LoadoutSession> sessions = new ConcurrentHashMap<>();
    private volatile boolean accepting = true;
    private record Providers(WeaponProviderValidator weapons, AttachmentCompatibilityValidator attachments) {}
    private volatile Providers providers = new Providers(w -> CompatibilityInfo.unavailable(), (w, p) -> CompatibilityInfo.unavailable());

    public LoadoutService(WeaponCatalog catalog, LoadoutRepository repository, UnlockRepository unlockRepository,
            WeaponCustomizationRepository customizationRepository, ExecutorService worker,
            PermissionGateway permissions, Logger logger) {
        this.catalog = catalog; this.repository = repository; this.worker = worker;
        this.permissions = permissions; this.logger = logger;
        this.unlocks = new UnlockService(unlockRepository);
        this.customizations = new WeaponCustomizationService(customizationRepository, new AttachmentValidator(unlocks));
        this.validator = new LoadoutValidator(unlocks);
    }
    /** Trusted adapter registration. Hooks run on the worker and must not block waiting for Bukkit. */
    @Override public void installProviderValidators(WeaponProviderValidator weapons, AttachmentCompatibilityValidator attachments) {
        providers = new Providers(Objects.requireNonNull(weapons), Objects.requireNonNull(attachments));
    }
    /** Called by the join listener; a fresh generation prevents late completions after quit/rejoin. */
    public CompletableFuture<LoadoutResult<Void>> loadPlayer(UUID player) {
        if (!accepting || player == null) return completed(ResultCode.NOT_READY, "Backend not accepting loads");
        LoadoutSession session = new LoadoutSession(); sessions.put(player, session);
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
    /** Stop accepting calls before draining Database.executor(). */
    public void shutdown() {
        accepting = false;
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
        return online(player, (s, p, c) -> select(s, p, c, type, slot, Ids.normalize(rawId), expected));
    }
    @Override public CompletableFuture<LoadoutResult<ClassLoadout>> resetSlot(UUID player, BackpackType type, LoadoutSlot slot, long expected) {
        return online(player, (s, p, c) -> {
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
        return online(player, (s, p, c) -> {
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
        return online(plan.playerId(), (s, p, c) -> {
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
        return withPermissions(player, p -> enqueue(() -> {
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
        return online(player, (s, p, c) -> {
            if (type == null) return invalid();
            repository.setPreferredClass(player, type);
            s.confirmed = new LoadoutPlayerData(s.confirmed.unlocks(), s.confirmed.loadouts(), s.confirmed.customizations(), type);
            return LoadoutResult.ok(type);
        });
    }
    @Override public CompletableFuture<LoadoutResult<DeploymentLoadout>> getDeploymentLoadout(UUID player, BackpackType type) {
        return online(player, (s, p, c) -> {
            if (type == null) return invalid();
            LoadoutSnapshot snapshot = validator.snapshot(c, s.confirmed.unlocks(), p, s.confirmed.loadouts().get(type));
            Map<LoadoutSlot, DeploymentEquipment> equipment = new EnumMap<>(LoadoutSlot.class);
            boolean deployable = snapshot.issues().stream().noneMatch(i -> i.fallbackId() == null);
            Providers runtime = providers;
            for (var slot : snapshot.effectiveEquipment().entrySet()) {
                WeaponDefinition weapon = c.weapons().get(slot.getValue());
                WeaponCustomization plan = customizations.get(player, weapon.id(), s.confirmed.customizations());
                ResultCode check = customizations.validate(c, s.confirmed.unlocks(), p, weapon, plan);
                CompatibilityInfo compatibility;
                if (check != ResultCode.OK) compatibility = new CompatibilityInfo(CompatibilityInfo.State.REJECTED, "Saved customization invalid: " + check);
                else {
                    compatibility = Objects.requireNonNull(runtime.weapons().validate(weapon));
                    if (compatibility.deployable() && (weapon.attachmentsEnabled() || !plan.attachments().isEmpty() || !plan.skinId().isEmpty()))
                        compatibility = Objects.requireNonNull(runtime.attachments().validate(weapon, plan));
                }
                deployable &= compatibility.deployable();
                Map<String, AttachmentDefinition> definitions = new HashMap<>();
                plan.attachments().forEach((attachmentSlot, id) -> {
                    AttachmentDefinition definition = c.attachments().get(id);
                    if (definition != null) definitions.put(attachmentSlot, definition);
                });
                equipment.put(slot.getKey(), new DeploymentEquipment(weapon, plan, definitions, compatibility));
            }
            if (equipment.isEmpty()) deployable = false;
            DeploymentLoadout description = new DeploymentLoadout(PROTOCOL_VERSION, snapshot, equipment, deployable);
            return deployable ? LoadoutResult.ok(description) : new LoadoutResult<>(ResultCode.PROVIDER_UNAVAILABLE, description, "Description contains unverified/invalid provider resources or missing defaults");
        });
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
        if (!accepting || player == null) return completed(ResultCode.NOT_READY, "Player/backend not ready");
        LoadoutSession expected = sessions.get(player);
        if (expected == null || expected.confirmed == null) return completed(ResultCode.NOT_READY, "Player has not loaded");
        return withPermissions(player, p -> enqueue(() -> {
            if (!p.online() || sessions.get(player) != expected || expected.confirmed == null) return LoadoutResult.fail(ResultCode.NOT_READY, "Player session changed");
            return operation.run(expected, p, catalog.snapshot());
        }));
    }
    private <T> CompletableFuture<LoadoutResult<T>> withPermissions(UUID player, java.util.function.Function<PermissionSnapshot, CompletableFuture<LoadoutResult<T>>> next) {
        if (!accepting) return completed(ResultCode.NOT_READY, "Backend shutting down");
        try {
            return permissions.capture(player, catalog.snapshot().permissions()).thenCompose(next)
                    .exceptionally(e -> { logger.log(Level.WARNING, "Permission snapshot unavailable", e); return LoadoutResult.fail(ResultCode.NOT_READY, "Permission snapshot unavailable"); });
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Permission snapshot scheduling failed", e);
            return completed(ResultCode.NOT_READY, "Permission scheduler unavailable");
        }
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
                catch (RuntimeException e) { logger.log(Level.SEVERE, "Loadout/provider operation failed", e); future.complete(LoadoutResult.fail(ResultCode.PROVIDER_UNAVAILABLE, "Backend/provider operation failed; see server log")); }
            });
        } catch (RejectedExecutionException e) { future.complete(LoadoutResult.fail(ResultCode.NOT_READY, "Database worker closed")); }
        return future;
    }
    private static <T> LoadoutResult<T> invalid() { return LoadoutResult.fail(ResultCode.INVALID_INPUT, "Invalid input"); }
    private static <T> LoadoutResult<T> conflict() { return LoadoutResult.fail(ResultCode.REVISION_CONFLICT, "Revision conflict; reread before retrying"); }
    private static <T> CompletableFuture<LoadoutResult<T>> completed(ResultCode code, String message) { return CompletableFuture.completedFuture(LoadoutResult.fail(code, message)); }
}
