package top.mores.bFBackpack;

import top.mores.bFBackpack.api.*;
import top.mores.bFBackpack.catalog.WeaponCatalog;
import top.mores.bFBackpack.service.*;
import top.mores.bFBackpack.storage.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;

/** Real SQLite and ordered executor, with an explicit simulated Bukkit permission thread. */
final class BackendFixture implements AutoCloseable {
    final Path folder;
    final WeaponCatalog catalog;
    final Database database;
    final SqlLoadoutRepository repository;
    final LoadoutService api;
    final ValidationEngine validation;
    final Set<UUID> online = ConcurrentHashMap.newKeySet();
    final Map<UUID, Set<String>> grants = new ConcurrentHashMap<>();
    final ExecutorService main = Executors.newSingleThreadExecutor(r -> new Thread(r, "test-bukkit-main"));
    /** Simulates a permission snapshot delayed by a busy main thread; no sleeping in tests. */
    volatile long permissionLagMs;

    BackendFixture(Path folder) throws Exception { this(folder, ValidationEngine.Settings.DEFAULT, 5000); }

    BackendFixture(Path folder, ValidationEngine.Settings settings) throws Exception { this(folder, settings, 5000); }

    BackendFixture(Path folder, ValidationEngine.Settings settings, long permissionMaxAgeMs) throws Exception {
        this.folder = folder;
        copyResources(folder);
        Logger logger = Logger.getLogger("backend-test-" + UUID.randomUUID()); logger.setLevel(Level.OFF);
        catalog = new WeaponCatalog(message -> {}); catalog.reload(folder);
        database = new Database(logger, new DbSettings(false, folder.resolve("test.db").toString(), "", 0, "", "", "", "", "bf_"));
        repository = new SqlLoadoutRepository(database);
        db(() -> { database.connect(); repository.initialize(); return null; });
        validation = new ValidationEngine(settings, logger);
        api = new LoadoutService(catalog, repository, repository, repository, database.executor(), (player, nodes) -> CompletableFuture.supplyAsync(() -> {
            if (!Thread.currentThread().getName().equals("test-bukkit-main")) throw new AssertionError("Permissions off server thread");
            Set<String> permitted = new HashSet<>(grants.getOrDefault(player, Set.of())); permitted.retainAll(nodes);
            return new PermissionSnapshot(player, online.contains(player), permitted,
                    System.currentTimeMillis() - permissionLagMs, 0);
        }, main), logger, validation, permissionMaxAgeMs);
    }

    static void copyResources(Path path) throws Exception {
        Files.createDirectories(path);
        for (String file : List.of("weapons.yml", "attachments.yml", "class-rules.yml")) {
            try (var stream = BackendFixture.class.getResourceAsStream("/" + file)) {
                Objects.requireNonNull(stream); Files.copy(stream, path.resolve(file), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    UUID join() throws Exception { UUID id = UUID.randomUUID(); join(id); return id; }

    void join(UUID id) throws Exception { online.add(id); org.junit.jupiter.api.Assertions.assertEquals(ResultCode.OK, await(api.loadPlayer(id)).code()); }

    /** Install a provider that verifies everything. */
    ValidationRegistration acceptAll() { return acceptAll("test-provider"); }

    ValidationRegistration acceptAll(String providerId) {
        return api.registerValidators(providerId, new ValidationProvider() {
            @Override public CompletableFuture<CompatibilityInfo> validateWeapon(WeaponDefinition weapon) {
                return CompletableFuture.completedFuture(CompatibilityInfo.verified("test weapon"));
            }
            @Override public CompletableFuture<CompatibilityInfo> validateAttachments(WeaponDefinition weapon, WeaponCustomization customization) {
                return CompletableFuture.completedFuture(CompatibilityInfo.verified("test attachments"));
            }
        });
    }

    static ValidationEngine.Settings shortTimeout(int timeoutMs) {
        return new ValidationEngine.Settings(timeoutMs, 8, 32);
    }

    <T> T db(Callable<T> task) throws Exception { return database.executor().submit(task).get(10, TimeUnit.SECONDS); }

    /** Wait until every issued request has passed the permission and database phases. */
    void drain() throws Exception {
        main.submit(() -> null).get(5, TimeUnit.SECONDS);
        database.executor().submit(() -> null).get(5, TimeUnit.SECONDS);
    }

    static <T> T await(CompletableFuture<T> future) throws Exception { return future.get(10, TimeUnit.SECONDS); }

    @Override public void close() { api.shutdown(); database.close(); main.shutdownNow(); }
}
