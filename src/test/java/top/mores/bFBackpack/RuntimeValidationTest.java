package top.mores.bFBackpack;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import top.mores.bFBackpack.api.*;
import top.mores.bFBackpack.backpack.BackpackType;
import top.mores.bFBackpack.service.ValidationEngine;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static top.mores.bFBackpack.BackendFixture.await;

/** Phase-two runtime validation: off-thread, bounded, timeout-aware and always fail-closed. */
class RuntimeValidationTest {
    @TempDir Path folder;
    BackendFixture f;
    UUID player;
    static final String GUN = "battlefield:stg44";

    @BeforeEach void setup() throws Exception { f = new BackendFixture(folder); player = f.join(); }
    @AfterEach void cleanup() { f.close(); }

    private DeploymentLoadout deployment() throws Exception {
        return await(f.api.getDeploymentLoadout(player, BackpackType.ASSAULT)).value();
    }

    @Test void verifiedProviderMakesTheExactDescriptionDeployable() throws Exception {
        var registration = f.acceptAll();
        var result = await(f.api.getDeploymentLoadout(player, BackpackType.ASSAULT));
        assertEquals(ResultCode.OK, result.code());
        assertTrue(result.value().deployable());
        assertEquals(ResultCode.OK, result.value().status());
        assertTrue(result.value().runtimeIssues().isEmpty());
        assertEquals(LoadoutSlot.values().length, result.value().equipment().size());
        assertEquals("test-provider", result.value().binding().providerId());
        assertEquals(registration.generation(), result.value().binding().providerGeneration());
        assertTrue(result.value().equipment().values().stream().allMatch(e -> e.compatibility().deployable()));
    }

    @Test void missingResourceAndIncompatibleAttachmentKeepTheirOwnCodes() throws Exception {
        f.api.registerValidators("strict", new ValidationProvider() {
            @Override public CompletableFuture<CompatibilityInfo> validateWeapon(WeaponDefinition weapon) {
                return CompletableFuture.completedFuture(weapon.id().equals("battlefield:p38")
                        ? CompatibilityInfo.rejected(ResultCode.RESOURCE_NOT_FOUND, "gunId missing in TaCZ")
                        : CompatibilityInfo.verified("ok"));
            }
            @Override public CompletableFuture<CompatibilityInfo> validateAttachments(WeaponDefinition weapon, WeaponCustomization customization) {
                return CompletableFuture.completedFuture(CompatibilityInfo.rejected(ResultCode.ATTACHMENT_INVALID, "slot unsupported"));
            }
        });
        await(f.api.selectAttachment(player, GUN, "SCOPE", "battlefield:scope_basic", 0));
        var result = await(f.api.getDeploymentLoadout(player, BackpackType.ASSAULT));
        assertEquals(ResultCode.RESOURCE_NOT_FOUND, result.code());
        assertFalse(result.value().deployable());
        assertEquals(1, result.value().runtimeIssues().stream().filter(i -> i.code() == ResultCode.RESOURCE_NOT_FOUND).count());
        assertEquals(ResultCode.RESOURCE_NOT_FOUND,
                result.value().equipment().get(LoadoutSlot.SECONDARY).compatibility().detail());
        assertEquals(ResultCode.ATTACHMENT_INVALID,
                result.value().equipment().get(LoadoutSlot.PRIMARY).compatibility().detail());
    }

    @Test void throwingProviderFailsClosedAndDoesNotBreakLaterRequests() throws Exception {
        f.api.registerValidators("broken", new ValidationProvider() {
            @Override public CompletableFuture<CompatibilityInfo> validateWeapon(WeaponDefinition weapon) {
                throw new IllegalStateException("adapter exploded");
            }
            @Override public CompletableFuture<CompatibilityInfo> validateAttachments(WeaponDefinition weapon, WeaponCustomization customization) {
                return null;
            }
        });
        var failed = await(f.api.getDeploymentLoadout(player, BackpackType.ASSAULT));
        assertEquals(ResultCode.PROVIDER_UNAVAILABLE, failed.code());
        assertFalse(failed.value().deployable());
        assertEquals(LoadoutSlot.values().length, failed.value().runtimeIssues().size());
        assertFalse(failed.value().runtimeIssues().get(0).reason().contains("\tat "), "no stack trace in diagnostics");

        f.acceptAll();
        assertTrue(await(f.api.getDeploymentLoadout(player, BackpackType.ASSAULT)).value().deployable());
    }

