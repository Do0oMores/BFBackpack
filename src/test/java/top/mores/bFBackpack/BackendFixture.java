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
    final Set<UUID> online = ConcurrentHashMap.newKeySet();
    final Map<UUID, Set<String>> grants = new ConcurrentHashMap<>();
    final ExecutorService main = Executors.newSingleThreadExecutor(r -> new Thread(r, "test-bukkit-main"));
    BackendFixture(Path folder) throws Exception {
        this.folder = folder;
        copyResources(folder);
        Logger logger = Logger.getLogger("backend-test-" + UUID.randomUUID()); logger.setLevel(Level.OFF);
        catalog = new WeaponCatalog(message -> {}); catalog.reload(folder);
        database = new Database(logger, new DbSettings(false, folder.resolve("test.db").toString(), "", 0, "", "", "", "", "bf_"));
        repository = new SqlLoadoutRepository(database);
        db(() -> { database.connect(); repository.initialize(); return null; });
        api = new LoadoutService(catalog, repository, repository, repository, database.executor(), (player, nodes) -> CompletableFuture.supplyAsync(() -> {
            if (!Thread.currentThread().getName().equals("test-bukkit-main")) throw new AssertionError("Permissions off server thread");
            Set<String> permitted = new HashSet<>(grants.getOrDefault(player, Set.of())); permitted.retainAll(nodes);
            return new PermissionSnapshot(online.contains(player), permitted);
        }, main), logger);
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
    <T> T db(Callable<T> task) throws Exception { return database.executor().submit(task).get(10, TimeUnit.SECONDS); }
    static <T> T await(CompletableFuture<T> future) throws Exception { return future.get(10, TimeUnit.SECONDS); }
    @Override public void close() { api.shutdown(); database.close(); main.shutdownNow(); }
}
