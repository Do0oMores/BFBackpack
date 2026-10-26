package top.mores.bFBackpack.api;

/** Forge adapter hook. Called on the backend worker: marshal to Forge's thread if needed. */
@FunctionalInterface
public interface WeaponProviderValidator {
    /** Verify the real provider resource, including gunId, without constructing an item. */
    CompatibilityInfo validate(WeaponDefinition weapon);
}