    @Test void unresolvedFutureTimesOutWithAnExplicitCode() throws Exception {
        try (var slow = new BackendFixture(folder.resolve("timeout"), BackendFixture.shortTimeout(150))) {
            UUID id = slow.join();
            CompletableFuture<CompatibilityInfo> never = new CompletableFuture<>();
            slow.api.registerValidators("hanging", new ValidationProvider() {
                @Override public CompletableFuture<CompatibilityInfo> validateWeapon(WeaponDefinition weapon) { return never; }
                @Override public CompletableFuture<CompatibilityInfo> validateAttachments(WeaponDefinition weapon, WeaponCustomization customization) { return never; }
            });
            var result = await(slow.api.getDeploymentLoadout(id, BackpackType.ASSAULT));
            assertEquals(ResultCode.VALIDATION_TIMEOUT, result.code());
            assertEquals(ResultCode.VALIDATION_TIMEOUT, result.value().status());
            assertFalse(result.value().deployable());
            assertTrue(result.value().runtimeIssues().stream().allMatch(i -> i.code() == ResultCode.VALIDATION_TIMEOUT));
            assertFalse(never.isDone());
        }
    }

    @Test void validationRunsOffTheDatabaseWorkerAndTheFutureFollowsIt() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CompletableFuture<CompatibilityInfo> controlled = new CompletableFuture<>();
        AtomicReference<String> validationThread = new AtomicReference<>();
        AtomicReference<String> completionThread = new AtomicReference<>();
        f.api.registerValidators("threaded", new ValidationProvider() {
            @Override public CompletableFuture<CompatibilityInfo> validateWeapon(WeaponDefinition weapon) {
                validationThread.set(Thread.currentThread().getName());
                started.countDown();
                return controlled;
            }
            @Override public CompletableFuture<CompatibilityInfo> validateAttachments(WeaponDefinition weapon, WeaponCustomization customization) {
                return controlled;
            }
        });
        var future = f.api.getDeploymentLoadout(player, BackpackType.ASSAULT);
        future.thenAccept(result -> completionThread.set(Thread.currentThread().getName()));
        assertTrue(started.await(5, TimeUnit.SECONDS), "validation never started");
        assertTrue(validationThread.get().startsWith("BFBackpack-Validation"), validationThread.get());
        controlled.complete(CompatibilityInfo.verified("ok"));
        assertEquals(ResultCode.OK, await(future).code());
        assertNotNull(completionThread.get());
        assertFalse(completionThread.get().startsWith("BFBackpack-DB"), completionThread.get());
        assertFalse(completionThread.get().startsWith("test-bukkit-main"), completionThread.get());
    }

    @Test void slowValidationDoesNotStallDatabaseReadsOrWrites() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CompletableFuture<CompatibilityInfo> controlled = new CompletableFuture<>();
        f.api.registerValidators("slow", new ValidationProvider() {
            @Override public CompletableFuture<CompatibilityInfo> validateWeapon(WeaponDefinition weapon) {
                started.countDown();
                return controlled;
            }
            @Override public CompletableFuture<CompatibilityInfo> validateAttachments(WeaponDefinition weapon, WeaponCustomization customization) {
                return controlled;
            }
        });
        CompletableFuture<LoadoutResult<DeploymentLoadout>> pending = f.api.getDeploymentLoadout(player, BackpackType.ASSAULT);
        assertTrue(started.await(5, TimeUnit.SECONDS), "validation never started");

        // The database worker must still serve reads and commits while validation is in flight.
        assertEquals(ResultCode.OK, await(f.api.getLoadout(player, BackpackType.ASSAULT)).code());
        assertEquals(ResultCode.OK, await(f.api.selectEquipment(player, BackpackType.ASSAULT, LoadoutSlot.PRIMARY, GUN, 0)).code());
        assertEquals(1, await(f.api.getLoadout(player, BackpackType.ASSAULT)).value().revision());
        assertFalse(pending.isDone(), "deployment must still be waiting for validation");

        controlled.complete(CompatibilityInfo.verified("ok"));
        // The loadout changed while validating, so the description is bound to a stale revision.
        assertEquals(ResultCode.REVISION_CONFLICT, await(pending).code());
    }

    @Test void saturatedValidationPoolRejectsInsteadOfQueueingForever() throws Exception {
        try (var busy = new BackendFixture(folder.resolve("busy"), new ValidationEngine.Settings(5000, 1, 1))) {
            UUID id = busy.join();
            CountDownLatch started = new CountDownLatch(1);
            CompletableFuture<CompatibilityInfo> controlled = new CompletableFuture<>();
            busy.api.registerValidators("blocked", new ValidationProvider() {
                @Override public CompletableFuture<CompatibilityInfo> validateWeapon(WeaponDefinition weapon) {
                    started.countDown();
                    return controlled;
                }
                @Override public CompletableFuture<CompatibilityInfo> validateAttachments(WeaponDefinition weapon, WeaponCustomization customization) {
                    return controlled;
                }
            });
            List<CompletableFuture<LoadoutResult<DeploymentLoadout>>> requests = new ArrayList<>();
            for (int i = 0; i < 6; i++) requests.add(busy.api.getDeploymentLoadout(id, BackpackType.ASSAULT));
            busy.drain();   // all six left phase one: one runs, one queues, the rest are rejected
            assertTrue(started.await(5, TimeUnit.SECONDS), "validation never started");
            long busyCount = 0;
            List<CompletableFuture<LoadoutResult<DeploymentLoadout>>> accepted = new ArrayList<>();
            for (var request : requests) {
                if (request.isDone()) {
                    assertEquals(ResultCode.VALIDATION_BUSY, await(request).code());
                    busyCount++;
                } else accepted.add(request);
            }
            assertEquals(4, busyCount, "max-concurrent 1 + queue 1 bounds outstanding work to two requests");
            assertEquals(2, accepted.size());
            controlled.complete(CompatibilityInfo.verified("ok"));
            for (var request : accepted) assertTrue(await(request).value().deployable());
        }
    }

    @Test void timeoutKeepsResourcesUnverifiedAndWritesNothing() throws Exception {
        try (var slow = new BackendFixture(folder.resolve("late"), BackendFixture.shortTimeout(120))) {
            UUID id = slow.join();
            CompletableFuture<CompatibilityInfo> late = new CompletableFuture<>();
            slow.api.registerValidators("late", new ValidationProvider() {
                @Override public CompletableFuture<CompatibilityInfo> validateWeapon(WeaponDefinition weapon) { return late; }
                @Override public CompletableFuture<CompatibilityInfo> validateAttachments(WeaponDefinition weapon, WeaponCustomization customization) { return late; }
            });
            var timedOut = await(slow.api.getDeploymentLoadout(id, BackpackType.ASSAULT));
            assertEquals(ResultCode.VALIDATION_TIMEOUT, timedOut.code());

            // A late completion cannot retroactively approve the already-returned description.
            late.complete(CompatibilityInfo.verified("too late"));
            assertFalse(timedOut.value().deployable());
            assertEquals(0, await(slow.api.getLoadout(id, BackpackType.ASSAULT)).value().revision());
            assertEquals(0, await(slow.api.getWeaponCustomization(id, GUN)).value().revision());

            slow.acceptAll();
            assertTrue(await(slow.api.getDeploymentLoadout(id, BackpackType.ASSAULT)).value().deployable());
        }
    }
}
