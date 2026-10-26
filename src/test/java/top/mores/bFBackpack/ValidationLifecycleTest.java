package top.mores.bFBackpack;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import top.mores.bFBackpack.api.*;
import top.mores.bFBackpack.backpack.BackpackType;
import top.mores.bFBackpack.listener.ValidationOwners;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static top.mores.bFBackpack.BackendFixture.await;

/** Runtime validator registration, replacement, unregistration and shutdown cleanup. */
class ValidationLifecycleTest {
    @TempDir Path folder;
    BackendFixture f;
    UUID player;

    @BeforeEach void setup() throws Exception { f = new BackendFixture(folder); player = f.join(); }
    @AfterEach void cleanup() { f.close(); }

    private ValidationProvider rejecting(String marker) {
        return new ValidationProvider() {
            @Override public CompletableFuture<CompatibilityInfo> validateWeapon(WeaponDefinition weapon) {
                return CompletableFuture.completedFuture(CompatibilityInfo.rejected(ResultCode.RESOURCE_NOT_FOUND, marker));
            }
            @Override public CompletableFuture<CompatibilityInfo> validateAttachments(WeaponDefinition weapon, WeaponCustomization customization) {
                return CompletableFuture.completedFuture(CompatibilityInfo.rejected(ResultCode.RESOURCE_NOT_FOUND, marker));
            }
        };
    }

    @Test void registrationIsActiveAndReportsItsIdentity() {
        var registration = f.acceptAll("bridge-a");
        assertTrue(registration.active());
        assertEquals("bridge-a", registration.providerId());
        assertTrue(registration.generation() > 0);
        assertTrue(f.validation.hasProvider());
        assertEquals("bridge-a", f.validation.providerId());
        assertEquals(registration.generation(), f.validation.providerGeneration());
    }

    @Test void duplicateRegistrationReplacesAndTheOldHandleBecomesInert() throws Exception {
        var first = f.acceptAll("bridge");
        long firstGeneration = first.generation();
        var second = f.api.registerValidators("bridge", rejecting("second"));
        assertFalse(first.active());
        assertTrue(second.active());
        assertTrue(second.generation() > firstGeneration);

        first.close();                                  // stale handle must not unregister the replacement
        assertTrue(f.validation.hasProvider());
        assertEquals(second.generation(), f.validation.providerGeneration());
        first.close();                                  // idempotent

        var result = await(f.api.getDeploymentLoadout(player, BackpackType.ASSAULT));
        assertEquals(ResultCode.RESOURCE_NOT_FOUND, result.code());
        assertTrue(result.value().runtimeIssues().get(0).reason().contains("second"));
    }

    @Test void unregisteredProviderStopsNewRequestsAndCanBeRegisteredAgain() throws Exception {
        var registration = f.acceptAll();
        assertTrue(await(f.api.getDeploymentLoadout(player, BackpackType.ASSAULT)).value().deployable());

        registration.close();
        assertFalse(registration.active());
        assertFalse(f.validation.hasProvider());
        var unavailable = await(f.api.getDeploymentLoadout(player, BackpackType.ASSAULT));
        assertEquals(ResultCode.PROVIDER_UNAVAILABLE, unavailable.code());
        assertFalse(unavailable.value().deployable());

        f.acceptAll();
        assertTrue(await(f.api.getDeploymentLoadout(player, BackpackType.ASSAULT)).value().deployable());
    }

    @Test void inFlightRequestKeepsTheProviderItWasAcceptedWith() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CompletableFuture<CompatibilityInfo> controlled = new CompletableFuture<>();
        var registration = f.api.registerValidators("in-flight", new ValidationProvider() {
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
        registration.close();
        controlled.complete(CompatibilityInfo.verified("verified by the accepted provider"));
        assertEquals(ResultCode.OK, await(pending).code());
        assertEquals(ResultCode.PROVIDER_UNAVAILABLE,
                await(f.api.getDeploymentLoadout(player, BackpackType.ASSAULT)).code());
    }

    @Test void incompatibleApiVersionAndInvalidIdAreRejectedAtomically() {
        ValidationProvider future = new ValidationProvider() {
            @Override public int apiVersion() { return BFBackpackAPI.API_VERSION + 1; }
            @Override public CompletableFuture<CompatibilityInfo> validateWeapon(WeaponDefinition weapon) { return CompletableFuture.completedFuture(CompatibilityInfo.unavailable()); }
            @Override public CompletableFuture<CompatibilityInfo> validateAttachments(WeaponDefinition weapon, WeaponCustomization customization) { return CompletableFuture.completedFuture(CompatibilityInfo.unavailable()); }
        };
        var error = assertThrows(IllegalArgumentException.class, () -> f.api.registerValidators("future", future));
        assertTrue(error.getMessage().contains("API_INCOMPATIBLE"));
        assertFalse(f.validation.hasProvider());

        assertThrows(IllegalArgumentException.class, () -> f.api.registerValidators("bad id!", rejecting("x")));
        assertFalse(f.validation.hasProvider());
    }

    @Test void pluginOwnerReleaseClosesOnlyItsOwnRegistrations() {
        var registration = f.acceptAll("bridge");
        var owners = new ValidationOwners();
        owners.bind("BridgePlugin", registration);
        assertEquals(1, owners.owners());
        assertEquals(0, owners.release("OtherPlugin"));
        assertTrue(registration.active());
        assertEquals(1, owners.release("BridgePlugin"));
        assertFalse(registration.active());
        assertEquals(0, owners.owners());
        assertEquals(0, owners.release("BridgePlugin"));
    }

    @Test void shutdownDropsRegistrationsAndFailsPendingAndNewRequests() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CompletableFuture<CompatibilityInfo> controlled = new CompletableFuture<>();
        f.api.registerValidators("pending", new ValidationProvider() {
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
        f.api.shutdown();
        assertFalse(f.validation.hasProvider());
        var failed = await(pending);
        assertEquals(ResultCode.NOT_READY, failed.code());
        assertNotNull(failed.value());
        assertFalse(failed.value().deployable());
        assertEquals(ResultCode.NOT_READY, await(f.api.getDeploymentLoadout(player, BackpackType.ASSAULT)).code());
        assertThrows(IllegalStateException.class, () -> f.api.registerValidators("late", rejecting("late")));
    }
}
