package top.mores.bFBackpack.api;

/**
 * Legacy synchronous Forge adapter hook, wrapped by {@link SyncValidationProvider}.
 * <p>Called on a bounded BFBackpack validation thread, never on the database worker. A blocking
 * implementation occupies one validation slot and still cannot stall database reads or writes, but such
 * an adapter should move to {@link ValidationProvider} and marshal to Forge asynchronously.</p>
 */
@FunctionalInterface
public interface WeaponProviderValidator {
    /** Verify the real provider resource, including gunId, without constructing an item. */
    CompatibilityInfo validate(WeaponDefinition weapon);
}
