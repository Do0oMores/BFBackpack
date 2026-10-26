package top.mores.bFBackpack;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import top.mores.bFBackpack.api.*;
import top.mores.bFBackpack.backpack.BackpackType;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static top.mores.bFBackpack.BackendFixture.await;

class LoadoutServiceTest {
    @TempDir Path folder;
    BackendFixture f;
    UUID player;
    static final String GUN = "battlefield:stg44", OTHER = "battlefield:gewehr43", SCOPE = "battlefield:scope_basic";
    @BeforeEach void setup() throws Exception { f = new BackendFixture(folder); player = f.join(); }
    @AfterEach void cleanup() { f.close(); }
    ClassLoadout loadout(BackpackType type) throws Exception { return await(f.api.getLoadout(player, type)).value(); }
    @Test void initializesFourClassesWithIndependentDefaults() throws Exception {
        for (BackpackType type : BackpackType.values()) {
            ClassLoadout l = loadout(type); assertEquals(0, l.revision()); assertEquals(6, l.equipment().size());
            assertEquals(type, l.soldierClass()); assertTrue(await(f.api.validateLoadout(player, type)).value().issues().isEmpty());
        }
        assertEquals("battlefield:mp40", loadout(BackpackType.MEDIC).equipment().get(LoadoutSlot.PRIMARY));
    }
    @Test void notReadyBeforeLoadAfterQuitAndDuringLoad() throws Exception {
        UUID unloaded = UUID.randomUUID(); assertEquals(ResultCode.NOT_READY, await(f.api.getLoadout(unloaded, BackpackType.ASSAULT)).code());
        CountDownLatch blocker = new CountDownLatch(1); f.database.executor().execute(() -> { try { blocker.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } });
        CompletableFuture<LoadoutResult<Void>> loading = f.api.loadPlayer(unloaded);
        assertEquals(ResultCode.NOT_READY, await(f.api.getCatalog(unloaded, BackpackType.ASSAULT)).code());
        f.api.unloadPlayer(unloaded); blocker.countDown(); assertEquals(ResultCode.NOT_READY, await(loading).code());
        f.api.unloadPlayer(player); assertEquals(ResultCode.NOT_READY, await(f.api.getLoadout(player, BackpackType.ASSAULT)).code());
    }
    @Test void unknownWeaponUnknownAttachmentAndInvalidInputHaveExplicitCodes() throws Exception {
        assertEquals(ResultCode.NOT_FOUND, await(f.api.selectEquipment(player, BackpackType.ASSAULT, LoadoutSlot.PRIMARY, "battlefield:missing", 0)).code());
        assertEquals(ResultCode.NOT_FOUND, await(f.api.selectAttachment(player, GUN, "SCOPE", "battlefield:missing", 0)).code());
        assertEquals(ResultCode.INVALID_INPUT, await(f.api.selectEquipment(player, BackpackType.ASSAULT, LoadoutSlot.PRIMARY, "oops", 0)).code());
        assertEquals(ResultCode.ATTACHMENT_INVALID, await(f.api.removeAttachment(player, GUN, "UNRECOGNIZED", 0)).code());
    }
    @Test void classSlotAndUnlockChecksCannotBeBypassed() throws Exception {
        assertEquals(ResultCode.CLASS_DENIED, await(f.api.selectEquipment(player, BackpackType.ASSAULT, LoadoutSlot.PRIMARY, "battlefield:mp40", 0)).code());
        assertEquals(ResultCode.CLASS_DENIED, await(f.api.selectEquipment(player, BackpackType.ASSAULT, LoadoutSlot.SECONDARY, GUN, 0)).code());
        assertEquals(ResultCode.LOCKED, await(f.api.selectEquipment(player, BackpackType.ASSAULT, LoadoutSlot.PRIMARY, OTHER, 0)).code());
    }
    @Test void permissionsAreFreshAndPermanentGrantSurvivesRevocation() throws Exception {
        f.grants.put(player, Set.of("bfbackpack.weapon.gewehr43"));
        assertTrue(await(f.api.isUnlocked(player, OTHER)).value().unlocked());
        assertEquals(ResultCode.OK, await(f.api.selectEquipment(player, BackpackType.ASSAULT, LoadoutSlot.PRIMARY, OTHER, 0)).code());
        f.grants.remove(player);
        assertFalse(await(f.api.isUnlocked(player, OTHER)).value().unlocked());
        var snapshot = await(f.api.validateLoadout(player, BackpackType.ASSAULT)).value();
        assertEquals(OTHER, snapshot.saved().equipment().get(LoadoutSlot.PRIMARY));
        assertEquals(GUN, snapshot.effectiveEquipment().get(LoadoutSlot.PRIMARY));
        assertEquals(ResultCode.LOCKED, snapshot.issues().get(0).code());
        assertTrue(await(f.api.grantUnlock(player, OTHER)).value().permanent());
        assertTrue(await(f.api.isUnlocked(player, OTHER)).value().unlocked());
        assertFalse(await(f.api.revokeUnlock(player, OTHER)).value().unlocked());
    }
    @Test void requiredPermissionIsIndependentOfPermanentUnlock() throws Exception {
        Path weapons = folder.resolve("weapons.yml");
        Files.writeString(weapons, Files.readString(weapons).replace("  battlefield:gewehr43:\n", "  battlefield:gewehr43:\n    restrictions:\n      permission: bfbackpack.special\n"));
        assertEquals(ResultCode.OK, await(f.api.reloadCatalog(folder)).code());
        await(f.api.grantUnlock(player, OTHER));
        assertEquals(ResultCode.PERMISSION_DENIED, await(f.api.selectEquipment(player, BackpackType.ASSAULT, LoadoutSlot.PRIMARY, OTHER, 0)).code());
        f.grants.put(player, Set.of("bfbackpack.special"));
        assertEquals(ResultCode.OK, await(f.api.selectEquipment(player, BackpackType.ASSAULT, LoadoutSlot.PRIMARY, OTHER, 0)).code());
        f.grants.remove(player); assertEquals(ResultCode.PERMISSION_DENIED, await(f.api.getWeaponCustomization(player, OTHER)).code());
    }
    @Test void catalogHasSeparateClassLegalityAndUnlockStatus() throws Exception {
        var entries = await(f.api.getCatalog(player, BackpackType.ASSAULT)).value();
        assertTrue(entries.stream().anyMatch(e -> e.weapon().id().equals(OTHER) && !e.unlock().unlocked() && e.access() == ResultCode.LOCKED));
        assertTrue(entries.stream().noneMatch(e -> e.weapon().id().equals("battlefield:mp40")));
    }
    @Test void attachmentCatalogAndDeploymentExposeProviderResourceIds() throws Exception {
        var attachments = await(f.api.getAttachmentCatalog(player, GUN)).value();
        assertEquals(2, attachments.size());
        assertTrue(attachments.stream().anyMatch(a -> a.attachment().id().equals("battlefield:scope_advanced") && !a.unlock().unlocked()));
        await(f.api.selectAttachment(player, GUN, "SCOPE", SCOPE, 0));
        var description = await(f.api.getDeploymentLoadout(player, BackpackType.ASSAULT)).value();
        assertEquals("example:scope_basic", description.equipment().get(LoadoutSlot.PRIMARY).attachmentDefinitions().get("SCOPE").providerId());
        assertEquals(ResultCode.INVALID_INPUT, await(f.api.getCatalog(player, BackpackType.ASSAULT, LoadoutSlot.PRIMARY, Set.of("battlefield:missing"))).code());
        assertEquals(1, await(f.api.getCatalog(player, BackpackType.MEDIC, LoadoutSlot.PRIMARY, Set.of("battlefield:smg"))).value().size());
    }
    @Test void lateLoadCannotReplaceNewJoinGeneration() throws Exception {
        UUID id = UUID.randomUUID(); f.online.add(id);
        CountDownLatch release = new CountDownLatch(1);
        f.database.executor().execute(() -> { try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } });
        var first = f.api.loadPlayer(id); f.api.unloadPlayer(id); var second = f.api.loadPlayer(id); release.countDown();
        assertEquals(ResultCode.NOT_READY, await(first).code()); assertEquals(ResultCode.OK, await(second).code());
        assertEquals(ResultCode.OK, await(f.api.getLoadout(id, BackpackType.ASSAULT)).code());
    }
    @Test void changingOneClassAndPreferredClassDoesNotAffectOthers() throws Exception {
        var medicBefore = loadout(BackpackType.MEDIC); await(f.api.grantUnlock(player, OTHER));
        assertEquals(ResultCode.OK, await(f.api.selectEquipment(player, BackpackType.ASSAULT, LoadoutSlot.PRIMARY, OTHER, 0)).code());
        assertEquals(medicBefore, loadout(BackpackType.MEDIC));
        assertEquals(ResultCode.OK, await(f.api.setPreferredClass(player, BackpackType.RECON)).code());
        assertEquals(BackpackType.RECON, await(f.api.getPreferredClass(player)).value());
        assertEquals(OTHER, loadout(BackpackType.ASSAULT).equipment().get(LoadoutSlot.PRIMARY));
        assertEquals(ResultCode.OK, await(f.api.resetSlot(player, BackpackType.ASSAULT, LoadoutSlot.PRIMARY, 1)).code());
        assertEquals(GUN, loadout(BackpackType.ASSAULT).equipment().get(LoadoutSlot.PRIMARY));
    }
    @Test void weaponsAndPlayersHaveIsolatedCustomizationMaps() throws Exception {
        await(f.api.grantUnlock(player, OTHER)); UUID second = f.join();
        var saved = await(f.api.selectAttachment(player, GUN, "scope", SCOPE, 0)); assertEquals(ResultCode.OK, saved.code());
        assertEquals(SCOPE, await(f.api.getWeaponCustomization(player, GUN)).value().attachments().get("SCOPE"));
        assertTrue(await(f.api.getWeaponCustomization(player, OTHER)).value().attachments().isEmpty());
        assertTrue(await(f.api.getWeaponCustomization(second, GUN)).value().attachments().isEmpty());
        await(f.api.selectAttachment(player, OTHER, "SCOPE", SCOPE, 0));
        assertEquals(ResultCode.OK, await(f.api.removeAttachment(player, GUN, "SCOPE", 1)).code());
        assertEquals(SCOPE, await(f.api.getWeaponCustomization(player, OTHER)).value().attachments().get("SCOPE"));
    }
    @Test void attachmentUnlockTypeAndCompatibilityAllChecked() throws Exception {
        assertEquals(ResultCode.LOCKED, await(f.api.selectAttachment(player, GUN, "SCOPE", "battlefield:scope_advanced", 0)).code());
        assertEquals(ResultCode.ATTACHMENT_INVALID, await(f.api.selectAttachment(player, GUN, "MUZZLE", SCOPE, 0)).code());
        assertEquals(ResultCode.ATTACHMENT_INVALID, await(f.api.selectAttachment(player, "battlefield:mp40", "SCOPE", SCOPE, 0)).code());
        f.grants.put(player, Set.of("bfbackpack.attachment.scope_advanced"));
        assertEquals(ResultCode.OK, await(f.api.selectAttachment(player, GUN, "SCOPE", "battlefield:scope_advanced", 0)).code());
        f.grants.remove(player);
        var deployment = await(f.api.getDeploymentLoadout(player, BackpackType.ASSAULT));
        assertFalse(deployment.value().deployable());
        assertEquals(CompatibilityInfo.State.REJECTED, deployment.value().equipment().get(LoadoutSlot.PRIMARY).compatibility().state());
    }
    @Test void skinWithoutCatalogIsRejectedAndFullPlanCopiesInput() throws Exception {
        assertEquals(ResultCode.ATTACHMENT_INVALID, await(f.api.saveWeaponCustomization(new WeaponCustomization(player, GUN, Map.of(), "unknown:skin", 0))).code());
        Map<String, String> map = new HashMap<>(); map.put("SCOPE", SCOPE);
        WeaponCustomization plan = new WeaponCustomization(player, GUN, map, "", 0); map.clear();
        assertEquals(ResultCode.OK, await(f.api.saveWeaponCustomization(plan)).code());
        assertEquals(Map.of("SCOPE", SCOPE), await(f.api.getWeaponCustomization(player, GUN)).value().attachments());
    }
    @Test void staleRevisionsNeverOverwriteConfirmedData() throws Exception {
        await(f.api.selectAttachment(player, GUN, "SCOPE", SCOPE, 0));
        assertEquals(ResultCode.REVISION_CONFLICT, await(f.api.removeAttachment(player, GUN, "SCOPE", 0)).code());
        assertEquals(Map.of("SCOPE", SCOPE), await(f.api.getWeaponCustomization(player, GUN)).value().attachments());
        await(f.api.selectEquipment(player, BackpackType.ASSAULT, LoadoutSlot.PRIMARY, GUN, 0));
        assertEquals(ResultCode.REVISION_CONFLICT, await(f.api.selectEquipment(player, BackpackType.ASSAULT, LoadoutSlot.PRIMARY, GUN, 0)).code());
    }
    @Test void concurrentSameRevisionHasExactlyOneWinner() throws Exception {
        List<CompletableFuture<LoadoutResult<ClassLoadout>>> futures = new ArrayList<>();
        for (int i = 0; i < 16; i++) futures.add(f.api.selectEquipment(player, BackpackType.ASSAULT, LoadoutSlot.PRIMARY, GUN, 0));
        int ok = 0, conflict = 0;
        for (var future : futures) { ResultCode code = await(future).code(); if (code == ResultCode.OK) ok++; if (code == ResultCode.REVISION_CONFLICT) conflict++; }
        assertEquals(1, ok); assertEquals(15, conflict); assertEquals(1, loadout(BackpackType.ASSAULT).revision());
    }
    @Test void crossInstanceConflictRefreshesCacheWithoutOverwritingDatabase() throws Exception {
        f.db(() -> f.repository.saveLoadout(new ClassLoadout(player, BackpackType.ASSAULT, Map.of(LoadoutSlot.PRIMARY, GUN), 0)));
        assertEquals(ResultCode.REVISION_CONFLICT, await(f.api.selectEquipment(player, BackpackType.ASSAULT, LoadoutSlot.PRIMARY, GUN, 0)).code());
        assertEquals(1, loadout(BackpackType.ASSAULT).revision());
        assertEquals(1, loadout(BackpackType.ASSAULT).equipment().size());
    }
    @Test void hotReloadInvalidatesSavedEquipmentWithoutDeletingRecords() throws Exception {
        await(f.api.grantUnlock(player, OTHER)); await(f.api.selectEquipment(player, BackpackType.ASSAULT, LoadoutSlot.PRIMARY, OTHER, 0));
        Path rules = folder.resolve("class-rules.yml"); Files.writeString(rules, Files.readString(rules).replace("deny-items: []", "deny-items: [battlefield:gewehr43]"));
        assertEquals(ResultCode.OK, await(f.api.reloadCatalog(folder)).code());
        var validated = await(f.api.validateLoadout(player, BackpackType.ASSAULT)).value();
        assertEquals(OTHER, validated.saved().equipment().get(LoadoutSlot.PRIMARY));
        assertEquals(GUN, validated.effectiveEquipment().get(LoadoutSlot.PRIMARY)); assertEquals(ResultCode.CLASS_DENIED, validated.issues().get(0).code());
        assertEquals(OTHER, f.db(() -> f.repository.loadPlayer(player, f.catalog.snapshot())).loadouts().get(BackpackType.ASSAULT).equipment().get(LoadoutSlot.PRIMARY));
    }
    @Test void deploymentRequiresRuntimeVerificationOfExactPlan() throws Exception {
        var missing = await(f.api.getDeploymentLoadout(player, BackpackType.ASSAULT));
        assertEquals(ResultCode.PROVIDER_UNAVAILABLE, missing.code()); assertFalse(missing.value().deployable());
        f.api.installProviderValidators(w -> new CompatibilityInfo(CompatibilityInfo.State.VERIFIED, "test provider"), (w, p) -> CompatibilityInfo.unavailable());
        assertFalse(await(f.api.getDeploymentLoadout(player, BackpackType.ASSAULT)).value().deployable());
        f.api.installProviderValidators(w -> new CompatibilityInfo(CompatibilityInfo.State.VERIFIED, "test provider"), (w, p) -> new CompatibilityInfo(CompatibilityInfo.State.VERIFIED, "test compatibility"));
        assertTrue(await(f.api.getDeploymentLoadout(player, BackpackType.ASSAULT)).value().deployable());
        f.api.installProviderValidators(w -> { throw new IllegalStateException("provider failed"); }, (w, p) -> CompatibilityInfo.unavailable());
        assertEquals(ResultCode.PROVIDER_UNAVAILABLE, await(f.api.getDeploymentLoadout(player, BackpackType.ASSAULT)).code());
    }
    @Test void missingDefaultCannotBecomeDeployableEvenWithAcceptingProvider() throws Exception {
        Path rules = folder.resolve("class-rules.yml");
        Files.writeString(rules, Files.readString(rules).replace("      default: battlefield:stg44", "      deny-items: [battlefield:stg44]" ).replace("      deny-items: []\n", ""));
        assertEquals(ResultCode.OK, await(f.api.reloadCatalog(folder)).code());
        f.api.installProviderValidators(w -> new CompatibilityInfo(CompatibilityInfo.State.VERIFIED, "test"), (w, p) -> new CompatibilityInfo(CompatibilityInfo.State.VERIFIED, "test"));
        var result = await(f.api.getDeploymentLoadout(player, BackpackType.ASSAULT));
        assertEquals(ResultCode.LOADOUT_INVALID, result.code()); assertFalse(result.value().deployable());
        assertNull(result.value().loadout().issues().get(0).fallbackId());
        assertFalse(result.value().equipment().containsKey(LoadoutSlot.PRIMARY));
    }
    @Test void dtoCollectionsAndApiVersionAreSafe() throws Exception {
        assertThrows(UnsupportedOperationException.class, () -> loadout(BackpackType.ASSAULT).equipment().clear());
        assertThrows(UnsupportedOperationException.class, () -> await(f.api.getCatalog(player, BackpackType.ASSAULT)).value().clear());
        assertThrows(UnsupportedOperationException.class, () -> await(f.api.getWeaponCustomization(player, GUN)).value().attachments().put("SCOPE", SCOPE));
        assertEquals(ResultCode.API_INCOMPATIBLE, new BFBackpackAPIProvider(f.api).requireVersion(99).code());
        assertEquals(ResultCode.OK, new BFBackpackAPIProvider(f.api).requireVersion(3).code());
    }
    @Test void offlinePermanentUnlockIsIdempotentAndSurvivesJoining() throws Exception {
        UUID offline = UUID.randomUUID(); await(f.api.grantUnlock(offline, OTHER)); await(f.api.grantUnlock(offline, OTHER));
        assertEquals(Set.of(OTHER), await(f.api.getPlayerUnlocks(offline)).value()); f.join(offline);
        assertTrue(await(f.api.isUnlocked(offline, OTHER)).value().permanent());
        await(f.api.revokeUnlock(offline, OTHER)); assertTrue(await(f.api.getPlayerUnlocks(offline)).value().isEmpty());
    }
}
