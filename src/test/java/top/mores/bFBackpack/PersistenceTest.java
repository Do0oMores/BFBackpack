package top.mores.bFBackpack;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import top.mores.bFBackpack.api.*;
import top.mores.bFBackpack.backpack.BackpackType;
import top.mores.bFBackpack.storage.*;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static top.mores.bFBackpack.BackendFixture.await;

class PersistenceTest {
    @TempDir Path folder;
    @Test void sqliteRecoversUnlocksAllClassesCustomizationAndSettingsAfterRestart() throws Exception {
        UUID player;
        try (var f = new BackendFixture(folder)) {
            player = f.join();
            await(f.api.grantUnlock(player, "battlefield:gewehr43"));
            await(f.api.grantUnlock(player, "battlefield:scope_advanced"));
            await(f.api.selectEquipment(player, BackpackType.ASSAULT, LoadoutSlot.PRIMARY, "battlefield:gewehr43", 0));
            await(f.api.selectAttachment(player, "battlefield:gewehr43", "SCOPE", "battlefield:scope_advanced", 0));
            await(f.api.selectAttachment(player, "battlefield:stg44", "SCOPE", "battlefield:scope_basic", 0));
            await(f.api.setPreferredClass(player, BackpackType.SUPPORT));
        }
        try (var f = new BackendFixture(folder)) {
            f.join(player);
            var loaded = await(f.api.getLoadout(player, BackpackType.ASSAULT)).value();
            assertEquals("battlefield:gewehr43", loaded.equipment().get(LoadoutSlot.PRIMARY)); assertEquals(1, loaded.revision());
            assertEquals("battlefield:scope_advanced", await(f.api.getWeaponCustomization(player, "battlefield:gewehr43")).value().attachments().get("SCOPE"));
            assertEquals("battlefield:scope_basic", await(f.api.getWeaponCustomization(player, "battlefield:stg44")).value().attachments().get("SCOPE"));
            assertEquals(BackpackType.SUPPORT, await(f.api.getPreferredClass(player)).value());
            assertEquals(Set.of("battlefield:gewehr43", "battlefield:scope_advanced"), await(f.api.getPlayerUnlocks(player)).value());
            for (BackpackType type : BackpackType.values()) assertNotNull(await(f.api.getLoadout(player, type)).value());
        }
    }
    @Test void failedWriteDoesNotPublishCacheOrClaimSuccessAndCanRecover() throws Exception {
        try (var f = new BackendFixture(folder)) {
            UUID player = f.join(); var before = await(f.api.getLoadout(player, BackpackType.ASSAULT)).value();
            f.db(() -> { try (var s = f.database.connection().createStatement()) { s.execute("CREATE TRIGGER fail_loadout BEFORE UPDATE ON bf_player_loadout BEGIN SELECT RAISE(FAIL, 'test failure'); END"); } return null; });
            assertEquals(ResultCode.DATABASE_ERROR, await(f.api.selectEquipment(player, BackpackType.ASSAULT, LoadoutSlot.PRIMARY, "battlefield:stg44", 0)).code());
            assertEquals(before, await(f.api.getLoadout(player, BackpackType.ASSAULT)).value());
            assertEquals(before, f.db(() -> f.repository.loadPlayer(player, f.catalog.snapshot())).loadouts().get(BackpackType.ASSAULT));
            f.db(() -> { try (var s = f.database.connection().createStatement()) { s.execute("DROP TRIGGER fail_loadout"); } return null; });
            assertEquals(ResultCode.OK, await(f.api.selectEquipment(player, BackpackType.ASSAULT, LoadoutSlot.PRIMARY, "battlefield:stg44", 0)).code());
        }
    }
    @Test void failedCustomizationTransactionRollsBackInitialRowInsert() throws Exception {
        try (var f = new BackendFixture(folder)) {
            UUID player = f.join();
            f.db(() -> { try (var s = f.database.connection().createStatement()) { s.execute("CREATE TRIGGER fail_customization BEFORE UPDATE ON bf_weapon_customization BEGIN SELECT RAISE(FAIL, 'test failure'); END"); } return null; });
            assertEquals(ResultCode.DATABASE_ERROR, await(f.api.selectAttachment(player, "battlefield:stg44", "SCOPE", "battlefield:scope_basic", 0)).code());
            assertTrue(f.db(() -> f.repository.getCustomizations(player)).isEmpty());
            assertTrue(await(f.api.getWeaponCustomization(player, "battlefield:stg44")).value().attachments().isEmpty());
        }
    }
    @Test void sqlCompareAndSwapRejectsStaleDuplicateAndInventedRevision() throws Exception {
        try (var f = new BackendFixture(folder)) {
            UUID player = f.join();
            ClassLoadout l = await(f.api.getLoadout(player, BackpackType.ASSAULT)).value();
            f.db(() -> { f.repository.saveLoadout(l); assertThrows(RevisionConflictException.class, () -> f.repository.saveLoadout(l)); return null; });
            WeaponCustomization initial = new WeaponCustomization(player, "battlefield:stg44", Map.of(), "", 0);
            f.db(() -> {
                assertEquals(1, f.repository.saveCustomization(initial).revision());
                assertThrows(RevisionConflictException.class, () -> f.repository.saveCustomization(initial));
                assertThrows(RevisionConflictException.class, () -> f.repository.saveCustomization(new WeaponCustomization(player, "battlefield:gewehr43", Map.of(), "", 99)));
                return null;
            });
        }
    }
    @Test void corruptOrFutureJsonIsNotSilentlyReset() throws Exception {
        try (var f = new BackendFixture(folder)) {
            UUID player = f.join();
            assertThrows(SQLException.class, () -> LoadoutJson.loadout(player, BackpackType.ASSAULT, "{\"schemaVersion\":99,\"equipment\":{}}", 0));
            assertThrows(SQLException.class, () -> LoadoutJson.customization(player, "battlefield:stg44", "not json", 0));
            f.api.unloadPlayer(player);
            f.db(() -> { try (PreparedStatement s = f.database.connection().prepareStatement("UPDATE bf_player_loadout SET schema_version = 99 WHERE player_uuid = ?")) { s.setString(1, player.toString()); s.executeUpdate(); } return null; });
            assertEquals(ResultCode.DATABASE_ERROR, await(f.api.loadPlayer(player)).code());
            assertEquals(ResultCode.NOT_READY, await(f.api.getLoadout(player, BackpackType.ASSAULT)).code());
        }
    }
    @Test void shutdownDrainsAcceptedWritesBeforeConnectionClose() throws Exception {
        UUID player;
        try (var f = new BackendFixture(folder)) {
            player = f.join(); CountDownLatch release = new CountDownLatch(1);
            f.database.executor().execute(() -> { try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } });
            var pending = f.api.selectAttachment(player, "battlefield:stg44", "SCOPE", "battlefield:scope_basic", 0);
            f.main.submit(() -> {}).get(5, TimeUnit.SECONDS); // permission continuation has enqueued the write
            f.api.shutdown();
            assertEquals(ResultCode.NOT_READY, await(f.api.getLoadout(player, BackpackType.ASSAULT)).code());
            release.countDown(); f.database.close();
            assertEquals(ResultCode.OK, await(pending).code()); assertTrue(f.database.executor().isTerminated());
        }
        try (var f = new BackendFixture(folder)) {
            f.join(player); assertEquals(1, await(f.api.getWeaponCustomization(player, "battlefield:stg44")).value().revision());
        }
    }
    @Test void mysqlDialectUsesInnoDbBinaryIdsAndPortableCasSql() {
        var mysql = BackendSchema.statements("bf_", true); var sqlite = BackendSchema.statements("bf_", false);
        assertEquals(5, mysql.size()); assertTrue(mysql.stream().allMatch(s -> s.endsWith("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin")));
        assertTrue(mysql.get(2).contains("LONGTEXT")); assertTrue(sqlite.get(2).contains("TEXT"));
        assertTrue(sqlite.stream().noneMatch(s -> s.contains("ENGINE=")));
        assertEquals("INSERT IGNORE INTO ", BackendSchema.insertIgnore(true)); assertEquals("INSERT OR IGNORE INTO ", BackendSchema.insertIgnore(false));
        assertThrows(IllegalArgumentException.class, () -> BackendSchema.statements("bf_; DROP", true));
    }
    @Test void retainedLegacyRepositoryAlsoRefusesForcedOrPromotedRevisions() throws Exception {
        try (var f = new BackendFixture(folder)) {
            f.db(() -> {
                f.database.initializeLegacyTables();
                var legacy = new SqlBackpackRepository(f.database, java.util.logging.Logger.getLogger("legacy-test"));
                UUID player = UUID.randomUUID(); var empty = new org.bukkit.inventory.ItemStack[0];
                assertEquals(1, legacy.save(player, BackpackType.ASSAULT, 9, empty, 0));
                assertThrows(RevisionConflictException.class, () -> legacy.save(player, BackpackType.ASSAULT, 9, empty, 0));
                assertThrows(RevisionConflictException.class, () -> legacy.save(player, BackpackType.MEDIC, 9, empty, 7));
                return null;
            });
        }
    }
}
