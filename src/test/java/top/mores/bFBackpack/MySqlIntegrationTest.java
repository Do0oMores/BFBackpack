package top.mores.bFBackpack;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import top.mores.bFBackpack.api.*;
import top.mores.bFBackpack.backpack.BackpackType;
import top.mores.bFBackpack.catalog.WeaponCatalog;
import top.mores.bFBackpack.storage.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

/** Optional real MySQL test; creates and drops only its randomly prefixed test tables. */
@EnabledIfEnvironmentVariable(named = "BF_TEST_MYSQL_HOST", matches = ".+")
class MySqlIntegrationTest {
    @TempDir Path folder;
    @Test void mysqlTransactionsPersistenceAndOptimisticLocks() throws Exception {
        BackendFixture.copyResources(folder); var catalog = new WeaponCatalog(s -> {}); catalog.reload(folder);
        String prefix = "bft_" + UUID.randomUUID().toString().substring(0, 8) + "_";
        var settings = new DbSettings(true, "", System.getenv("BF_TEST_MYSQL_HOST"), Integer.parseInt(System.getenv().getOrDefault("BF_TEST_MYSQL_PORT", "3306")),
                System.getenv("BF_TEST_MYSQL_DATABASE"), System.getenv("BF_TEST_MYSQL_USER"), System.getenv("BF_TEST_MYSQL_PASSWORD"),
                "useSSL=false&connectTimeout=5000&socketTimeout=10000", prefix);
        try (var database = new Database(Logger.getLogger("mysql-test"), settings)) {
            database.executor().submit(() -> {
                try {
                    database.connect(); var repo = new SqlLoadoutRepository(database); repo.initialize(); UUID player = UUID.randomUUID();
                    var data = repo.loadPlayer(player, catalog.snapshot()); assertEquals(4, data.loadouts().size());
                    ClassLoadout old = data.loadouts().get(BackpackType.ASSAULT); assertEquals(1, repo.saveLoadout(old).revision());
                    assertThrows(RevisionConflictException.class, () -> repo.saveLoadout(old));
                    WeaponCustomization plan = new WeaponCustomization(player, "battlefield:stg44", Map.of("SCOPE", "battlefield:scope_basic"), "", 0);
                    repo.saveCustomization(plan); assertThrows(RevisionConflictException.class, () -> repo.saveCustomization(plan));
                    repo.grantUnlock(player, "battlefield:gewehr43", "TEST"); repo.grantUnlock(player, "battlefield:gewehr43", "TEST");
                    assertEquals(1, repo.getPlayerUnlocks(player).size()); repo.setPreferredClass(player, BackpackType.RECON);
                    assertEquals(BackpackType.RECON, repo.loadPlayer(player, catalog.snapshot()).preferredClass());
                    assertEquals(plan.attachments(), repo.getCustomizations(player).get(plan.weaponId()).attachments());
                    repo.revokeUnlock(player, "battlefield:gewehr43"); assertTrue(repo.getPlayerUnlocks(player).isEmpty());
                } finally {
                    try (var statement = database.connection().createStatement()) {
                        for (String table : List.of("player_unlock", "weapon_customization", "player_loadout", "player_settings", "backend_schema")) statement.execute("DROP TABLE IF EXISTS " + prefix + table);
                    }
                }
                return null;
            }).get(30, TimeUnit.SECONDS);
        }
    }
}
