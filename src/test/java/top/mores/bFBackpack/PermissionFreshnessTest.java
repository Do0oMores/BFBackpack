package top.mores.bFBackpack;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import top.mores.bFBackpack.api.*;
import top.mores.bFBackpack.backpack.BackpackType;
import top.mores.bFBackpack.service.ValidationEngine;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static top.mores.bFBackpack.BackendFixture.await;

/** Permission snapshots: fresh enough to authorize, bound to one session, never reused after revoke. */
class PermissionFreshnessTest {
    @TempDir Path folder;
    static final String GUN = "battlefield:stg44", OTHER = "battlefield:gewehr43";
    static final String PERMISSION = "bfbackpack.weapon.gewehr43";

    /** Short maximum snapshot age so an aged snapshot is simulated without sleeping. */
    private BackendFixture fixture(Path path) throws Exception {
        return new BackendFixture(path, ValidationEngine.Settings.DEFAULT, 200);
    }

    @Test void agedSnapshotRejectsMutationsButStillAllowsQueries() throws Exception {
        try (var f = fixture(folder)) {
            UUID player = f.join();
            f.permissionLagMs = 1_000;
            assertEquals(ResultCode.OK, await(f.api.getLoadout(player, BackpackType.ASSAULT)).code());
            assertEquals(ResultCode.PERMISSION_STALE,
                    await(f.api.selectEquipment(player, BackpackType.ASSAULT, LoadoutSlot.PRIMARY, GUN, 0)).code());
            assertEquals(ResultCode.PERMISSION_STALE,
                    await(f.api.selectAttachment(player, GUN, "SCOPE", "battlefield:scope_basic", 0)).code());
            assertEquals(0, await(f.api.getLoadout(player, BackpackType.ASSAULT)).value().revision());
            assertEquals(0, await(f.api.getWeaponCustomization(player, GUN)).value().revision());

            f.permissionLagMs = 0;
            assertEquals(ResultCode.OK,
                    await(f.api.selectEquipment(player, BackpackType.ASSAULT, LoadoutSlot.PRIMARY, GUN, 0)).code());
        }
    }

    @Test void agedSnapshotCanNeverAuthorizeADeployment() throws Exception {
        try (var f = fixture(folder)) {
            UUID player = f.join();
            f.grants.put(player, Set.of(PERMISSION));
            f.acceptAll();
            f.permissionLagMs = 1_000;
            var result = await(f.api.getDeploymentLoadout(player, BackpackType.ASSAULT));
            assertEquals(ResultCode.PERMISSION_STALE, result.code());
            assertEquals(ResultCode.PERMISSION_STALE, result.value().status());
            assertFalse(result.value().deployable());
            assertTrue(result.value().runtimeIssues().stream().allMatch(i -> i.code() == ResultCode.PERMISSION_STALE));
            assertEquals(player, result.value().binding().playerId());
        }
    }

    @Test void revokedTemporaryPermissionCannotBeDeployedAndFallsBackSafely() throws Exception {
        try (var f = fixture(folder)) {
            UUID player = f.join();
            f.acceptAll();
            f.grants.put(player, Set.of(PERMISSION));
            assertEquals(ResultCode.OK,
                    await(f.api.selectEquipment(player, BackpackType.ASSAULT, LoadoutSlot.PRIMARY, OTHER, 0)).code());

            f.grants.remove(player);   // revoked after the edit, before deployment
            var result = await(f.api.getDeploymentLoadout(player, BackpackType.ASSAULT));
            var deployed = result.value().equipment().get(LoadoutSlot.PRIMARY).definition().id();
            assertNotEquals(OTHER, deployed);
            assertEquals(GUN, deployed);
            assertEquals(OTHER, result.value().loadout().saved().equipment().get(LoadoutSlot.PRIMARY));
            assertEquals(ResultCode.LOCKED, result.value().loadout().issues().get(0).code());
            assertTrue(result.value().deployable());
        }
    }

    @Test void permanentUnlockStaysIndependentFromTemporaryPermission() throws Exception {
        try (var f = fixture(folder)) {
            UUID player = f.join();
            f.acceptAll();
            assertTrue(await(f.api.grantUnlock(player, OTHER)).value().permanent());
            f.grants.put(player, Set.of(PERMISSION));
            assertEquals(ResultCode.OK,
                    await(f.api.selectEquipment(player, BackpackType.ASSAULT, LoadoutSlot.PRIMARY, OTHER, 0)).code());

            f.grants.remove(player);
            var result = await(f.api.getDeploymentLoadout(player, BackpackType.ASSAULT));
            assertEquals(OTHER, result.value().equipment().get(LoadoutSlot.PRIMARY).definition().id());
            assertTrue(result.value().deployable());
            assertTrue(await(f.api.getPlayerUnlocks(player)).value().contains(OTHER), "revoking a permission must not delete permanent unlocks");
        }
    }

    @Test void quitAndRejoinDuringValidationInvalidatesTheDescription() throws Exception {
        try (var f = fixture(folder)) {
            UUID player = f.join();
            CountDownLatch started = new CountDownLatch(1);
            CompletableFuture<CompatibilityInfo> controlled = new CompletableFuture<>();
            f.acceptAll("session");
            var accepted = await(f.api.getDeploymentLoadout(player, BackpackType.ASSAULT)).value().binding();
            f.api.registerValidators("session", new ValidationProvider() {
                @Override public CompletableFuture<CompatibilityInfo> validateWeapon(WeaponDefinition weapon) {
                    started.countDown();
                    return controlled;
                }
                @Override public CompletableFuture<CompatibilityInfo> validateAttachments(WeaponDefinition weapon, WeaponCustomization customization) {
                    return controlled;
                }
            });
            var pending = f.api.getDeploymentLoadout(player, BackpackType.ASSAULT);
            assertTrue(started.await(5, TimeUnit.SECONDS), "validation never started");

            f.api.unloadPlayer(player);   // quit
            f.join(player);               // rejoin creates a new session generation
            controlled.complete(CompatibilityInfo.verified("old session"));

            var result = await(pending);
            assertEquals(ResultCode.NOT_READY, result.code());
            assertFalse(result.value().deployable());
            assertEquals(player, result.value().binding().playerId());
            assertEquals(accepted.sessionGeneration(), result.value().binding().sessionGeneration());
            assertTrue(await(f.api.getDeploymentLoadout(player, BackpackType.ASSAULT)).value().deployable());
        }
    }
}
