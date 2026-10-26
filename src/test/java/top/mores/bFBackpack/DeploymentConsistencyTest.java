package top.mores.bFBackpack;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import top.mores.bFBackpack.api.*;
import top.mores.bFBackpack.backpack.BackpackType;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static top.mores.bFBackpack.BackendFixture.await;

/** A description is bound to exact revisions; concurrent edits or reloads can never mix into it. */
class DeploymentConsistencyTest {
    @TempDir Path folder;
    static final String GUN = "battlefield:stg44", OTHER = "battlefield:gewehr43", SCOPE = "battlefield:scope_basic";

    @Test void bindingCarriesEveryRevisionAndTheProviderGeneration() throws Exception {
        try (var f = new BackendFixture(folder)) {
            UUID player = f.join();
            var registration = f.acceptAll("binding-provider");
            assertEquals(ResultCode.OK, await(f.api.selectAttachment(player, GUN, "SCOPE", SCOPE, 0)).code());
            long catalogRevision = f.catalog.snapshot().revision();

            var result = await(f.api.getDeploymentLoadout(player, BackpackType.ASSAULT));
            var binding = result.value().binding();
            assertEquals(player, binding.playerId());
            assertEquals(BackpackType.ASSAULT, binding.soldierClass());
            assertEquals(0, binding.loadoutRevision());
            assertEquals(catalogRevision, binding.catalogRevision());
            assertEquals(1, result.value().equipment().get(LoadoutSlot.PRIMARY).customization().revision());
            assertEquals("binding-provider", binding.providerId());
            assertEquals(registration.generation(), binding.providerGeneration());
            assertTrue(binding.permissionCapturedAt() > 0);
            assertTrue(binding.sessionGeneration() > 0);
            assertTrue(binding.stillCurrent());
            assertEquals(BFBackpackAPI.PROTOCOL_VERSION, result.value().protocolVersion());
        }
    }

    @Test void catalogReloadDuringValidationRejectsTheOldConclusion() throws Exception {
        try (var f = new BackendFixture(folder)) {
            UUID player = f.join();
            long oldRevision = f.catalog.snapshot().revision();
            var controlled = new CompletableFuture<CompatibilityInfo>();
            var started = new CountDownLatch(1);
            f.api.registerValidators("reload", new ValidationProvider() {
                @Override public CompletableFuture<CompatibilityInfo> validateWeapon(WeaponDefinition weapon) { started.countDown(); return controlled; }
                @Override public CompletableFuture<CompatibilityInfo> validateAttachments(WeaponDefinition weapon, WeaponCustomization customization) { return controlled; }
            });
            var pending = f.api.getDeploymentLoadout(player, BackpackType.ASSAULT);
            assertTrue(started.await(5, TimeUnit.SECONDS), "validation never started");

            assertEquals(ResultCode.OK, await(f.api.reloadCatalog(folder)).code());
            controlled.complete(CompatibilityInfo.verified("verified against the old catalog"));

            var result = await(pending);
            assertEquals(ResultCode.REVISION_CONFLICT, result.code());
            assertFalse(result.value().deployable());
            assertEquals(oldRevision, result.value().binding().catalogRevision());
            assertFalse(result.value().binding().stillCurrent());
            assertEquals(oldRevision + 1, f.catalog.snapshot().revision());
        }
    }

    @Test void loadoutEditDuringValidationRejectsWithoutMixingRevisions() throws Exception {
        try (var f = new BackendFixture(folder)) {
            UUID player = f.join();
            await(f.api.grantUnlock(player, OTHER));
            var controlled = new CompletableFuture<CompatibilityInfo>();
            var started = new CountDownLatch(1);
            f.api.registerValidators("edit", new ValidationProvider() {
                @Override public CompletableFuture<CompatibilityInfo> validateWeapon(WeaponDefinition weapon) { started.countDown(); return controlled; }
                @Override public CompletableFuture<CompatibilityInfo> validateAttachments(WeaponDefinition weapon, WeaponCustomization customization) { return controlled; }
            });
            var pending = f.api.getDeploymentLoadout(player, BackpackType.ASSAULT);
            assertTrue(started.await(5, TimeUnit.SECONDS), "validation never started");

            assertEquals(ResultCode.OK,
                    await(f.api.selectEquipment(player, BackpackType.ASSAULT, LoadoutSlot.PRIMARY, OTHER, 0)).code());
            controlled.complete(CompatibilityInfo.verified("verified against the old loadout"));

            var result = await(pending);
            assertEquals(ResultCode.REVISION_CONFLICT, result.code());
            assertFalse(result.value().deployable());
            assertEquals(0, result.value().binding().loadoutRevision());
            assertEquals(GUN, result.value().loadout().saved().equipment().get(LoadoutSlot.PRIMARY));
            assertEquals(GUN, result.value().equipment().get(LoadoutSlot.PRIMARY).definition().id());
            assertEquals(OTHER, await(f.api.getLoadout(player, BackpackType.ASSAULT)).value().equipment().get(LoadoutSlot.PRIMARY));
        }
    }

    @Test void timedOutValidationNeverApprovesANewerLoadout() throws Exception {
        try (var f = new BackendFixture(folder, BackendFixture.shortTimeout(120))) {
            UUID player = f.join();
            var late = new CompletableFuture<CompatibilityInfo>();
            f.api.registerValidators("late", new ValidationProvider() {
                @Override public CompletableFuture<CompatibilityInfo> validateWeapon(WeaponDefinition weapon) { return late; }
                @Override public CompletableFuture<CompatibilityInfo> validateAttachments(WeaponDefinition weapon, WeaponCustomization customization) { return late; }
            });
            var timedOut = await(f.api.getDeploymentLoadout(player, BackpackType.ASSAULT));
            assertEquals(ResultCode.VALIDATION_TIMEOUT, timedOut.code());
            assertEquals(0, timedOut.value().binding().loadoutRevision());

            assertEquals(ResultCode.OK,
                    await(f.api.selectEquipment(player, BackpackType.ASSAULT, LoadoutSlot.PRIMARY, GUN, 0)).code());
            late.complete(CompatibilityInfo.verified("too late for revision zero"));
            assertFalse(timedOut.value().deployable());
            assertEquals(0, timedOut.value().binding().loadoutRevision());

            f.acceptAll();
            var current = await(f.api.getDeploymentLoadout(player, BackpackType.ASSAULT));
            assertEquals(ResultCode.OK, current.code());
            assertEquals(1, current.value().binding().loadoutRevision());
            assertTrue(current.value().deployable());
        }
    }
}
